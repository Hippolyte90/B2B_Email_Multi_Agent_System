package fr.paybridge.mas;

import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.conversation.HistoryEvent;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.mail.EmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTest {
    private EmailMessage message(List<EmailAddress> recipients) {
        return new EmailMessage("m1", "t1", new EmailAddress("sender@client.example"), recipients,
                "Sujet", "Corps", OffsetDateTime.parse("2026-11-12T10:00:00+01:00"), ClientScope.client("a"));
    }

    private Conversation conversation() {
        return Conversation.start(message(List.of(new EmailAddress("support@paybridge.example"))));
    }

    @Test
    void snapshotsAndRecipientsCannotBeModifiedExternally() {
        var recipients = new ArrayList<>(List.of(new EmailAddress("support@paybridge.example")));
        var email = message(recipients);
        recipients.clear();
        var original = Conversation.start(email);
        var next = original.record(new HistoryEvent("generated:1", "m1", HistoryEvent.Type.GENERATED,
                Instant.parse("2026-11-12T10:00:00Z"), "draft:1"));
        assertEquals(1, email.recipients().size());
        assertEquals(1, original.history().size());
        assertEquals(2, next.history().size());
        assertThrows(UnsupportedOperationException.class, () -> next.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> next.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> email.recipients().clear());
    }

    @Test
    void historyDistinguishesGeneratedRejectedApprovedSimulatedAndConfirmed() {
        var current = conversation();
        var types = List.of(HistoryEvent.Type.GENERATED, HistoryEvent.Type.REJECTED,
                HistoryEvent.Type.GENERATED, HistoryEvent.Type.EDITED, HistoryEvent.Type.APPROVED,
                HistoryEvent.Type.SEND_SIMULATED, HistoryEvent.Type.SEND_CONFIRMED);
        for (int i = 0; i < types.size(); i++) {
            current = current.record(new HistoryEvent("event:" + i, "m1", types.get(i),
                    Instant.parse("2026-11-12T10:00:00Z").plusSeconds(i), "version:" + i));
        }
        assertEquals(HistoryEvent.Type.RECEIVED, current.history().getFirst().type());
        assertEquals(types, current.history().subList(1, current.history().size()).stream()
                .map(HistoryEvent::type).toList());
        // On teste ici la représentation des événements, pas un mécanisme d'envoi réel.
        assertEquals(1, current.history().stream().filter(e -> e.type() == HistoryEvent.Type.SEND_CONFIRMED).count());
    }

    @Test
    void rejectsEventForUnknownMessage() {
        assertThrows(IllegalArgumentException.class, () -> conversation().record(new HistoryEvent(
                "e1", "other-message", HistoryEvent.Type.GENERATED, Instant.EPOCH, "v1")));
    }

    @Test
    void eventReimportIsIdempotentButConflictingIdIsRejected() {
        var event = new HistoryEvent("e1", "m1", HistoryEvent.Type.GENERATED, Instant.EPOCH, "v1");
        var current = conversation().record(event);
        assertSame(current, current.record(event));
        assertThrows(IllegalArgumentException.class, () -> current.record(new HistoryEvent(
                "e1", "m1", HistoryEvent.Type.REJECTED, Instant.EPOCH, "v1")));
    }

    @Test
    void rejectsEmptyRecipientList() {
        assertThrows(IllegalArgumentException.class, () -> message(List.of()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "bad", "a@b", "a@example..com", "x@example.com\r\nBcc:other@example.com"})
    void rejectsInvalidAddresses(String address) {
        assertThrows(RuntimeException.class, () -> new EmailAddress(address));
    }

    @Test
    void normalizesOnlyDomainPartOfAddress() {
        assertEquals("Alice@example.com", new EmailAddress(" Alice@EXAMPLE.COM ").value());
    }

    @Test
    void rejectsProjectWithoutClient() {
        assertThrows(IllegalArgumentException.class, () -> new ClientScope(Optional.empty(), Optional.of("p1")));
    }
}
