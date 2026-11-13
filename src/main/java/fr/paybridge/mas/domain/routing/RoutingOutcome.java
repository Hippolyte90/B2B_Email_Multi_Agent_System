package fr.paybridge.mas.domain.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RoutingOutcome(String strategyId, long strategyVersion, Status status,
                             List<RoutingCandidate> candidates, Optional<String> selectedAgentId) {
    public enum Status { SELECTED, AMBIGUOUS, FALLBACK }

    public RoutingOutcome {
        if (strategyId == null || strategyId.isBlank() || strategyVersion < 1) {
            throw new IllegalArgumentException("Identité de stratégie obligatoire");
        }
        Objects.requireNonNull(status);
        selectedAgentId = Objects.requireNonNull(selectedAgentId);
        var copiedCandidates = List.copyOf(candidates);
        candidates = copiedCandidates;
        if (candidates.stream().map(RoutingCandidate::agentId).distinct().count() != candidates.size()
                || (status == Status.AMBIGUOUS) == selectedAgentId.isPresent()
                || (status == Status.AMBIGUOUS && candidates.size() < 2)
                || selectedAgentId.filter(id -> copiedCandidates.stream().noneMatch(c -> c.agentId().equals(id))).isPresent()) {
            throw new IllegalArgumentException("Résultat de routage incohérent");
        }
    }
}
