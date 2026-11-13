# I12-A — Messagerie locale

Contrôle du 13 novembre 2026, Windows, JDK 25.0.2, bytecode Java 21 et JavaFX 21.0.13. Base séparée : `data/runtime/i12-a-gui.sqlite`. Aucun compte Gmail connecté pendant ce contrôle ; génération factice explicite.

`./mvnw.cmd -o verify` : **434 tests réussis**, sans échec, erreur ou ignoré : 348 unitaires/parcours et 86 intégrations (74 SQLite, 12 HTTP). Ajouts : 13 `MailboxServiceTest`, 8 `MailboxPresenterTest` et 7 `MailboxStorageSqliteIT`. Une attente historique restée au schéma 4 a été corrigée ; les cycles suivants passent avec le schéma 5. Les migrations depuis les schémas précédents, les reprises, les conflits entre connexions, le rollback avec audit et la corruption sont vérifiés.

Parcours graphique exécuté avec `--gui --fake --db data/runtime/i12-a-gui.sqlite` :

1. Affichage des dix dossiers et de sept messages reçus synthétiques ; les dossiers sans données affichent zéro.
2. Sélection de Notifications en double : texte affiché, indicateur lu enregistré, aucune génération automatique.
3. Ajouter aux favoris : Favoris passe à 1. Archiver : Réception passe à 6, Archives à 1, Tous les reçus reste à 7.
4. Ouvrir le message dans Archives puis dans l'assistant MAS : IntegrationAgent proposé. Générer explicitement produit une proposition non approuvée.
5. Revenir dans Messagerie puis Brouillons : compteur 1, objet, destinataire et texte de la proposition disponibles ; bouton de reprise dans l'assistant.
6. Fermer normalement, relancer le même JAR avec la même base : Réception 6, Archives 1, Favoris 1 et Brouillons 1. Le brouillon est lisible, avec statut et date localisés. Fermer normalement : code 0.

Contrôle SQLite indépendant après fermeture : sept messages, une génération, un brouillon, zéro envoi simulé. `route-002` possède le classement ARCHIVE, lu et favori, révision 3. Le redémarrage et la consultation n'ont ajouté aucune génération ni transmission.

Les cas Spam/Corbeille/restauration, recherche, confirmation réelle factice, simulation, échec et incertitude sont vérifiés automatiquement. Ils ne sont pas tous revendiqués comme contrôles graphiques. Aucun envoi réel n'a été testé. Le classement reste manuel, sans filtre anti-spam automatique. Cette étape constitue la base locale ; la connexion Gmail est une étape distincte.
