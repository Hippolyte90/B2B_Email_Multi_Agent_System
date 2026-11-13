package fr.paybridge.mas.domain.generation;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.routing.RoutingDecision;
import java.time.Instant;
import java.util.*;

/** Journal de travail distinct du résultat réussi et du cycle de brouillon. */
public record GenerationRecord(UUID taskId, RoutingDecision routing, AgentDefinition agent, GenerationSettings settings,
                               Instant startedAt, State state, Optional<Instant> finishedAt, Optional<AgentResult> result) {
    public enum State { RUNNING, COMPLETED, FAILED, CANCELLED, SUPERSEDED, INTERRUPTED }
    public GenerationRecord {
        Objects.requireNonNull(taskId); Objects.requireNonNull(routing); Objects.requireNonNull(agent); Objects.requireNonNull(settings);
        Objects.requireNonNull(startedAt); Objects.requireNonNull(state); Objects.requireNonNull(finishedAt); Objects.requireNonNull(result);
        if (!routing.effectiveAgentId().filter(agent.id()::equals).isPresent()
                || (state == State.RUNNING) == finishedAt.isPresent()
                || (state != State.RUNNING && state != State.INTERRUPTED) != result.isPresent()
                || result.filter(r -> !r.taskId().equals(taskId) || !r.agentId().equals(agent.id()) || !r.status().name().equals(state.name())).isPresent()
                || result.flatMap(AgentResult::receipt).filter(r -> !r.routing().equals(routing) || !r.agent().equals(agent)).isPresent()) {
            throw new IllegalArgumentException("Journal de génération incohérent");
        }
    }
    public static GenerationRecord running(UUID id, RoutingDecision routing, AgentDefinition agent, GenerationSettings settings, Instant at) {
        return new GenerationRecord(id, routing, agent, settings, at, State.RUNNING, Optional.empty(), Optional.empty());
    }
    public GenerationRecord finish(AgentResult outcome, Instant at) {
        if (state != State.RUNNING) { throw new IllegalArgumentException("Tâche déjà terminée"); }
        return new GenerationRecord(taskId, routing, agent, settings, startedAt, State.valueOf(outcome.status().name()), Optional.of(at), Optional.of(outcome));
    }
    public GenerationRecord interrupt(Instant at) {
        if (state != State.RUNNING) { throw new IllegalArgumentException("Tâche déjà terminée"); }
        return new GenerationRecord(taskId, routing, agent, settings, startedAt, State.INTERRUPTED, Optional.of(at), Optional.empty());
    }
}
