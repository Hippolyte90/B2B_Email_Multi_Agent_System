package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.console.DraftReviewConsole;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.mail.SimulatedEmailSender;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryWorkflowTest {
    @Test
    void consoleApprovalTriggersOnlyASimulationAndRepeatedSendIsIdempotent() throws Exception {
        var fixture = new DraftTestFixture();
        var draft = fixture.create();
        var adapter = new SimulatedEmailSender();
        var sender = new SendApprovedReplyUseCase(fixture.drafts, fixture.conversations, adapter,
                new EmailAddress("support@paybridge.example"), fixture.clock, UUID::randomUUID);
        var output = new StringWriter();
        var commands = new BufferedReader(new StringReader("envoyer\napprouver\nenvoyer\nenvoyer\nversion 1\nenvoyer\nvoir\nboite-sortie\nquitter\n"));
        new DraftReviewConsole(fixture.service, fixture.conversations.findMessage("message").orElseThrow(), draft, fixture.allowed,
                fixture.budget, commands, new PrintWriter(output, true), Optional.of(sender)).run();
        assertEquals(1, adapter.entries().size());
        assertTrue(output.toString().contains("statut=SIMULATED"));
        assertTrue(output.toString().contains("Envoi simulé"));
        assertTrue(output.toString().contains("La version courante et son contenu exact doivent être approuvés"));
        assertTrue(output.toString().contains("Affichez la version courante"));
        assertTrue(fixture.conversations.findById("thread").orElseThrow().confirmedSent().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyConfirmedRealTransportSnapshotsEnterTheNextMessageContext(boolean confirmed) {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var approved = fixture.service.approve(initial.id(), fixture.scope, initial.revision(), 1);
        // Transport factice dans les deux cas : aucun réseau, même avec le mode REAL de test.
        EmailSender adapter = confirmed ? new EmailSender() {
            public TransportMode mode() { return TransportMode.REAL; }
            public DeliveryResult send(ApprovedEmail e) { return new DeliveryResult(DeliveryStatus.CONFIRMED, Optional.of("fake-confirmation"), "TEST_CONFIRMED"); }
        } : new SimulatedEmailSender();
        var sender = new SendApprovedReplyUseCase(fixture.drafts, fixture.conversations, adapter,
                new EmailAddress("support@paybridge.example"), fixture.clock, UUID::randomUUID);
        var sent = sender.send(approved.id(), fixture.scope, approved.revision(), 1);
        var conversation = fixture.conversations.findById("thread").orElseThrow();
        assertEquals(confirmed ? 1 : 0, conversation.confirmedSent().size());
        if (confirmed) {
            assertTrue(conversation.confirmedSent().getFirst().content().contains(approved.current().content().body()));
            assertSame(conversation, conversation.recordDelivery(sent));
        }
        new ImportEmailUseCase(fixture.conversations, new ApprovedSenderDirectory(Map.of(fixture.alice, fixture.scope)))
                .execute(new ImportEmailCommand("followup", "thread", fixture.alice, List.of(new EmailAddress("support@paybridge.example")),
                        "Question suivante", "Voici une nouvelle demande sur le SDK", OffsetDateTime.ofInstant(fixture.clock.instant(), ZoneOffset.UTC)));
        var routeId = UUID.randomUUID();
        fixture.decisions.add(new RoutingDecision(routeId, "followup", "thread", fixture.scope, fixture.registry.snapshot().revision(), fixture.clock.instant(),
                new RoutingOutcome("rules", 1, RoutingOutcome.Status.SELECTED, List.of(new RoutingCandidate("Tech", 3, List.of())), Optional.of("Tech")), List.of()));
        fixture.router.start(routeId, fixture.scope, fixture.allowed, Set.of(fixture.alice), Optional.empty(), fixture.budget)
                .result().toCompletableFuture().join();
        var context = fixture.requests.getLast().context();
        assertEquals(confirmed, context.history().stream().anyMatch(m -> m.kind().name().equals("SENT_CONFIRMED")));
        assertEquals(confirmed, context.userData().contains(approved.current().content().body()));
    }

    @Test
    void localOutboxIsIdempotentAndCannotOverwriteAnEnvelopeWithSameAttemptId() {
        var fixture = new DraftTestFixture();
        var draft = fixture.create();
        var approved = fixture.service.approve(draft.id(), fixture.scope, draft.revision(), 1);
        var adapter = new SimulatedEmailSender();
        var envelope = new ApprovedEmail(UUID.randomUUID(), approved.id(), "message", "thread", fixture.scope,
                new EmailAddress("support@paybridge.example"), approved.approval().orElseThrow());
        var first = adapter.send(envelope);
        assertEquals(first, adapter.send(envelope));
        var changedApproval = new fr.paybridge.mas.domain.draft.DraftApproval(1,
                new fr.paybridge.mas.domain.draft.DraftContent("Autre objet", "Autre texte", envelope.approval().content().recipients()), fixture.clock.instant());
        var changed = new ApprovedEmail(envelope.attemptId(), envelope.draftId(), envelope.messageId(), envelope.conversationId(), envelope.scope(), envelope.sender(), changedApproval);
        assertThrows(IllegalArgumentException.class, () -> adapter.send(changed));
        assertEquals(List.of(envelope), adapter.entries());
    }

    @Test
    void interruptionPreservesUnknownStatusAndRestoresThreadInterruptFlag() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var approved = fixture.service.approve(initial.id(), fixture.scope, initial.revision(), 1);
        var sender = new SendApprovedReplyUseCase(fixture.drafts, fixture.conversations, new EmailSender() {
            public TransportMode mode() { return TransportMode.REAL; }
            public DeliveryResult send(ApprovedEmail e) throws InterruptedException { throw new InterruptedException(); }
        }, new EmailAddress("support@paybridge.example"), fixture.clock, UUID::randomUUID);
        try {
            assertEquals(DeliveryStatus.UNKNOWN, sender.send(approved.id(), fixture.scope, approved.revision(), 1).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
