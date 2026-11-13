package fr.paybridge.mas.domain.routing;

import fr.paybridge.mas.domain.mail.ClientScope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** La décision automatique est conservée telle quelle, avec les corrections successives. */
public record RoutingDecision(UUID id, String messageId, String conversationId, ClientScope scope,
                               long registryRevision, Instant decidedAt, RoutingOutcome initialOutcome,
                               List<Correction> corrections) {
    public record Correction(String agentId, String comment, Instant occurredAt) {
        public Correction {
            if (agentId == null || agentId.isBlank() || comment == null || comment.isBlank()) {
                throw new IllegalArgumentException("Correction et motif obligatoires");
            }
            Objects.requireNonNull(occurredAt);
        }
    }

    public RoutingDecision {
        Objects.requireNonNull(id);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(decidedAt);
        Objects.requireNonNull(initialOutcome);
        corrections = List.copyOf(corrections);
        if (messageId == null || messageId.isBlank() || conversationId == null || conversationId.isBlank() || registryRevision < 1) {
            throw new IllegalArgumentException("Références de décision invalides");
        }
    }

    public Optional<String> effectiveAgentId() {
        return corrections.isEmpty() ? initialOutcome.selectedAgentId() : Optional.of(corrections.getLast().agentId());
    }

    public RoutingDecision corrected(String agentId, String comment, Instant at) {
        var updated = new ArrayList<>(corrections);
        updated.add(new Correction(agentId, comment, at));
        return new RoutingDecision(id, messageId, conversationId, scope, registryRevision, decidedAt, initialOutcome, updated);
    }
}
