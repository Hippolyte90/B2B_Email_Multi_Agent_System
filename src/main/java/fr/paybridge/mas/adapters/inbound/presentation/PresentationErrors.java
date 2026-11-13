package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.application.workspace.WorkspaceGenerationFailure;
import java.util.concurrent.*;

public final class PresentationErrors {
    private PresentationErrors() { }
    public static String message(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) { error = error.getCause(); }
        if(error instanceof fr.paybridge.mas.application.workspace.MailboxFailure failure){
            return switch(failure.reason()){
                case ACCESS_DENIED -> "Accès à la messagerie refusé. Vérifiez la configuration OAuth et le compte autorisé, puis reconnectez-vous.";
                case RATE_LIMIT -> "Limite de la messagerie atteinte. Attendez avant d'actualiser ; aucun envoi n'est relancé automatiquement.";
                case UNAVAILABLE -> "Opération de messagerie non confirmée. Vérifiez la connexion et actualisez avant de réessayer.";
            };
        }
        if (error instanceof WorkspaceGenerationFailure failure) {
            return switch (failure.code()) {
                case ENGINE_NOT_INSTALLED -> "Ollama n’est pas installé ou son exécutable est introuvable. Installez Ollama, puis réessayez.";
                case ENGINE_START_FAILED -> "Ollama n’a pas pu démarrer. Vérifiez son installation et la disponibilité du port local, puis réessayez.";
                case GENERATION_DISABLED -> "Rédaction désactivée : relancez sans --fake pour utiliser le modèle local. Aucune réponse prédéfinie.";
                case CANCELLED -> "Génération annulée. Aucun nouveau texte n'a été approuvé.";
                case UNAVAILABLE -> "Le moteur local est indisponible. Vérifiez qu'il est démarré.";
                case TIMEOUT -> "Le moteur local n'a pas répondu dans le délai prévu.";
                case STALE_CONTEXT -> "La configuration ou le routage a changé. Actualisez avant de réessayer.";
                case CONTEXT_LIMIT -> "Le contexte dépasse la capacité du modèle.";
                case INCOMPLETE_RESPONSE -> "Le modèle a atteint sa limite avant de terminer la réponse. Aucun texte coupé n’a été conservé ; réessayez une réponse plus courte.";
                case STORAGE_ERROR -> "Enregistrement local impossible. Actualisez avant toute nouvelle action.";
                case MODEL_NOT_LOCAL -> "Le modèle demandé ne satisfait pas le fonctionnement local requis.";
                default -> "Aucune proposition valide. Le brouillon précédent est conservé.";
            };
        }
        if (error instanceof PersistenceFailure failure) {
            return failure.code() == PersistenceFailure.Code.ALREADY_OPEN ? "Cette base est déjà ouverte. Fermez l'autre session."
                    : "Accès au stockage impossible (" + failure.code() + "). Aucune relance d'envoi automatique.";
        }
        if (error instanceof java.io.IOException) { return "Configuration ou fichier illisible. Vérifiez les fichiers locaux."; }
        if (error instanceof IllegalArgumentException) { return "Action refusée : vérifiez les champs, le choix d'agent et la version affichée, puis actualisez."; }
        return "L'action n'a pas pu aboutir. Actualisez pour vérifier l'état enregistré.";
    }
}
