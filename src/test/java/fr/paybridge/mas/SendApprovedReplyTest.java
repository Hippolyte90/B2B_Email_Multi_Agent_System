package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.mail.SimulatedEmailSender;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SendApprovedReplyTest {
    private Draft approved(DraftTestFixture fixture) {
        var draft = fixture.create();
        return fixture.service.approve(draft.id(), fixture.scope, draft.revision(), draft.current().number());
    }
    private SendApprovedReplyUseCase usecase(DraftTestFixture fixture, EmailSender sender) {
        return new SendApprovedReplyUseCase(fixture.drafts, fixture.conversations, sender,
                new EmailAddress("support@paybridge.example"), fixture.clock, UUID::randomUUID);
    }
    private EmailSender transport(TransportMode mode, Function<ApprovedEmail, DeliveryResult> action) {
        return new EmailSender() {
            public TransportMode mode() { return mode; }
            public DeliveryResult send(ApprovedEmail email) { return action.apply(email); }
        };
    }

    @Test
    void cannotSendUnapprovedOrRejectedDraft() {
        var fixture = new DraftTestFixture();
        var draft = fixture.create();
        var sender = usecase(fixture, transport(TransportMode.SIMULATED, e -> fail("Appel sans approbation")));
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), fixture.scope, draft.revision(), 1));
        var rejected = fixture.reject(draft, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), fixture.scope, rejected.revision(), 1));
        assertTrue(fixture.service.find(draft.id(), fixture.scope).sendAttempts().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "subject", "recipients"})
    void contentChangesInvalidateThePreviousApprovalBeforeTransport(String field) {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var content = draft.current().content();
        var edited = fixture.service.edit(draft.id(), fixture.scope, draft.revision(), 1,
                new DraftContent(field.equals("subject") ? "Objet changé" : content.subject(), field.equals("body") ? "Texte changé" : content.body(),
                        field.equals("recipients") ? List.of(new EmailAddress("other@client-a.example")) : content.recipients()));
        var sender = usecase(fixture, transport(TransportMode.SIMULATED, e -> fail("Appel d'une version non approuvée")));
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), fixture.scope, draft.revision(), 1));
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), fixture.scope, edited.revision(), 2));
    }

    @Test
    void sendsTheExactApprovedSnapshotOnceAndReturnsSameAttemptOnDoubleClick() {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var adapter = new SimulatedEmailSender();
        var sender = usecase(fixture, adapter);
        var result = sender.send(draft.id(), fixture.scope, draft.revision(), 1);
        assertEquals(DeliveryStatus.SIMULATED, result.status());
        assertEquals(draft.approval().orElseThrow(), adapter.entries().getFirst().approval());
        assertEquals(draft.current().content(), adapter.entries().getFirst().approval().content());
        var duplicate = sender.send(draft.id(), fixture.scope, draft.revision(), 1);
        assertEquals(result, duplicate);
        assertEquals(1, adapter.entries().size());
        assertEquals(1, sender.attempts(draft.id(), fixture.scope).size());
        assertThrows(UnsupportedOperationException.class, () -> adapter.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> sender.attempts(draft.id(), fixture.scope).clear());
        var conversation = fixture.conversations.findById("thread").orElseThrow();
        assertTrue(conversation.history().stream().anyMatch(e -> e.type().name().equals("SEND_SIMULATED")));
        assertTrue(conversation.confirmedSent().isEmpty());
        assertTrue(conversation.history().stream().noneMatch(e -> e.type().name().equals("SEND_CONFIRMED")));
    }

    @Test
    void pendingReservationPreventsConcurrentDuplicatesAndEdits() throws Exception {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var sender = usecase(fixture, new EmailSender() {
            public TransportMode mode() { return TransportMode.SIMULATED; }
            public DeliveryResult send(ApprovedEmail email) throws InterruptedException {
                calls.incrementAndGet(); entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) { throw new InterruptedException(); }
                return new DeliveryResult(DeliveryStatus.SIMULATED, Optional.of("local-test"), "LOCAL_OUTBOX_ONLY");
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> sender.send(draft.id(), fixture.scope, draft.revision(), 1));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var pending = sender.send(draft.id(), fixture.scope, draft.revision(), 1);
                assertEquals(DeliveryStatus.PENDING, pending.status());
                var current = fixture.service.find(draft.id(), fixture.scope);
                assertThrows(IllegalArgumentException.class, () -> fixture.service.edit(current.id(), fixture.scope, current.revision(), 1,
                        new DraftContent("Objet", "Changement pendant transport", current.current().content().recipients())));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            assertEquals(DeliveryStatus.SIMULATED, first.get(3, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void failedDeliveryIsNotRetriedAutomaticallyButExplicitRetryIsAllowed() {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var calls = new AtomicInteger();
        var sender = usecase(fixture, transport(TransportMode.SIMULATED, e -> calls.incrementAndGet() == 1
                ? new DeliveryResult(DeliveryStatus.FAILED, Optional.empty(), "REJECTED_BEFORE_DELIVERY")
                : new DeliveryResult(DeliveryStatus.SIMULATED, Optional.of("local-retry"), "LOCAL_OUTBOX_ONLY")));
        var failed = sender.send(draft.id(), fixture.scope, draft.revision(), 1);
        assertEquals(DeliveryStatus.FAILED, failed.status());
        assertEquals(failed, sender.send(draft.id(), fixture.scope, draft.revision(), 1));
        assertEquals(1, calls.get());
        var current = fixture.service.find(draft.id(), fixture.scope);
        var retried = sender.retryFailed(draft.id(), fixture.scope, current.revision(), 1);
        assertEquals(DeliveryStatus.SIMULATED, retried.status());
        assertNotEquals(failed.email().attemptId(), retried.email().attemptId());
        assertEquals(2, calls.get());
        var terminal = fixture.service.find(draft.id(), fixture.scope);
        assertThrows(IllegalArgumentException.class, () -> sender.retryFailed(draft.id(), fixture.scope, terminal.revision(), 1));
    }

    @Test
    void exceptionMeansUnknownAndBlocksRetryOrEditingWithoutLeakingErrorBody() {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var calls = new AtomicInteger();
        var sender = usecase(fixture, new EmailSender() {
            public TransportMode mode() { return TransportMode.REAL; }
            public DeliveryResult send(ApprovedEmail e) throws IOException { calls.incrementAndGet(); throw new IOException("SECRET_RESPONSE_BODY"); }
        });
        var result = sender.send(draft.id(), fixture.scope, draft.revision(), 1);
        assertEquals(DeliveryStatus.UNKNOWN, result.status());
        assertFalse(result.result().orElseThrow().toString().contains("SECRET"));
        var current = fixture.service.find(draft.id(), fixture.scope);
        assertThrows(IllegalArgumentException.class, () -> sender.retryFailed(current.id(), fixture.scope, current.revision(), 1));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.edit(current.id(), fixture.scope, current.revision(), 1,
                new DraftContent("Nouvel objet", "Texte", current.current().content().recipients())));
        assertEquals(result, sender.send(current.id(), fixture.scope, current.revision(), 1));
        assertEquals(1, calls.get());
        assertTrue(fixture.conversations.findById("thread").orElseThrow().confirmedSent().isEmpty());
    }

    @Test
    void nullOrContradictoryTransportResultCannotBecomeConfirmedDelivery() {
        for (boolean nullResult : List.of(true, false)) {
            var fixture = new DraftTestFixture();
            var draft = approved(fixture);
            var sender = usecase(fixture, transport(TransportMode.SIMULATED, e -> nullResult ? null
                    : new DeliveryResult(DeliveryStatus.CONFIRMED, Optional.of("not-a-real-delivery"), "BAD_ADAPTER")));
            assertEquals(DeliveryStatus.UNKNOWN, sender.send(draft.id(), fixture.scope, draft.revision(), 1).status());
            assertTrue(fixture.conversations.findById("thread").orElseThrow().confirmedSent().isEmpty());
        }
    }

    @Test
    void authorizationStalenessAndConfiguredSenderAreCheckedBeforeAnyAttempt() {
        var fixture = new DraftTestFixture();
        var draft = approved(fixture);
        var adapter = new SimulatedEmailSender();
        var sender = usecase(fixture, adapter);
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), ClientScope.client("client-b"), draft.revision(), 1));
        assertThrows(IllegalArgumentException.class, () -> sender.send(draft.id(), fixture.scope, draft.revision() - 1, 1));
        assertThrows(IllegalArgumentException.class, () -> sender.attempts(draft.id(), ClientScope.client("client-b")));
        var wrongSender = new SendApprovedReplyUseCase(fixture.drafts, fixture.conversations, adapter,
                new EmailAddress("wrong@paybridge.example"), fixture.clock, UUID::randomUUID);
        assertThrows(IllegalArgumentException.class, () -> wrongSender.send(draft.id(), fixture.scope, draft.revision(), 1));
        assertTrue(adapter.entries().isEmpty());
        assertTrue(fixture.service.find(draft.id(), fixture.scope).sendAttempts().isEmpty());
    }

    @Test
    void aNewEditedVersionNeedsItsOwnApprovalBeforeItsOwnTransmission() {
        var fixture = new DraftTestFixture();
        var original = approved(fixture);
        var adapter = new SimulatedEmailSender();
        var sender = usecase(fixture, adapter);
        sender.send(original.id(), fixture.scope, original.revision(), 1);
        var sent = fixture.service.find(original.id(), fixture.scope);
        var edited = fixture.service.edit(sent.id(), fixture.scope, sent.revision(), 1,
                new DraftContent("Objet corrigé", "Réponse corrigée", sent.current().content().recipients()));
        assertThrows(IllegalArgumentException.class, () -> sender.send(edited.id(), fixture.scope, edited.revision(), 2));
        var approved = fixture.service.approve(edited.id(), fixture.scope, edited.revision(), 2);
        sender.send(approved.id(), fixture.scope, approved.revision(), 2);
        assertEquals(List.of(1, 2), adapter.entries().stream().map(e -> e.approval().version()).toList());
    }

    @Test
    void malformedAddressesOrDuplicateRecipientsCannotCreateAnEnvelopeToSend() {
        assertThrows(IllegalArgumentException.class, () -> new EmailAddress("alice@example.com\r\nBcc:other@example.com"));
        var alice = new EmailAddress("alice@example.com");
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet", "Texte", List.of(alice, alice)));
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet", "Texte", List.of()));
    }
}
