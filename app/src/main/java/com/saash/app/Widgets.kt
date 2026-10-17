package com.saash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

private val metrics = listOf(
    "servicesUp" to "Services opérationnels",
    "servicesTotal" to "Services connectés",
    "servicesDegraded" to "Services dégradés",
    "servicesDown" to "Services en panne",
    "incidentsOpen" to "Pannes en cours",
    "requestsPending" to "Demandes en attente",
    "usersTotal" to "Utilisateurs",
    "usersActive" to "Utilisateurs actifs (24 h)",
    "events24h" to "Événements (24 h)"
)

private val widgetTypes = listOf(
    "stat" to "Compteur",
    "chart" to "Graphique d'activité (7 jours)",
    "status_list" to "État des services",
    "incidents" to "Pannes récentes",
    "requests" to "Demandes d'accès",
    "usage" to "Usage par utilisateur",
    "button" to "Bouton",
    "switch" to "Interrupteur",
    "org_chart" to "Organigramme",
    "form" to "Formulaire",
    "text" to "Note / texte"
)

private fun defaultTitle(type: String) = widgetTypes.firstOrNull { it.first == type }?.second ?: type

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val d = vm.currentDashboard
    var editing by remember { mutableStateOf(false) }
    var draft by remember(d?.id, d?.widgets) { mutableStateOf(d?.widgets ?: emptyList()) }
    var step by remember { mutableStateOf("") } // "", type, metric, action, form, text, newboard
    var pendingType by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun add(w: Widget) {
        draft = draft + w
        step = ""
    }

    fun newId() = "w" + System.currentTimeMillis()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.dashboards, key = { it.id }) { board ->
                    FilterChip(
                        selected = board.id == d?.id,
                        onClick = {
                            editing = false
                            vm.dashboardIndex = vm.dashboards.indexOf(board)
                        },
                        label = { Text(board.name) }
                    )
                }
            }
        }
        if (vm.canEdit && d != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!editing) {
                        OutlinedButton(onClick = { editing = true }) { Text("Modifier l'écran") }
                        TextButton(onClick = { step = "newboard" }) { Text("+ Nouveau tableau") }
                    } else {
                        Button(onClick = {
                            vm.saveDashboard(d.copy(widgets = draft))
                            editing = false
                        }) { Text("Enregistrer") }
                        OutlinedButton(onClick = {
                            draft = d.widgets
                            editing = false
                        }) { Text("Annuler") }
                    }
                }
            }
        }
        if (editing) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { step = "type" }) { Text("+ Ajouter un élément") }
                    if (vm.dashboards.size > 1 && d != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Supprimer ce tableau") }
                    }
                }
            }
        }
        val shown = if (editing) draft else (d?.widgets ?: emptyList())
        if (d == null) {
            item { Text("Aucun tableau de bord. Appuyez sur ↻ pour recharger.") }
        } else if (shown.isEmpty()) {
            item { Text("Ce tableau est vide. Utilisez « Modifier l'écran » pour ajouter des éléments.") }
        }
        items(shown.size, key = { shown[it].id }) { i ->
            val w = shown[i]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WidgetView(w, vm)
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = i > 0,
                            onClick = {
                                val l = draft.toMutableList()
                                val t = l[i]; l[i] = l[i - 1]; l[i - 1] = t
                                draft = l
                            }
                        ) { Text("↑ Monter") }
                        TextButton(
                            enabled = i < draft.size - 1,
                            onClick = {
                                val l = draft.toMutableList()
                                val t = l[i]; l[i] = l[i + 1]; l[i + 1] = t
                                draft = l
                            }
                        ) { Text("↓ Descendre") }
                        TextButton(onClick = { draft = draft.filter { it.id != w.id } }) { Text("Retirer") }
                    }
                }
            }
        }
    }

    // ----- Assistant d'ajout d'élément -----
    when (step) {
        "type" -> ChoiceDialog("Que voulez-vous ajouter ?", widgetTypes, { step = "" }) { t ->
            pendingType = t
            when (t) {
                "stat" -> step = "metric"
                "button" -> step = "button"
                "switch" -> step = "switch"
                "org_chart" -> step = "chart"
                "form" -> step = "form"
                "text" -> step = "text"
                else -> add(Widget(newId(), t, defaultTitle(t)))
            }
        }
        "metric" -> ChoiceDialog("Quel chiffre afficher ?", metrics, { step = "" }) { m ->
            add(Widget(newId(), "stat", metrics.first { it.first == m }.second, metric = m))
        }
        "button" -> ChoiceDialog(
            "Quel bouton afficher ?",
            vm.controls.filter { !it.isSwitch }.map { it.id.toString() to "${it.name} (${it.code})" },
            { step = "" }
        ) { id ->
            val c = vm.controls.first { it.id.toString() == id }
            add(Widget(newId(), "button", c.name, refId = c.id))
        }
        "switch" -> ChoiceDialog(
            "Quel interrupteur afficher ?",
            vm.controls.filter { it.isSwitch }.map { it.id.toString() to "${it.name} (${it.code})" },
            { step = "" }
        ) { id ->
            val c = vm.controls.first { it.id.toString() == id }
            add(Widget(newId(), "switch", c.name, refId = c.id))
        }
        "chart" -> ChoiceDialog(
            "Quel organigramme afficher ?",
            vm.charts.map { it.id.toString() to it.name },
            { step = "" }
        ) { id ->
            val c = vm.charts.first { it.id.toString() == id }
            add(Widget(newId(), "org_chart", c.name, refId = c.id))
        }
        "form" -> ChoiceDialog(
            "Quel formulaire afficher ?",
            vm.forms.map { it.id.toString() to it.name },
            { step = "" }
        ) { id ->
            val f = vm.forms.first { it.id.toString() == id }
            add(Widget(newId(), "form", f.name, refId = f.id))
        }
        "text" -> FormDialog("Note", listOf("Titre", "Texte"), { step = "" }, { v ->
            add(Widget(newId(), "text", v[0], text = v[1]))
        })
        "newboard" -> FormDialog("Nouveau tableau de bord", listOf("Nom"), { step = "" }, { v ->
            if (v[0].isNotBlank()) vm.createDashboard(v[0].trim())
            step = ""
        })
    }
    if (confirmDelete && d != null) {
        ConfirmDialog("Supprimer le tableau « ${d.name} » ?", { confirmDelete = false }) {
            confirmDelete = false
            editing = false
            vm.deleteDashboard(d.id)
        }
    }
}

@Composable
fun WidgetView(w: Widget, vm: AppViewModel) {
    when (w.type) {
        "stat" -> SectionCard(null) {
            Text(w.title, style = MaterialTheme.typography.labelMedium)
            Text(
                "${vm.summary.values[w.metric] ?: 0}",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
            )
        }
        "chart" -> SectionCard(w.title) {
            val data = vm.summary.chart
            if (data.isEmpty()) {
                Text("Pas encore d'activité.", style = MaterialTheme.typography.bodySmall)
            } else {
                val max = data.maxOf { it.second }.coerceAtLeast(1)
                Row(
                    Modifier.fillMaxWidth().height(90.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    data.forEach { (_, v) ->
                        Box(
                            Modifier.weight(1f).height((80f * v / max).coerceAtLeast(2f).dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.forEach { (label, _) ->
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        "status_list" -> SectionCard(w.title) {
            if (vm.connectors.isEmpty()) Text("Aucun SaaS connecté.", style = MaterialTheme.typography.bodySmall)
            vm.connectors.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.name)
                    Text(statusLabel(c.status), color = statusColor(c.status), fontWeight = FontWeight.Bold)
                }
            }
        }
        "incidents" -> SectionCard(w.title) {
            if (vm.incidents.isEmpty()) Text("Aucune panne.", style = MaterialTheme.typography.bodySmall)
            vm.incidents.take(5).forEach { i ->
                Text(
                    "${i.connector} — ${i.title} · " + if (i.resolvedAt > 0) "résolu" else "en cours",
                    color = if (i.resolvedAt > 0) OkColor else DownColor
                )
            }
        }
        "requests" -> SectionCard(w.title) {
            val pending = vm.requests.filter { it.state == "pending" }
            if (pending.isEmpty()) Text("Aucune demande en attente.", style = MaterialTheme.typography.bodySmall)
            pending.take(5).forEach { r ->
                Text("${r.requester} : ${r.reason}")
                if (vm.canEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.decide(r.id, true) }) { Text("Accepter") }
                        OutlinedButton(onClick = { vm.decide(r.id, false) }) { Text("Refuser") }
                    }
                }
            }
        }
        "usage" -> SectionCard(w.title) {
            if (!vm.canEdit) {
                Text("Réservé à l'équipe.", style = MaterialTheme.typography.bodySmall)
            } else if (vm.usage.isEmpty()) {
                Text("Aucune donnée d'usage reçue.", style = MaterialTheme.typography.bodySmall)
            }
            vm.usage.take(8).forEach { u ->
                Text("${u.user} (${u.connector}) : ${u.sessions} sessions · ${u.minutes} min")
            }
        }
        "button" -> SectionCard(null) {
            val c = vm.controls.firstOrNull { it.id == w.refId }
            val clipboard = LocalClipboardManager.current
            val uriHandler = LocalUriHandler.current
            if (c == null) {
                Text("Ce bouton n'existe plus.", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(
                    onClick = {
                        vm.press(c) { action, value ->
                            if (action == "open_link") uriHandler.openUri(value)
                            if (action == "copy_code") {
                                clipboard.setText(AnnotatedString(value))
                                vm.message = "Code copié : $value"
                            }
                        }
                    },
                    enabled = vm.canEdit,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(c.name) }
                Text("Code : ${c.code}", style = MaterialTheme.typography.labelSmall)
            }
        }
        "switch" -> SectionCard(null) {
            val c = vm.controls.firstOrNull { it.id == w.refId }
            if (c == null) {
                Text("Cet interrupteur n'existe plus.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(c.name, fontWeight = FontWeight.Bold)
                        Text("Code : ${c.code}", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = c.state, onCheckedChange = { vm.toggle(c, it) }, enabled = vm.canEdit)
                }
            }
        }
        "org_chart" -> SectionCard(w.title) {
            val chart = vm.charts.firstOrNull { it.id == w.refId } ?: vm.charts.firstOrNull()
            if (chart == null || chart.nodes.isEmpty()) Text("Organigramme vide.", style = MaterialTheme.typography.bodySmall)
            if (chart != null) OrgTree(chart.nodes, null, 0)
        }
        "form" -> {
            val f = vm.forms.firstOrNull { it.id == w.refId }
            if (f == null) SectionCard(w.title) { Text("Ce formulaire n'existe plus.") } else SectionCard(f.name) { FormFill(f, vm) }
        }
        "text" -> SectionCard(w.title) { Text(w.text) }
        else -> SectionCard(w.title) { Text("Élément inconnu (${w.type}). Mettez l'application à jour.") }
    }
}

@Composable
fun OrgTree(
    nodes: List<OrgNode>,
    parent: Int?,
    depth: Int,
    onAddChild: ((OrgNode) -> Unit)? = null,
    onDelete: ((OrgNode) -> Unit)? = null
) {
    if (depth > 20) return
    nodes.filter { it.parentId == parent }.forEach { n ->
        Column(Modifier.padding(start = (depth * 16).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (depth > 0) "└ " else "● ")
                Text(n.name, fontWeight = FontWeight.Bold)
                if (n.title.isNotBlank()) Text("  ${n.title}", style = MaterialTheme.typography.bodySmall)
            }
            if (onAddChild != null || onDelete != null) {
                Row {
                    if (onAddChild != null) TextButton(onClick = { onAddChild(n) }) { Text("+ Sous-poste") }
                    if (onDelete != null) TextButton(onClick = { onDelete(n) }) { Text("Supprimer") }
                }
            }
        }
        OrgTree(nodes, n.id, depth + 1, onAddChild, onDelete)
    }
}

@Composable
fun FormFill(form: FormDef, vm: AppViewModel) {
    val values = remember(form.id) {
        mutableStateListOf<String>().apply { repeat(form.fields.size) { add("") } }
    }
    form.fields.forEachIndexed { i, f ->
        OutlinedTextField(
            value = values.getOrElse(i) { "" },
            onValueChange = { if (i < values.size) values[i] = it },
            label = { Text(f.label) },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Button(onClick = {
        val data = HashMap<String, String>()
        form.fields.forEachIndexed { i, f -> data[f.name] = values.getOrElse(i) { "" } }
        vm.submitForm(form.id, data)
        for (i in values.indices) values[i] = ""
    }) { Text("Envoyer") }
}
