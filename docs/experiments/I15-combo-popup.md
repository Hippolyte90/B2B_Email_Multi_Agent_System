# I15 — Listes déroulantes lisibles

13 novembre 2026, Windows, JavaFX 21.0.13 et JDK 25.0.2. Le problème a été reproduit dans Configuration / Agents : les cinq options étaient présentes dans le contrôle, mais les règles CSS globales `.list-view` et `.list-cell` rendaient le fond du popup transparent. Les textes se superposaient au formulaire.

Correction : le style transparent des listes est limité aux classes `mail-list` et `pending-list`. Les popups de ComboBox disposent d'un fond blanc, d'une bordure et de couleurs explicites pour les options et leur sélection. Aucun identifiant, prompt ou comportement métier n'est modifié.

`./mvnw.cmd -o verify` : **514 tests réussis**, sans échec, erreur ou ignoré (417 unitaires/parcours, 80 SQLite, 17 HTTP). Le correctif est graphique ; aucune assertion reproduisant simplement le texte CSS n'a été ajoutée. Les tests existants de configuration restent exécutés et le rendu est contrôlé séparément.

Contrôle natif du JAR reconstruit, sur une base de test séparée avec `--gui --fake --static-demo` : ouvrir Configuration / Agents, ouvrir le sélecteur, constater les cinq options sur un fond opaque, sélectionner IntegrationAgent puis vérifier son identifiant, son rôle technique, ses catégories, ses sources et son prompt affichés. La sélection fonctionne sans sauvegarder de modification de configuration. Fermeture de la seule fenêtre de test avec code 0 ; la fenêtre utilisateur existante est préservée.

La feuille de style étant chargée au lancement, fermer puis relancer l'application pour appliquer la correction à une fenêtre déjà ouverte.
