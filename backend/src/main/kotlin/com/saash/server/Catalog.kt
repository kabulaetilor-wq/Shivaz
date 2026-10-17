package com.saash.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** scope : internal = exécuté par SaaS H, client = exécuté sur le téléphone, external = appelle l'API d'un SaaS connecté. */
data class ButtonKind(
    val key: String,
    val label: String,
    val description: String,
    val scope: String,
    val method: String = "POST",
    val path: String = "",
    val body: String = ""
)

data class SaasTemplate(
    val key: String,
    val name: String,
    val authName: String,
    val healthPath: String,
    val hint: String,
    val events: List<String>
)

/** Chaque nœud = (nom, fonction, index du parent dans la liste, -1 pour la racine). */
data class OrgTemplate(
    val key: String,
    val name: String,
    val description: String,
    val nodes: List<Triple<String, String, Int>>
)

object Catalog {

    val buttonKinds = listOf(
        ButtonKind("check_now", "Vérifier les services", "Contrôle immédiatement tous les SaaS connectés", "internal"),
        ButtonKind("notify_team", "Notifier l'équipe", "Envoie une notification à toute l'organisation (le corps = le message)", "internal"),
        ButtonKind("create_incident", "Déclarer un incident", "Ouvre un incident manuel (le corps = le titre)", "internal"),
        ButtonKind("resolve_incidents", "Résoudre les incidents", "Clôture tous les incidents ouverts", "internal"),
        ButtonKind("approve_all", "Accepter toutes les demandes", "Accepte les demandes d'accès en attente", "internal"),
        ButtonKind("refuse_all", "Refuser toutes les demandes", "Refuse les demandes d'accès en attente", "internal"),
        ButtonKind("emit_event", "Signal vers mon code", "Envoie le code du bouton à l'URL de rappel de votre application", "internal"),
        ButtonKind("open_link", "Ouvrir un lien", "Ouvre une adresse web (mettez l'URL dans « chemin »)", "client", "GET"),
        ButtonKind("copy_code", "Copier le code / ID", "Copie le code du bouton pour le coller dans votre code", "client", "GET"),
        ButtonKind("api_call", "Appel API libre", "Appelle l'API d'un SaaS connecté (méthode, chemin, corps)", "external", "POST", "/"),
        ButtonKind("restart", "Redémarrer", "Demande un redémarrage au SaaS", "external", "POST", "/restart"),
        ButtonKind("deploy", "Déployer", "Lance un déploiement", "external", "POST", "/deploy"),
        ButtonKind("backup", "Sauvegarder", "Lance une sauvegarde", "external", "POST", "/backup"),
        ButtonKind("clear_cache", "Vider le cache", "Vide le cache du SaaS", "external", "POST", "/cache/clear"),
        ButtonKind("maintenance_on", "Activer la maintenance", "Passe le SaaS en mode maintenance", "external", "POST", "/maintenance", "{\"enabled\":true}"),
        ButtonKind("maintenance_off", "Désactiver la maintenance", "Sort le SaaS du mode maintenance", "external", "POST", "/maintenance", "{\"enabled\":false}"),
        ButtonKind("send_report", "Envoyer un rapport", "Déclenche l'envoi d'un rapport", "external", "POST", "/reports/send"),
        ButtonKind("invite_user", "Inviter un utilisateur", "Invite un utilisateur dans le SaaS", "external", "POST", "/users/invite", "{\"email\":\"prenom@exemple.com\"}"),
        ButtonKind("revoke_access", "Révoquer un accès", "Retire un accès dans le SaaS", "external", "POST", "/access/revoke", "{\"email\":\"prenom@exemple.com\"}"),
        ButtonKind("export_data", "Exporter les données", "Demande un export des données", "external", "GET", "/export")
    )

    fun buttonKind(key: String): ButtonKind? = buttonKinds.firstOrNull { it.key == key }

    // Modèles génériques : les adresses sont à compléter, les chemins d'API sont des conventions à adapter.
    val saasTemplates = listOf(
        SaasTemplate("crm", "CRM (clients et ventes)", "", "/health", "Suivi des clients : recevez les nouvelles demandes et l'usage.", listOf("access_request", "usage", "incident")),
        SaasTemplate("ecommerce", "Boutique en ligne", "", "/status", "Surveillez la disponibilité de la boutique et les commandes.", listOf("event", "incident", "resolved")),
        SaasTemplate("helpdesk", "Support client (tickets)", "", "/health", "Recevez les tickets comme demandes d'accès ou événements.", listOf("access_request", "event")),
        SaasTemplate("chat", "Messagerie d'équipe", "", "/health", "Suivez l'usage de la messagerie et ses pannes.", listOf("usage", "incident")),
        SaasTemplate("cicd", "Code et déploiement (CI/CD)", "", "/status", "Pannes de pipeline et déploiements en événements.", listOf("event", "incident", "resolved")),
        SaasTemplate("payments", "Paiements et facturation", "X-Api-Key", "/health", "Alertes de panne de paiement ; clé dans un en-tête.", listOf("incident", "resolved", "event")),
        SaasTemplate("projects", "Gestion de projet", "", "/health", "Accès aux projets et usage par équipe.", listOf("access_request", "usage")),
        SaasTemplate("mailing", "Marketing e-mail", "", "/health", "Suivi des envois et des incidents de campagne.", listOf("event", "incident")),
        SaasTemplate("hr", "Ressources humaines", "", "/health", "Demandes d'accès du personnel et usage.", listOf("access_request", "usage")),
        SaasTemplate("hosting", "Hébergement et monitoring", "", "/", "Surveillance d'un serveur ou d'un site : état, latence, pannes.", listOf("incident", "resolved"))
    )

    val orgTemplates = listOf(
        OrgTemplate(
            "startup", "Startup SaaS", "Petite équipe produit et technique",
            listOf(
                Triple("Direction", "CEO", -1), Triple("Technique", "CTO", 0), Triple("Produit", "Responsable produit", 0),
                Triple("Ventes", "Responsable ventes", 0), Triple("Support", "Support client", 0),
                Triple("Backend", "Développeurs backend", 1), Triple("Mobile", "Développeurs mobile", 1), Triple("Design", "Designer", 2)
            )
        ),
        OrgTemplate(
            "enterprise", "Entreprise classique", "Direction et départements",
            listOf(
                Triple("Direction générale", "Directeur général", -1), Triple("Finance", "DAF", 0), Triple("Ressources humaines", "DRH", 0),
                Triple("Technique", "Directeur technique", 0), Triple("Commercial", "Directeur commercial", 0),
                Triple("Comptabilité", "Équipe comptable", 1), Triple("Recrutement", "Chargés de recrutement", 2),
                Triple("Informatique", "Équipe IT", 3), Triple("Ventes terrain", "Commerciaux", 4)
            )
        ),
        OrgTemplate(
            "support_ops", "Support et exploitation", "Gestion des pannes et du support",
            listOf(
                Triple("Exploitation", "Responsable exploitation", -1), Triple("Support niveau 1", "Premier contact", 0),
                Triple("Support niveau 2", "Experts", 0), Triple("Astreinte", "SRE / astreinte", 0),
                Triple("Gestion des incidents", "Coordinateur", 3), Triple("Base de connaissances", "Rédacteurs", 1)
            )
        ),
        OrgTemplate(
            "vendor", "Éditeur de SaaS", "Produit, ingénierie et succès client",
            listOf(
                Triple("Fondateur", "Direction", -1), Triple("Produit", "Product manager", 0), Triple("Ingénierie", "Responsable technique", 0),
                Triple("Succès client", "Customer success", 0), Triple("Ventes", "Responsable ventes", 0),
                Triple("Design", "UX / UI", 1), Triple("Backend", "Développeurs", 2), Triple("Mobile", "Développeurs", 2),
                Triple("Onboarding", "Équipe d'intégration", 3), Triple("Partenariats", "Business developers", 4)
            )
        ),
        OrgTemplate(
            "project", "Équipe projet", "Organisation par projet",
            listOf(
                Triple("Sponsor", "Commanditaire", -1), Triple("Chef de projet", "Pilotage", 0),
                Triple("Responsable technique", "Architecture", 1), Triple("Responsable qualité", "Tests et recette", 1),
                Triple("Développeurs", "Équipe de réalisation", 2), Triple("Testeurs", "Équipe qualité", 3),
                Triple("Utilisateurs pilotes", "Retours terrain", 1)
            )
        )
    )

    fun json(): JsonObject = buildJsonObject {
        put("buttonKinds", buildJsonArray {
            buttonKinds.forEach { k ->
                add(buildJsonObject {
                    put("key", k.key); put("label", k.label); put("description", k.description)
                    put("scope", k.scope); put("method", k.method); put("path", k.path); put("body", k.body)
                })
            }
        })
        put("saasTemplates", buildJsonArray {
            saasTemplates.forEach { t ->
                add(buildJsonObject {
                    put("key", t.key); put("name", t.name); put("authName", t.authName)
                    put("healthPath", t.healthPath); put("hint", t.hint)
                    put("events", buildJsonArray { t.events.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                })
            }
        })
        put("orgTemplates", buildJsonArray {
            orgTemplates.forEach { t ->
                add(buildJsonObject {
                    put("key", t.key); put("name", t.name); put("description", t.description); put("count", t.nodes.size)
                })
            }
        })
    }

    /** Crée les postes du modèle dans l'organigramme donné. */
    fun applyOrgTemplate(orgId: Int, chartId: Int, key: String) {
        val t = orgTemplates.firstOrNull { it.key == key } ?: return
        val ids = ArrayList<Int>()
        t.nodes.forEach { (name, title, parentIdx) ->
            val parent = if (parentIdx >= 0) ids[parentIdx] else null
            ids.add(
                Db.insert(
                    "INSERT INTO org_nodes(org_id,chart_id,parent_id,name,title) VALUES(?,?,?,?,?) RETURNING id",
                    listOf(orgId, chartId, parent, name, title)
                )
            )
        }
    }
}
