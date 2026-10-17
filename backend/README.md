# SaaS H — backend (Kotlin / Ktor / PostgreSQL)

## Déployer sans PC (Render)
1. Poussez tout le dépôt sur GitHub (le fichier `render.yaml` est à la racine).
2. Sur render.com → **New → Blueprint** → choisissez votre dépôt. Render crée l'API et la base PostgreSQL.
3. Une fois déployé, ajoutez la variable `PUBLIC_URL` = l'URL https de l'API (ex. `https://saash-api.onrender.com`), puis redéployez.
4. Ouvrez `https://…/health` : vous devez voir `{"ok":true}`.
5. Dans l'application, saisissez cette URL à l'écran de connexion.

Variables : `DATABASE_URL`, `JWT_SECRET`, `DATA_KEY` (chiffrement des clés API), `PUBLIC_URL`,
`DB_SSL=true` si la base exige SSL, `ALLOW_PRIVATE_TARGETS=true` (dev uniquement).

## Concepts
- **Organisation** : `manager` (gère ses SaaS) ou `vendor` (éditeur qui suit ses clients).
- **Rôles** : `ADMIN` (tout), `MEMBER` (voir, décider, actions, créer des interfaces), `CLIENT` (voir et envoyer des demandes/formulaires).
- **Connecteurs** : clé API (`bearer`, `header`, `query`), OAuth2 (`/oauth/{id}/start`), webhooks entrants, connecteur personnalisé (URL + chemin de santé + config JSON).
- **Webhook entrant** : `POST /webhooks/{token}` avec un JSON `{"type":"access_request","user":"Awa","reason":"…"}`,
  `{"type":"usage","user":"Awa","minutes":15}`, `{"type":"incident","title":"…"}`, `{"type":"resolved"}` ou tout autre type.
  Pour `access_request`, un champ optionnel `"ref"` (l'identifiant de la demande chez vous) est conservé et **renvoyé avec la décision**.
- **Tableaux de bord** : `layout = {"widgets":[{"id","type","title",…}]}` ; types : `stat` (`metric`), `chart` (`events7d`),
  `status_list`, `incidents`, `requests`, `usage`, `button` (`refId` = id de l'action), `org_chart`, `form` (`refId` = id du formulaire), `text` (`text`).
  Métriques : `servicesUp`, `servicesTotal`, `servicesDown`, `incidentsOpen`, `requestsPending`, `usersTotal`, `usersActive`, `events24h`.
- **Automatisations** : déclencheur (`incident_opened`, `incident_resolved`, `request_created`, `request_decided`, `event`)
  → action `notify`, `run_action` ou `webhook`. Variables `{{connector}}`, `{{title}}`, `{{user}}`…

## Modèles, boutons, interrupteurs, organigrammes
- `GET /catalog` : **10 modèles de SaaS** (CRM, boutique, support, messagerie, CI/CD, paiements, projets, e-mailing, RH, hébergement),
  **20 types de boutons** et **5 modèles d'organigramme** (startup, entreprise, support/exploitation, éditeur de SaaS, équipe projet).
- Boutons (`/controls`, type `button`) : 7 internes (vérifier, notifier, déclarer/résoudre un incident, accepter/refuser tout, signal vers mon code),
  2 côté téléphone (ouvrir un lien, copier le code), 11 qui appellent l'API d'un SaaS (appel libre, redémarrer, déployer, sauvegarder, vider le cache,
  maintenance on/off, rapport, inviter, révoquer, exporter). Les chemins par défaut sont des exemples à adapter.
- Interrupteurs (`type: switch`) : état stocké, appel optionnel d'un SaaS (`{{state}}` = true/false).
- Chaque bouton/interrupteur a un **code / ID** choisi par l'utilisateur (unique dans l'organisation).
- Organigrammes : plusieurs par organisation (`/charts`), création depuis un modèle, postes modifiables (`/org`).

## Intégrer un code / ID dans votre propre code
Clé API : `GET /org-settings` (admin) ou menu « Intégration » de l'app.
```
curl -H "X-Api-Key: sk_…" https://API/v1/controls/<code>                 # lire (interrupteur : "on")
curl -X POST -H "X-Api-Key: sk_…" https://API/v1/controls/<code>/press   # déclencher un bouton
curl -X PUT  -H "X-Api-Key: sk_…" -H "Content-Type: application/json" -d '{"on":true}' https://API/v1/controls/<code>/state
```
Rappel : si `callbackUrl` est défini (`PUT /org-settings`), chaque action est envoyée en POST JSON
`{"type":"button"|"switch","code":"…","by":"…","ts":…}` avec `X-SaaSH-Signature: sha256=HMAC_SHA256(clé API, corps)`.

## Décision d'une demande d'accès
Quand une demande est acceptée ou refusée (manuellement ou par « Accepter/Refuser toutes les demandes »), l'URL de rappel reçoit
`{"type":"request_decided","state":"accepted"|"refused","user":"…","ref":"…","by":"…","ts":…}` signé en HMAC-SHA256.
Le SaaS d'origine accorde ou refuse alors l'accès (voir le dépôt séparé MonSaaS).

## Sécurité intégrée
Mots de passe BCrypt, JWT 14 jours, clés API chiffrées (AES-GCM), isolation stricte par organisation,
limitation des essais de connexion, protection SSRF (adresses internes refusées, pas de redirection suivie).

## Limites connues
- Pas de renouvellement automatique des tokens OAuth (refresh token) : à reconnecter à l'expiration.
- Notifications : l'app interroge `/notifications` (pas de push Firebase pour l'instant).
- Pas de vérification de signature des webhooks : le jeton secret dans l'URL fait office de clé.
