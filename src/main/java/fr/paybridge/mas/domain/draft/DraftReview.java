package fr.paybridge.mas.domain.draft;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DraftReview(Type type, int version, Instant at, Optional<String> comment, Optional<UUID> reference) {
    public enum Type { GENERATED, EDITED, APPROVED, REJECTED, REGENERATION_REQUESTED,
        REGENERATION_FAILED, REGENERATION_CANCELLED, REGENERATION_SUPERSEDED,
        SEND_REQUESTED, SEND_SIMULATED, SEND_CONFIRMED, SEND_FAILED, SEND_UNKNOWN }

    public DraftReview {
        Objects.requireNonNull(type);
        Objects.requireNonNull(at);
        Objects.requireNonNull(comment);
        Objects.requireNonNull(reference);
        if (version < 1) { throw new IllegalArgumentException("Version de relecture invalide"); }
    }
}
