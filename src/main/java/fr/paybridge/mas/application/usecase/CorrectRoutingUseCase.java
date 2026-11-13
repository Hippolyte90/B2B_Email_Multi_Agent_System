package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.RoutingDecisionRepository;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class CorrectRoutingUseCase {
    private final AgentRegistry registry;
    private final RoutingDecisionRepository decisions;
    private final Clock clock;

    public CorrectRoutingUseCase(AgentRegistry registry, RoutingDecisionRepository decisions, Clock clock) {
        this.registry = Objects.requireNonNull(registry);
        this.decisions = Objects.requireNonNull(decisions);
        this.clock = Objects.requireNonNull(clock);
    }

    public RoutingDecision execute(UUID id, String agentId, String comment, ClientScope authorizedScope, Set<String> allowedAgentIds) {
        Objects.requireNonNull(authorizedScope);
        var decision = decisions.find(id).filter(d -> d.scope().equals(authorizedScope))
                .orElseThrow(() -> new IllegalArgumentException("Décision absente ou hors périmètre"));
        var snapshot = registry.snapshot();
        if (snapshot.revision() != decision.registryRevision()) {
            throw new IllegalArgumentException("Le registre a changé ; effectuez un nouveau routage");
        }
        if (!allowedAgentIds.contains(agentId) || snapshot.activeAgents().stream().noneMatch(a -> a.id().equals(agentId))) {
            throw new IllegalArgumentException("Agent de correction inactif, absent ou non autorisé");
        }
        var updated = decision.corrected(agentId, comment, clock.instant());
        decisions.replace(decision, updated);
        return updated;
    }
}
