package com.saash.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Control(
    val id: Int,
    val orgId: Int,
    val type: String, // button | switch
    val kind: String,
    val name: String,
    val code: String,
    val connectorId: Int?,
    val method: String,
    val path: String,
    val body: String,
    val state: Boolean
)

object Controls {
    private val CODE = Regex("^[A-Za-z0-9_.-]{2,40}$")
    private val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

    private fun map(rs: ResultSet) = Control(
        rs.getInt("id"), rs.getInt("org_id"), rs.getString("type"), rs.getString("kind"), rs.getString("name"),
        rs.getString("code"), rs.getInt("connector_id").takeIf { !rs.wasNull() }, rs.getString("method"),
        rs.getString("path"), rs.getString("body"), rs.getBoolean("state")
    )

    fun list(orgId: Int): List<Control> =
        Db.query("SELECT * FROM controls WHERE org_id=? ORDER BY id", listOf(orgId)) { map(it) }

    fun byId(orgId: Int, id: Int): Control? =
        Db.query("SELECT * FROM controls WHERE org_id=? AND id=?", listOf(orgId, id)) { map(it) }.firstOrNull()

    fun byCode(orgId: Int, code: String): Control? =
        Db.query("SELECT * FROM controls WHERE org_id=? AND code=?", listOf(orgId, code)) { map(it) }.firstOrNull()

    fun json(c: Control, connectorName: String): JsonObject = buildJsonObject {
        put("id", c.id); put("type", c.type); put("kind", c.kind); put("name", c.name); put("code", c.code)
        put("connectorId", c.connectorId); put("connector", connectorName)
        put("method", c.method); put("path", c.path); put("body", c.body); put("state", c.state)
    }

    fun validMethod(m: String?): String {
        val up = (m ?: "POST").uppercase()
        if (up !in METHODS) err(400, "Méthode invalide")
        return up
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "ctl" }

    /** Vérifie le code choisi (ou en génère un) et garantit son unicité dans l'organisation. */
    fun checkCode(orgId: Int, wanted: String, name: String, ignoreId: Int? = null): String {
        val code = if (wanted.isBlank()) slug(name) + "-" + Security.token(2) else wanted.trim()
        if (!CODE.matches(code)) err(400, "Code invalide : 2 à 40 caractères (lettres, chiffres, - _ .)")
        val other = byCode(orgId, code)
        if (other != null && other.id != ignoreId) err(409, "Ce code est déjà utilisé")
        return code
    }

    private fun fill(text: String, c: Control, who: String, on: Boolean? = null): String {
        var out = text.replace("{{who}}", who).replace("{{code}}", c.code).replace("{{name}}", c.name)
        if (on != null) out = out.replace("{{state}}", on.toString())
        return out
    }

    private fun callExternal(c: Control, who: String, on: Boolean?, defaultBody: String = ""): Http.Res {
        val cid = c.connectorId ?: err(400, "Aucun SaaS associé à ce contrôle")
        val conn = Repo.connector(c.orgId, cid) ?: err(404, "Connecteur introuvable")
        if (conn.baseUrl.isBlank()) err(400, "Ce connecteur n'a pas d'URL")
        val raw = c.body.ifBlank { defaultBody }
        val body = if (raw.isBlank()) null else fill(raw, c, who, on)
        val headers = Repo.authHeaders(conn).toMutableMap()
        if (body != null) headers["Content-Type"] = "application/json"
        return Http.call(c.method, Repo.urlFor(conn, c.path), headers, body)
    }

    private val blockedInAutomation = setOf("check_now", "create_incident", "resolve_incidents")

    /** Exécute un bouton. fromAutomation = déclenché par une règle (évite les boucles). */
    suspend fun press(orgId: Int, c: Control, who: String, input: JsonObject? = null, fromAutomation: Boolean = false): JsonObject {
        if (c.type != "button") err(400, "Ce n'est pas un bouton")
        if (fromAutomation && c.kind in blockedInAutomation) err(400, "Action non autorisée depuis une automatisation")
        val message = (input?.str("message") ?: "").ifBlank { c.body }
        var info = ""
        var client = ""
        var ok = true
        var status = 0

        when (c.kind) {
            "check_now" -> {
                val list = Repo.connectors(orgId).filter { it.baseUrl.isNotBlank() }
                Repo.checkAll(list)
                info = "${list.size} service(s) vérifié(s)"
            }
            "notify_team" -> {
                Repo.notify(orgId, c.name, fill(message, c, who).ifBlank { "Notification de $who" })
                info = "Équipe notifiée"
            }
            "create_incident" -> {
                Repo.openIncident(orgId, null, c.name, fill(message, c, who).ifBlank { c.name })
                info = "Incident déclaré"
            }
            "resolve_incidents" -> {
                val n = Db.exec("UPDATE incidents SET resolved_at=? WHERE org_id=? AND resolved_at=0", listOf(Repo.now(), orgId))
                if (n > 0) Repo.notify(orgId, "✅ Incidents résolus", "$n incident(s) clôturé(s) par $who")
                info = "$n incident(s) résolu(s)"
            }
            "approve_all", "refuse_all" -> {
                val state = if (c.kind == "approve_all") "accepted" else "refused"
                val pending = Db.query(
                    "SELECT requester, ref FROM access_requests WHERE org_id=? AND state='pending'", listOf(orgId)
                ) { Pair(it.getString(1), it.getString(2)) }
                val n = Db.exec(
                    "UPDATE access_requests SET state=?, decided_by=?, decided_at=? WHERE org_id=? AND state='pending'",
                    listOf(state, who, Repo.now(), orgId)
                )
                pending.forEach { (user, ref) ->
                    Callbacks.send(
                        orgId,
                        buildJsonObject {
                            put("type", "request_decided"); put("state", state); put("user", user)
                            put("ref", ref); put("by", who); put("ts", Repo.now())
                        }
                    )
                }
                info = "$n demande(s) traitée(s)"
            }
            "emit_event" -> info = "Signal envoyé"
            "open_link" -> {
                client = "open_link"
                info = c.path
            }
            "copy_code" -> {
                client = "copy_code"
                info = c.code
            }
            else -> {
                if (Catalog.buttonKind(c.kind) == null) err(400, "Type de bouton inconnu")
                val r = callExternal(c, who, null)
                status = r.code
                ok = r.code in 200..399
                info = "HTTP ${r.code} en ${r.ms} ms"
            }
        }

        Repo.addEvent(orgId, c.connectorId, "control", c.code, 0, "{\"code\":\"${c.code}\",\"kind\":\"${c.kind}\"}")
        Callbacks.send(
            orgId,
            buildJsonObject {
                put("type", "button"); put("code", c.code); put("name", c.name); put("kind", c.kind)
                put("by", who); put("ts", Repo.now())
            }
        )
        return buildJsonObject {
            put("ok", ok); put("info", info); put("client", client); put("status", status)
        }
    }

    suspend fun toggle(orgId: Int, c: Control, on: Boolean, who: String, fromAutomation: Boolean = false): JsonObject {
        if (c.type != "switch") err(400, "Ce n'est pas un interrupteur")
        var info = "Interrupteur ${if (on) "activé" else "désactivé"}"
        if (c.connectorId != null && c.path.isNotBlank()) {
            val r = try {
                callExternal(c, who, on, "{\"enabled\":{{state}}}")
            } catch (e: HttpError) {
                throw e
            } catch (e: Exception) {
                err(502, "Le SaaS n'a pas répondu : ${e.message}")
            }
            if (r.code !in 200..399) err(502, "Le SaaS a refusé (HTTP ${r.code})")
            info += " · HTTP ${r.code}"
        }
        Db.exec("UPDATE controls SET state=? WHERE id=? AND org_id=?", listOf(on, c.id, orgId))
        Repo.addEvent(orgId, c.connectorId, "switch", c.code, 0, "{\"code\":\"${c.code}\",\"on\":$on}")
        Callbacks.send(
            orgId,
            buildJsonObject {
                put("type", "switch"); put("code", c.code); put("name", c.name); put("on", on)
                put("by", who); put("ts", Repo.now())
            }
        )
        if (!fromAutomation) {
            Automations.fire(orgId, "event", mapOf("type" to "switch", "code" to c.code, "state" to on.toString(), "user" to who))
        }
        return buildJsonObject { put("ok", true); put("info", info); put("on", on) }
    }
}

/** Envoie les actions des boutons/interrupteurs à l'URL de rappel de l'organisation, signées en HMAC-SHA256. */
object Callbacks {
    fun hmac(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun send(orgId: Int, payload: JsonObject) {
        val row = Db.query("SELECT callback_url, api_key FROM orgs WHERE id=?", listOf(orgId)) {
            Pair(it.getString(1), it.getString(2))
        }.firstOrNull() ?: return
        if (row.first.isBlank()) return
        Thread {
            try {
                val body = payload.toString()
                Http.call(
                    "POST", row.first,
                    mapOf("Content-Type" to "application/json", "X-SaaSH-Signature" to "sha256=" + hmac(row.second, body)),
                    body
                )
            } catch (e: Exception) {
                println("Rappel en échec : ${e.message}")
            }
        }.start()
    }
}
