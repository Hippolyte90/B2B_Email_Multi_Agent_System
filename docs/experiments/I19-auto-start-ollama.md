# I19 — Démarrage automatique d’Ollama

Vérification du 13 novembre 2026, Windows, JDK 25.0.2, cible Java 21.

## Réalisation

La GUI réutilise le service Ollama configuré ou lance l’exécutable installé avec `serve`, sans shell ni téléchargement. Le serveur est limité au loopback configuré et réutilise `data/runtime/ollama-models`, cloud et debug désactivés. Une attente bornée de 30 secondes précède le préchargement I18. Aucune arrivée n’est programmée avant disponibilité du serveur et chargement du modèle. Les erreurs d’installation et de lancement sont distinctes et permettent de réessayer. Cette fonction s’applique aux modes graphiques avec LLM actif, y compris les fixtures statiques ; `--fake` ne lance aucun serveur.

## Vérifications automatisées

`./mvnw.cmd -o verify` : BUILD SUCCESS, **556 tests réussis**, zéro échec/erreur/ignoré (443 unitaires/parcours, 87 SQLite, 26 HTTP). Log final : `target/i19-verify.log`.

Treize nouveaux tests unitaires, sans Ollama ou processus réel : réutilisation du service disponible, démarrage puis attente avant préchargement, installation absente, processus interrompu, timeout de démarrage avec nettoyage, annulation avant lancement/pendant attente, échec du modèle conservant le serveur prêt, seconde préparation sans second serveur, recherche PATH et installation Windows standard, environnement loopback/modèles/cloud/debug. Les tests I18 conservent les gardes avant flux, la fermeture et les réponses HTTP contrôlées.

## Contrôle réel Windows

Avant modification, aucun processus Ollama et aucun serveur disponible sur 11434 ou 11435 lors du diagnostic. Exécutable installé trouvé dans `AppData/Local/Programs/Ollama/ollama.exe` ; manifeste du modèle qwen2.5:0.5b déjà présent dans le stockage du projet. Aucun script de démarrage exécuté manuellement pendant le contrôle.

Lancement du JAR final avec `--gui --db data/runtime/i19-auto-start.sqlite`. La fenêtre affiche **Modèle local prêt — démarrage du flux d’emails**, zéro erreur, puis une première arrivée programmée à 21:21:14. Le serveur `/api/version` répond avec 0.40.15 et `/api/ps` expose qwen2.5:0.5b chargé : le chargement réel est confirmé séparément de l’affichage.

Fenêtre de contrôle fermée avant la première arrivée. Processus GUI terminé avec code zéro ; base isolée contenant zéro message, brouillon et envoi simulé. La fenêtre utilisateur existante reste ouverte. Le serveur prêt est conservé pour les prochaines sessions, conformément au comportement documenté. Aucun email réel, compte Google ou nouveau modèle utilisé/téléchargé.

## Limites

Ce contrôle prouve le démarrage et le préchargement réels sur ce poste ; il n’évalue pas la qualité des réponses ni les expériences I10. Les erreurs d’installation et de démarrage sont couvertes par doublures déterministes ; aucune panne volontaire du serveur utilisateur n’a été provoquée. La résidence mémoire de 30 minutes reste une demande au moteur, sans garantie contre une panne ou une éviction. Un serveur préexistant n’est jamais arrêté. Les sorties du processus enfant sont ignorées ; les diagnostics utilisateur utilisent des codes assainis.

Référence : [configuration officielle des variables Ollama](https://github.com/ollama/ollama/blob/main/envconfig/config.go).
