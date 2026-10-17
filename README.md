# SaaS H

Plateforme pour superviser et piloter des SaaS : application Android (Kotlin + Compose) + backend (Kotlin/Ktor + PostgreSQL).

```
saas-h/
├─ app/        Application Android
├─ backend/    API Ktor + PostgreSQL (voir backend/README.md)
├─ render.yaml Déploiement du backend sur Render (sans PC)
└─ .github/workflows/  build.yml (APK) et backend.yml (compilation du serveur)
```

## Fonctions
- **Comptes et rôles** : organisation « gestionnaire » ou « éditeur », rôles Admin / Équipe / Client.
- **Connexion des SaaS** : clé API, OAuth, webhooks entrants, connecteur personnalisé.
- **Surveillance réelle** côté serveur toutes les minutes : état, latence, disponibilité, pannes, durée.
- **Alertes** : notifications du téléphone (app ouverte ou fermée, toutes les 15 min en arrière-plan).
- **Demandes d'accès** : reçues par webhook ou formulaire, acceptées/refusées depuis l'app.
- **Utilisateurs et usage** : reçus par webhook.
- **Interfaces personnalisables** : tableaux de bord avec compteurs, graphique, listes, boutons d'action, organigramme, formulaires, notes.
- **10 modèles de SaaS** modifiables, **20 types de boutons** et des **interrupteurs**, chacun avec un **code / ID** à utiliser dans votre code (API par clé + rappel signé).
- **5 modèles d'organigramme**, plusieurs organigrammes par organisation, postes modifiables.
- **Formulaires** et **automatisations** (« si panne alors notifier / appeler un webhook / lancer un bouton »).

## Mise en route sans PC
1. Poussez ce dossier sur GitHub (branche `main`).
2. **Backend** : render.com → New → Blueprint → votre dépôt. Ajoutez `PUBLIC_URL` (l'URL https du service), redéployez. Détails : `backend/README.md`.
3. **APK** : onglet Actions → « Build APK » → artefact `SaaS-H-debug-apk`.
4. Ouvrez l'app, saisissez l'URL du serveur, créez votre compte.
