package fr.paybridge.mas.application.routing;

import fr.paybridge.mas.domain.routing.RoutingOutcome;
import fr.paybridge.mas.domain.routing.RoutingRequest;

public interface RoutingStrategy {
    RoutingOutcome route(RoutingRequest request);
}
