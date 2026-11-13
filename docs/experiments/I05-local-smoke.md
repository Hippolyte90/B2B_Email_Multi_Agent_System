# I05 - Vérification technique de l'inférence locale

Date : 13 novembre 2026. Ces observations sont un test de fonctionnement, pas l'évaluation comparative I10.

## Environnement observé

- Windows, JDK 25.0.2, compilation `release 21`, Maven 3.9.9.
- Mémoire physique observée : 14 870 306 816 octets ; processeur graphique intégré AMD Radeon. Le journal Ollama indique une exécution CPU pour cette instance.
- Ollama 0.40.15, instance dédiée `127.0.0.1:11435`, démarrée avec `OLLAMA_NO_CLOUD=1` et stockage `data/runtime/ollama-models`.
- Modèle local `qwen2.5:0.5b`, 397 821 319 octets selon `/api/tags` ; digest `a8b0c51577010a279d933d14c2a8ab4b268079d44c5c8830c0a93900f1827c67`.
- Capacité maximale annoncée par les métadonnées du modèle : 32768 ; fenêtre demandée par l'application : 8192. Ce nombre ne constitue pas une mesure de qualité.
- Configuration finale : sortie maximale 1024, température 0.2, graine 42, délai global 120 secondes, marge de protocole 128. Le compteur préalable reste une estimation en octets UTF-8 ; les mesures retournées par le moteur sont indiquées séparément.

Le modèle et le moteur ont été consultés via leurs API locales. La description et la licence du modèle sont disponibles sur la [fiche officielle Qwen2.5 0.5B](https://ollama.com/library/qwen2.5:0.5b). L'adaptateur suit les API officielles [chat](https://docs.ollama.com/api/chat) et [informations du modèle](https://docs.ollama.com/api-reference/show-model-details).

## Vérifications effectuées

`./mvnw.cmd -o -Preal-llm verify` exécute les tests unitaires, les scénarios HTTP contrôlés et un test réel opt-in. Les tests ordinaires ne téléchargent pas de modèle et n'appellent pas Ollama. Les tests unitaires n'utilisent aucun réseau ; les tests HTTP utilisent un serveur loopback éphémère.

Les scénarios contrôlés couvrent notamment les erreurs de moteur, les sorties vides/malformées/incomplètes, les redirections, les métadonnées distantes, le dépassement de capacité, le timeout et l'annulation. Un premier échec a révélé qu'une annulation HTTP pouvait être enveloppée en `ExecutionException` ; sa classification a été corrigée sans masquer les erreurs réseau véritables.

Le test réel vérifie une sortie non vide, corrélée à la tâche et identifiée comme non simulée. Il n'évalue pas la vérité des affirmations du modèle ni la pertinence métier.

## Parcours complets observés

| Cas | Résultat technique | Observation qualitative |
| --- | --- | --- |
| Faux LLM explicite | Parcours complet ; `simulated=true`, aucune inférence | Texte fixe servant uniquement à vérifier l'orchestration |
| Premiers essais avec contexte complet | Réponse incomplète refusée ; aucun brouillon valide | La sortie atteignait sa limite ; le code distingue désormais `INCOMPLETE_RESPONSE` |
| Suivi d'historique `route-007`, prompt IntegrationAgent v3 | Réponse complète, 6528 ms, entrée 707 tokens, sortie 53 tokens | Hors sujet : demande une traduction française alors que les messages sont déjà en français |
| Demande directe `route-002`, même configuration finale | Réponse complète, 7000 ms, entrée 674 tokens, sortie 109 tokens | Identifie vaguement le problème de doublon et demande des détails, mais comporte un refus inutile, des répétitions et ne demande pas la version du SDK comme attendu |

Les traces brutes synthétiques sont conservées dans `I05-local-smoke.txt` et `I05-history-limit.txt`. Aucun email réel, secret ou donnée bancaire n'est utilisé. Le mode console affiche la proposition pour relecture et n'envoie rien.

Le prompt technique a été clarifié et versionné à 3 et la réserve de sortie a été augmentée entre les premiers essais et les deux derniers. Plusieurs paramètres ayant changé, ces observations ne permettent pas d'attribuer les différences à une seule cause. La graine et les paramètres ne garantissent pas une reproductibilité bit à bit sur un autre matériel ou une autre version du moteur.

## Limites et suite

Le petit modèle permet de vérifier le traitement exclusivement local avec peu de ressources, mais sa qualité est insuffisante pour une utilisation autonome. Une réponse techniquement complète n'est pas une réponse satisfaisante. La relecture, l'édition et le rejet prévus à I06 sont nécessaires ; l'envoi reste absent jusqu'à I07 et exige ensuite une validation explicite.

I10 devra mesurer sur un corpus séparé routage, pertinence, exactitude, influence des prompts et des contextes, erreurs et limites, puis comparer les configurations définies dans MAS_SPEC.md. Le choix d'un modèle local plus capable et son coût pourront être étudiés sans modifier le framework.
