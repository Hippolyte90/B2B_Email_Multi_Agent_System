package fr.paybridge.mas;

import fr.paybridge.mas.domain.conversation.*;
import fr.paybridge.mas.domain.context.MemoryEntry;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSnapshotTest {
    private final EmailMessage message = new EmailMessage("m", "c", new EmailAddress("alice@client-a.example"),
            List.of(new EmailAddress("support@paybridge.example")), "Sujet", "Contenu synthétique",
            OffsetDateTime.parse("2026-11-13T12:00:00+01:00"), ClientScope.client("client-a"));
    private final Conversation original = Conversation.start(message);

    @Test void restoresExactEventsWithoutChangingStatus() {
        var conversation = original.record(new HistoryEvent("pending", "m", HistoryEvent.Type.SEND_PENDING, Instant.EPOCH, "attempt"));
        var restored = Conversation.restore(conversation.snapshot());
        assertEquals(conversation.snapshot(), restored.snapshot());
        assertTrue(restored.confirmedSent().isEmpty());
        assertFalse(restored.history().stream().anyMatch(event -> event.type() == HistoryEvent.Type.SEND_CONFIRMED));
    }

    @Test void rejectsForeignScope() {
        assertThrows(IllegalArgumentException.class, () -> Conversation.restore(new Conversation.Snapshot("c", ClientScope.client("other"),
                original.messages(), original.history(), List.of())));
    }

    @Test void rejectsMissingReception() {
        assertThrows(IllegalArgumentException.class, () -> Conversation.restore(new Conversation.Snapshot("c", message.scope(), original.messages(), List.of(), List.of())));
    }

    @Test void rejectsUnknownEventMessage() {
        var events = new ArrayList<>(original.history());
        events.add(new HistoryEvent("x", "absent", HistoryEvent.Type.GENERATED, Instant.EPOCH, "task"));
        assertThrows(IllegalArgumentException.class, () -> Conversation.restore(new Conversation.Snapshot("c", message.scope(), original.messages(), events, List.of())));
    }

    @Test void rejectsDuplicateMessagesRatherThanSilentlyDeduplicating() {
        assertThrows(IllegalArgumentException.class, () -> Conversation.restore(new Conversation.Snapshot("c", message.scope(), List.of(message, message), original.history(), List.of())));
    }

    @Test void rejectsInventedSentMemory() {
        var entry = new MemoryEntry("sent:attempt", "c", message.scope(), MemoryEntry.Kind.SENT_CONFIRMED, Instant.EPOCH, "Texte",
                Set.copyOf(message.recipients()));
        assertThrows(IllegalArgumentException.class, () -> Conversation.restore(new Conversation.Snapshot("c", message.scope(), original.messages(), original.history(), List.of(entry))));
    }

    @Test void restoresConfirmedMemoryOnlyWithMatchingConfirmation() {
        var confirmed = original.record(new HistoryEvent("sent", "m", HistoryEvent.Type.SEND_CONFIRMED, Instant.EPOCH, "attempt"));
        var entry = new MemoryEntry("sent:attempt", "c", message.scope(), MemoryEntry.Kind.SENT_CONFIRMED, Instant.EPOCH, "Texte synthétique",
                Set.copyOf(message.recipients()));
        var snapshot = new Conversation.Snapshot("c", message.scope(), confirmed.messages(), confirmed.history(), List.of(entry));
        assertEquals(snapshot, Conversation.restore(snapshot).snapshot());
    }

    @Test void snapshotDefensivelyCopiesLists() {
        var messages = new ArrayList<>(original.messages());
        var snapshot = new Conversation.Snapshot("c", message.scope(), messages, original.history(), List.of());
        messages.clear();
        assertEquals(1, snapshot.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
    }
}
