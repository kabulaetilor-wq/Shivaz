package com.saash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val OkColor = Color(0xFF2E7D32)
val WarnColor = Color(0xFFF9A825)
val DownColor = Color(0xFFC62828)
val GreyColor = Color(0xFF757575)

fun statusColor(s: String) = when (s) {
    "up" -> OkColor
    "degraded" -> WarnColor
    "down" -> DownColor
    else -> GreyColor
}

fun statusLabel(s: String) = when (s) {
    "up" -> "Opérationnel"
    "degraded" -> "Dégradé"
    "down" -> "En panne"
    else -> "Non vérifié"
}

fun fmt(ms: Long): String =
    if (ms <= 0L) "jamais" else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))

fun duration(startMs: Long, endMs: Long): String {
    val min = ((endMs - startMs) / 60000).coerceAtLeast(0)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!title.isNullOrBlank()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun FormDialog(
    title: String,
    labels: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
    secretIndexes: Set<Int> = emptySet(),
    hint: String? = null,
    submitLabel: String = "Valider",
    initial: List<String> = emptyList()
) {
    val values = remember {
        mutableStateListOf<String>().apply { repeat(labels.size) { add(initial.getOrElse(it) { "" }) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = if (i in secretIndexes) PasswordVisualTransformation() else VisualTransformation.None
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(values.toList()) }) { Text(submitLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Liste de choix : chaque option est (valeur, libellé). */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (options.isEmpty()) Text("Rien à choisir pour le moment.")
                options.forEach { (value, label) ->
                    OutlinedButton(onClick = { onPick(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirmer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
