# I14 — Fenêtre adaptée à l'écran

13 novembre 2026. Correction demandée après une capture montrant une fenêtre trop haute, dont la barre de titre sortait de l'écran. L'ancienne scène utilisait 1220 × 900 unités logiques et une taille minimale de 1000 × 760, indépendamment de la zone utile et de la mise à l'échelle Windows.

La taille initiale est maintenant calculée depuis `Screen.getPrimary().getVisualBounds()` : au plus 94 % de la largeur et 92 % de la hauteur disponibles, plafonnées à 1220 × 900 pour l'ensemble de la fenêtre. La position est centrée dans cette zone, hors barre des tâches. Les minimums 760 × 480 sont eux-mêmes plafonnés à la taille initiale si l'écran est plus petit. Les unités logiques JavaFX tiennent compte de la mise à l'échelle fournie par le système.

Les deux séparateurs de Messagerie permettent de régler les largeurs des dossiers, de la liste et de la lecture. Les textes et actions passent à la ligne ; le détail de lecture possède un défilement pour garder ses actions accessibles lorsque la hauteur diminue. La liste de validation et l'éditeur tolèrent une largeur inférieure. Les boutons natifs agrandir/restaurer restent disponibles.

`./mvnw.cmd -o verify` : **514 tests réussis**, sans échec, erreur ou ignoré : 417 unitaires/parcours, 80 SQLite et 17 HTTP. Les six nouveaux `WindowPlacementTest` vérifient l'inclusion dans la zone utilisable sur portable, les bornes logiques réduites correspondant à une mise à l'échelle, les coordonnées négatives d'un écran secondaire, les petites zones, le plafonnement sur grand écran et les entrées invalides.

Contrôle Windows du JAR final avec `--gui --fake --static-demo --db data/runtime/i14-layout.sqlite` : barre de titre et boutons visibles au lancement ; agrandissement puis restauration observés ; glissement du séparateur entre liste et lecture observé, avec adaptation des actions. Fermeture normale de la seule fenêtre de test, code 0. La fenêtre utilisateur déjà ouverte a été laissée intacte. Les réglages Windows de résolution/DPI n'ont pas été modifiés : leurs variantes sont contrôlées par la géométrie pure, pas par un changement réel de configuration système.

Les données et la configuration restent inchangées. Une fenêtre déjà ouverte conserve son ancienne version : fermer puis relancer le JAR reconstruit. La position et les largeurs choisies ne sont pas persistées entre lancements dans cet incrément.
