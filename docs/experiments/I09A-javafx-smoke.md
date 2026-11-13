# I09-A — Parcours graphique de relecture

Vérifications du 13 novembre 2026, Windows, JDK 25.0.2, bytecode Java 21, JavaFX Controls 21.0.13. Les données sont synthétiques ; `--fake` utilise le texte fixe de `DemoLlmGateway`. Aucun modèle réel ni serveur de messagerie n'a été sollicité.

## Validation automatisée

Commande finale : `./mvnw.cmd -o verify`. **370 tests passés**, aucun échec, erreur ou ignoré : 304 unitaires/parcours, 54 SQLite temporaires et 12 HTTP contrôlés. Les 23 nouveaux tests sont dans `ReviewWorkspaceServiceTest` (9) et `ReviewPresenterTest` (14). Rapports Maven dans `target/surefire-reports/` et `target/failsafe-reports/`.

Ils couvrent absence d'action au chargement, messages autorisés, ambiguïté/choix humain, capture non approuvée, édition invalidant l'approbation, révision obsolète, contenu transmis exact, relecture/régénération, rechargement des agents, travail hors présentation, champs invalides, version historique, texte humain conservé sur erreur, annulation avant démarrage et en cours, réponse tardive ignorée, fermeture et erreurs sans détail fournisseur sensible. Ces tests ne démarrent pas JavaFX ; le rendu est vérifié séparément ci-dessous.

## Contrôle réel de la fenêtre

Commande exécutée depuis la racine :

```powershell
& 'C:/Program Files/Java/jdk-25.0.2/bin/java.exe' -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar --gui --fake --db data/runtime/i09-a-gui.sqlite
```

Fenêtre native « PayBridge — Assistant multi-agent » observée et contrôlée via les API du skill computer-use. Le contrôle a porté sur affichage, boutons, champs et changements d'état, sans manipulation directe de leur logique par scripts.

| Parcours | Résultat observé |
| --- | --- |
| S01 commercial | « Offres et tarifs » propose CommercialAgent ; Générer crée une proposition non approuvée |
| Édition | Le sujet devient « Re: Offre relue pour le client A » ; l'approbation se désactive tant que la modification n'est pas enregistrée |
| Validation | Enregistrer crée v2 EDITED ; Approuver et simuler l'envoi conduit à APPROVED/révision 5 |
| Boîte | Une tentative `24e3199a-df3c-49a2-9d3d-46e1ad93b812`, v2, SIMULATED, code LOCAL_OUTBOX_ONLY ; aucune livraison réelle |
| S05 ambigu | « Demande urgente » affiche Choix requis et désactive Générer ; le choix OnsiteSupportAgent doit être confirmé avant génération |
| S09 rejet | Commentaire « Restez en français et qualifiez le lieu sans promettre une intervention. », puis Rejeter / régénérer : v2 GENERATED, révision 4, non approuvée |
| S03 sur site | « Accompagnement dans nos bureaux » propose OnsiteSupportAgent sans choisir arbitrairement un autre rôle |
| Configuration | Rôles, prompts, versions, références et métadonnées visibles ; Recharger les agents réussit en conservant les reçus antérieurs |

L'indicateur de travail et le bouton d'annulation ont été observés pendant les tâches ; les cas d'annulation sont exercés de façon déterministe dans les tests, pas par un essai avec un modèle lent réel.

## Fermeture et reprise

Fermeture par Alt+F4 : arrêt des ressources et processus terminé avec code 0. Relance du JAR reconstruit sur la même base, puis ouverture du message commercial : v2 APPROVED/révision 5, sujet relu conservé, bouton d'approbation désactivé pour la version déjà transmise. Aucun appel modèle ou envoi au chargement.

Une lecture SQLite indépendante confirme après les deux sessions : deux brouillons, trois propositions archivées et une seule entrée de boîte simulée. `route-001` contient deux versions, APPROVED et une tentative ; `route-005` contient deux versions, GENERATED et aucune tentative. Le commentaire utile est présent dans le reçu de la régénération. La réouverture ne crée ni proposition ni enveloppe supplémentaire.

Un commentaire saisi sur le message commercial est effacé à l'ouverture du message d'accompagnement : le champ présente à nouveau son texte indicatif vide. Le commentaire d'un autre message n'est donc pas réutilisé implicitement. Seconde fermeture : code 0.

## Ajustements et limites

Le contrôle initial a révélé des avertissements d'écriture du cache natif par défaut hors du projet. `GuiBootstrap` fixe désormais `javafx.cachedir` sous `data/runtime/javafx-cache`, sauf propriété explicitement fournie. Après reconstruction et relance, `glass.dll` et les autres composants natifs sont présents dans ce dossier ; l'avertissement de création de cache a disparu.

Des avertissements JDK sur accès natif/Unsafe et JavaFX sur le classpath restent présents, sans erreur du parcours observé. Le JAR est compilé avec les composants natifs Windows ; un lancement sur un véritable JDK 21 et sur d'autres OS n'a pas été vérifié. Les traces stdout/stderr de vérification sont sous `data/runtime/i09-a-*`, hors versionnement, et ne constituent pas des résultats de qualité LLM.

La simulation renvoie le même texte technique quelle que soit la catégorie ; elle vérifie le parcours, pas la pertinence commerciale ou métier. I09-A est réalisé. I09 reste en cours : l'écran consulte et recharge la configuration, mais sa création/modification graphique reste I09-B. Les expériences comparatives et le rendu académique restent I10/I11.
