package com.saash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun SaasHApp(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    if (!vm.loggedIn) {
        LoginScreen(vm, snackbar)
    } else {
        MainScreen(vm, snackbar)
    }
}

@Composable
private fun LoginScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var url by remember { mutableStateOf(vm.api.baseUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var orgName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("manager") }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("SaaS H", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (register) "Créer un compte et une organisation" else "Connexion",
                style = MaterialTheme.typography.titleMedium
            )
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Adresse du serveur (https://…)") }
            )
            if (register) {
                OutlinedTextField(orgName, { orgName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nom de l'organisation") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "manager", { kind = "manager" }, { Text("Je gère des SaaS") })
                    FilterChip(kind == "vendor", { kind = "vendor" }, { Text("Je vends un SaaS") })
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Votre nom") })
            }
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") })
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Mot de passe (8 caractères min.)") },
                visualTransformation = PasswordVisualTransformation()
            )
            Button(
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val server = url.trim().trimEnd('/')
                    if (register) vm.register(server, orgName.trim(), kind, name.trim(), email.trim(), password)
                    else vm.login(server, email.trim(), password)
                }
            ) { Text(if (register) "Créer mon compte" else "Se connecter") }
            TextButton(onClick = { register = !register }) {
                Text(if (register) "J'ai déjà un compte" else "Créer un compte")
            }
            Text(
                "Le premier chargement peut prendre une minute si le serveur gratuit était en veille.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var tab by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf("") }
    var showRequestForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Scheduler.start(vm.getApplication())
        vm.refresh(true)
        vm.pollNotifications()
        while (true) {
            delay(30_000)
            vm.pollNotifications()
            vm.refresh()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val tabs = listOf(
        TabItem("Accueil", Icons.Default.Home),
        TabItem("Services", Icons.Default.Settings),
        TabItem("Demandes", Icons.Default.Notifications),
        TabItem("Pannes", Icons.Default.Warning),
        TabItem("Plus", Icons.Default.Menu)
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(vm.me?.orgName ?: "SaaS H") },
                actions = {
                    IconButton(onClick = { vm.refresh(true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) }
                    )
                }
            }
        },
        floatingActionButton = {
            if ((tab == 1 && vm.isAdmin) || tab == 2) {
                FloatingActionButton(onClick = {
                    if (tab == 1) step = "kind" else showRequestForm = true
                }) { Icon(Icons.Default.Add, contentDescription = "Ajouter") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> DashboardScreen(vm)
                1 -> ServicesScreen(vm, step) { step = it }
                2 -> RequestsScreen(vm, showRequestForm) { showRequestForm = false }
                3 -> IncidentsScreen(vm)
                else -> MoreScreen(vm)
            }
        }
    }
}
