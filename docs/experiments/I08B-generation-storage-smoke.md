# I08-B — Configurations et propositions durables

Vérifications du 13 novembre 2026, Windows, JDK 25.0.2, bytecode Java 21. Les données sont synthétiques et le fournisseur de génération est `DemoLlmGateway` : texte fixe explicitement simulé, aucun modèle réel appelé, aucun email transmis.

## Tests automatisés

Commande : `./mvnw.cmd -o verify`. Résultat final : **303 tests passés**, aucun échec, erreur ou ignoré ; 256 tests unitaires/parcours, 35 SQLite temporaires et 12 HTTP contrôlés. Rapports Maven dans `target/surefire-reports/` et `target/failsafe-reports/`. Le profil `real-llm` n'a pas été exécuté.

I08-B ajoute 23 tests unitaires (`AgentArchiveTest`, `GenerationArchiveTest`, `SourceArchivingTest`) et 19 intégrations (`GenerationStorageSqliteIT`). Ils couvrent historique du registre, publication refusée si sauvegarde échoue, concurrence, droits et absence de repli vers une archive, version de source immuable, persistance des reçus/paramètres/tokens/durées, reprise en périmètre, refus avant lecture d'un résultat hors périmètre, transaction résultat+événement, audit sans textes, migration conservant les données et rollback d'une migration en échec.

## Deux processus successifs

Commande exécutée deux fois :

```powershell
& 'C:/Program Files/Java/jdk-25.0.2/bin/java.exe' -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --generate --fake --db data/runtime/i08-b-smoke.sqlite
```

Premier lancement : génération simulée, tâche `9fd5dbb0-e00c-4acb-a1db-7d4f46ac20dc`, agent IntegrationAgent. Parcours enregistré : ROUTER_RECEIVED, CONTEXT_PREPARED, SPECIALIST_DELEGATED, LLM_RETURNED, ROUTER_RETURNED. Proposition annoncée comme archivée et non approuvée. Code de sortie 0.

Second lancement : `REPRISE SQLITE`, même tâche et même agent, annonce « aucun nouvel appel LLM ». Même proposition et même trace historique relues. Code de sortie 0. Aucun état d'approbation ou d'envoi n'est créé.

## Nouvelle génération explicite

Même commande avec `--fresh`, exécutée après la vérification finale : nouvelle tâche `3d3cdbb6-2ff2-425c-8c0b-4cb197762fbd`, simulation explicitement annoncée, code de sortie 0. L'ancienne proposition reste archivée.

Lecture indépendante de la base avec `sqlite3` Python en mode lecture seule : schéma 2, deux générations et seulement trois sources avec texte archivé : `integration`, `integration-client-a`, `public-service` (toutes version 1). Les textes du client B et du personnel interne ne figurent pas dans `source_text` pour ce parcours du client A.

## Migration d'une ancienne base réelle de démonstration

Exécution : `--storage-demo data/runtime/i08-a-smoke.sqlite`, base créée en schéma 1 pendant I08-A. Résultat : schéma 2, 7 `ALREADY_PRESENT`, 6 fils, 7 messages, 7 décisions et 14 entrées d'audit. Code de sortie 0. Aucun message, décision ou audit dupliqué.

## Limite explicitement vérifiée

Exécution : `--review --fake --db data/runtime/i08-b-smoke.sqlite`. Refus, code de sortie 1, message indiquant que `--db` en relecture attend I08-C. Aucun cycle de relecture éphémère n'est présenté comme durable.

I08-B est terminé ; I08 reste en cours. Le journal des tâches RUNNING/interrompues, brouillons/validations/commentaires, tentatives et boîte simulée persistée restent I08-C. La base est en clair et relève des droits du poste. Ces tests valident le fonctionnement logiciel, pas la qualité d'un modèle réel.
