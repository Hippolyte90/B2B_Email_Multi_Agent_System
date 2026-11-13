# I08-A — Reprise SQLite entre deux processus

Vérification effectuée le 13 novembre 2026, sous Windows avec JDK 25.0.2, bytecode Java 21 et pilote SQLite JDBC 3.51.1.0. Données exclusivement synthétiques. Aucun moteur LLM ni transport email sollicité.

## Vérification automatisée

`./mvnw.cmd -o verify` : succès, 261 tests sans échec, erreur ou test ignoré. Répartition : 233 tests unitaires/parcours (8 nouveaux tests de restauration), 12 HTTP contrôlés et 16 tests SQLite temporaires. Rapports Maven : `target/surefire-reports/`, `target/failsafe-reports/`. Le profil modèle réel n'a pas été exécuté.

Les tests SQLite couvrent initialisation/version de schéma, réouverture, fuseau et contenu exacts, corrections, sauvegarde identique sans doublon, rollback des données et de l'audit, échec imbriqué intercepté, collision globale de message, mise à jour obsolète entre deux connexions, identité/périmètre des décisions, base étrangère, payload corrompu sans fuite, audit limité aux métadonnées, import après reprise, imports concurrents et fermeture.

## Vérification du JAR

Commande exécutée deux fois successivement, chaque invocation terminant son processus :

```powershell
& 'C:/Program Files/Java/jdk-25.0.2/bin/java.exe' -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --storage-demo data/runtime/i08-a-smoke.sqlite
```

Premier lancement : `IMPORTED` pour `route-001` à `route-007`, code de sortie 0.

Second lancement : `ALREADY_PRESENT` pour ces 7 identifiants, code de sortie 0.

Les deux lancements donnent les mêmes compteurs : schéma 1, 6 fils, 7 messages, 7 décisions, 14 entrées d'audit. Le deuxième lancement ne crée aucune décision ni entrée d'audit supplémentaire. Le JAR embarqué charge correctement le pilote et relit la base après fermeture. JDK 25 affiche un avertissement d'accès natif pour le pilote, sans échec de l'opération.

## Portée et limites

I08-A est terminé et vérifié ; **I08 reste en cours**. Les instantanés de conversation gardent leurs événements et ne fabriquent pas d'envoi confirmé. Cette démonstration ne crée ni brouillon ni tentative de transmission. Les commandes de relecture/génération restent en mémoire. La persistance du registre, des sources, des générations, des brouillons et des envois, ainsi que la récupération après interruption de ces tâches, reste à réaliser en I08-B/C.

La base locale contient les données en clair et n'est pas un mécanisme de chiffrement. L'audit contient uniquement des métadonnées. Cette vérification ne mesure pas la qualité d'un modèle et ne remplace pas les expériences comparatives I10.
