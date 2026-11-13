package fr.paybridge.mas.domain.routing;

import java.util.List;

public record RoutingRule(String id, String category, Field field, List<String> terms, int weight) {
    public enum Field { SUBJECT, BODY, THREAD, SENDER, CLIENT, PROJECT }

    public RoutingRule {
        if (id == null || id.isBlank() || category == null || category.isBlank() || field == null || weight < 1) {
            throw new IllegalArgumentException("Règle de routage invalide");
        }
        terms = List.copyOf(terms);
        if (terms.isEmpty() || terms.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Termes de règle obligatoires");
        }
    }
}
