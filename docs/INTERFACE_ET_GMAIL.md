# Messagerie locale et connecteur Gmail

Ce guide décrit les dossiers, le parcours de relecture et la configuration du connecteur Gmail facultatif. La génération reste assurée par un modèle local ; toute transmission nécessite l’approbation du contenu exact et des destinataires.

## État du connecteur

| Fonction | État |
| --- | --- |
| Messagerie locale et classement durable | Implémentés et vérifiés |
| OAuth, import et séparation des comptes | Implémentés et testés avec un fournisseur de test ; premier essai Google à réaliser |
| Classement distant, export de brouillon et envoi approuvé | Implémentés et testés avec un fournisseur de test ; aucun envoi réel validé |
| Nouveau message indépendant, pièces jointes et labels personnalisés | Non implémentés |

## Messagerie locale

L'onglet Messagerie ouvre une vue dossiers / liste / lecture. La recherche porte sur objet, corps, expéditeur et destinataires du dossier courant ; casse et accents sont ignorés. Les dossiers et le routage métier sont indépendants : « Spam » est un classement de messagerie, « Intégration » est une compétence d'agent.

Réception, Archives, Spam et Corbeille sont des emplacements exclusifs des messages reçus. Les favoris sont un indicateur distinct. Tous les reçus exclut Spam et Corbeille. La corbeille est réversible, sans effacement définitif. Le classement spam est manuel ; aucun détecteur automatique n'est revendiqué. La sélection marque un reçu comme lu ; le bouton permet de le remettre non lu.

Brouillons affiche la dernière version locale de réponse. Une version déjà confirmée ou simulée, ou bloquée par une tentative en cours/incertaine, n'est pas affichée comme brouillon disponible. Une nouvelle édition est à nouveau un brouillon. Envoyés ne contient que des tentatives réellement CONFIRMED ; le contenu affiché est l'instantané approuvé de cette tentative. Envois simulés reste séparé. À envoyer / échecs regroupe PENDING, FAILED et UNKNOWN ; le dossier n'effectue aucune relance automatique.

Le bouton Ouvrir dans l'assistant MAS permet de router et de préparer une réponse, ou de reprendre le brouillon courant. Lire, rechercher et classer ne déclenchent aucun LLM ni envoi. La génération, régénération et transmission de messages en Spam/Corbeille sont refusées dans l'application jusqu'à restauration, même si une ancienne vue est encore ouverte. Les saisies non enregistrées dans l'assistant bloquent les changements de sélection et les classements.

## Configurer Gmail

La connexion Google est facultative. Sans option Gmail, l'application utilise sa messagerie locale et son transport simulé. Aucune configuration Google n'est nécessaire pour lancer :

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui
```

Quand le projet Google sera disponible :

1. Activer Gmail API dans ce projet, configurer son consentement OAuth et ajouter le compte de test si le projet est en mode test.
2. Créer un client OAuth de type **Application de bureau** et télécharger son JSON. Le fichier doit posséder la clé `installed`, avec `client_id` et `client_secret`.
3. Placer ce fichier dans `config/local/gmail-oauth.json` (répertoire exclu du versionnement). Le modèle vide `config/examples/gmail-oauth.example.json` décrit le format mais n'est pas une autorisation utilisable.
4. Lancer la commande suivante et autoriser le compte dans le navigateur système. Aucun changement de code n'est prévu pour renseigner ces identifiants.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui --gmail-credentials config/local/gmail-oauth.json
```

Le lancement attend le consentement pendant au plus trois minutes. Si le client OAuth est incomplet ou absent, il échoue sans basculer silencieusement vers un autre compte ou un fournisseur de génération. Le navigateur sert uniquement à l'autorisation ; l'application Java reste l'interface de travail. Les jetons d'accès et de rafraîchissement restent en mémoire, sans fichier de jetons ni stockage dans SQLite. Refaire l'autorisation après fermeture ; le rafraîchissement pendant la session est automatique.

Le périmètre demandé est `gmail.modify`, nécessaire à la lecture et au classement des messages, aux brouillons et à l'envoi. Les origines OAuth/API sont fixées aux serveurs officiels Google, sans suivi de redirection HTTP par le client. PKCE S256, un état aléatoire et un rappel sur `127.0.0.1` avec port éphémère protègent le flux de bureau.

Le programme crée par défaut une base distincte par compte dans `data/runtime/`. L'option `--db` permet un chemin explicite ; les identifiants de message et de fil incluent dans tous les cas une empreinte du compte. Les fixtures de démonstration ne sont pas importées en mode Gmail. Les expéditeurs distants commencent dans un périmètre client inconnu : aucun accès aux documents privés de la startup ne leur est accordé par leur simple adresse ou le contenu du message.

**`--fake` désactive la rédaction sans réponse prédéfinie. Avec `--gmail-credentials`, les opérations de messagerie sont réelles**, même si la rédaction est désactivée. Le bandeau et le bouton d'approbation signalent ce mode. Pour une démonstration sans aucune opération distante, omettre entièrement `--gmail-credentials`.

## Utilisation du connecteur préparé

Actualiser charge au plus **200 messages récents**, avec pagination, Spam/Corbeille inclus, ainsi que les brouillons Gmail (plafond de 1000). Le retour à l'onglet Messagerie actualise également cette fenêtre. Il s'agit d'une actualisation manuelle bornée, pas d'une synchronisation intégrale de tout l'historique, d'un push ou d'une relève périodique. La recherche porte sur les messages chargés.

Les reçus sont importés de manière durable et réimporter le même identifiant ne crée pas de doublon. Lecture/non-lecture, favori et déplacement d'un reçu vers Réception, Archives, Spam ou Corbeille mettent à jour les labels Gmail correspondants. Les autres labels distants restent inchangés. Les labels personnalisés et catégories Gmail ne possèdent pas encore de dossiers dédiés dans cette interface.

Les envoyés et brouillons créés directement dans Gmail sont consultables. Un brouillon Gmail sans origine MAS est en lecture seule dans cette version. Un reçu peut être ouvert dans l'assistant pour générer puis éditer une réponse locale. Dans Brouillons, **Enregistrer ce brouillon dans Gmail** exporte explicitement son contenu sans l'envoyer ; les générations ne sont pas téléversées automatiquement. L'association au brouillon distant est conservée dans son en-tête `X-MAS-Draft-Id`. Un changement distant depuis la dernière actualisation bloque son remplacement et exige une nouvelle vérification ; ce contrôle n'est pas une transaction distribuée avec Gmail.

**Approuver et envoyer réellement** envoie la version exacte et ses destinataires via Gmail API. S'il existe un brouillon Gmail associé, l'envoi fournit à nouveau ce contenu exact dans `drafts.send` ; sinon il utilise `messages.send`. Les garde-fous du domaine restent actifs. Le reçu distant est revérifié avant envoi pour refuser un message désormais en Spam/Corbeille. Un accusé Gmail signifie acceptation par le fournisseur, pas preuve de lecture ou de livraison finale au destinataire. Timeout, réponse inexploitable ou erreur serveur après demande d'envoi donnent UNKNOWN ; aucune relance automatique ni fausse confirmation.

Le MIME sortant est du texte UTF-8, sujet encodé et replié, destinataires approuvés et références de réponse. La lecture privilégie le texte simple ; le HTML est affiché comme texte sans exécuter ses scripts ou charger ses ressources. Les pièces jointes ne sont pas téléchargées. Composition indépendante d'un nouveau message, pièces jointes et édition libre des brouillons Gmail ne sont pas encore disponibles.

**État de validation :** protocole et parcours vérifiés avec fournisseur factice et SQLite ; aucun projet/client OAuth réel n'a été renseigné, aucun compte n'a été autorisé et aucun email réel n'a été envoyé. Le premier essai de compte reste nécessaire avant de revendiquer un fonctionnement validé avec Google. Bilan : `docs/experiments/I12BC-gmail-preparation.md`.

## Références et limites

Choix technique : adaptateur Gmail API avec autorisation OAuth pour application de bureau, sans demander le mot de passe Gmail à l'application. L'API prend en charge messages, fils, brouillons et labels : [présentation officielle](https://developers.google.com/workspace/gmail/api/guides). Le flux de bureau requiert une configuration de projet Google et un client OAuth : [documentation OAuth](https://developers.google.com/identity/protocols/oauth2/native-app).

Gmail utilise des labels multiples : INBOX, SENT, DRAFT, SPAM, TRASH, UNREAD, STARRED et des catégories. Le classement local exclusif n'est pas un modèle complet des labels Gmail. SENT et DRAFT ont des règles propres au fournisseur : [gestion officielle des labels](https://developers.google.com/workspace/gmail/api/guides/labels). Une simple approbation ou simulation ne produit pas un label SENT.

La première connexion présente les accès demandés : [scopes Gmail](https://developers.google.com/workspace/gmail/api/auth/scopes). Aucun jeton, mot de passe ou fichier de credentials ne doit être copié dans les fixtures, la base métier ou les logs. Les tests utilisent exclusivement des valeurs synthétiques et un échange de jetons factice.

Un expéditeur Gmail ne devient pas un client approuvé parce que son email le prétend. Le cache et les imports locaux contiennent les emails en clair ; protéger le dossier du compte utilisateur. Une coupure pendant l'envoi exige une vérification dans Gmail avant toute suite : la réconciliation automatique des résultats incertains n'est pas implémentée. Le projet Google et les politiques de son organisation peuvent limiter l'accès ; ils devront être vérifiés lors du premier essai.

La connexion Google concerne le transport des emails. La génération doit continuer exclusivement via le modèle local ; aucun service de génération Google n'est prévu.

Le champ de réponse reste vide jusqu’à un résultat du LLM local. Les anciens textes fixes simulés non modifiés sont retirés des listes actives au prochain démarrage et archivés ; les éditions humaines et envois réels restent conservés.
