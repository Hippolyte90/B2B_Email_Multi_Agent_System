# I17 — Disposition de la messagerie

Contrôle du 13 novembre 2026, Windows, JDK 25.0.2, cible Java 21.

La capture utilisateur demande environ 13 % pour les dossiers, 69 % pour la liste des emails et 18 % pour la lecture. `MailboxPane` utilise désormais un seul SplitPane à trois panneaux, séparateurs à 0,13 et 0,82. Les largeurs minimales priment dans une petite fenêtre. Les proportions souhaitées sont réappliquées lors des changements de largeur ; un filtre de relâchement de souris mémorise le réglage manuel dans la session, avant consommation de l’événement par la skin JavaFX.

## Validation exécutée

- `./mvnw.cmd -o verify` sur la version finale : BUILD SUCCESS, 524 tests réussis, zéro échec/erreur/ignoré (420 unitaires/parcours, 87 SQLite et 17 HTTP). Log : `target/i17-verify.log`.
- Contrôle visuel distinct sur le JAR final, avec `--gui --fake --static-demo --db data/runtime/i17-mailbox-layout.sqlite` : disposition initiale, agrandissement avec liste centrale très large, déplacement du séparateur liste/lecture, puis restauration avec réglage manuel conservé sous contraintes de largeur minimale.
- Fermeture de la fenêtre d’essai uniquement ; fenêtre utilisateur existante conservée. Aucun modèle, compte Google ou envoi utilisé. Base de démonstration séparée.

Aucun nouveau test unitaire de rendu n’est ajouté pour cette modification visuelle ; les tests existants de présentation sont réexécutés. Le contrôle visuel ne constitue pas un test unitaire et ne couvre pas toutes les résolutions/DPI. Les proportions manuelles ne sont pas persistées après fermeture ; un nouveau lancement reprend la disposition demandée.
