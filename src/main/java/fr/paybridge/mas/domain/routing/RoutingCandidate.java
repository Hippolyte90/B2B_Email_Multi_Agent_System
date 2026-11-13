package fr.paybridge.mas.domain.routing;

import java.util.List;

public record RoutingCandidate(String agentId, int score, List<String> reasons) {
    public RoutingCandidate {
        if (agentId == null || agentId.isBlank() || score < 0) {
            throw new IllegalArgumentException("Candidat de routage invalide");
        }
        reasons = List.copyOf(reasons);
    }
}
