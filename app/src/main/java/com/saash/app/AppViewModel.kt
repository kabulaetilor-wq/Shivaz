package com.saash.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val api = Api(app)

    var loggedIn by mutableStateOf(api.token.isNotBlank())
        private set
    var me by mutableStateOf<Me?>(
        if (api.meJson.isNotBlank()) runCatching { JSONObject(api.meJson).toMe() }.getOrNull() else null
    )
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    var summary by mutableStateOf(Summary(emptyMap(), emptyList()))
        private set
    var connectors by mutableStateOf<List<Conn>>(emptyList())
        private set
    var requests by mutableStateOf<List<Req>>(emptyList())
        private set
    var incidents by mutableStateOf<List<Incident>>(emptyList())
        private set
    var usage by mutableStateOf<List<UsageRow>>(emptyList())
        private set
    var controls by mutableStateOf<List<Control>>(emptyList())
        private set
    var charts by mutableStateOf<List<OrgChart>>(emptyList())
        private set
    var catalog by mutableStateOf(Catalog(emptyList(), emptyList(), emptyList()))
        private set
    var orgSettings by mutableStateOf<OrgSettings?>(null)
        private set
    var forms by mutableStateOf<List<FormDef>>(emptyList())
        private set
    var automations by mutableStateOf<List<Automation>>(emptyList())
        private set
    var users by mutableStateOf<List<TeamUser>>(emptyList())
        private set
    var dashboards by mutableStateOf<List<Dashboard>>(emptyList())
        private set
    var dashboardIndex by mutableIntStateOf(0)

    val isClient: Boolean get() = me?.role == "CLIENT"
    val isAdmin: Boolean get() = me?.role == "ADMIN"
    val canEdit: Boolean get() = me?.role == "ADMIN" || me?.role == "MEMBER"

    val currentDashboard: Dashboard?
        get() = dashboards.getOrNull(dashboardIndex.coerceIn(0, (dashboards.size - 1).coerceAtLeast(0)))

    // ---------- Exécution sûre ----------
    private fun run(showBusy: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (showBusy) busy = true
            try {
                block()
            } catch (e: ApiError) {
                if (e.message == Api.SESSION_EXPIRED) {
                    logout()
                    message = "Session expirée, reconnectez-vous."
                } else {
                    message = e.message
                }
            } catch (e: Exception) {
                message = "Erreur : ${e.message}"
            } finally {
                if (showBusy) busy = false
            }
        }
    }

    // ---------- Compte ----------
    fun saveServer(url: String) {
        api.baseUrl = url
    }

    fun login(url: String, email: String, password: String) = run {
        api.baseUrl = url
        val res = api.call("POST", "/auth/login", JSONObject().put("email", email).put("password", password))
        startSession(JSONObject(res))
    }

    fun register(url: String, orgName: String, kind: String, name: String, email: String, password: String) = run {
        api.baseUrl = url
        val body = JSONObject().put("orgName", orgName).put("kind", kind).put("name", name)
            .put("email", email).put("password", password)
        startSession(JSONObject(api.call("POST", "/auth/register", body)))
    }

    private suspend fun startSession(res: JSONObject) {
        api.token = res.getString("token")
        val user = res.getJSONObject("user")
        api.meJson = user.toString()
        me = user.toMe()
        loggedIn = true
        refreshNow()
    }

    fun logout() {
        api.logout()
        loggedIn = false
        me = null
        dashboards = emptyList()
        connectors = emptyList()
    }

    // ---------- Chargement ----------
    fun refresh(showBusy: Boolean = false) = run(showBusy) { refreshNow() }

    private suspend fun refreshNow() {
        summary = JSONObject(api.call("GET", "/summary")).toSummary()
        connectors = JSONArray(api.call("GET", "/connectors")).objs().map { it.toConn() }
        requests = JSONArray(api.call("GET", "/requests")).objs().map { it.toReq() }
        incidents = JSONArray(api.call("GET", "/incidents")).objs().map { it.toIncident() }
        dashboards = JSONArray(api.call("GET", "/dashboards")).objs().map { it.toDashboard() }
        charts = JSONArray(api.call("GET", "/charts")).objs().map { it.toChart() }
        controls = JSONArray(api.call("GET", "/controls")).objs().map { it.toControl() }
        if (catalog.kinds.isEmpty()) catalog = JSONObject(api.call("GET", "/catalog")).toCatalog()
        if (isAdmin) orgSettings = JSONObject(api.call("GET", "/org-settings")).toOrgSettings()
        forms = JSONArray(api.call("GET", "/forms")).objs().map { it.toForm() }
        if (canEdit) {
            usage = JSONArray(api.call("GET", "/usage")).objs().map { it.toUsage() }
            users = JSONArray(api.call("GET", "/users")).objs().map { it.toTeamUser() }
            automations = JSONArray(api.call("GET", "/automations")).objs().map { it.toAutomation() }
        }
        if (dashboardIndex >= dashboards.size) dashboardIndex = 0
    }

    /** Récupère les nouvelles alertes et les affiche comme notifications du téléphone. */
    fun pollNotifications() = run(false) {
        val list = JSONArray(api.call("GET", "/notifications?after=${api.lastNotifId}")).objs().map { it.toNotif() }
        if (list.isNotEmpty()) {
            val first = api.lastNotifId == 0
            api.lastNotifId = list.maxOf { it.id }
            if (!first && api.notifyEnabled) {
                list.sortedBy { it.id }.takeLast(5).forEach {
                    Notifier.send(getApplication(), it.id, it.title, it.body)
                }
            }
        }
    }

    fun setNotifyEnabled(v: Boolean) {
        api.notifyEnabled = v
    }

    // ---------- Actions génériques ----------
    private fun mutate(method: String, path: String, body: JSONObject? = null, ok: String? = null) = run {
        api.call(method, path, body)
        refreshNow()
        if (ok != null) message = ok
    }

    // Connecteurs
    fun addConnector(body: JSONObject) = mutate("POST", "/connectors", body, "SaaS connecté")
    fun deleteConnector(id: Int) = mutate("DELETE", "/connectors/$id", null, "Connecteur supprimé")
    fun checkConnector(id: Int) = mutate("POST", "/connectors/$id/check")
    fun startOauth(id: Int, onUrl: (String) -> Unit) = run {
        val url = JSONObject(api.call("GET", "/oauth/$id/start")).getString("url")
        onUrl(url)
    }

    // Demandes
    fun addRequest(requester: String, reason: String) =
        mutate("POST", "/requests", JSONObject().put("requester", requester).put("reason", reason), "Demande envoyée")

    fun decide(id: Int, accept: Boolean) =
        mutate("POST", "/requests/$id/decide", JSONObject().put("accept", accept), if (accept) "Demande acceptée" else "Demande refusée")

    // Équipe
    fun addUser(name: String, email: String, password: String, role: String) =
        mutate(
            "POST", "/users",
            JSONObject().put("name", name).put("email", email).put("password", password).put("role", role),
            "Utilisateur créé"
        )

    fun deleteUser(id: Int) = mutate("DELETE", "/users/$id")

    // Organigrammes
    fun addChart(name: String, template: String) {
        val b = JSONObject().put("name", name)
        if (template.isNotBlank()) b.put("template", template)
        mutate("POST", "/charts", b, "Organigramme créé")
    }

    fun deleteChart(id: Int) = mutate("DELETE", "/charts/$id")

    fun addOrgNode(chartId: Int, parentId: Int?, name: String, title: String) {
        val b = JSONObject().put("chartId", chartId).put("name", name).put("title", title)
        if (parentId != null) b.put("parentId", parentId)
        mutate("POST", "/org", b)
    }

    fun deleteOrgNode(id: Int) = mutate("DELETE", "/org/$id")

    // Tableaux de bord
    fun saveDashboard(d: Dashboard) =
        mutate("PUT", "/dashboards/${d.id}", JSONObject().put("name", d.name).put("layout", d.layoutJson()))

    fun createDashboard(name: String) = run {
        api.call("POST", "/dashboards", JSONObject().put("name", name).put("layout", JSONObject().put("widgets", JSONArray())))
        refreshNow()
        dashboardIndex = (dashboards.size - 1).coerceAtLeast(0)
    }

    fun deleteDashboard(id: Int) = run {
        api.call("DELETE", "/dashboards/$id")
        refreshNow()
        dashboardIndex = 0
    }

    // Boutons et interrupteurs
    fun addControl(body: JSONObject) = mutate("POST", "/controls", body, "Créé")

    fun updateControl(id: Int, body: JSONObject) = mutate("PUT", "/controls/$id", body, "Modifié")

    fun deleteControl(id: Int) = mutate("DELETE", "/controls/$id")

    /** Lance un bouton. onClient reçoit les actions à faire sur le téléphone (ouvrir un lien, copier un code). */
    fun press(c: Control, onClient: (String, String) -> Unit) = run {
        val r = JSONObject(api.call("POST", "/controls/${c.id}/press", JSONObject()))
        val client = r.optString("client")
        if (client.isNotEmpty()) {
            onClient(client, r.optString("info"))
        } else {
            message = (if (r.optBoolean("ok", true)) "✓ " else "✗ ") + r.optString("info")
        }
        refreshNow()
    }

    fun toggle(c: Control, on: Boolean) = run {
        val r = JSONObject(api.call("POST", "/controls/${c.id}/toggle", JSONObject().put("on", on)))
        message = r.optString("info")
        refreshNow()
    }

    fun updateConnector(id: Int, body: JSONObject) = mutate("PUT", "/connectors/$id", body, "SaaS modifié")

    // Intégration
    fun saveCallback(url: String) = mutate("PUT", "/org-settings", JSONObject().put("callbackUrl", url), "URL de rappel enregistrée")

    fun rotateKey() = mutate("POST", "/org-settings/rotate-key", JSONObject(), "Nouvelle clé API générée")

    // Formulaires
    fun addForm(name: String, target: String, fieldLabels: List<String>) {
        val fields = JSONArray()
        fieldLabels.forEachIndexed { i, label ->
            val key = if (target == "access_request" && i == 0) "user" else if (target == "access_request" && i == 1) "reason" else "f$i"
            fields.put(JSONObject().put("name", key).put("label", label).put("type", "text"))
        }
        mutate("POST", "/forms", JSONObject().put("name", name).put("target", target).put("fields", fields), "Formulaire créé")
    }

    fun deleteForm(id: Int) = mutate("DELETE", "/forms/$id")

    fun submitForm(id: Int, data: Map<String, String>) {
        val b = JSONObject()
        data.forEach { (k, v) -> b.put(k, v) }
        mutate("POST", "/forms/$id/submit", b, "Formulaire envoyé")
    }

    // Automatisations
    fun addAutomation(name: String, trigger: String, action: JSONObject) =
        mutate(
            "POST", "/automations",
            JSONObject().put("name", name).put("trigger", trigger).put("action", action),
            "Règle créée"
        )

    fun deleteAutomation(id: Int) = mutate("DELETE", "/automations/$id")
}
