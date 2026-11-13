# I18 — Préchargement avant le flux

Validation du 13 novembre 2026, Windows, JDK 25.0.2, cible Java 21.

## Résultat

La fenêtre apparaît avant le chargement. Avec le LLM local actif, le flux synthétique ne crée aucun minuteur avant une confirmation de préchargement. L’adaptateur vérifie d’abord le modèle et la capacité par `/api/show`, puis le charge par `/api/chat` avec une liste de messages vide. Il ne produit aucune réponse. Les paramètres demandent le contexte actif et une résidence de 30 minutes, renouvelée lors des générations.

Une erreur bloque le flux et affiche un bouton Réessayer le chargement du modèle. La fermeture annule la préparation. Aucun réessai automatique, téléchargement ou démarrage de serveur n’est ajouté. Le mode `--fake`, rédaction désactivée, conserve la réception sans préparation.

## Tests exécutés

`./mvnw.cmd -o verify` : BUILD SUCCESS, **543 tests réussis** sans échec/erreur/ignoré : 430 unitaires/parcours, 87 SQLite et 26 HTTP. Log : `target/i18-verify.log`.

Dix tests `ModelStartupTest` : flux bloqué avant succès, premier minuteur seulement après préparation, absence de réception et génération après échec, retry explicite, clics multiples, fermeture avant et pendant chargement, annulation, résultat tardif ignoré, erreur fournisseur assainie, rejet d’exécuteur et fermeture idempotente. Aucun délai réel nécessaire dans ces tests.

Neuf tests HTTP supplémentaires : sérialisation sans email/prompt, options de contexte et résidence, réponse invalide/incomplète/autre modèle/unload, refus du modèle distant avant chargement, expiration et annulation. Le test de génération existant vérifie aussi le renouvellement de `keep_alive`. Les serveurs HTTP contrôlés et les bases temporaires restent distincts des tests unitaires.

## Contrôle Windows

Le moteur configuré est indisponible sur 127.0.0.1:11435, confirmé par une lecture locale de `/api/tags`. Lancement du JAR reconstruit avec `--gui --demo-fast --db data/runtime/i18-startup-unavailable.sqlite` : fenêtre disponible, bandeau Flux en attente, motif de moteur indisponible et bouton Réessayer. Réception et Validations en attente à zéro, même après une nouvelle tentative et au-delà du délai accéléré maximal de trois secondes.

Lecture SQLite : zéro message, brouillon, résultat de génération et envoi simulé. La fenêtre de test a été fermée ; la fenêtre utilisateur existante est préservée. Aucun email réel, compte Google, modèle téléchargé ou appel de génération sur un vrai modèle.

## Limites

Le succès de préparation est testé avec des doublures et HTTP contrôlé ; aucun vrai préchargement ne peut être revendiqué tant que le moteur est indisponible. Les expériences I10 restent ouvertes. Une panne ou une éviction mémoire ultérieure peut toujours faire échouer une génération. Les anciens emails en échec ne sont pas relancés automatiquement. La résidence demandée n’est pas une garantie de permanence.

Référence : [API officielle Ollama, chargement d’un modèle](https://github.com/ollama/ollama/blob/main/docs/api.md#load-a-model-1).
