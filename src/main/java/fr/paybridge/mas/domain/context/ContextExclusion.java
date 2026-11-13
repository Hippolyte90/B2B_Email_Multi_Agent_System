package fr.paybridge.mas.domain.context;

import java.util.Objects;

/** Uniquement des références et motifs, sans le contenu rejeté. */
public record ContextExclusion(String reference, Reason reason) {
    public enum Reason {
        NOT_ASSIGNED_TO_AGENT, INTERNAL_ONLY, WRONG_SCOPE, RECIPIENT_NOT_ALLOWED,
        UNKNOWN_SOURCE, UNREADABLE_SOURCE, NOT_RELEVANT, DUPLICATE, BUDGET,
        HISTORY_NOT_ELIGIBLE, HISTORY_LIMIT
    }

    public ContextExclusion {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Référence d'exclusion obligatoire");
        }
        Objects.requireNonNull(reason);
    }
}
