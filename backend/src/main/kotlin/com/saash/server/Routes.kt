package com.saash.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.mindrot.jbcrypt.BCrypt
import java.sql.ResultSet

private fun List<JsonObject>.arr(): JsonArray = JsonArray(this)

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

private fun JsonObject.cleanStr(key: String, max: Int = 200): String = (str(key) ?: "").trim().take(max)

private fun authResponse(uid: Int, org: Int, role: String, name: String, email: String, orgName: String, kind: String): JsonObject =
    buildJsonObject {
        put("token", Security.jwt(uid, org, role))
        put("user", buildJsonObject {
            put("id", uid); put("name", name); put("email", email); put("role", role)
            put("orgId", org); put("orgName", orgName); put("kind", kind)
        })
    }

private fun connectorJson(c: Connector, admin: Boolean): JsonObject = buildJsonObject {
    put("id", c.id); put("name", c.name); put("kind", c.kind)
    put("baseUrl", c.baseUrl); put("healthPath", c.healthPath)
    put("authType", c.authType); put("authName", c.authName)
    put("hasSecret", c.secretEnc.isNotEmpty())
    put("status", c.status); put("latency", c.latency)
    put("checks", c.checks)
    put("uptime", if (c.checks == 0) 0.0 else Math.round(c.okChecks * 1000.0 / c.checks) / 10.0)
    put("lastChecked", c.lastChecked)
    put("config", parseObject(c.config) ?: JsonObject(emptyMap()))
    if (admin) put("webhookUrl", Env.publicUrl + "/webhooks/" + c.webhookToken)
}

private fun requestJson(rs: ResultSet): JsonObject = buildJsonObject {
    put("id", rs.getInt("id")); put("connectorId", rs.getInt("connector_id").takeIf { !rs.wasNull() })
    put("requester", rs.getString("requester")); put("reason", rs.getString("reason"))
    put("state", rs.getString("state")); put("decidedBy", rs.getString("decided_by"))
    put("createdAt", rs.getLong("created_at"))
}

private fun orgFromApiKey(key: String?): Int {
    if (key.isNullOrBlank() || !key.startsWith("sk_")) err(401, "Clé API manquante ou invalide")
    return Db.query("SELECT id FROM orgs WHERE api_key=?", listOf(key)) { it.getInt(1) }.firstOrNull()
        ?: err(401, "Clé API manquante ou invalide")
}

/** Routes publiques : comptes, webhooks entrants, API par clé, retour OAuth. */
fun Route.publicRoutes() {
    get("/") { call.respondText("SaaS H API") }
    get("/health") { call.respond(buildJsonObject { put("ok", true) }) }

    post("/auth/register") {
        val b = call.receive<JsonObject>()
        val orgName = b.cleanStr("orgName")
        val kind = if (b.str("kind") == "vendor") "vendor" else "manager"
        val name = b.cleanStr("name")
        val email = b.cleanStr("email").lowercase()
        val password = b.str("password") ?: ""
        if (orgName.isBlank() || name.isBlank() || !email.contains("@")) err(400, "Champs manquants")
        if (password.length < 8) err(400, "Mot de passe : 8 caractères minimum")
        if (Db.query("SELECT 1 FROM users WHERE email=?", listOf(email)) { 1 }.isNotEmpty()) err(409, "Email déjà utilisé")
        val orgId = Db.insert(
            "INSERT INTO orgs(name,kind,created_at,api_key) VALUES(?,?,?,?) RETURNING id",
            listOf(orgName, kind, Repo.now(), "sk_" + Security.token(24))
        )
        val uid = Db.insert(
            "INSERT INTO users(org_id,email,name,pass_hash,role,created_at) VALUES(?,?,?,?,?,?) RETURNING id",
            listOf(orgId, email, name, BCrypt.hashpw(password, BCrypt.gensalt()), "ADMIN", Repo.now())
        )
        Defaults.seed(orgId, kind)
        call.respond(HttpStatusCode.Created, authResponse(uid, orgId, "ADMIN", name, email, orgName, kind))
    }

    post("/auth/login") {
        val b = call.receive<JsonObject>()
        val email = b.cleanStr("email").lowercase()
        val password = b.str("password") ?: ""
        Security.checkLoginAllowed(email)
        val row = Db.query(
            "SELECT u.id, u.org_id, u.name, u.pass_hash, u.role, o.name, o.kind FROM users u JOIN orgs o ON o.id=u.org_id WHERE u.email=?",
            listOf(email)
        ) { arrayOf<Any>(it.getInt(1), it.getInt(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)) }
            .firstOrNull()
        if (row == null || !BCrypt.checkpw(password, row[3] as String)) {
            Security.loginFailed(email)
            err(401, "Email ou mot de passe incorrect")
        }
        Security.loginOk(email)
        call.respond(
            authResponse(row[0] as Int, row[1] as Int, row[4] as String, row[2] as String, email, row[5] as String, row[6] as String)
        )
    }

    // Les SaaS envoient leurs événements ici (le jeton dans l'URL identifie le connecteur).
    post("/webhooks/{token}") {
        val c = Repo.connectorByToken(call.parameters["token"] ?: "") ?: err(404, "Inconnu")
        val text = call.receiveText()
        if (text.length > 100_000) err(413, "Message trop gros")
        val b = parseObject(text) ?: err(400, "JSON invalide")
        val type = b.cleanStr("type", 60).ifEmpty { "event" }
        val user = b.cleanStr("user", 120)
        val minutes = (b.int("minutes") ?: 0).coerceIn(0, 100_000)
        Repo.addEvent(c.orgId, c.id, type, user, minutes, text)
        when (type) {
            "access_request" -> Repo.createRequest(c.orgId, c.id, user.ifEmpty { "Inconnu" }, b.cleanStr("reason", 500), b.cleanStr("ref", 100))
            "incident" -> Repo.openIncident(c.orgId, c.id, c.name, b.cleanStr("title", 200).ifEmpty { "Incident signalé" })
            "resolved" -> Repo.resolveIncident(c.orgId, c.id, c.name)
            else -> Automations.fire(
                c.orgId, "event",
                mapOf("connector" to c.name, "connectorId" to c.id.toString(), "type" to type, "user" to user)
            )
        }
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- API publique pour VOTRE code (clé API dans l'en-tête X-Api-Key) ----------
    get("/v1/controls/{code}") {
        val orgId = orgFromApiKey(call.request.headers["X-Api-Key"])
        val c = Controls.byCode(orgId, call.parameters["code"] ?: "") ?: err(404, "Code inconnu")
        call.respond(buildJsonObject {
            put("code", c.code); put("name", c.name); put("type", c.type)
            if (c.type == "switch") put("on", c.state)
        })
    }

    post("/v1/controls/{code}/press") {
        val orgId = orgFromApiKey(call.request.headers["X-Api-Key"])
        val c = Controls.byCode(orgId, call.parameters["code"] ?: "") ?: err(404, "Code inconnu")
        val input = try { call.receive<JsonObject>() } catch (e: Exception) { null }
        call.respond(Controls.press(orgId, c, "api", input))
    }

    put("/v1/controls/{code}/state") {
        val orgId = orgFromApiKey(call.request.headers["X-Api-Key"])
        val c = Controls.byCode(orgId, call.parameters["code"] ?: "") ?: err(404, "Code inconnu")
        val on = call.receive<JsonObject>().bool("on") ?: err(400, "on requis")
        call.respond(Controls.toggle(orgId, c, on, "api"))
    }

    get("/oauth/callback") {
        val state = call.request.queryParameters["state"] ?: err(400, "state manquant")
        val code = call.request.queryParameters["code"] ?: err(400, "code manquant")
        val row = Db.query(
            "SELECT org_id, connector_id, ts FROM oauth_states WHERE state=?", listOf(state)
        ) { Triple(it.getInt(1), it.getInt(2), it.getLong(3)) }.firstOrNull() ?: err(400, "Session expirée")
        Db.exec("DELETE FROM oauth_states WHERE state=?", listOf(state))
        if (Repo.now() - row.third > 10 * 60_000) err(400, "Session expirée")
        val c = Repo.connector(row.first, row.second) ?: err(404, "Connecteur introuvable")
        val cfg = parseObject(c.config) ?: err(400, "Configuration OAuth manquante")
        val res = Http.call(
            "POST", cfg.str("tokenUrl") ?: err(400, "tokenUrl manquant"),
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            Http.form(
                "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to Env.publicUrl + "/oauth/callback",
                "client_id" to (cfg.str("clientId") ?: ""), "client_secret" to (cfg.str("clientSecret") ?: "")
            )
        )
        val token = parseObject(res.body)?.str("access_token")
        if (token == null) {
            call.respondText("Échec de la connexion OAuth (code ${res.code}).", ContentType.Text.Html, HttpStatusCode.BadGateway)
        } else {
            Db.exec(
                "UPDATE connectors SET secret=?, auth_type='bearer' WHERE id=?",
                listOf(Security.enc(token), c.id)
            )
            call.respondText("<h2>${c.name} est connecté ✅</h2><p>Vous pouvez retourner dans l'application SaaS H.</p>", ContentType.Text.Html)
        }
    }
}

/** Routes protégées par JWT. */
fun Route.apiRoutes() {

    get("/me") {
        val x = call.ctx()
        val row = Db.query(
            "SELECT u.name, u.email, o.name, o.kind FROM users u JOIN orgs o ON o.id=u.org_id WHERE u.id=?", listOf(x.uid)
        ) { arrayOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }.firstOrNull() ?: err(401, "Compte introuvable")
        call.respond(buildJsonObject {
            put("id", x.uid); put("name", row[0]); put("email", row[1]); put("role", x.role)
            put("orgId", x.orgId); put("orgName", row[2]); put("kind", row[3])
        })
    }

    // ---------- Utilisateurs de l'organisation ----------
    get("/users") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        call.respond(Db.query("SELECT id,name,email,role FROM users WHERE org_id=? ORDER BY id", listOf(x.orgId)) {
            buildJsonObject { put("id", it.getInt(1)); put("name", it.getString(2)); put("email", it.getString(3)); put("role", it.getString(4)) }
        }.arr())
    }

    post("/users") {
        val x = call.ctx(); x.need("ADMIN")
        val b = call.receive<JsonObject>()
        val role = b.str("role")?.uppercase()
        if (role != "MEMBER" && role != "CLIENT" && role != "ADMIN") err(400, "Rôle invalide")
        val email = b.cleanStr("email").lowercase()
        val password = b.str("password") ?: ""
        if (b.cleanStr("name").isBlank() || !email.contains("@")) err(400, "Champs manquants")
        if (password.length < 8) err(400, "Mot de passe : 8 caractères minimum")
        if (Db.query("SELECT 1 FROM users WHERE email=?", listOf(email)) { 1 }.isNotEmpty()) err(409, "Email déjà utilisé")
        val id = Db.insert(
            "INSERT INTO users(org_id,email,name,pass_hash,role,created_at) VALUES(?,?,?,?,?,?) RETURNING id",
            listOf(x.orgId, email, b.cleanStr("name"), BCrypt.hashpw(password, BCrypt.gensalt()), role, Repo.now())
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    delete("/users/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        val id = call.pathId()
        if (id == x.uid) err(400, "Vous ne pouvez pas vous supprimer")
        Db.exec("DELETE FROM users WHERE id=? AND org_id=?", listOf(id, x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Connecteurs ----------
    get("/connectors") {
        val x = call.ctx()
        call.respond(Repo.connectors(x.orgId).map { connectorJson(it, x.role == "ADMIN") }.arr())
    }

    post("/connectors") {
        val x = call.ctx(); x.need("ADMIN")
        val b = call.receive<JsonObject>()
        val name = b.cleanStr("name")
        if (name.isBlank()) err(400, "Nom requis")
        val baseUrl = b.cleanStr("baseUrl", 500)
        if (baseUrl.isNotEmpty()) Ssrf.check(baseUrl)
        val kind = b.str("kind").takeIf { it in listOf("apikey", "oauth", "webhook", "custom") } ?: "apikey"
        val authType = b.str("authType").takeIf { it in listOf("none", "bearer", "header", "query") } ?: "none"
        val id = Db.insert(
            "INSERT INTO connectors(org_id,name,kind,base_url,health_path,auth_type,auth_name,secret,config,webhook_token) VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id",
            listOf(
                x.orgId, name, kind, baseUrl, b.cleanStr("healthPath", 300), authType, b.cleanStr("authName", 100),
                Security.enc(b.str("secret") ?: ""), (b.obj("config") ?: JsonObject(emptyMap())).toString(), Security.token()
            )
        )
        Repo.connector(x.orgId, id)?.let { c ->
            if (c.baseUrl.isNotEmpty()) Thread { runCatching { Repo.checkOne(c) } }.start()
        }
        call.respond(HttpStatusCode.Created, connectorJson(Repo.connector(x.orgId, id)!!, true))
    }

    put("/connectors/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        val c = Repo.connector(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        val b = call.receive<JsonObject>()
        val baseUrl = if (b.containsKey("baseUrl")) b.cleanStr("baseUrl", 500) else c.baseUrl
        if (baseUrl.isNotEmpty()) Ssrf.check(baseUrl)
        val secret = b.str("secret")
        Db.exec(
            "UPDATE connectors SET name=?, base_url=?, health_path=?, auth_type=?, auth_name=?, secret=?, config=? WHERE id=? AND org_id=?",
            listOf(
                if (b.containsKey("name")) b.cleanStr("name") else c.name, baseUrl,
                if (b.containsKey("healthPath")) b.cleanStr("healthPath", 300) else c.healthPath,
                b.str("authType").takeIf { it in listOf("none", "bearer", "header", "query") } ?: c.authType,
                if (b.containsKey("authName")) b.cleanStr("authName", 100) else c.authName,
                if (secret.isNullOrBlank()) c.secretEnc else Security.enc(secret),
                b.obj("config")?.toString() ?: c.config, c.id, x.orgId
            )
        )
        call.respond(connectorJson(Repo.connector(x.orgId, c.id)!!, true))
    }

    delete("/connectors/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        val id = call.pathId()
        Db.exec("DELETE FROM connectors WHERE id=? AND org_id=?", listOf(id, x.orgId))
        Db.exec("DELETE FROM controls WHERE connector_id=? AND org_id=?", listOf(id, x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    post("/connectors/{id}/check") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val c = Repo.connector(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        if (c.baseUrl.isBlank()) err(400, "Pas d'URL à vérifier")
        Repo.checkOne(c)
        call.respond(connectorJson(Repo.connector(x.orgId, c.id)!!, x.role == "ADMIN"))
    }

    get("/oauth/{id}/start") {
        val x = call.ctx(); x.need("ADMIN")
        val c = Repo.connector(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        val cfg = parseObject(c.config) ?: err(400, "Configuration OAuth manquante")
        val authUrl = cfg.str("authUrl") ?: err(400, "authUrl manquant dans la configuration")
        val state = Security.token(16)
        Db.exec("DELETE FROM oauth_states WHERE ts < ?", listOf(Repo.now() - 10 * 60_000))
        Db.exec("INSERT INTO oauth_states(state,org_id,connector_id,ts) VALUES(?,?,?,?)", listOf(state, x.orgId, c.id, Repo.now()))
        val url = authUrl + (if (authUrl.contains("?")) "&" else "?") + Http.form(
            "response_type" to "code", "client_id" to (cfg.str("clientId") ?: ""),
            "redirect_uri" to Env.publicUrl + "/oauth/callback", "scope" to (cfg.str("scope") ?: ""), "state" to state
        )
        call.respond(buildJsonObject { put("url", url) })
    }

    // ---------- Demandes d'accès ----------
    get("/requests") {
        val x = call.ctx()
        val sql = if (x.role == "CLIENT")
            "SELECT * FROM access_requests WHERE org_id=? AND requester=(SELECT name FROM users WHERE id=${x.uid}) ORDER BY id DESC LIMIT 200"
        else "SELECT * FROM access_requests WHERE org_id=? ORDER BY id DESC LIMIT 200"
        call.respond(Db.query(sql, listOf(x.orgId)) { requestJson(it) }.arr())
    }

    post("/requests") {
        val x = call.ctx()
        val b = call.receive<JsonObject>()
        val requester = b.cleanStr("requester").ifEmpty { "Utilisateur" }
        val id = Repo.createRequest(x.orgId, b.int("connectorId"), requester, b.cleanStr("reason", 500))
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    post("/requests/{id}/decide") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val id = call.pathId()
        val accept = call.receive<JsonObject>().bool("accept") ?: err(400, "accept requis")
        val who = Db.query("SELECT name FROM users WHERE id=?", listOf(x.uid)) { it.getString(1) }.firstOrNull() ?: ""
        val n = Db.exec(
            "UPDATE access_requests SET state=?, decided_by=?, decided_at=? WHERE id=? AND org_id=? AND state='pending'",
            listOf(if (accept) "accepted" else "refused", who, Repo.now(), id, x.orgId)
        )
        if (n == 0) err(409, "Demande déjà traitée ou introuvable")
        val state = if (accept) "accepted" else "refused"
        val info = Db.query("SELECT requester, ref FROM access_requests WHERE id=?", listOf(id)) { Pair(it.getString(1), it.getString(2)) }.firstOrNull()
        // Le SaaS d'origine est prévenu par le rappel signé : il peut accorder ou refuser l'accès.
        Callbacks.send(
            x.orgId,
            buildJsonObject {
                put("type", "request_decided"); put("state", state); put("user", info?.first ?: "")
                put("ref", info?.second ?: ""); put("by", who); put("ts", Repo.now())
            }
        )
        Automations.fire(x.orgId, "request_decided", mapOf("state" to state, "by" to who, "user" to (info?.first ?: "")))
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Usage, incidents, résumé ----------
    get("/usage") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        call.respond(
            Db.query(
                """SELECT e.user_ref, COALESCE(c.name,'') cname, COUNT(*) sessions, COALESCE(SUM(e.minutes),0) minutes, MAX(e.ts) last_ts
                   FROM events e LEFT JOIN connectors c ON c.id=e.connector_id
                   WHERE e.org_id=? AND e.type='usage' AND e.user_ref<>''
                   GROUP BY e.user_ref, c.name ORDER BY minutes DESC LIMIT 200""",
                listOf(x.orgId)
            ) {
                buildJsonObject {
                    put("user", it.getString(1)); put("connector", it.getString(2))
                    put("sessions", it.getInt(3)); put("minutes", it.getInt(4)); put("last", it.getLong(5))
                }
            }.arr()
        )
    }

    get("/incidents") {
        val x = call.ctx()
        call.respond(
            Db.query(
                "SELECT i.id, i.title, i.started_at, i.resolved_at, COALESCE(c.name,'') FROM incidents i LEFT JOIN connectors c ON c.id=i.connector_id WHERE i.org_id=? ORDER BY i.id DESC LIMIT 100",
                listOf(x.orgId)
            ) {
                buildJsonObject {
                    put("id", it.getInt(1)); put("title", it.getString(2)); put("startedAt", it.getLong(3))
                    put("resolvedAt", it.getLong(4)); put("connector", it.getString(5))
                }
            }.arr()
        )
    }

    get("/summary") {
        val x = call.ctx()
        val org = listOf(x.orgId)
        fun count(sql: String, params: List<Any?> = org): Int = Db.query(sql, params) { it.getInt(1) }.first()
        val day = Repo.now() - 86_400_000L
        val chart = Db.query(
            "SELECT to_char(to_timestamp(ts/1000.0),'DD/MM') d, COUNT(*) FROM events WHERE org_id=? AND ts>? GROUP BY d ORDER BY MIN(ts)",
            listOf(x.orgId, Repo.now() - 7 * 86_400_000L)
        ) { Pair(it.getString(1), it.getInt(2)) }
        call.respond(buildJsonObject {
            put("servicesTotal", count("SELECT COUNT(*) FROM connectors WHERE org_id=?"))
            put("servicesUp", count("SELECT COUNT(*) FROM connectors WHERE org_id=? AND status='up'"))
            put("servicesDegraded", count("SELECT COUNT(*) FROM connectors WHERE org_id=? AND status='degraded'"))
            put("servicesDown", count("SELECT COUNT(*) FROM connectors WHERE org_id=? AND status='down'"))
            put("requestsPending", count("SELECT COUNT(*) FROM access_requests WHERE org_id=? AND state='pending'"))
            put("incidentsOpen", count("SELECT COUNT(*) FROM incidents WHERE org_id=? AND resolved_at=0"))
            put("usersTotal", count("SELECT COUNT(*) FROM users WHERE org_id=?"))
            put("usersActive", count("SELECT COUNT(DISTINCT user_ref) FROM events WHERE org_id=? AND ts>? AND user_ref<>''", listOf(x.orgId, day)))
            put("events24h", count("SELECT COUNT(*) FROM events WHERE org_id=? AND ts>?", listOf(x.orgId, day)))
            putJsonArray("events7d") {
                chart.forEach { (label, v) -> add(buildJsonObject { put("label", label); put("value", v) }) }
            }
        })
    }

    // ---------- Tableaux de bord personnalisables ----------
    get("/dashboards") {
        val x = call.ctx()
        call.respond(
            Db.query("SELECT id,name,layout FROM dashboards WHERE org_id=? ORDER BY id", listOf(x.orgId)) {
                buildJsonObject {
                    put("id", it.getInt(1)); put("name", it.getString(2))
                    put("layout", parseObject(it.getString(3)) ?: JsonObject(emptyMap()))
                }
            }.arr()
        )
    }

    post("/dashboards") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        val layout = b.obj("layout") ?: err(400, "layout requis")
        if (layout.toString().length > 100_000) err(413, "Layout trop gros")
        val id = Db.insert(
            "INSERT INTO dashboards(org_id,name,layout,updated_at) VALUES(?,?,?,?) RETURNING id",
            listOf(x.orgId, b.cleanStr("name").ifEmpty { "Nouveau tableau" }, layout.toString(), Repo.now())
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    put("/dashboards/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        val layout = b.obj("layout") ?: err(400, "layout requis")
        if (layout.toString().length > 100_000) err(413, "Layout trop gros")
        val n = Db.exec(
            "UPDATE dashboards SET name=?, layout=?, updated_at=? WHERE id=? AND org_id=?",
            listOf(b.cleanStr("name").ifEmpty { "Tableau" }, layout.toString(), Repo.now(), call.pathId(), x.orgId)
        )
        if (n == 0) err(404, "Introuvable")
        call.respond(buildJsonObject { put("ok", true) })
    }

    delete("/dashboards/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        Db.exec("DELETE FROM dashboards WHERE id=? AND org_id=?", listOf(call.pathId(), x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Organigrammes (plusieurs, à partir de modèles) ----------
    get("/charts") {
        val x = call.ctx()
        val charts = Db.query("SELECT id,name FROM org_charts WHERE org_id=? ORDER BY id", listOf(x.orgId)) { Pair(it.getInt(1), it.getString(2)) }
        val nodes = Db.query("SELECT id,chart_id,parent_id,name,title FROM org_nodes WHERE org_id=? ORDER BY id", listOf(x.orgId)) { rs ->
            val chart = rs.getInt(2)
            val parent = rs.getInt(3).takeIf { !rs.wasNull() }
            Pair(
                chart,
                buildJsonObject {
                    put("id", rs.getInt(1)); put("parentId", parent)
                    put("name", rs.getString(4)); put("title", rs.getString(5))
                }
            )
        }
        call.respond(
            charts.map { (id, name) ->
                buildJsonObject {
                    put("id", id); put("name", name)
                    put("nodes", JsonArray(nodes.filter { it.first == id }.map { it.second }))
                }
            }.arr()
        )
    }

    post("/charts") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        val template = b.str("template")?.takeIf { it.isNotBlank() }
        if (template != null && Catalog.orgTemplates.none { it.key == template }) err(400, "Modèle inconnu")
        val name = b.cleanStr("name").ifEmpty { Catalog.orgTemplates.firstOrNull { it.key == template }?.name ?: "Organigramme" }
        val count = Db.query("SELECT COUNT(*) FROM org_charts WHERE org_id=?", listOf(x.orgId)) { it.getInt(1) }.first()
        if (count >= 20) err(400, "Limite de 20 organigrammes atteinte")
        val id = Db.insert("INSERT INTO org_charts(org_id,name) VALUES(?,?) RETURNING id", listOf(x.orgId, name))
        if (template != null) Catalog.applyOrgTemplate(x.orgId, id, template)
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    put("/charts/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        val n = Db.exec("UPDATE org_charts SET name=? WHERE id=? AND org_id=?", listOf(b.cleanStr("name").ifEmpty { "Organigramme" }, call.pathId(), x.orgId))
        if (n == 0) err(404, "Introuvable")
        call.respond(buildJsonObject { put("ok", true) })
    }

    delete("/charts/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val id = call.pathId()
        Db.exec("DELETE FROM org_nodes WHERE chart_id=? AND org_id=?", listOf(id, x.orgId))
        Db.exec("DELETE FROM org_charts WHERE id=? AND org_id=?", listOf(id, x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    post("/org") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        if (b.cleanStr("name").isBlank()) err(400, "Nom requis")
        val chart = b.int("chartId") ?: err(400, "chartId requis")
        if (Db.query("SELECT 1 FROM org_charts WHERE id=? AND org_id=?", listOf(chart, x.orgId)) { 1 }.isEmpty()) err(404, "Organigramme introuvable")
        val parent = b.int("parentId")
        if (parent != null && Db.query("SELECT 1 FROM org_nodes WHERE id=? AND org_id=? AND chart_id=?", listOf(parent, x.orgId, chart)) { 1 }.isEmpty()) err(400, "Parent invalide")
        val id = Db.insert(
            "INSERT INTO org_nodes(org_id,chart_id,parent_id,name,title) VALUES(?,?,?,?,?) RETURNING id",
            listOf(x.orgId, chart, parent, b.cleanStr("name"), b.cleanStr("title"))
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    put("/org/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val id = call.pathId()
        val b = call.receive<JsonObject>()
        val parent = b.int("parentId")
        if (parent != null) {
            // empêche les boucles : on remonte la chaîne des parents
            var cur: Int? = parent
            var steps = 0
            while (cur != null && steps < 200) {
                if (cur == id) err(400, "Boucle dans l'organigramme")
                cur = Db.query("SELECT parent_id FROM org_nodes WHERE id=? AND org_id=?", listOf(cur, x.orgId)) {
                    it.getInt(1).takeIf { _ -> !it.wasNull() }
                }.firstOrNull()
                steps++
            }
        }
        val n = Db.exec(
            "UPDATE org_nodes SET name=?, title=?, parent_id=? WHERE id=? AND org_id=?",
            listOf(b.cleanStr("name").ifEmpty { "Sans nom" }, b.cleanStr("title"), parent, id, x.orgId)
        )
        if (n == 0) err(404, "Introuvable")
        call.respond(buildJsonObject { put("ok", true) })
    }

    delete("/org/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val id = call.pathId()
        val parent = Db.query("SELECT parent_id FROM org_nodes WHERE id=? AND org_id=?", listOf(id, x.orgId)) {
            it.getInt(1).takeIf { _ -> !it.wasNull() }
        }.firstOrNull()
        Db.exec("UPDATE org_nodes SET parent_id=? WHERE parent_id=? AND org_id=?", listOf(parent, id, x.orgId))
        Db.exec("DELETE FROM org_nodes WHERE id=? AND org_id=?", listOf(id, x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Catalogue : modèles de SaaS, types de boutons, modèles d'organigramme ----------
    get("/catalog") {
        call.ctx()
        call.respond(Catalog.json())
    }

    // ---------- Boutons et interrupteurs (avec code / ID) ----------
    get("/controls") {
        val x = call.ctx()
        val names = Repo.connectors(x.orgId).associate { it.id to it.name }
        call.respond(Controls.list(x.orgId).map { Controls.json(it, names[it.connectorId] ?: "") }.arr())
    }

    post("/controls") {
        val x = call.ctx(); x.need("ADMIN")
        val b = call.receive<JsonObject>()
        val type = if (b.str("type") == "switch") "switch" else "button"
        val kind = if (type == "switch") "switch" else (b.str("kind") ?: "api_call")
        val def = Catalog.buttonKind(kind)
        if (type == "button" && def == null) err(400, "Type de bouton inconnu")
        val name = b.cleanStr("name")
        if (name.isBlank()) err(400, "Nom requis")
        val connectorId = b.int("connectorId")
        if (connectorId != null) Repo.connector(x.orgId, connectorId) ?: err(404, "Connecteur introuvable")
        val external = type == "button" && def!!.scope == "external"
        if (external && connectorId == null) err(400, "Choisissez le SaaS à appeler")
        val method = Controls.validMethod(b.str("method") ?: def?.method ?: "POST")
        val path = b.cleanStr("path", 500).ifEmpty { def?.path ?: "" }
        val body = b.cleanStr("body", 5000).ifEmpty { def?.body ?: "" }
        val code = Controls.checkCode(x.orgId, b.cleanStr("code", 40), name)
        val id = Db.insert(
            "INSERT INTO controls(org_id,type,kind,name,code,connector_id,method,path,body,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
            listOf(x.orgId, type, kind, name, code, connectorId, method, path, body, b.bool("state") ?: false, Repo.now())
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id); put("code", code) })
    }

    put("/controls/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        val c = Controls.byId(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        val b = call.receive<JsonObject>()
        val name = if (b.containsKey("name")) b.cleanStr("name").ifEmpty { c.name } else c.name
        val code = if (b.containsKey("code") && b.cleanStr("code", 40).isNotEmpty()) Controls.checkCode(x.orgId, b.cleanStr("code", 40), name, c.id) else c.code
        val connectorId = if (b.containsKey("connectorId")) b.int("connectorId") else c.connectorId
        if (connectorId != null) Repo.connector(x.orgId, connectorId) ?: err(404, "Connecteur introuvable")
        Db.exec(
            "UPDATE controls SET name=?, code=?, connector_id=?, method=?, path=?, body=? WHERE id=? AND org_id=?",
            listOf(
                name, code, connectorId,
                if (b.containsKey("method")) Controls.validMethod(b.str("method")) else c.method,
                if (b.containsKey("path")) b.cleanStr("path", 500) else c.path,
                if (b.containsKey("body")) b.cleanStr("body", 5000) else c.body,
                c.id, x.orgId
            )
        )
        call.respond(buildJsonObject { put("ok", true); put("code", code) })
    }

    delete("/controls/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        Db.exec("DELETE FROM controls WHERE id=? AND org_id=?", listOf(call.pathId(), x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    post("/controls/{id}/press") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val c = Controls.byId(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        val input = try { call.receive<JsonObject>() } catch (e: Exception) { null }
        val who = Db.query("SELECT name FROM users WHERE id=?", listOf(x.uid)) { it.getString(1) }.firstOrNull() ?: ""
        call.respond(Controls.press(x.orgId, c, who, input))
    }

    post("/controls/{id}/toggle") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val c = Controls.byId(x.orgId, call.pathId()) ?: err(404, "Introuvable")
        val on = call.receive<JsonObject>().bool("on") ?: err(400, "on requis")
        val who = Db.query("SELECT name FROM users WHERE id=?", listOf(x.uid)) { it.getString(1) }.firstOrNull() ?: ""
        call.respond(Controls.toggle(x.orgId, c, on, who))
    }

    // ---------- Intégration : clé API et URL de rappel ----------
    get("/org-settings") {
        val x = call.ctx(); x.need("ADMIN")
        val row = Db.query("SELECT api_key, callback_url FROM orgs WHERE id=?", listOf(x.orgId)) { Pair(it.getString(1), it.getString(2)) }.first()
        call.respond(buildJsonObject {
            put("apiKey", row.first); put("callbackUrl", row.second); put("publicUrl", Env.publicUrl)
        })
    }

    put("/org-settings") {
        val x = call.ctx(); x.need("ADMIN")
        val url = call.receive<JsonObject>().cleanStr("callbackUrl", 500)
        if (url.isNotEmpty()) Ssrf.check(url)
        Db.exec("UPDATE orgs SET callback_url=? WHERE id=?", listOf(url, x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    post("/org-settings/rotate-key") {
        val x = call.ctx(); x.need("ADMIN")
        val key = "sk_" + Security.token(24)
        Db.exec("UPDATE orgs SET api_key=? WHERE id=?", listOf(key, x.orgId))
        call.respond(buildJsonObject { put("apiKey", key) })
    }

    // ---------- Formulaires ----------
    get("/forms") {
        val x = call.ctx()
        call.respond(
            Db.query("SELECT id,name,fields,target FROM forms WHERE org_id=? ORDER BY id", listOf(x.orgId)) {
                buildJsonObject {
                    put("id", it.getInt(1)); put("name", it.getString(2))
                    put("fields", try { Json.parseToJsonElement(it.getString(3)) } catch (e: Exception) { JsonArray(emptyList()) })
                    put("target", it.getString(4))
                }
            }.arr()
        )
    }

    post("/forms") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        val b = call.receive<JsonObject>()
        val fields = b["fields"] as? JsonArray ?: err(400, "fields requis")
        if (b.cleanStr("name").isBlank()) err(400, "Nom requis")
        val target = if (b.str("target") == "access_request") "access_request" else "custom"
        val id = Db.insert(
            "INSERT INTO forms(org_id,name,fields,target) VALUES(?,?,?,?) RETURNING id",
            listOf(x.orgId, b.cleanStr("name"), fields.toString(), target)
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    delete("/forms/{id}") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        Db.exec("DELETE FROM forms WHERE id=? AND org_id=?", listOf(call.pathId(), x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    post("/forms/{id}/submit") {
        val x = call.ctx()
        val id = call.pathId()
        val target = Db.query("SELECT target FROM forms WHERE id=? AND org_id=?", listOf(id, x.orgId)) { it.getString(1) }
            .firstOrNull() ?: err(404, "Formulaire introuvable")
        val data = call.receive<JsonObject>()
        if (data.toString().length > 20_000) err(413, "Données trop grosses")
        val who = Db.query("SELECT name FROM users WHERE id=?", listOf(x.uid)) { it.getString(1) }.firstOrNull() ?: ""
        Db.exec(
            "INSERT INTO form_submissions(org_id,form_id,data,submitter,ts) VALUES(?,?,?,?,?)",
            listOf(x.orgId, id, data.toString(), who, Repo.now())
        )
        if (target == "access_request") {
            Repo.createRequest(x.orgId, data.int("connectorId"), data.cleanStr("user").ifEmpty { who }, data.cleanStr("reason", 500))
        } else {
            Repo.notify(x.orgId, "Formulaire reçu", "$who a envoyé un formulaire")
        }
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Automatisations ----------
    get("/automations") {
        val x = call.ctx(); x.need("ADMIN", "MEMBER")
        call.respond(
            Db.query("SELECT id,name,trigger,config,action,enabled FROM automations WHERE org_id=? ORDER BY id", listOf(x.orgId)) {
                buildJsonObject {
                    put("id", it.getInt(1)); put("name", it.getString(2)); put("trigger", it.getString(3))
                    put("config", parseObject(it.getString(4)) ?: JsonObject(emptyMap()))
                    put("action", parseObject(it.getString(5)) ?: JsonObject(emptyMap()))
                    put("enabled", it.getBoolean(6))
                }
            }.arr()
        )
    }

    post("/automations") {
        val x = call.ctx(); x.need("ADMIN")
        val b = call.receive<JsonObject>()
        val trigger = b.str("trigger")
        if (trigger !in listOf("incident_opened", "incident_resolved", "request_created", "request_decided", "event")) err(400, "Déclencheur invalide")
        val action = b.obj("action") ?: err(400, "action requise")
        if (action.str("type") !in listOf("notify", "run_action", "webhook")) err(400, "Type d'action invalide")
        action.str("url")?.let { Ssrf.check(it) }
        val id = Db.insert(
            "INSERT INTO automations(org_id,name,trigger,config,action) VALUES(?,?,?,?,?) RETURNING id",
            listOf(x.orgId, b.cleanStr("name").ifEmpty { "Règle" }, trigger, (b.obj("config") ?: JsonObject(emptyMap())).toString(), action.toString())
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", id) })
    }

    delete("/automations/{id}") {
        val x = call.ctx(); x.need("ADMIN")
        Db.exec("DELETE FROM automations WHERE id=? AND org_id=?", listOf(call.pathId(), x.orgId))
        call.respond(buildJsonObject { put("ok", true) })
    }

    // ---------- Notifications (l'app les interroge régulièrement) ----------
    get("/notifications") {
        val x = call.ctx()
        val after = call.request.queryParameters["after"]?.toIntOrNull() ?: 0
        call.respond(
            Db.query(
                "SELECT id,title,body,ts FROM notifications WHERE org_id=? AND id>? ORDER BY id DESC LIMIT 50",
                listOf(x.orgId, after)
            ) {
                buildJsonObject {
                    put("id", it.getInt(1)); put("title", it.getString(2)); put("body", it.getString(3)); put("ts", it.getLong(4))
                }
            }.arr()
        )
    }
}

/** Contenu de départ pour une nouvelle organisation. */
object Defaults {
    private fun widget(id: String, type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("id", id); put("type", type); block() }

    fun seed(orgId: Int, kind: String) {
        val widgets: List<JsonElement> = if (kind == "vendor") listOf(
            widget("w1", "stat") { put("title", "Utilisateurs actifs (24 h)"); put("metric", "usersActive") },
            widget("w2", "stat") { put("title", "Événements (24 h)"); put("metric", "events24h") },
            widget("w3", "stat") { put("title", "Pannes en cours"); put("metric", "incidentsOpen") },
            widget("w4", "chart") { put("title", "Activité sur 7 jours"); put("metric", "events7d") },
            widget("w5", "usage") { put("title", "Usage par client") },
            widget("w6", "status_list") { put("title", "Services") }
        ) else listOf(
            widget("w1", "stat") { put("title", "Services OK"); put("metric", "servicesUp") },
            widget("w2", "stat") { put("title", "Pannes en cours"); put("metric", "incidentsOpen") },
            widget("w3", "stat") { put("title", "Demandes en attente"); put("metric", "requestsPending") },
            widget("w4", "status_list") { put("title", "Services") },
            widget("w5", "requests") { put("title", "Demandes d'accès") },
            widget("w6", "incidents") { put("title", "Pannes récentes") }
        )
        val layout = buildJsonObject { put("widgets", JsonArray(widgets)) }
        Db.exec(
            "INSERT INTO dashboards(org_id,name,layout,updated_at) VALUES(?,?,?,?)",
            listOf(orgId, "Tableau principal", layout.toString(), Repo.now())
        )
        val fields = buildJsonArray {
            add(buildJsonObject { put("name", "user"); put("label", "Votre nom"); put("type", "text") })
            add(buildJsonObject { put("name", "reason"); put("label", "Motif de la demande"); put("type", "text") })
        }
        Db.exec(
            "INSERT INTO forms(org_id,name,fields,target) VALUES(?,?,?,?)",
            listOf(orgId, "Demande d'accès", fields.toString(), "access_request")
        )
        val template = if (kind == "vendor") "vendor" else "support_ops"
        val chartId = Db.insert(
            "INSERT INTO org_charts(org_id,name) VALUES(?,?) RETURNING id",
            listOf(orgId, Catalog.orgTemplates.first { it.key == template }.name)
        )
        Catalog.applyOrgTemplate(orgId, chartId, template)
        Db.exec(
            "INSERT INTO controls(org_id,type,kind,name,code,method,path,body,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(orgId, "button", "check_now", "Vérifier maintenant", "check-now", "POST", "", "", false, Repo.now())
        )
        Db.exec(
            "INSERT INTO controls(org_id,type,kind,name,code,method,path,body,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            listOf(orgId, "switch", "switch", "Mode maintenance", "maintenance", "POST", "", "", false, Repo.now())
        )
    }
}
