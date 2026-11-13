package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.util.Optional;
import java.util.UUID;

public interface RoutingDecisionRepository {
    void add(RoutingDecision decision);
    Optional<RoutingDecision> find(UUID id);
    void replace(RoutingDecision expected, RoutingDecision updated);
}
