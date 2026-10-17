package com.saash.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// ====================== Services ======================

@Composable
fun ServicesScreen(vm: AppViewModel, step: String, onStep: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var kind by remember { mutableStateOf("custom") }
    var template by remember { mutableStateOf<SaasTemplate?>(null) }
    var toDelete by remember { mutableStateOf<Conn?>(null) }
    var editing by remember { mutableStateOf<Conn?>(null) }

    if (vm.connectors.isEmpty()) {
        Empty(if (vm.isAdmin) "Aucun SaaS connecté. Appuyez sur + pour en ajouter un (10 modèles disponibles)." else "Aucun SaaS connecté.")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(vm.connectors, key = { it.id }) { c ->
                SectionCard(null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(statusLabel(c.status), color = statusColor(c.status), fontWeight = FontWeight.Bold)
                    }
                    if (c.baseUrl.isNotBlank()) Text(c.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text("Latence : ${c.latency} ms · Disponibilité : ${c.uptime}%")
                    Text("Dernier contrôle : ${fmt(c.lastChecked)} · type : ${c.kind}", style = MaterialTheme.typography.bodySmall)
                    if (vm.isAdmin && c.webhookUrl.isNotBlank()) {
                        Text("Webhook : ${c.webhookUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (vm.canEdit) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (c.baseUrl.isNotBlank()) TextButton(onClick = { vm.checkConnector(c.id) }) { Text("Vérifier") }
                            if (vm.isAdmin) TextButton(onClick = { editing = c }) { Text("Modifier") }
                            if (vm.isAdmin && c.kind == "oauth") {
                                TextButton(onClick = { vm.startOauth(c.id) { url -> uriHandler.openUri(url) } }) { Text("Autoriser") }
                            }
                            if (vm.isAdmin && c.webhookUrl.isNotBlank()) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(c.webhookUrl)) }) { Text("Copier webhook") }
                            }
                            if (vm.isAdmin) TextButton(onClick = { toDelete = c }) { Text("Supprimer") }
                        }
                    }
                }
            }
        }
    }

    when (step) {
        "kind" -> ChoiceDialog(
            "Comment connecter ce SaaS ?",
            listOf(
                "template" to "Partir d'un modèle (${vm.catalog.saas.size})",
                "custom" to "Connecteur personnalisé (clé API)",
                "oauth" to "OAuth (connexion par compte)",
                "webhook" to "Webhook entrant (le SaaS m'envoie des événements)"
            ),
            { onStep("") }
        ) { k ->
            if (k == "template") {
                onStep("template")
            } else {
                kind = k
                onStep("form")
            }
        }
        "template" -> ChoiceDialog(
            "Choisissez un modèle (modifiable ensuite)",
            vm.catalog.saas.map { it.key to it.name },
            { onStep("") }
        ) { key ->
            template = vm.catalog.saas.firstOrNull { it.key == key }
            onStep("tform")
        }
        "tform" -> template?.let { t ->
            FormDialog(
                t.name,
                listOf("Nom", "URL de base (https://…)", "Chemin de santé", "En-tête d'authentification (vide = Bearer)", "Clé / secret"),
                { onStep("") },
                { v ->
                    vm.addConnector(customConnector(v, "custom"))
                    onStep("")
                },
                secretIndexes = setOf(4),
                hint = t.hint + " Vous pourrez tout modifier plus tard.",
                initial = listOf(t.name, "https://", t.healthPath, t.authName, "")
            )
        }
        "form" -> when (kind) {
            "oauth" -> FormDialog(
                "Connecter par OAuth",
                listOf("Nom", "URL de base de l'API (optionnel)", "URL d'autorisation", "URL du jeton", "Client ID", "Client secret", "Scopes"),
                { onStep("") },
                { v ->
                    val cfg = JSONObject().put("authUrl", v[2]).put("tokenUrl", v[3]).put("clientId", v[4])
                        .put("clientSecret", v[5]).put("scope", v[6])
                    vm.addConnector(
                        JSONObject().put("name", v[0]).put("kind", "oauth").put("baseUrl", v[1]).put("config", cfg)
                    )
                    onStep("")
                },
                secretIndexes = setOf(5),
                hint = "À déclarer chez le SaaS comme URL de redirection : ${vm.api.baseUrl}/oauth/callback"
            )
            "webhook" -> FormDialog(
                "Webhook entrant",
                listOf("Nom (ex : Mon application)"),
                { onStep("") },
                { v ->
                    vm.addConnector(JSONObject().put("name", v[0]).put("kind", "webhook"))
                    onStep("")
                },
                hint = "Un lien secret sera généré. Le SaaS y envoie des événements : demandes d'accès, usage, pannes."
            )
            else -> FormDialog(
                "Connecteur personnalisé",
                listOf("Nom", "URL de base", "Chemin de santé", "En-tête d'authentification (vide = Bearer)", "Clé / secret"),
                { onStep("") },
                { v ->
                    vm.addConnector(customConnector(v, "custom"))
                    onStep("")
                },
                secretIndexes = setOf(4),
                initial = listOf("", "https://", "", "", "")
            )
        }
    }

    editing?.let { c ->
        FormDialog(
            "Modifier ${c.name}",
            listOf("Nom", "URL de base", "Chemin de santé", "En-tête d'authentification (vide = Bearer)", "Nouvelle clé (vide = inchangée)"),
            { editing = null },
            { v ->
                val b = JSONObject().put("name", v[0]).put("baseUrl", v[1]).put("healthPath", v[2])
                    .put("authName", v[3]).put("authType", if (v[3].isBlank()) "bearer" else "header")
                if (v[4].isNotBlank()) b.put("secret", v[4])
                vm.updateConnector(c.id, b)
                editing = null
            },
            secretIndexes = setOf(4),
            initial = listOf(c.name, c.baseUrl, c.healthPath, c.authName, "")
        )
    }

    toDelete?.let { c ->
        ConfirmDialog("Supprimer « ${c.name} » et ses boutons ?", { toDelete = null }) {
            vm.deleteConnector(c.id)
            toDelete = null
        }
    }
}

private fun customConnector(v: List<String>, kind: String): JSONObject {
    val authType = when {
        v[4].isBlank() -> "none"
        v[3].isBlank() -> "bearer"
        else -> "header"
    }
    return JSONObject().put("name", v[0]).put("kind", kind).put("baseUrl", v[1].trim().let { if (it == "https://") "" else it })
        .put("healthPath", v[2]).put("authType", authType).put("authName", v[3]).put("secret", v[4])
}

// ====================== Demandes ======================

@Composable
fun RequestsScreen(vm: AppViewModel, showForm: Boolean, onClose: () -> Unit) {
    if (vm.requests.isEmpty()) {
        Empty("Aucune demande d'accès.")
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(vm.requests, key = { it.id }) { r ->
                SectionCard(r.requester) {
                    if (r.reason.isNotBlank()) Text(r.reason)
                    Text(fmt(r.createdAt), style = MaterialTheme.typography.bodySmall)
                    when (r.state) {
                        "pending" -> if (vm.canEdit) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.decide(r.id, true) }) { Text("Accepter") }
                                OutlinedButton(onClick = { vm.decide(r.id, false) }) { Text("Refuser") }
                            }
                        } else Text("En attente", color = WarnColor, fontWeight = FontWeight.Bold)
                        "accepted" -> Text("Acceptée par ${r.decidedBy}", color = OkColor, fontWeight = FontWeight.Bold)
                        else -> Text("Refusée par ${r.decidedBy}", color = DownColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (showForm) {
        if (vm.isClient) {
            FormDialog("Nouvelle demande d'accès", listOf("Motif"), onClose, { v ->
                vm.addRequest(vm.me?.name ?: "Client", v[0])
                onClose()
            })
        } else {
            FormDialog("Nouvelle demande d'accès", listOf("Demandeur", "Motif"), onClose, { v ->
                vm.addRequest(v[0].ifBlank { "Inconnu" }, v[1])
                onClose()
            })
        }
    }
}

// ====================== Pannes ======================

@Composable
fun IncidentsScreen(vm: AppViewModel) {
    if (vm.incidents.isEmpty()) {
        Empty("Aucune panne enregistrée.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(vm.incidents, key = { it.id }) { i ->
            SectionCard("${i.connector} — ${i.title}") {
                Text("Début : ${fmt(i.startedAt)}")
                if (i.resolvedAt > 0) {
                    Text("Résolu après ${duration(i.startedAt, i.resolvedAt)}", color = OkColor, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        "En cours depuis ${duration(i.startedAt, System.currentTimeMillis())}",
                        color = DownColor, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ====================== Plus (menu) ======================

@Composable
fun MoreScreen(vm: AppViewModel) {
    var sub by remember { mutableStateOf("") }
    BackHandler(enabled = sub.isNotEmpty()) { sub = "" }

    if (sub.isEmpty()) {
        val items = buildList {
            if (vm.canEdit) add("team" to "Équipe et usage")
            add("org" to "Organigramme")
            add("controls" to "Boutons et interrupteurs")
            add("forms" to "Formulaires")
            if (vm.isAdmin) add("auto" to "Automatisations")
            if (vm.isAdmin) add("integration" to "Intégration (clé API, code / ID)")
            add("settings" to "Paramètres et compte")
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (key, label) ->
                OutlinedButton(onClick = { sub = key }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { sub = "" }) { Text("← Retour") }
        when (sub) {
            "team" -> TeamScreen(vm)
            "org" -> OrgScreen(vm)
            "controls" -> ControlsScreen(vm)
            "integration" -> IntegrationScreen(vm)
            "forms" -> FormsScreen(vm)
            "auto" -> AutomationsScreen(vm)
            else -> SettingsScreen(vm)
        }
    }
}

@Composable
private fun TeamScreen(vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf("MEMBER") }
    var pickRole by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<TeamUser?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (vm.isAdmin) item { Button(onClick = { pickRole = true }) { Text("+ Ajouter un utilisateur") } }
        item { Text("Utilisateurs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(vm.users, key = { "u" + it.id }) { u ->
            SectionCard(null) {
                Text(u.name, fontWeight = FontWeight.Bold)
                Text("${u.email} · ${u.role}", style = MaterialTheme.typography.bodySmall)
                if (vm.isAdmin && u.id != vm.me?.id) {
                    TextButton(onClick = { toDelete = u }) { Text("Supprimer") }
                }
            }
        }
        item { Text("Usage (reçu par webhook)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (vm.usage.isEmpty()) {
            item { Text("Aucune donnée d'usage. Envoyez {\"type\":\"usage\",\"user\":\"…\",\"minutes\":15} à l'URL webhook d'un connecteur.", style = MaterialTheme.typography.bodySmall) }
        }
        items(vm.usage.size, key = { "g$it" }) { i ->
            val u = vm.usage[i]
            SectionCard(null) {
                Text(u.user, fontWeight = FontWeight.Bold)
                Text("${u.connector} · ${u.sessions} sessions · ${u.minutes} min")
            }
        }
    }

    if (pickRole) {
        ChoiceDialog(
            "Rôle du nouvel utilisateur",
            listOf("MEMBER" to "Équipe (voir, décider, créer)", "CLIENT" to "Client (voir et demander)", "ADMIN" to "Administrateur"),
            { pickRole = false }
        ) { r ->
            role = r
            pickRole = false
            adding = true
        }
    }
    if (adding) {
        FormDialog(
            "Nouvel utilisateur",
            listOf("Nom", "Email", "Mot de passe (8 caractères min.)"),
            { adding = false },
            { v ->
                vm.addUser(v[0], v[1], v[2], role)
                adding = false
            },
            secretIndexes = setOf(2)
        )
    }
    toDelete?.let { u ->
        ConfirmDialog("Supprimer ${u.name} ?", { toDelete = null }) {
            vm.deleteUser(u.id)
            toDelete = null
        }
    }
}

@Composable
private fun OrgScreen(vm: AppViewModel) {
    var selected by remember { mutableIntStateOf(0) }
    var parent by remember { mutableStateOf<Int?>(null) }
    var step by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<OrgNode?>(null) }
    var deleteChart by remember { mutableStateOf(false) }
    val chart = vm.charts.firstOrNull { it.id == selected } ?: vm.charts.firstOrNull()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.charts, key = { it.id }) { c ->
                    FilterChip(selected = c.id == chart?.id, onClick = { selected = c.id }, label = { Text(c.name) })
                }
            }
        }
        if (vm.canEdit) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { step = "template" }) { Text("+ Nouvel organigramme") }
                    if (chart != null) TextButton(onClick = { deleteChart = true }) { Text("Supprimer celui-ci") }
                }
            }
            if (chart != null) {
                item { OutlinedButton(onClick = { parent = null; step = "node" }) { Text("+ Poste à la racine") } }
            }
        }
        if (chart == null) {
            item { Text("Aucun organigramme. Créez-en un à partir d'un des 5 modèles.") }
        } else {
            item {
                if (chart.nodes.isEmpty()) Text("Organigramme vide.")
                val addChild: (OrgNode) -> Unit = { n -> parent = n.id; step = "node" }
                val del: (OrgNode) -> Unit = { n -> toDelete = n }
                OrgTree(
                    chart.nodes, null, 0,
                    onAddChild = if (vm.canEdit) addChild else null,
                    onDelete = if (vm.canEdit) del else null
                )
            }
        }
    }

    when (step) {
        "template" -> ChoiceDialog(
            "Modèle d'organigramme",
            vm.catalog.orgs.map { it.key to "${it.name} · ${it.count} postes" } + ("" to "Organigramme vide"),
            { step = "" }
        ) { key ->
            template = key
            step = "name"
        }
        "name" -> FormDialog(
            "Nom de l'organigramme",
            listOf("Nom"),
            { step = "" },
            { v ->
                vm.addChart(v[0].trim().ifEmpty { "Organigramme" }, template)
                step = ""
            },
            initial = listOf(vm.catalog.orgs.firstOrNull { it.key == template }?.name ?: ""),
            hint = "Vous pourrez ajouter, retirer et renommer les postes ensuite."
        )
        "node" -> FormDialog("Nouveau poste", listOf("Nom", "Fonction"), { step = "" }, { v ->
            if (v[0].isNotBlank() && chart != null) vm.addOrgNode(chart.id, parent, v[0].trim(), v[1].trim())
            step = ""
        })
    }
    toDelete?.let { n ->
        ConfirmDialog("Supprimer « ${n.name} » ? Ses sous-postes remontent d'un niveau.", { toDelete = null }) {
            vm.deleteOrgNode(n.id)
            toDelete = null
        }
    }
    if (deleteChart && chart != null) {
        ConfirmDialog("Supprimer l'organigramme « ${chart.name} » ?", { deleteChart = false }) {
            vm.deleteChart(chart.id)
            deleteChart = false
        }
    }
}

@Composable
private fun ControlsScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var step by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<KindDef?>(null) }
    var connId by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Control?>(null) }
    var toDelete by remember { mutableStateOf<Control?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.isAdmin) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { step = "kind" }) { Text("+ Bouton") }
                    OutlinedButton(onClick = { step = "swConn" }) { Text("+ Interrupteur") }
                }
            }
        }
        if (vm.controls.isEmpty()) {
            item { Text("Aucun bouton ni interrupteur. ${vm.catalog.kinds.size} types de boutons sont disponibles.") }
        }
        items(vm.controls, key = { it.id }) { c ->
            SectionCard(c.name) {
                Text(
                    (if (c.isSwitch) "Interrupteur" else vm.catalog.kinds.firstOrNull { it.key == c.kind }?.label ?: c.kind) +
                        if (c.connector.isNotBlank()) " · ${c.connector}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Code / ID : ", style = MaterialTheme.typography.bodySmall)
                    Text(c.code, fontWeight = FontWeight.Bold)
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(c.code))
                        vm.message = "Code copié : ${c.code}"
                    }) { Text("Copier") }
                }
                if (c.isSwitch) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (c.state) "Activé" else "Désactivé")
                        Switch(checked = c.state, onCheckedChange = { vm.toggle(c, it) }, enabled = vm.canEdit)
                    }
                } else if (vm.canEdit) {
                    Button(onClick = {
                        vm.press(c) { action, value ->
                            if (action == "open_link") uriHandler.openUri(value)
                            if (action == "copy_code") {
                                clipboard.setText(AnnotatedString(value))
                                vm.message = "Code copié : $value"
                            }
                        }
                    }) { Text("Lancer") }
                }
                if (vm.isAdmin) {
                    Row {
                        TextButton(onClick = { editing = c }) { Text("Modifier") }
                        TextButton(onClick = { toDelete = c }) { Text("Supprimer") }
                    }
                }
            }
        }
    }

    when (step) {
        "kind" -> ChoiceDialog(
            "Type de bouton (${vm.catalog.kinds.size})",
            vm.catalog.kinds.map { it.key to it.label },
            { step = "" }
        ) { key ->
            val def = vm.catalog.kinds.firstOrNull { it.key == key }
            kind = def
            connId = null
            step = if (def?.scope == "external") "conn" else "form"
        }
        "conn" -> ChoiceDialog(
            "Quel SaaS appeler ?",
            vm.connectors.filter { it.baseUrl.isNotBlank() }.map { it.id.toString() to it.name },
            { step = "" }
        ) { id ->
            connId = id.toInt()
            step = "form"
        }
        "form" -> kind?.let { def ->
            val external = def.scope == "external"
            val message = def.key == "notify_team" || def.key == "create_incident"
            val link = def.key == "open_link"
            val labels = buildList {
                add("Nom du bouton")
                add("Code / ID (vide = automatique)")
                if (external) { add("Méthode"); add("Chemin"); add("Corps JSON") }
                if (message) add("Message")
                if (link) add("Adresse (https://…)")
            }
            val initial = buildList {
                add(def.label)
                add("")
                if (external) { add(def.method); add(def.path); add(def.body) }
            }
            FormDialog(
                def.label,
                labels,
                { step = "" },
                { v ->
                    val b = JSONObject().put("type", "button").put("kind", def.key).put("name", v[0]).put("code", v[1])
                    if (connId != null) b.put("connectorId", connId)
                    if (external) b.put("method", v[2]).put("path", v[3]).put("body", v[4])
                    if (message) b.put("body", v[2])
                    if (link) b.put("path", v[2])
                    vm.addControl(b)
                    step = ""
                },
                hint = def.description + if (external) " Les chemins sont des exemples : adaptez-les à l'API de votre SaaS." else "",
                initial = initial
            )
        }
        "swConn" -> ChoiceDialog(
            "L'interrupteur doit-il appeler un SaaS ?",
            listOf("" to "Non : mon code lit son état") +
                vm.connectors.filter { it.baseUrl.isNotBlank() }.map { it.id.toString() to "Oui : ${it.name}" },
            { step = "" }
        ) { id ->
            connId = id.toIntOrNull()
            step = "swForm"
        }
        "swForm" -> {
            val withApi = connId != null
            FormDialog(
                "Nouvel interrupteur",
                if (withApi) listOf("Nom", "Code / ID (vide = automatique)", "Méthode", "Chemin", "Corps JSON") else listOf("Nom", "Code / ID (vide = automatique)"),
                { step = "" },
                { v ->
                    val b = JSONObject().put("type", "switch").put("name", v[0]).put("code", v[1])
                    if (withApi) b.put("connectorId", connId).put("method", v[2]).put("path", v[3]).put("body", v[4])
                    vm.addControl(b)
                    step = ""
                },
                hint = if (withApi) "{{state}} est remplacé par true ou false." else "Votre code lit l'état avec la clé API (menu Intégration).",
                initial = if (withApi) listOf("", "", "POST", "/maintenance", "{\"enabled\":{{state}}}") else emptyList()
            )
        }
    }

    editing?.let { c ->
        FormDialog(
            "Modifier ${c.name}",
            listOf("Nom", "Code / ID", "Méthode", "Chemin", "Corps"),
            { editing = null },
            { v ->
                vm.updateControl(c.id, JSONObject().put("name", v[0]).put("code", v[1]).put("method", v[2]).put("path", v[3]).put("body", v[4]))
                editing = null
            },
            hint = "Changer le code casse les appels de votre code qui utilisent l'ancien.",
            initial = listOf(c.name, c.code, c.method, c.path, c.body)
        )
    }
    toDelete?.let { c ->
        ConfirmDialog("Supprimer « ${c.name} » (code ${c.code}) ?", { toDelete = null }) {
            vm.deleteControl(c.id)
            toDelete = null
        }
    }
}

@Composable
private fun IntegrationScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val s = vm.orgSettings
    var editCallback by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }

    if (s == null) {
        Empty("Réservé aux administrateurs.")
        return
    }
    val base = s.publicUrl.ifBlank { vm.api.baseUrl }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Clé API (à garder secrète)") {
            Text(s.apiKey, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(s.apiKey))
                    vm.message = "Clé copiée"
                }) { Text("Copier") }
                TextButton(onClick = { confirmRotate = true }) { Text("Régénérer") }
            }
        }
        SectionCard("URL de rappel (callback)") {
            Text(s.callbackUrl.ifBlank { "Non définie" })
            Text(
                "Chaque bouton ou interrupteur utilisé est envoyé à cette adresse en POST JSON, avec l'en-tête X-SaaSH-Signature = sha256=HMAC(clé API, corps).",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { editCallback = true }) { Text("Modifier") }
        }
        SectionCard("Utiliser un code / ID dans votre code") {
            val snippet = "Lire un interrupteur :\n" +
                "curl -H \"X-Api-Key: <clé>\" $base/v1/controls/<code>\n\n" +
                "Déclencher un bouton :\n" +
                "curl -X POST -H \"X-Api-Key: <clé>\" $base/v1/controls/<code>/press\n\n" +
                "Changer un interrupteur :\n" +
                "curl -X PUT -H \"X-Api-Key: <clé>\" -H \"Content-Type: application/json\" -d '{\"on\":true}' $base/v1/controls/<code>/state"
            Text(snippet, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(snippet))
                vm.message = "Exemple copié"
            }) { Text("Copier l'exemple") }
            Text(
                "Le <code> est le « Code / ID » affiché sur chaque bouton ou interrupteur (menu Boutons et interrupteurs).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    if (editCallback) {
        FormDialog(
            "URL de rappel",
            listOf("Adresse https:// (vide = désactivé)"),
            { editCallback = false },
            { v ->
                vm.saveCallback(v[0].trim())
                editCallback = false
            },
            initial = listOf(s.callbackUrl)
        )
    }
    if (confirmRotate) {
        ConfirmDialog("Régénérer la clé ? L'ancienne cessera de fonctionner immédiatement.", { confirmRotate = false }) {
            vm.rotateKey()
            confirmRotate = false
        }
    }
}

@Composable
private fun FormsScreen(vm: AppViewModel) {
    var step by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("custom") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.canEdit) item { Button(onClick = { step = "target" }) { Text("+ Créer un formulaire") } }
        items(vm.forms, key = { it.id }) { f ->
            SectionCard(f.name) {
                Text(
                    if (f.target == "access_request") "Crée une demande d'accès" else "Notifie l'équipe",
                    style = MaterialTheme.typography.bodySmall
                )
                FormFill(f, vm)
                if (vm.canEdit) TextButton(onClick = { vm.deleteForm(f.id) }) { Text("Supprimer le formulaire") }
            }
        }
    }
    when (step) {
        "target" -> ChoiceDialog(
            "Que fait ce formulaire ?",
            listOf("access_request" to "Crée une demande d'accès", "custom" to "Envoie une notification à l'équipe"),
            { step = "" }
        ) { t ->
            target = t
            step = "form"
        }
        "form" -> FormDialog(
            "Nouveau formulaire",
            listOf("Nom", "Champs séparés par des virgules"),
            { step = "" },
            { v ->
                val labels = v[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (v[0].isNotBlank() && labels.isNotEmpty()) vm.addForm(v[0].trim(), target, labels)
                step = ""
            },
            hint = if (target == "access_request") "Le 1er champ = nom du demandeur, le 2e = motif." else null
        )
    }
}

@Composable
private fun AutomationsScreen(vm: AppViewModel) {
    var step by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("incident_opened") }
    var actionType by remember { mutableStateOf("notify") }

    val triggers = listOf(
        "incident_opened" to "Quand une panne démarre",
        "incident_resolved" to "Quand une panne est résolue",
        "request_created" to "Quand une demande arrive",
        "request_decided" to "Quand une demande est traitée",
        "event" to "Quand un webhook envoie un événement"
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = { step = "trigger" }) { Text("+ Créer une règle") } }
        if (vm.automations.isEmpty()) item { Text("Aucune règle. Exemple : « quand une panne démarre → envoyer une notification ».") }
        items(vm.automations, key = { it.id }) { a ->
            SectionCard(a.name) {
                Text(
                    (triggers.firstOrNull { it.first == a.trigger }?.second ?: a.trigger) + " → " + a.actionType,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { vm.deleteAutomation(a.id) }) { Text("Supprimer") }
            }
        }
    }
    when (step) {
        "trigger" -> ChoiceDialog("Déclencheur", triggers, { step = "" }) { t ->
            trigger = t
            step = "action"
        }
        "action" -> ChoiceDialog(
            "Action à effectuer",
            listOf("notify" to "Envoyer une notification", "webhook" to "Appeler un webhook (Slack, etc.)", "run_action" to "Lancer un bouton d'action"),
            { step = "" }
        ) { a ->
            actionType = a
            step = if (a == "run_action") "pick_action" else "form"
        }
        "pick_action" -> ChoiceDialog(
            "Quel bouton lancer ?",
            vm.controls.filter { !it.isSwitch }.map { it.id.toString() to it.name },
            { step = "" }
        ) { id ->
            vm.addAutomation(
                "Règle : ${vm.controls.firstOrNull { it.id.toString() == id }?.name ?: "action"}",
                trigger, JSONObject().put("type", "run_action").put("actionId", id.toInt())
            )
            step = ""
        }
        "form" -> FormDialog(
            "Nouvelle règle",
            if (actionType == "webhook") listOf("Nom", "URL du webhook") else listOf("Nom", "Titre de la notification"),
            { step = "" },
            { v ->
                val action = if (actionType == "webhook") {
                    JSONObject().put("type", "webhook").put("url", v[1])
                        .put("body", "{\"text\":\"{{connector}} {{title}}\"}")
                } else {
                    JSONObject().put("type", "notify").put("title", v[1].ifBlank { "Alerte" })
                        .put("body", "{{connector}} {{title}}")
                }
                vm.addAutomation(v[0].ifBlank { "Règle" }, trigger, action)
                step = ""
            },
            hint = "Variables disponibles : {{connector}}, {{title}}, {{user}}"
        )
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    var notify by remember { mutableStateOf(vm.api.notifyEnabled) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Compte") {
            Text("${vm.me?.name} · ${vm.me?.role}")
            Text("${vm.me?.orgName} (${if (vm.me?.kind == "vendor") "éditeur" else "gestionnaire"})")
            Text("Serveur : ${vm.api.baseUrl}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Alertes sur le téléphone")
            Switch(checked = notify, onCheckedChange = { notify = it; vm.setNotifyEnabled(it) })
        }
        OutlinedButton(onClick = { vm.refresh(true) }, modifier = Modifier.fillMaxWidth()) { Text("Actualiser") }
        Button(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Se déconnecter") }
    }
}
