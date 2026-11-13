# I16 — Réponses sans contenu prédéfini

Vérification du 13 novembre 2026, Windows, JDK 25.0.2, compilation Java 21.

## Changement

Le générateur fixe de production est supprimé. Sans résultat local, l’éditeur reste vide ; `--fake` désactive la rédaction et retourne GENERATION_DISABLED. Au changement de message, l’ancien texte est effacé avant chargement. Le flux d’emails synthétiques I13 demeure disponible. Les sept anciennes fixtures immédiates exigent `--static-demo`.

Le schéma 6 archive puis retire des dépôts actifs les anciennes réponses portant le marqueur de simulation fixe, exclusivement si leurs versions n’ont pas été éditées et si aucun transport réel n’est présent. Les reçus référencés par des brouillons conservés restent actifs. Aucun corps d’email n’est ajouté à l’audit de diagnostic.

## Vérifications exécutées

`./mvnw.cmd -o verify` : **BUILD SUCCESS**, 524 tests, zéro échec/erreur/ignoré : 420 unitaires/parcours, 87 SQLite temporaires et 17 HTTP contrôlés. Trois nouveaux tests unitaires couvrent l’absence de réponse/brouillon et l’effacement du précédent éditeur. Sept nouvelles intégrations couvrent le retrait et l’archivage, les envois simulés, la conservation des éditions humaines, des autres générations et de l’historique réel (transport entièrement en mémoire), les résultats sans brouillon et le rollback complet en cas d’échec. La réouverture est également contrôlée.

## Contrôle Windows

Copie SQLite par backup de la base synthétique `data/runtime/i13-live-gui.sqlite` vers `data/runtime/i16-no-default.sqlite`, sans modifier la base source. JAR reconstruit ouvert avec `--gui --fake --static-demo --db data/runtime/i16-no-default.sqlite`.

- Avant migration : schéma 5, 20 brouillons fixes.
- Après migration : schéma 6, zéro brouillon, zéro résultat de génération actif, zéro envoi simulé ; 41 instantanés archivés (20 brouillons, 20 générations, un envoi simulé).
- Les 20 messages reçus sont conservés ; sept fixtures supplémentaires sont explicitement importées par `--static-demo`, soit 27 reçus.
- L’onglet Validations en attente annonce zéro. Sélection d’un reçu, ouverture de l’assistant : aucun brouillon, sujet/destinataires vides et corps de réponse vide.
- Clic Générer une réponse sous `--fake` : erreur explicite de rédaction désactivée, aucun brouillon ni texte de secours. Vérification par capture et arbre d’accessibilité.
- Fenêtre d’essai fermée normalement (processus terminé avec code zéro). La fenêtre utilisateur déjà ouverte est conservée et utilise encore son ancien code jusqu’à son redémarrage.

## Limites

Aucun nouveau test avec un modèle réel ni avec Gmail. Les arrivées temporisées et réponses locales réelles relèvent des contrôles existants et des expériences I10 encore ouvertes. Les archives restent en clair comme les autres données SQLite ; elles ne sont pas exposées dans les listes de messagerie. Les anciennes traces de démonstration à texte fixe sont conservées comme preuves historiques.
