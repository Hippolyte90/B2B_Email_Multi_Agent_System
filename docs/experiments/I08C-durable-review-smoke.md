# I08-C — Relecture, boîte simulée et récupération durables

Vérifications du 13 novembre 2026, Windows, JDK 25.0.2, bytecode Java 21, SQLite JDBC 3.51.1.0. Données exclusivement synthétiques, fournisseur `DemoLlmGateway`, aucun modèle réel sollicité ni email livré.

## Vérification automatisée

`./mvnw.cmd -o verify` : **347 tests passés**, aucun échec, erreur ou ignoré. Répartition : 281 unitaires/parcours, 54 SQLite temporaires et 12 HTTP contrôlés. I08-C ajoute 25 unitaires (`DraftSnapshotTest`, `InterruptedWorkTest`) et 19 SQLite (`DraftStorageSqliteIT`). Rapports dans `target/surefire-reports/` et `target/failsafe-reports/`.

Les tests couvrent restauration exacte des états/versions/approbations/transmissions, rejet d'un état approuvé inventé, journal corrélé, tâches et régénérations interrompues, récupération idempotente, conflits de révision, refus sans approbation/hors périmètre, atomicité brouillon+événement+audit, réservation avant transport, résultat terminal en échec après écriture de la boîte, rollback du journal de génération, exclusivité, absence de doublon après reprise et migration 2->3. Les interruptions sont représentées par des états réservés et des fautes injectées ; une coupure électrique physique n'a pas été testée.

## Deux lancements avec une même base

Commande :

```powershell
& 'C:/Program Files/Java/jdk-25.0.2/bin/java.exe' -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --review --fake --db data/runtime/i08-c-smoke.sqlite
```

Premier processus : commandes `objet Objet vérifié après reprise`, `approuver`, `quitter`. Résultat : brouillon `0f086c8e-4558-4282-9c1b-ea6017eedcc4`, version 2, état APPROVED, tentative `b85dde41-d68a-4034-96bb-d22fa8cb6287` SIMULATED, une seule entrée dans `simulated_outbox`. Code de sortie 0.

Second processus : `boite-sortie`, `envoyer`, `historique`, `quitter`. Affichage `REPRISE DU BROUILLON`, aucun nouvel appel modèle. Une lecture indépendante SQLite avant/après confirme un payload de brouillon exactement identique et toujours une entrée en boîte. Code de sortie 0. L'approbation est retrouvée, sans transmission au démarrage ; la demande explicite `envoyer` retrouve la tentative existante.

Traces synthétiques locales, exclues du versionnement : `data/runtime/i08-c-first.txt`, `data/runtime/i08-c-second.txt`.

## Exclusivité entre processus

Un processus de relecture est maintenu ouvert après affichage du brouillon. Une seconde invocation sur la même base est refusée avec ALREADY_OPEN, code 1. La première session termine ensuite normalement avec `quitter`, code 0. Ce contrôle utilise deux processus Java réels, en complément du test de verrou dans une même JVM.

## Rejet et régénération

Base séparée `data/runtime/i08-c-regeneration.sqlite`, même commande de relecture avec `--fake`. Saisie : `rejeter Demandez la version du SDK.`, `attendre`, `approuver`, `quitter`. Résultat : deux versions, aucune réservation de régénération restante, deux tâches COMPLETED dans `generation_work` et une tentative SIMULATED. Code 0. Trace locale : `data/runtime/i08-c-regeneration.txt`.

## Migration d'une base I08-B

Invocation `--generate --fake --db data/runtime/i08-b-smoke.sqlite`, base précédemment créée en schéma 2. Résultat : schéma 3, deux propositions antérieures conservées, affichage REPRISE SQLITE sans nouvelle génération, code 0. Aucun journal RUNNING fictif n'est ajouté aux anciennes propositions.

## Limites

I08 est terminé dans son périmètre local et simulé. Le transport réel n'existe pas ; les branches REAL restent exercées par des doubles de test. UNKNOWN nécessite une vérification humaine et ne dispose pas de commande de réconciliation. La base et les journaux métier sont en clair ; l'audit diagnostic reste limité aux métadonnées. Le verrou est local au poste et ne fournit pas de coordination multi-postes. Aucune garantie de livraison réseau exactement une fois, de résistance à une coupure électrique réelle ou de qualité d'un modèle n'est déduite de ces tests. I09-I11 restent à réaliser.
