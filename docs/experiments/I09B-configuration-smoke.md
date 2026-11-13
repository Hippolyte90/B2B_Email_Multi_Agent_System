# I09-B — Édition et reprise des configurations

Vérification effectuée le 13 novembre 2026 sur Windows, JDK 25.0.2, cible Java 21 et JavaFX Controls 21.0.13. Données exclusivement synthétiques. Les parcours décrits utilisent `--fake` : aucun modèle appelé et aucun email réel envoyé.

## Vérifications automatisées

Commande : `./mvnw.cmd -o verify`, avec `JAVA_HOME` pointant vers le JDK et `MAVEN_USER_HOME` vers `.m2-local`.

Résultat du dernier cycle : **406 tests réussis**, sans échec, erreur ou test ignoré : 327 unitaires/parcours et 79 intégrations (67 SQLite, 12 HTTP). Le résumé Failsafe compte les tests du cycle courant ; un ancien XML du profil modèle réel peut rester dans le répertoire de rapports et ne fait pas partie de ce bilan.

Ajouts : 15 tests `ConfigurationEditingTest`, 8 `ConfigurationInputsTest` et 13 `ConfigurationStorageSqliteIT`. Ils couvrent validation des formulaires, versions, protection du dernier repli, références, sauvegarde concurrente obsolète, rollback configuration/registre/audit, reprise, historique, migration 3 vers 4 et données corrompues. Les intégrations vérifient aussi qu'un nouveau prompt est utilisé par la génération suivante, qu'une source d'un autre client est exclue avant lecture et que les paramètres des générations antérieures restent conservés.

## Parcours graphique exécuté

Lancement du JAR construit avec `--gui --fake --db data/runtime/i09-b-gui.sqlite`. Base de contrôle distincte de la base par défaut.

1. Dans Configuration / Agents, modifier le prompt administratif puis enregistrer. Confirmation affichée, définition versionnée et révision de configuration 1.
2. Créer `PartnerSupportAgent`, rôle Assistance aux partenaires, catégorie `general`, prompt de clarification et aucune source. La liste comporte six agents ; le nouvel agent reste sélectionné. Révision 2.
3. Dans Sources, sélectionner `administration` et modifier seulement son texte public : ajouter la demande d'une référence de dossier. Enregistrer produit la version 2 du document et la révision 3. Les permissions et portées de sources n'ont pas été modifiées lors du contrôle graphique ; leurs validations sont couvertes automatiquement.
4. Dans Moteur local, enregistrer une limite de sortie de 768 au lieu de 1024. Révision 4. La ligne En service reste à 1024 dans cette session et la notice annonce la prise d'effet au prochain lancement.
5. Fermer puis relancer avec la même base. L'écran reprend la révision 4 et le prompt administratif modifié. Moteur local affiche maintenant **En service : qwen2.5:0.5b · capacité 8192 · sortie 768**.
6. Fermer la fenêtre. Lancer ensuite le même JAR avec `--generate --fake --db data/runtime/i09-b-gui.sqlite`. La commande réussit et archive une proposition non approuvée pour IntegrationAgent ; aucun email envoyé.

Contrôle indépendant SQLite : schéma 4, quatre instantanés de configuration, six agents dont PartnerSupportAgent, limite sauvegardée 768. Le texte édité est conservé comme surcharge ; les versions finales sont AccountAdminAgent 4, CommercialAgent 3, GenericAgent 3, IntegrationAgent 5, OnsiteSupportAgent 3 et PartnerSupportAgent 3. Les changements de sources et de paramètres font progresser les définitions pour invalider les préparations antérieures.

Les sauvegardes de configuration seules ont laissé zéro génération et zéro entrée de boîte simulée. Après la commande de génération explicite, la base contient une génération avec `settings.outputLimit=768` et toujours zéro entrée de boîte simulée. Identifiant de tâche : `5e76885e-100c-4af2-bd40-f93576f5e1d7`.

## Limites et état

I09 est terminé dans le périmètre local prévu. Les fichiers initiaux de `config/` ne sont pas réécrits par les formulaires ; les changements appartiennent à la base sélectionnée. La configuration sauvegardée devient prioritaire au redémarrage. Les agents et sources s'appliquent après sauvegarde ; les paramètres du moteur exigent un redémarrage.

La base conserve les textes en clair et suppose un opérateur local de confiance. Le JAR embarque les bibliothèques natives Windows ; les autres systèmes ne sont pas vérifiés. Les avertissements JavaFX liés au classpath, à l'accès natif et à Unsafe restent présents avec ce JDK, sans empêcher les parcours observés. Aucun nouvel essai de modèle réel ni SMTP n'a été effectué. La qualité des réponses et les comparaisons restent à traiter à I10 ; rapport, contributions réelles et livraison restent I11.
