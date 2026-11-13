package fr.paybridge.mas.domain.generation;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.util.Objects;

public record AgentTask(GenerationRequest request, RoutingDecision routing, AgentDefinition agent) {
    public AgentTask {
        Objects.requireNonNull(request);
        Objects.requireNonNull(routing);
        Objects.requireNonNull(agent);
        if (!agent.active() || !routing.effectiveAgentId().filter(agent.id()::equals).isPresent()) {
            throw new IllegalArgumentException("La tâche ne correspond pas au spécialiste sélectionné");
        }
    }
}
