package fr.paybridge.mas.domain.conversation;

import java.time.Instant;
import java.util.Objects;

/** Métadonnées d'historique. N'exécute aucune transition de brouillon ou d'envoi. */
public record HistoryEvent(String id, String messageId, Type type, Instant occurredAt, String reference) {
    public enum Type {
        RECEIVED, GENERATED, EDITED, REJECTED, APPROVED, SEND_PENDING, SEND_SIMULATED, SEND_CONFIRMED, SEND_FAILED, SEND_UNKNOWN
    }

    public HistoryEvent {
        if (id == null || id.isBlank() || messageId == null || messageId.isBlank()
                || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Références d'historique obligatoires");
        }
        Objects.requireNonNull(type, "Type d'événement obligatoire");
        Objects.requireNonNull(occurredAt, "Date d'événement obligatoire");
    }
}
