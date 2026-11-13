package fr.paybridge.mas.domain.conversation;

import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.context.MemoryEntry;
import fr.paybridge.mas.domain.delivery.SendAttempt;
import fr.paybridge.mas.domain.delivery.DeliveryStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Agrégat immuable : un identifiant de fil ne peut pas changer de périmètre. */
public final class Conversation {
    private final String id;
    private final ClientScope scope;
    private final List<EmailMessage> messages;
    private final List<HistoryEvent> history;
    private final List<MemoryEntry> confirmedSent;

    private Conversation(String id, ClientScope scope, List<EmailMessage> messages, List<HistoryEvent> history, List<MemoryEntry> confirmedSent) {
        this.id = id;
        this.scope = scope;
        this.messages = List.copyOf(messages);
        this.history = List.copyOf(history);
        this.confirmedSent = List.copyOf(confirmedSent);
    }

    public static Conversation start(EmailMessage message) {
        Objects.requireNonNull(message, "Message obligatoire");
        return new Conversation(message.conversationId(), message.scope(), List.of(), List.of(), List.of()).append(message);
    }

    public Conversation append(EmailMessage message) {
        Objects.requireNonNull(message, "Message obligatoire");
        if (!id.equals(message.conversationId()) || !scope.equals(message.scope())) {
            throw new IllegalArgumentException("Le message ne correspond pas au fil et à son périmètre");
        }
        var existing = messages.stream().filter(m -> m.id().equals(message.id())).findFirst();
        if (existing.isPresent()) {
            if (existing.get().equals(message)) {
                return this;
            }
            throw new IllegalArgumentException("Identifiant message déjà utilisé pour un contenu différent");
        }
        var updated = new ArrayList<>(messages);
        updated.add(message);
        updated.sort(Comparator.comparing((EmailMessage m) -> m.receivedAt().toInstant()).thenComparing(EmailMessage::id));
        return new Conversation(id, scope, updated, history, confirmedSent).record(new HistoryEvent(
                "received:" + message.id(), message.id(), HistoryEvent.Type.RECEIVED,
                message.receivedAt().toInstant(), message.id()));
    }

    public Conversation record(HistoryEvent event) {
        Objects.requireNonNull(event, "Événement obligatoire");
        if (messages.stream().noneMatch(m -> m.id().equals(event.messageId()))) {
            throw new IllegalArgumentException("Message de l'événement absent du fil");
        }
        var existing = history.stream().filter(e -> e.id().equals(event.id())).findFirst();
        if (existing.isPresent()) {
            if (existing.get().equals(event)) {
                return this;
            }
            throw new IllegalArgumentException("Identifiant d'événement déjà utilisé");
        }
        var updated = new ArrayList<>(history);
        updated.add(event);
        updated.sort(Comparator.comparing(HistoryEvent::occurredAt).thenComparing(HistoryEvent::id));
        return new Conversation(id, scope, messages, updated, confirmedSent);
    }

    public Conversation recordDelivery(SendAttempt attempt) {
        var email = attempt.email();
        if (!id.equals(email.conversationId()) || !scope.equals(email.scope())
                || messages.stream().noneMatch(m -> m.id().equals(email.messageId()))) {
            throw new IllegalArgumentException("Tentative d'un autre fil, message ou périmètre");
        }
        var type = switch (attempt.status()) {
            case PENDING -> HistoryEvent.Type.SEND_PENDING;
            case SIMULATED -> HistoryEvent.Type.SEND_SIMULATED;
            case CONFIRMED -> HistoryEvent.Type.SEND_CONFIRMED;
            case FAILED -> HistoryEvent.Type.SEND_FAILED;
            case UNKNOWN -> HistoryEvent.Type.SEND_UNKNOWN;
        };
        var at = attempt.completedAt().orElse(attempt.startedAt());
        var updated = record(new HistoryEvent("send:" + email.attemptId() + ":" + attempt.status(), email.messageId(), type, at, email.attemptId().toString()));
        if (attempt.status() != DeliveryStatus.CONFIRMED) { return updated; }
        var participants = new java.util.HashSet<>(email.approval().content().recipients());
        participants.add(email.sender());
        var memory = new MemoryEntry("sent:" + email.attemptId(), id, scope, MemoryEntry.Kind.SENT_CONFIRMED, at,
                email.approval().content().subject() + "\n" + email.approval().content().body(), participants);
        if (confirmedSent.stream().anyMatch(m -> m.id().equals(memory.id()))) {
            if (confirmedSent.stream().noneMatch(memory::equals)) { throw new IllegalArgumentException("Identifiant de mémoire d'envoi dupliqué"); }
            return updated;
        }
        var sent = new ArrayList<>(confirmedSent); sent.add(memory);
        sent.sort(Comparator.comparing(MemoryEntry::occurredAt).thenComparing(MemoryEntry::id));
        return new Conversation(id, scope, messages, updated.history, sent);
    }

    public String id() { return id; }
    public ClientScope scope() { return scope; }
    public List<EmailMessage> messages() { return messages; }
    public List<HistoryEvent> history() { return history; }
    public List<MemoryEntry> confirmedSent() { return confirmedSent; }

    /** Format métier indépendant du stockage ; la restauration ne contourne pas les invariants. */
    public record Snapshot(String id, ClientScope scope, List<EmailMessage> messages,
                           List<HistoryEvent> history, List<MemoryEntry> confirmedSent) {
        public Snapshot {
            Objects.requireNonNull(scope);
            if (id == null || id.isBlank()) { throw new IllegalArgumentException("Identifiant de fil obligatoire"); }
            messages = List.copyOf(messages);
            history = List.copyOf(history);
            confirmedSent = List.copyOf(confirmedSent);
        }
    }

    public Snapshot snapshot() { return new Snapshot(id, scope, messages, history, confirmedSent); }

    public static Conversation restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot.messages().isEmpty()) { throw new IllegalArgumentException("Fil vide"); }
        var restored = new Conversation(snapshot.id(), snapshot.scope(), List.of(), List.of(), List.of());
        for (var message : snapshot.messages()) { restored = restored.append(message); }
        // Les événements RECEIVED doivent correspondre exactement aux messages restaurés.
        var received = restored.history;
        if (!snapshot.history().stream().filter(e -> e.type() == HistoryEvent.Type.RECEIVED).toList().equals(received)) {
            throw new IllegalArgumentException("Historique de réception incohérent");
        }
        for (var event : snapshot.history()) { restored = restored.record(event); }
        var sent = snapshot.confirmedSent();
        if (sent.stream().map(MemoryEntry::id).distinct().count() != sent.size()) {
            throw new IllegalArgumentException("Mémoire dupliquée");
        }
        for (var entry : sent) {
            if (!entry.conversationId().equals(snapshot.id()) || !entry.scope().equals(snapshot.scope())
                    || entry.kind() != MemoryEntry.Kind.SENT_CONFIRMED
                    || !entry.id().startsWith("sent:")
                    || restored.history.stream().noneMatch(e -> e.type() == HistoryEvent.Type.SEND_CONFIRMED
                        && entry.id().equals("sent:" + e.reference()) && e.occurredAt().equals(entry.occurredAt()))) {
                throw new IllegalArgumentException("Mémoire envoyée incohérente");
            }
        }
        var sortedSent = sent.stream().sorted(Comparator.comparing(MemoryEntry::occurredAt).thenComparing(MemoryEntry::id)).toList();
        restored = new Conversation(snapshot.id(), snapshot.scope(), restored.messages, restored.history, sortedSent);
        if (!restored.snapshot().equals(snapshot)) { throw new IllegalArgumentException("Instantané non canonique"); }
        return restored;
    }
}
