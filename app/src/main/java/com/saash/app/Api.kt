package com.saash.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiError(message: String) : Exception(message)

/** Client HTTP du backend SaaS H. L'adresse du serveur et le jeton sont gardés sur le téléphone. */
class Api(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("saash_api", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var lastNotifId: Int
        get() = prefs.getInt("lastNotif", 0)
        set(v) = prefs.edit().putInt("lastNotif", v).apply()

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    var meJson: String
        get() = prefs.getString("me", "") ?: ""
        set(v) = prefs.edit().putString("me", v).apply()

    fun logout() {
        prefs.edit().remove("token").remove("me").remove("lastNotif").apply()
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) throw ApiError("Adresse du serveur manquante")
            var c: HttpURLConnection? = null
            try {
                c = URL(baseUrl + path).openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = 20000
                c.readTimeout = 60000 // un serveur gratuit peut mettre du temps à se réveiller
                c.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    if (code == 401 && token.isNotBlank()) throw ApiError(SESSION_EXPIRED)
                    val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                    throw ApiError(if (msg.isBlank()) "Erreur $code" else msg)
                }
                text
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError("Connexion impossible : ${e.message}")
            } finally {
                c?.disconnect()
            }
        }

    companion object {
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
    }
}

// ---------- Modèles ----------
data class Me(val id: Int, val name: String, val email: String, val role: String, val orgName: String, val kind: String)

data class Conn(
    val id: Int, val name: String, val kind: String, val baseUrl: String, val healthPath: String,
    val status: String, val latency: Int, val uptime: Double, val lastChecked: Long,
    val webhookUrl: String, val authName: String = ""
)

data class Req(val id: Int, val requester: String, val reason: String, val state: String, val decidedBy: String, val createdAt: Long)
data class Incident(val id: Int, val title: String, val connector: String, val startedAt: Long, val resolvedAt: Long)
data class UsageRow(val user: String, val connector: String, val sessions: Int, val minutes: Int)
data class OrgNode(val id: Int, val parentId: Int?, val name: String, val title: String)
data class Control(
    val id: Int, val type: String, val kind: String, val name: String, val code: String,
    val connectorId: Int?, val connector: String, val method: String, val path: String,
    val body: String, val state: Boolean
) {
    val isSwitch: Boolean get() = type == "switch"
}
data class OrgChart(val id: Int, val name: String, val nodes: List<OrgNode>)
data class KindDef(val key: String, val label: String, val description: String, val scope: String, val method: String, val path: String, val body: String)
data class SaasTemplate(val key: String, val name: String, val authName: String, val healthPath: String, val hint: String)
data class OrgTemplate(val key: String, val name: String, val description: String, val count: Int)
data class Catalog(val kinds: List<KindDef>, val saas: List<SaasTemplate>, val orgs: List<OrgTemplate>)
data class OrgSettings(val apiKey: String, val callbackUrl: String, val publicUrl: String)
data class FormField(val name: String, val label: String)
data class FormDef(val id: Int, val name: String, val target: String, val fields: List<FormField>)
data class Automation(val id: Int, val name: String, val trigger: String, val actionType: String)
data class TeamUser(val id: Int, val name: String, val email: String, val role: String)
data class Notif(val id: Int, val title: String, val body: String)

data class Widget(
    val id: String, val type: String, val title: String,
    val metric: String = "", val refId: Int = 0, val text: String = ""
)

data class Dashboard(val id: Int, val name: String, val widgets: List<Widget>)

data class Summary(val values: Map<String, Int>, val chart: List<Pair<String, Int>>)

// ---------- Analyse JSON ----------
fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)

fun JSONObject.toMe() = Me(optInt("id"), optString("name"), optString("email"), optString("role"), optString("orgName"), optString("kind"))

fun JSONObject.toConn() = Conn(
    getInt("id"), optString("name"), optString("kind"), optString("baseUrl"), optString("healthPath"),
    optString("status"), optInt("latency"), optDouble("uptime", 0.0), optLong("lastChecked"), optString("webhookUrl"), optString("authName")
)

fun JSONObject.toReq() = Req(getInt("id"), optString("requester"), optString("reason"), optString("state"), optString("decidedBy"), optLong("createdAt"))
fun JSONObject.toIncident() = Incident(getInt("id"), optString("title"), optString("connector"), optLong("startedAt"), optLong("resolvedAt"))
fun JSONObject.toUsage() = UsageRow(optString("user"), optString("connector"), optInt("sessions"), optInt("minutes"))
fun JSONObject.toOrgNode() = OrgNode(getInt("id"), intOrNull("parentId"), optString("name"), optString("title"))
fun JSONObject.toControl() = Control(
    getInt("id"), optString("type"), optString("kind"), optString("name"), optString("code"),
    if (isNull("connectorId")) null else optInt("connectorId"), optString("connector"),
    optString("method"), optString("path"), optString("body"), optBoolean("state")
)
fun JSONObject.toChart(): OrgChart =
    OrgChart(getInt("id"), optString("name"), (optJSONArray("nodes") ?: JSONArray()).objs().map { it.toOrgNode() })
fun JSONObject.toCatalog() = Catalog(
    (optJSONArray("buttonKinds") ?: JSONArray()).objs().map {
        KindDef(it.optString("key"), it.optString("label"), it.optString("description"), it.optString("scope"), it.optString("method"), it.optString("path"), it.optString("body"))
    },
    (optJSONArray("saasTemplates") ?: JSONArray()).objs().map {
        SaasTemplate(it.optString("key"), it.optString("name"), it.optString("authName"), it.optString("healthPath"), it.optString("hint"))
    },
    (optJSONArray("orgTemplates") ?: JSONArray()).objs().map {
        OrgTemplate(it.optString("key"), it.optString("name"), it.optString("description"), it.optInt("count"))
    }
)
fun JSONObject.toOrgSettings() = OrgSettings(optString("apiKey"), optString("callbackUrl"), optString("publicUrl"))
fun JSONObject.toTeamUser() = TeamUser(getInt("id"), optString("name"), optString("email"), optString("role"))
fun JSONObject.toNotif() = Notif(getInt("id"), optString("title"), optString("body"))

fun JSONObject.toForm(): FormDef {
    val arr = optJSONArray("fields") ?: JSONArray()
    return FormDef(
        getInt("id"), optString("name"), optString("target"),
        arr.objs().map { FormField(it.optString("name"), it.optString("label")) }
    )
}

fun JSONObject.toAutomation() = Automation(
    getInt("id"), optString("name"), optString("trigger"),
    optJSONObject("action")?.optString("type") ?: ""
)

fun JSONObject.toWidget() = Widget(
    optString("id"), optString("type"), optString("title"),
    optString("metric"), optInt("refId", 0), optString("text")
)

fun Widget.toJson(): JSONObject =
    JSONObject().put("id", id).put("type", type).put("title", title)
        .put("metric", metric).put("refId", refId).put("text", text)

fun JSONObject.toDashboard(): Dashboard {
    val widgets = optJSONObject("layout")?.optJSONArray("widgets") ?: JSONArray()
    return Dashboard(getInt("id"), optString("name"), widgets.objs().map { it.toWidget() })
}

fun Dashboard.layoutJson(): JSONObject =
    JSONObject().put("widgets", JSONArray().also { arr -> widgets.forEach { arr.put(it.toJson()) } })

fun JSONObject.toSummary(): Summary {
    val values = HashMap<String, Int>()
    keys().forEach { k -> if (k != "events7d") values[k] = optInt(k) }
    val chart = (optJSONArray("events7d") ?: JSONArray()).objs().map { it.optString("label") to it.optInt("value") }
    return Summary(values, chart)
}
