# I13 — Flux d'emails et validations en attente

Vérifications du 13 novembre 2026 sur Windows, JDK 25.0.2, JavaFX 21.0.13 et bytecode cible Java 21. Source des arrivées : corpus synthétique `data/demo/live`, quatre emails pour chacune des cinq catégories métier. Aucun compte réel ni transport externe utilisé.

## Tests exécutés

`./mvnw.cmd -o verify` : **508 tests réussis**, sans échec, erreur ou ignoré : 411 unitaires/parcours et 97 intégrations (80 SQLite, 17 HTTP). Les ajouts sont 14 `LiveInboxFlowTest`, 3 `LiveDemoCatalogTest`, 3 `PendingValidationTest` et 2 `LiveInboxStorageSqliteIT`.

Les tests contrôlent sans attendre réellement les minutes : délai avant première arrivée, borne 60–180 secondes, variabilité, traitement sur worker, rédaction en file, arrivée pendant une génération, erreur d'import/LLM, ambiguïté, arrêt, résultat tardif, absence d'envoi, conservation d'une proposition existante et partage d'une génération active entre demandes manuelle/automatique. Les vingt emails sont routés vers les spécialistes attendus par la configuration réelle, sans étiquette de catégorie imposée au routeur. SQLite conserve vingt réponses non approuvées et les retrouve sans nouvelle génération au redémarrage.

## Parcours visuel accéléré

JAR lancé avec `--gui --fake --demo-fast --db data/runtime/i13-live-gui.sqlite` : cadence explicitement affichée ACCÉLÉRÉE 1–3 secondes. Le modèle est factice, son texte fixe ne démontre pas la qualité des réponses.

1. Le compteur évolue sans intervention : observation à 13 reçus/13 réponses, puis 16/16 et enfin 20/20, sans erreur ni choix requis.
2. Ouvrir directement Validations en attente affiche la liste des réponses, puis sélectionner une réponse commerciale affiche dans ce même onglet l'email d'origine, CommercialAgent, le sujet, les destinataires et le texte proposé. Les actions de relecture et validation sont disponibles sans changer d'onglet.
3. Contrôle SQLite **avant tout clic d'approbation** : 20 messages, 20 brouillons, zéro entrée de boîte simulée.
4. Cliquer explicitement Approuver et simuler l'envoi : le compteur passe à 19, la réponse traitée quitte la liste et le panneau invite à sélectionner la suivante.
5. Fermer normalement : code 0.

## Reprise à cadence normale

Relancer le JAR avec la même base et `--gui --fake`, sans option accélérée. L'interface retrouve 20 reçus, 19 brouillons à valider et 1 envoi simulé. Le nouveau flux indique 0/20 et 1–3 minutes, avec une prochaine arrivée programmée à 19:15:52. La fenêtre a été fermée après 34 secondes, avant cette échéance : code 0, aucun nouveau message arrivé.

Après fermeture, contrôle indépendant : 20 messages, 20 générations, 20 agrégats de brouillon, 19 états non approuvés et exactement 1 transmission simulée issue du clic humain. Aucun traitement supplémentaire ne s'est poursuivi après fermeture. Les bornes de la cadence normale sont vérifiées par faux timer et générateur déterministe ; ce contrôle visuel n'a pas attendu l'ensemble du lot de 20 à cadence normale.

## Limites explicites

Le flux est une démonstration synthétique qui fonctionne sans Google. Il n'injecte pas de faux messages dans Gmail. Chaque ouverture locale crée un nouveau lot, avec identifiants de session distincts, tout en conservant les anciennes données. Les anciens messages interrompus restent consultables pour reprise manuelle ; ils ne sont pas relancés silencieusement.

La génération réelle exige le moteur local configuré. Aucun nouvel essai de modèle réel n'a été effectué à I13. Le mode `--fake` permet de vérifier le parcours avec un texte fixe, sans revendiquer pertinence ou compréhension des emails. Une ambiguïté ou une erreur est signalée, et ne produit pas de faux brouillon réussi. Le flux n'approuve et n'envoie jamais ; l'action humaine existante reste obligatoire.
