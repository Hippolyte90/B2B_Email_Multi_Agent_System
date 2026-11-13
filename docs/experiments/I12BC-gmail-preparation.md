# I12-B/C — Connecteur Gmail facultatif préparé

13 novembre 2026. L'utilisateur souhaite préparer le code et compléter le projet Google ultérieurement. Le mode local reste disponible sans Google. Aucun compte réel n'a été connecté et aucun email réel n'a été envoyé.

## Vérification exécutée

`./mvnw.cmd -o verify` : **486 tests réussis**, sans échec, erreur ou ignoré : 391 unitaires/parcours et 95 intégrations (78 SQLite, 17 HTTP dont 5 rappels OAuth loopback). JDK 25.0.2, cible Java 21, Windows. Aucun appel à Google dans ces tests et aucun téléchargement de modèle.

Ajouts par rapport à I12-A :

- `GmailClientTest` : 15 cas, profil, pagination/déduplication/limites, MIME simple/multipart/HTML/charset/sujet encodé, pièces jointes ignorées, classement, export/mise à jour et contenu exact d'envoi.
- `GmailEmailSenderTest` : 9 cas, confirmation du fournisseur factice, refus certain, timeout, erreur serveur, réponse malformée, préparation échouée, brouillon associé, conflit et message devenu spam. Pas de relance implicite.
- `GmailMailboxTest` : 11 cas, import et périmètre inconnu, reprise sans doublon, séparation des comptes, classement local/distant, erreur réseau, envoyés/brouillons externes, export explicite, conflit distant, spam et disparition de la fenêtre.
- `GoogleDesktopOAuthTest` : 5 cas, lecture bureau, erreurs expurgées, query/state, origine HTTP et refus avant accès au jeton.
- `MailboxPresenterTest` : 3 cas supplémentaires, actualisation distante et export délégués au worker, mode connecté requis, travail humain non enregistré protégé.
- `GoogleDesktopOAuthHttpIT` : 5 cas, échange de code factice avec vrai rappel localhost, vérification state/PKCE, refus utilisateur, échec du navigateur simulé et rafraîchissement en mémoire avec horloge contrôlée. Le navigateur et Google sont remplacés.
- `GmailStorageSqliteIT` : 4 cas, reçu -> spécialiste -> faux LLM -> édition -> approbation exacte -> accusé Gmail factice, confirmation durable, résultat UNKNOWN durable sans relance, classement/reprise et séparation de deux comptes dans la même base.

Le JAR a aussi été lancé avec `--gui --fake --gmail-credentials config/examples/gmail-oauth.example.json`. Le modèle vide est refusé avec code 1 avant toute autorisation ; l'usage affiche la nouvelle option, sans secret ni faux état connecté.

Contrôle graphique du JAR final avec `--gui --fake --db data/runtime/i12-a-gui.sqlite` : la fenêtre démarre sans configuration Google, indique Gmail non connecté et Envoi simulé uniquement, puis retrouve les compteurs locaux (Réception 6, Brouillons 1, Archives 1, Favoris 1). Fermeture normale, code 0. Le parcours visuel détaillé des dossiers et brouillons est dans `I12A-mailbox-smoke.md`.

## Ce qui reste à valider avec Google

Le client OAuth bureau et le projet ne sont pas fournis. Le flux réel de consentement, les politiques du projet, les données réelles Gmail et un envoi de compte de test restent **non exécutés**. Les tests factices ne remplacent pas cette validation. Aucun résultat qualitatif du LLM réel n'est ajouté par cet incrément.

Quand le projet sera prêt, placer le JSON téléchargé dans `config/local/gmail-oauth.json`, puis suivre `docs/INTERFACE_ET_GMAIL.md`. Cette configuration active le code préparé ; aucun changement de source n'est prévu pour y renseigner le client OAuth.

La fenêtre Gmail est bornée à 200 messages récents plus les brouillons (maximum 1000), avec actualisation manuelle. Les pièces jointes, la création indépendante de nouveaux emails, les labels personnalisés et la réconciliation automatique des résultats incertains ne sont pas implémentés. Les jetons restent uniquement en mémoire ; une nouvelle autorisation est nécessaire après fermeture. Le cache métier local contient les messages en clair.
