package fr.paybridge.mas.domain.agent;

import java.util.Set;

/** Instantané complet : une tâche future conserve cette version, même après rechargement. */
public record AgentDefinition(String id, long version, String role, Set<String> categories,
                              String systemPrompt, Set<String> contextSources,
                              boolean active, boolean fallback) {
    public AgentDefinition {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Identifiant d'agent invalide");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Version d'agent positive obligatoire");
        }
        if (role == null || role.isBlank() || systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Rôle et prompt non vides obligatoires");
        }
        categories = Set.copyOf(categories);
        contextSources = Set.copyOf(contextSources);
        if (categories.isEmpty() || categories.stream().anyMatch(String::isBlank)
                || contextSources.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Catégories et références de sources invalides");
        }
    }
}
