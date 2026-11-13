package fr.paybridge.mas.application.draft;

import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.generation.AgentResult;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record DraftRegenerationHandle(UUID attemptId, CompletionStage<Outcome> result, Runnable cancel) {
    /** Le résultat du modèle n'est pas nécessairement intégrable à la version courante. */
    public record Outcome(AgentResult generation, boolean newVersionAdded, Optional<Draft> draft) { }
}
