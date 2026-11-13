package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.RoutingDecisionRepository;
import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryRoutingDecisionRepository implements RoutingDecisionRepository {
    private final Map<UUID, RoutingDecision> decisions = new HashMap<>();

    @Override
    public synchronized void add(RoutingDecision decision) {
        if (decisions.putIfAbsent(decision.id(), decision) != null) {
            throw new IllegalArgumentException("Identifiant de décision déjà présent");
        }
    }

    @Override
    public synchronized Optional<RoutingDecision> find(UUID id) {
        return Optional.ofNullable(decisions.get(id));
    }

    @Override
    public synchronized void replace(RoutingDecision expected, RoutingDecision updated) {
        if (!expected.id().equals(updated.id()) || !decisions.replace(expected.id(), expected, updated)) {
            throw new IllegalArgumentException("Décision modifiée entre-temps ; rechargez-la");
        }
    }
}
