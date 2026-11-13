package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.domain.generation.AgentTask;
import fr.paybridge.mas.domain.generation.GeneratedReply;

public interface SpecialistAgent {
    GeneratedReply handle(AgentTask task, CancellationSignal cancellation) throws GenerationException;
}
