package com.saash.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.ResultSet
import java.time.Duration

data class Connector(
    val id: Int,
    val orgId: Int,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val healthPath: String,
    val authType: String,
    val authName: String,
    val secretEnc: String,
    val config: String,
    val webhookToken: String,
    val status: String,
    val latency: Int,
    val checks: Int,
    val okChecks: Int,
    val lastChecked: Long
)

object Http {
    // Pas de redirection automatique : évite de contourner la protection SSRF.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    data class Res(val code: Int, val body: String, val ms: Long)

    fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Res {
        Ssrf.check(url)
        val start = System.currentTimeMillis()
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        headers.forEach { (k, v) -> b.header(k, v) }
        val pub = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        b.method(method.uppercase(), pub)
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Res(r.statusCode(), r.body().take(2000), System.currentTimeMillis() - start)
    }

    fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
}

object Repo {
    fun now(): Long = System.currentTimeMillis()

    private fun toConnector(rs: ResultSet) = Connector(
        rs.getInt("id"), rs.getInt("org_id"), rs.getString("name"), rs.getString("kind"),
        rs.getString("base_url"), rs.getString("health_path"), rs.getString("auth_type"),
        rs.getString("auth_name"), rs.getString("secret"), rs.getString("config"),
        rs.getString("webhook_token"), rs.getString("status"), rs.getInt("latency"),
        rs.getInt("checks"), rs.getInt("ok_checks"), rs.getLong("last_checked")
    )

    fun connectors(orgId: Int): List<Connector> =
        Db.query("SELECT * FROM connectors WHERE org_id=? ORDER BY id", listOf(orgId)) { toConnector(it) }

    fun connector(orgId: Int, id: Int): Connector? =
        Db.query("SELECT * FROM connectors WHERE org_id=? AND id=?", listOf(orgId, id)) { toConnector(it) }.firstOrNull()

    fun connectorByToken(token: String): Connector? =
        Db.query("SELECT * FROM connectors WHERE webhook_token=?", listOf(token)) { toConnector(it) }.firstOrNull()

    fun pingable(): List<Connector> =
        Db.query("SELECT * FROM connectors WHERE base_url <> ''") { toConnector(it) }

    fun authHeaders(c: Connector): Map<String, String> {
        val secret = Security.dec(c.secretEnc)
        if (secret.isEmpty()) return emptyMap()
        return when (c.authType) {
            "bearer" -> mapOf("Authorization" to "Bearer $secret")
            "header" -> if (c.authName.isNotBlank()) mapOf(c.authName to secret) else emptyMap()
            else -> emptyMap()
        }
    }

    fun urlFor(c: Connector, path: String): String {
        var url = c.baseUrl.trimEnd('/') + (if (path.isEmpty() || path.startsWith("/")) path else "/$path")
        if (c.authType == "query" && c.authName.isNotBlank()) {
            val secret = Security.dec(c.secretEnc)
            if (secret.isNotEmpty()) {
                url += (if (url.contains("?")) "&" else "?") +
                    URLEncoder.encode(c.authName, "UTF-8") + "=" + URLEncoder.encode(secret, "UTF-8")
            }
        }
        return url
    }

    // ---------- Notifications / événements ----------
    fun notify(orgId: Int, title: String, body: String = "") {
        Db.exec("INSERT INTO notifications(org_id,title,body,ts) VALUES(?,?,?,?)", listOf(orgId, title, body, now()))
    }

    fun addEvent(orgId: Int, connectorId: Int?, type: String, userRef: String, minutes: Int, payload: String) {
        Db.exec(
            "INSERT INTO events(org_id,connector_id,type,user_ref,minutes,payload,ts) VALUES(?,?,?,?,?,?,?)",
            listOf(orgId, connectorId, type, userRef, minutes, payload, now())
        )
    }

    // ---------- Demandes d'accès ----------
    /** ref = identifiant de la demande dans le SaaS d'origine : il revient avec la décision (rappel signé). */
    fun createRequest(orgId: Int, connectorId: Int?, requester: String, reason: String, ref: String = ""): Int {
        val id = Db.insert(
            "INSERT INTO access_requests(org_id,connector_id,requester,reason,created_at,ref) VALUES(?,?,?,?,?,?) RETURNING id",
            listOf(orgId, connectorId, requester, reason, now(), ref)
        )
        notify(orgId, "Nouvelle demande d'accès", "$requester : $reason")
        Automations.fire(orgId, "request_created", mapOf("user" to requester, "reason" to reason))
        return id
    }

    // ---------- Incidents ----------
    fun openIncident(orgId: Int, connectorId: Int?, name: String, title: String) {
        val open = Db.query(
            "SELECT 1 FROM incidents WHERE org_id=? AND connector_id IS NOT DISTINCT FROM ?::int AND resolved_at=0",
            listOf(orgId, connectorId)
        ) { 1 }
        if (open.isNotEmpty()) return
        Db.exec(
            "INSERT INTO incidents(org_id,connector_id,title,started_at) VALUES(?,?,?,?)",
            listOf(orgId, connectorId, title, now())
        )
        notify(orgId, "🔴 $name", title)
        Automations.fire(orgId, "incident_opened", mapOf("connector" to name, "title" to title, "connectorId" to (connectorId?.toString() ?: "")))
    }

    fun resolveIncident(orgId: Int, connectorId: Int?, name: String) {
        val n = Db.exec(
            "UPDATE incidents SET resolved_at=? WHERE org_id=? AND connector_id IS NOT DISTINCT FROM ?::int AND resolved_at=0",
            listOf(now(), orgId, connectorId)
        )
        if (n > 0) {
            notify(orgId, "✅ $name", "Service rétabli")
            Automations.fire(orgId, "incident_resolved", mapOf("connector" to name, "connectorId" to (connectorId?.toString() ?: "")))
        }
    }

    // ---------- Vérification de santé ----------
    suspend fun checkAll(list: List<Connector>) = coroutineScope {
        list.map { c -> async { runCatching { checkOne(c) } } }.awaitAll()
    }

    fun checkOne(c: Connector) {
        val (status, ms) = try {
            val r = Http.call("GET", urlFor(c, c.healthPath), authHeaders(c))
            val s = when {
                r.code >= 500 -> "down"
                r.ms > 1500 -> "degraded"
                else -> "up"
            }
            s to r.ms.toInt()
        } catch (e: Exception) {
            "down" to 0
        }
        Db.exec(
            "UPDATE connectors SET status=?, latency=?, checks=checks+1, ok_checks=ok_checks+?, last_checked=? WHERE id=?",
            listOf(status, ms, if (status == "down") 0 else 1, now(), c.id)
        )
        if (status == "down") openIncident(c.orgId, c.id, c.name, "Service injoignable")
        if (status == "up") resolveIncident(c.orgId, c.id, c.name)
    }
}

/** Règles « si X alors Y » définies par les utilisateurs. */
object Automations {
    fun fire(orgId: Int, trigger: String, ctx: Map<String, String>) {
        val rows = Db.query(
            "SELECT config, action FROM automations WHERE org_id=? AND enabled=TRUE AND trigger=?",
            listOf(orgId, trigger)
        ) { Pair(it.getString(1), it.getString(2)) }
        for ((cfgText, actText) in rows) {
            try {
                val cfg = Json.parseToJsonElement(cfgText).jsonObject
                val act = Json.parseToJsonElement(actText).jsonObject
                val wanted = cfg.str("connectorId")
                if (wanted != null && wanted != ctx["connectorId"]) continue
                execute(orgId, act, ctx)
            } catch (e: Exception) {
                println("Automatisation en échec : ${e.message}")
            }
        }
    }

    private fun tpl(text: String?, ctx: Map<String, String>): String {
        var out = text ?: ""
        ctx.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }

    private fun execute(orgId: Int, act: JsonObject, ctx: Map<String, String>) {
        when (act.str("type")) {
            "notify" -> Repo.notify(orgId, tpl(act.str("title") ?: "Automatisation", ctx), tpl(act.str("body"), ctx))
            "run_action" -> act.int("actionId")?.let { id ->
                val control = Controls.byId(orgId, id) ?: return
                kotlinx.coroutines.runBlocking {
                    if (control.type == "switch") Controls.toggle(orgId, control, act.bool("on") ?: true, "automatisation", true)
                    else Controls.press(orgId, control, "automatisation", null, true)
                }
            }
            "webhook" -> {
                val url = act.str("url") ?: return
                Http.call("POST", url, mapOf("Content-Type" to "application/json"), tpl(act.str("body") ?: "{}", ctx))
            }
        }
    }
}
