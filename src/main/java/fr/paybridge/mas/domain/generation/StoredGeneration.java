package fr.paybridge.mas.domain.generation;

import java.time.Instant;
import java.util.Objects;

/** Proposition réussie et ses paramètres ; aucune approbation ni livraison n'est implicite. */
public record StoredGeneration(AgentResult result, GenerationSettings settings, Instant completedAt) {
    public StoredGeneration {
        Objects.requireNonNull(result);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(completedAt);
        if (result.status() != AgentResult.Status.COMPLETED || !result.reply().orElseThrow().model().equals(settings.model())) {
            throw new IllegalArgumentException("Seule une génération réussie et corrélée peut être archivée");
        }
    }
}
