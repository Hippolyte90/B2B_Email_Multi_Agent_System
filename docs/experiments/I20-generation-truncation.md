# I20 — Réponses interrompues après chargement du modèle

Validation du 13 novembre 2026, Windows, JDK 25.0.2, compilation Java 21, Ollama 0.40.15, modèle local qwen2.5:0.5b déjà installé. Aucun téléchargement ni envoi réel.

## Diagnostic et correction

Le journal de `paybridge.sqlite`, consulté en lecture seule et sans afficher de corps d’email, contient plusieurs échecs INCOMPLETE_RESPONSE après délégation au spécialiste. Le modèle est prêt, mais ne termine pas ces réponses dans la limite de sortie. La fixture synthétique route-003 reproduit INCOMPLETE_RESPONSE avant correction ; ce comportement diffère des pannes UNAVAILABLE précédentes.

Le formateur de contexte demande désormais un seul corps d’email court, cible 120 mots, sans analyse/XML/répétition et avec au plus trois questions. Cette consigne entre dans le calcul de budget. L’adaptateur conserve la limite de sortie configurée et ajoute `repeat_penalty=1.15`, `repeat_last_n=256`. Les réponses avec fin `length` restent refusées. Le code d’erreur assaini est transmis dans les événements du flux et traduit précisément dans l’éditeur et le bandeau.

## Tests automatisés

`./mvnw.cmd -o verify` : BUILD SUCCESS, **559 tests réussis**, zéro échec/erreur/ignoré (446 unitaires/parcours, 87 SQLite, 26 HTTP). Log : `target/i20-verify.log`.

Trois tests supplémentaires : propagation de INCOMPLETE_RESPONSE sans brouillon/envoi, distinction UNAVAILABLE et diagnostic français de troncature. Le contrat HTTP vérifie les paramètres anti-répétition ; les tests existants de contexte vérifient le budget des instructions et le filtrage/échappement des données. Les réponses invalides/tronquées restent couvertes par HTTP contrôlé. Aucune égalité de texte généré n’est utilisée en test unitaire.

## Vérifications réelles

Un premier essai isolé après modification a encore retourné INCOMPLETE_RESPONSE ; les consignes ne garantissent pas l’arrêt du petit modèle. Un nouvel essai route-003, via un relais HTTP de test loopback dédié à cette seule fixture, a terminé avec `done_reason=stop`, 600 tokens en entrée et 336 en sortie, sans brouillon approuvé. Les fichiers de diagnostic synthétiques sont dans `target/i20-*` ; aucun trafic de la fenêtre utilisateur n’a été intercepté. Le relais a été arrêté après cet appel.

Contrôle final sur le JAR reconstruit : `--gui --demo-fast --db data/runtime/i20-live-generation.sqlite`. Le flux synthétique accéléré importe les vingt emails et prépare les réponses en série avec le vrai modèle. Résultat vérifié par SQLite et par le bandeau JavaFX :

| Agent | Résultats COMPLETED |
| --- | ---: |
| AccountAdminAgent | 4 |
| CommercialAgent | 4 |
| OnsiteSupportAgent | 4 |
| GenericAgent | 4 |
| IntegrationAgent | 4 |

Vingt messages, vingt brouillons, vingt générations terminées ; aucune génération FAILED ou RUNNING à la fin. Zéro approbation, tentative d’envoi et entrée en boîte simulée. L’onglet annonce Validations en attente (20) ; le bandeau indique 20/20 reçus, 20 réponses préparées et zéro erreur. La fenêtre de contrôle a été fermée ; les données restent dans la base isolée. La fenêtre et la base utilisateur n’ont pas été modifiées.

## Limites

Le succès de ce lot n’est pas une garantie pour les futurs messages. Le modèle peut ignorer la cible de longueur, produire une formulation maladroite ou des faits insuffisamment fondés ; les réponses restent à relire. L’essai isolé encore interrompu est conservé dans les preuves. Le nombre de mots n’est pas imposé par une troncature artificielle. Aucune relance automatique ou hausse implicite de budget ne masque un échec. Les anciens emails ayant échoué doivent faire l’objet d’une nouvelle génération explicite ; l’historique d’échecs n’est pas falsifié. Les expériences qualitatives I10 restent à effectuer.

Référence des paramètres : [Modelfile officiel Ollama](https://github.com/ollama/ollama/blob/main/docs/modelfile.mdx).
