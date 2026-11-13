package fr.paybridge.mas.domain.routing;

import java.util.List;

public record RoutingPolicy(String id, long version, int threshold, int ambiguityMargin,
                            int historyLimit, List<RoutingRule> rules) {
    public RoutingPolicy {
        if (id == null || id.isBlank() || version < 1 || threshold < 1 || ambiguityMargin < 0 || historyLimit < 0) {
            throw new IllegalArgumentException("Paramètres de routage invalides");
        }
        rules = List.copyOf(rules);
        if (rules.stream().map(RoutingRule::id).distinct().count() != rules.size()) {
            throw new IllegalArgumentException("Identifiant de règle dupliqué");
        }
        long totalWeight = rules.stream().mapToLong(RoutingRule::weight).sum();
        if (totalWeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Somme des poids trop élevée");
        }
    }
}
