package fr.paybridge.mas.domain.context;

import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public record MemoryEntry(String id, String conversationId, ClientScope scope, Kind kind,
                          Instant occurredAt, String content, Set<EmailAddress> shareableWith) {
    public enum Kind { RECEIVED, SENT_CONFIRMED, GENERATED, EDITED, REJECTED, APPROVED, SEND_SIMULATED }

    public MemoryEntry {
        if (id == null || id.isBlank() || conversationId == null || conversationId.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Entrée mémoire invalide");
        }
        Objects.requireNonNull(scope);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(occurredAt);
        shareableWith = Set.copyOf(shareableWith);
    }

    public static MemoryEntry received(EmailMessage message) {
        var participants = new HashSet<>(message.recipients());
        participants.add(message.sender());
        return new MemoryEntry(message.id(), message.conversationId(), message.scope(), Kind.RECEIVED,
                message.receivedAt().toInstant(), message.subject() + "\n" + message.body(), participants);
    }
}
