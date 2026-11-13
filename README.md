# PayBridge — Assistant multi-agent de rédaction d’emails

PayBridge est une application Java qui prépare des réponses aux emails d’une startup B2B de paiement et d’accompagnement à l’intégration. Un routeur sélectionne un agent spécialisé, qui utilise un modèle de langage exécuté localement et un contexte autorisé. Chaque réponse reste un brouillon soumis à la relecture et à l’approbation de l’utilisateur.

L’application ne traite aucun paiement et ne réserve aucun intervenant. Les données de démonstration sont synthétiques.

## Fonctionnalités

- Messagerie avec réception, brouillons, envoyés, archives, spam, corbeille, favoris et recherche.
- Routage vers cinq spécialistes : commercial, intégration, assistance sur site, administration et demandes générales. Les demandes ambiguës nécessitent un choix humain.
- Démarrage automatique d’Ollama et préchargement du modèle installé avant le flux de démonstration.
- Préparation automatique des réponses dans **Validations en attente**, sans approbation ni envoi automatiques.
- Modification, versionnement, rejet et régénération des brouillons ; contrôle du contenu exact et des destinataires avant transmission.
- Configuration des agents, prompts, sources et paramètres d’inférence ; persistance locale SQLite.
- Connecteur Gmail facultatif avec OAuth, préparé et testé avec un fournisseur de test. Son utilisation sur un compte Google réel reste à valider.

L’organisation du code et les choix de conception sont décrits dans [ARCHITECTURE.md](ARCHITECTURE.md). Le rapport est disponible en [PDF](docs/report/RAPPORT.pdf), en [Word modifiable](docs/report/RAPPORT.docx) et en [Markdown](docs/report/RAPPORT.md). Il présente la conception et les résultats mesurés.

## Prérequis

| Composant | Version ou condition |
| --- | --- |
| Java | JDK 21 ou supérieur ; compilation ciblant Java 21 |
| Maven | Wrapper fourni : Maven 3.9.9, wrapper 3.3.2 |
| Ollama | Installation locale pour la génération ; version de contrôle : 0.40.15 |
| Modèle | `qwen2.5:0.5b`, déjà installé dans le stockage du projet |
| Système vérifié | Windows, PowerShell et JDK 25.0.2 |

Le premier build nécessite un accès à Maven Central. Le JAR embarque JavaFX 21.0.13, SQLite JDBC 3.51.1.0 et Gson 2.13.2. Les tests utilisent JUnit Jupiter 5.11.4. Les versions sont fixées dans `pom.xml`.

Les composants natifs JavaFX dépendent du système sur lequel le JAR est compilé. Le build et l’interface ont été vérifiés sous Windows ; une distribution sur un autre système demande une compilation et une vérification adaptées. L’exécution sur un JDK 21 n’a pas encore été contrôlée directement.

## Compilation et tests

Exécuter les commandes depuis la racine du projet. Adapter `JAVA_HOME` au JDK installé :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
$env:MAVEN_USER_HOME = Join-Path $PWD '.m2-local'
./mvnw.cmd verify
```

Le JAR exécutable est produit dans `target/mas-email-assistant-0.1.0-SNAPSHOT.jar`.

```powershell
./mvnw.cmd test                   # Tests unitaires et parcours sans réseau
./mvnw.cmd verify                 # Build et intégrations SQLite/HTTP contrôlées
./mvnw.cmd -o verify              # Même vérification, si les dépendances sont en cache
./mvnw.cmd -Preal-llm verify       # Ajoute un test volontaire avec le modèle local installé
```

La suite standard ne requiert ni Ollama ni compte Google, ne télécharge aucun modèle et n’envoie aucun email. Les intégrations HTTP utilisent uniquement des serveurs de test sur loopback ; SQLite utilise des bases temporaires. Le cache Maven local est configuré dans `.mvn/maven.config`.

Dernière validation fonctionnelle acquise : **559 tests réussis**, sans échec, erreur ou test ignoré : 446 unitaires/parcours, 87 SQLite et 26 HTTP. Un contrôle séparé avec le modèle local a traité vingt emails synthétiques en vingt brouillons non approuvés, sans erreur ni envoi. Ces résultats et leurs limites sont détaillés dans le [bilan de génération](docs/experiments/I20-generation-truncation.md).

## Préparer le modèle local

Après installation d’Ollama, préparer explicitement le modèle une première fois :

```powershell
./scripts/Start-LocalLlm.ps1 -PullModel
```

Ce script démarre une instance locale sur `127.0.0.1:11435`, désactive les fonctions cloud et télécharge `qwen2.5:0.5b` dans `data/runtime/ollama-models`. Sans `-PullModel`, il démarre uniquement le serveur. Le modèle téléchargé reste disponible entre les sessions ; sa préparation ne doit pas être répétée à chaque lancement.

La GUI démarre ensuite automatiquement le serveur installé si nécessaire, ou réutilise celui qui répond sur l’adresse configurée. Elle attend la confirmation du préchargement avant de programmer les arrivées. Elle n’installe ni ne télécharge automatiquement de modèle. En cas d’échec, un message précise la cause et un bouton permet de réessayer.

Le serveur prêt reste en arrière-plan après fermeture de la fenêtre. Une résidence de trente minutes est demandée après le chargement et renouvelée après chaque génération ; une panne ou une éviction mémoire peut néanmoins interrompre une demande.

## Lancer l’application

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui
```

La fenêtre s’ouvre sur **Messagerie**. Après préparation du modèle, un lot de vingt emails synthétiques arrive dans un ordre mélangé : quatre messages par catégorie. Un délai aléatoire de **1 à 3 minutes** précède chaque arrivée, y compris la première. Les messages et brouillons déjà enregistrés restent accessibles.

À chaque réception, le routeur choisit un spécialiste et ajoute la rédaction à une file. Les arrivées se poursuivent pendant la génération. Le bandeau indique les réceptions, brouillons, erreurs et choix requis. Aucun texte de réponse prédéfini n’est fourni et aucune réponse tronquée n’est présentée comme complète.

| Option graphique | Effet |
| --- | --- |
| `--db chemin.sqlite` | Utiliser une base distincte pour la session |
| `--config fichier.properties` | Choisir la configuration d’inférence initiale |
| `--demo-fast` | Accélérer les arrivées à 1–3 secondes, avec indication visible |
| `--static-demo` | Charger sept exemples immédiats, sans flux temporisé |
| `--fake` | Désactiver la génération et le démarrage d’Ollama ; conserver la réception locale |
| `--gmail-credentials fichier.json` | Activer le connecteur Gmail facultatif |

Exemple de contrôle rapide avec le modèle local, sur une base séparée :

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui --demo-fast --db data/runtime/essai.sqlite
```

Pour consulter uniquement la messagerie de démonstration sans modèle, ajouter `--fake`. Aucune donnée synthétique n’est importée en mode Gmail.

## Relire et valider une réponse

1. Ouvrir **Validations en attente** et sélectionner un brouillon.
2. Lire le message reçu, le spécialiste sélectionné, les destinataires et la proposition.
3. Modifier si nécessaire le sujet, le texte ou les destinataires, puis cliquer sur **Enregistrer la version**.
4. Utiliser **Approuver et simuler l’envoi** en mode local. Cette action conserve le contenu exact dans la boîte simulée ; aucun email ne quitte le poste.

**Rejeter / régénérer** demande une nouvelle version, avec un retour facultatif. **Annuler la génération** interrompt la tentative en cours. Une modification du contenu ou des destinataires invalide l’approbation précédente. Une version ancienne reste consultable mais ne peut pas être approuvée à la place de la version courante.

Les réponses locales figurent dans **Brouillons**, les transmissions simulées dans **Envois simulés** et les transmissions réelles confirmées dans **Envoyés**. Les tentatives en cours, échouées ou incertaines sont séparées. Un résultat `UNKNOWN` signifie que la transmission n’a pas pu être déterminée ; aucune relance automatique n’est effectuée.

Pour un email dont la rédaction a échoué, l’ouvrir dans **Assistant MAS** et demander une nouvelle génération. Les compteurs et l’historique conservent les échecs précédents.

## Dossiers et disposition

La recherche porte sur le sujet, le corps et les adresses du dossier courant. Lecture, favoris et classement sont persistés. Archiver un reçu ou le déplacer vers Spam/Corbeille ne le détruit pas ; **Remettre en réception** le restaure. Un reçu en Spam/Corbeille doit être restauré avant génération ou envoi. Le classement du spam est manuel.

Les colonnes occupent initialement environ 13 % pour les dossiers, 69 % pour la liste et 18 % pour la lecture. Les séparateurs sont ajustables ; leurs proportions sont conservées pendant les redimensionnements de la session. Les largeurs minimales et le défilement préservent l’accès aux contrôles dans une fenêtre réduite.

## Configuration

L’onglet **Configuration** permet de gérer les agents, les textes et autorisations des sources, ainsi que les paramètres du moteur. Les sauvegardes sont versionnées et transactionnelles. Le dernier agent de repli actif ne peut pas être désactivé.

Les fichiers `config/` fournissent les valeurs initiales. Après une sauvegarde dans l’interface, la configuration de la base choisie est prioritaire ; les fichiers ne sont pas réécrits. Les modifications des paramètres du moteur prennent effet après fermeture et relance de l’application.

Configuration d’inférence fournie :

```properties
endpoint=http://127.0.0.1:11435/
model=qwen2.5:0.5b
capacity=8192
outputLimit=1024
temperature=0.2
seed=42
timeoutSeconds=120
framingOverhead=128
```

Chaque agent possède un rôle, un prompt, des catégories et des références de sources autorisées. Les droits de client, projet, visibilité et destinataires sont vérifiés avant la lecture et la sélection des documents. Les sources `INTERNAL_ONLY` et les données d’autres clients sont exclues des réponses destinées à un client.

Les instructions demandent un seul corps d’email court, sans recopier le contexte. L’adaptateur Ollama utilise aussi `repeat_penalty=1.15` et `repeat_last_n=256`. Ces paramètres limitent les répétitions sans garantir la qualité du contenu ni l’arrêt correct de toute génération.

## Persistance et confidentialité

La base par défaut est `data/runtime/paybridge.sqlite`. Une seule session peut ouvrir une même base ; utiliser `--db` pour des essais indépendants. La fermeture arrête le flux et annule les traitements en cours. Les versions, approbations et tentatives sont retrouvées au prochain lancement, sans transmission automatique.

Le schéma SQLite courant est **6**. Les migrations préservent les conversations et l’historique ; les anciens textes fixes simulés non modifiés sont retirés des listes actives et archivés. Les éditions humaines et historiques de transport réel sont conservés.

Les données locales et archives sont **en clair**. Protéger le dossier par les droits du compte et fermer les sessions avant toute copie de sauvegarde. Les modèles, bases, logs et identifiants sont exclus du versionnement. Ne pas placer de données confidentielles dans les exemples ou les bilans partagés.

## Gmail facultatif

Le fonctionnement local ne nécessite aucun compte Google. Pour utiliser Gmail, préparer un projet avec Gmail API et un client OAuth de type **Application de bureau**, puis placer son fichier JSON dans `config/local/gmail-oauth.json`. Le format attendu est illustré par `config/examples/gmail-oauth.example.json`, qui ne contient aucun identifiant utilisable.

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui --gmail-credentials config/local/gmail-oauth.json
```

La connexion passe par le navigateur système ; les jetons restent en mémoire. Le connecteur prévoit réception, classement, export explicite de brouillons et envoi de la version exacte approuvée. Une base distincte est choisie par compte. Les imports sont bornés et actualisés manuellement.

**Avec cette option, les opérations Gmail sont réelles, y compris si `--fake` désactive la rédaction.** L’approbation demande alors un envoi réel. Aucun compte Google ni échange réel n’a encore été validé : le premier contrôle sur compte de test reste nécessaire. Consulter le [guide Gmail](docs/INTERFACE_ET_GMAIL.md) pour la procédure et les limites.

## Commandes de diagnostic

Les commandes suivantes utilisent les données synthétiques fournies et n’effectuent aucun envoi :

```powershell
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --agents
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --route
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --context
& "$env:JAVA_HOME/bin/java.exe" -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --storage-demo
```

Pour générer ou relire en console, démarrer d’abord le moteur avec `./scripts/Start-LocalLlm.ps1`, puis utiliser `--generate` ou `--review`. `--message route-003` sélectionne un exemple et `--choose OnsiteSupportAgent` corrige explicitement le routage. Une relecture durable utilise la base locale par défaut ; `--memory` choisit une session éphémère. La console locale transmet uniquement à la boîte simulée après la commande d’approbation.

## Organisation du projet

| Emplacement | Contenu |
| --- | --- |
| `src/main/java/fr/paybridge/mas/domain` | Modèle métier et invariants |
| `src/main/java/fr/paybridge/mas/application` | Cas d’usage, ports et orchestration des agents |
| `src/main/java/fr/paybridge/mas/adapters` | Interface, configuration, stockage, LLM et connecteurs |
| `src/main/java/fr/paybridge/mas/bootstrap` | Assemblage et points d’entrée |
| `src/main/resources` | Styles de l’interface |
| `src/test/java` | Tests unitaires, parcours et intégrations |
| `config/` | Agents, prompts, politiques de routage et sources synthétiques |
| `data/demo/` | Emails de démonstration |
| `scripts/` | Préparation locale du moteur |
| `docs/experiments/` | Bilans de validation et limites observées |

## Limites

Le petit modèle peut produire des erreurs, des formulations maladroites ou une réponse incomplète. La relecture humaine est indispensable. Le contrôle fonctionnel sur vingt messages ne remplace pas une évaluation qualitative ou une comparaison avec un agent générique.

La recherche de contexte est lexicale, sans base vectorielle. Les pièces jointes, la composition indépendante d’un nouvel email, les labels Gmail personnalisés et la réconciliation automatique des envois incertains ne sont pas implémentés. Le stockage suppose un utilisateur local de confiance.

Le rapport est rédigé ; les noms, numéros étudiants et contributions réelles des membres restent à renseigner avant dépôt. Les expériences comparatives décrites comme protocole proposé ne sont pas encore exécutées. Les mesures du lot synthétique sont fournies dans `docs/report/results.json`, avec leur script de recalcul.
