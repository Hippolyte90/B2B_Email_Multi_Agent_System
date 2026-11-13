package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.domain.generation.GenerationFailure;

/** Code seul : aucune réponse fournisseur ou donnée de message dans l'erreur de présentation. */
public final class WorkspaceGenerationFailure extends RuntimeException {
    private final GenerationFailure code;
    public WorkspaceGenerationFailure(GenerationFailure code) { super(code.name()); this.code = code; }
    public GenerationFailure code() { return code; }
}
