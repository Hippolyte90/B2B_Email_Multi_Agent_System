package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.RecoverInterruptedWorkUseCase;
import fr.paybridge.mas.domain.conversation.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DraftStorageSqliteIT {
    @TempDir Path directory;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    Path file() { return directory.resolve("review.sqlite"); }
    Draft pendingSend(DurableTestFixture f) {
        var approved = f.approve(f.create());
        var pending = approved.reserveSend(1, UUID.randomUUID(), new EmailAddress("support@paybridge.example"), TransportMode.SIMULATED, false, clock.instant());
        f.store.inTransaction(() -> { f.store.drafts().replace(approved, pending); f.store.conversations().save(f.store.conversations().findById("thread").orElseThrow().recordDelivery(pending.sendAttempts().getLast())); return null; });
        return pending;
    }
    @Test void editedDraftAndExactApprovalSurviveRestart() {
        Draft.Snapshot expected;
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.create();
            draft = f.reviews.edit(draft.id(), f.data.scope, draft.revision(), 1, new DraftContent("Objet corrigé", "Texte corrigé", draft.current().content().recipients()));
            expected = f.approve(draft).snapshot();
        }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); assertEquals(expected, f.current().snapshot()); assertEquals(0, f.calls.get());
            assertEquals(f.current().current().content(), f.current().approval().orElseThrow().content());
        }
    }
    @Test void restoringApprovedDraftDoesNotSendAnything() {
        try (var session = new SqliteSession(file(), clock)) { var f = new DurableTestFixture(session.store()); f.approve(f.create()); }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); assertEquals(Draft.State.APPROVED, f.current().state()); assertTrue(f.current().sendAttempts().isEmpty()); assertTrue(f.store.simulatedSender().entries(f.data.scope).isEmpty());
        }
    }
    @Test void duplicateDraftForMessageIsRefused() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var draft = f.create();
            assertThrows(IllegalArgumentException.class, () -> store.drafts().add(draft));
            var generated = store.generations().latest("message", f.data.scope).orElseThrow().result();
            assertThrows(IllegalArgumentException.class, () -> store.drafts().add(Draft.generated(UUID.randomUUID(), "Autre", generated, clock.instant())));
            assertTrue(store.drafts().find(UUID.randomUUID()).isEmpty());
        }
    }
    @Test void staleRevisionAcrossConnectionsCannotOverwriteApproval() {
        try (var first = new SqliteStore(file()); var second = new SqliteStore(file())) {
            var f = new DurableTestFixture(first); var draft = f.create(); var stale = second.drafts().find(draft.id()).orElseThrow();
            var approved = f.approve(draft);
            assertThrows(IllegalArgumentException.class, () -> second.drafts().replace(stale, stale.reject(1, Optional.empty(), clock.instant())));
            assertEquals(approved.snapshot(), second.drafts().find(draft.id()).orElseThrow().snapshot());
        }
    }
    @Test void draftAndConversationApprovalRollBackTogether() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var draft = f.create(); int count = store.summary().auditEvents();
            var service = f.reviewService(f.failSavingConversation());
            assertThrows(PersistenceFailure.class, () -> service.approve(draft.id(), f.data.scope, draft.revision(), 1));
            assertEquals(draft.snapshot(), f.current().snapshot()); assertEquals(count, store.summary().auditEvents());
            assertFalse(store.conversations().findById("thread").orElseThrow().history().stream().anyMatch(e -> e.type() == HistoryEvent.Type.APPROVED));
        }
    }
    @Test void durableSendIsIdempotentAcrossRestart() {
        UUID attempt;
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.approve(f.create());
            var sent = f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1); attempt = sent.email().attemptId(); assertEquals(DeliveryStatus.SIMULATED, sent.status());
        }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.current(); int count = f.store.summary().auditEvents();
            assertEquals(attempt, f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1).email().attemptId());
            assertEquals(1, f.store.simulatedSender().entries(f.data.scope).size()); assertEquals(count, f.store.summary().auditEvents());
            assertTrue(f.store.conversations().findById("thread").orElseThrow().confirmedSent().isEmpty());
            assertTrue(f.store.simulatedSender().entries(ClientScope.client("other")).isEmpty());
        }
    }
    @Test void reservationFailureDoesNotCallTransport() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var draft = f.approve(f.create()); var calls = new AtomicInteger();
            EmailSender sender = new EmailSender() {
                public TransportMode mode() { return TransportMode.SIMULATED; }
                public DeliveryResult send(ApprovedEmail email) { calls.incrementAndGet(); return store.simulatedSender().send(email); }
            };
            assertThrows(PersistenceFailure.class, () -> f.delivery(f.failSavingConversation(), sender).send(draft.id(), f.data.scope, draft.revision(), 1));
            assertEquals(0, calls.get()); assertTrue(f.current().sendAttempts().isEmpty()); assertTrue(store.simulatedSender().entries(f.data.scope).isEmpty());
        }
    }
    @Test void pendingTransmissionRecoversToUnknownWithoutRetry() {
        UUID attempt;
        try (var store = new SqliteStore(file())) { var f = new DurableTestFixture(store); attempt = pendingSend(f).sendAttempts().getLast().email().attemptId(); }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.current(); var recovered = draft.sendAttempts().getLast();
            assertEquals(1, session.recovered().deliveries()); assertEquals(attempt, recovered.email().attemptId()); assertEquals(DeliveryStatus.UNKNOWN, recovered.status());
            assertEquals("PROCESS_INTERRUPTED", recovered.result().orElseThrow().code()); assertTrue(f.store.simulatedSender().entries(f.data.scope).isEmpty());
            assertEquals(recovered, f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1));
            assertThrows(IllegalArgumentException.class, () -> f.delivery.retryFailed(draft.id(), f.data.scope, draft.revision(), 1));
            assertThrows(IllegalArgumentException.class, () -> f.reviews.edit(draft.id(), f.data.scope, draft.revision(), 1, new DraftContent("Autre", "Autre", draft.current().content().recipients())));
        }
        try (var session = new SqliteSession(file(), clock)) { assertEquals(0, session.recovered().deliveries()); }
    }
    @Test void outboxWrittenBeforeCrashIsNotSentAgain() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var pending = pendingSend(f); var envelope = pending.sendAttempts().getLast().email();
            store.simulatedSender().send(envelope); store.simulatedSender().send(envelope); assertEquals(1, store.simulatedSender().entries(f.data.scope).size());
        }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.current(); assertEquals(DeliveryStatus.UNKNOWN, draft.sendAttempts().getLast().status());
            f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1); assertEquals(1, f.store.simulatedSender().entries(f.data.scope).size());
        }
    }
    @Test void pendingRegenerationAndRunningTaskRecoverWithoutNewVersion() {
        UUID task; DraftVersion rejectedVersion;
        try (var store = new SqliteStore(file())) {
            var initial = new DurableTestFixture(store); var draft = initial.create(); var queue = new ArrayList<Runnable>();
            var f = new DurableTestFixture(store, queue::add, store.journal());
            var handle = f.reviews.rejectAndRegenerate(draft.id(), f.data.scope, draft.revision(), 1, Optional.of("SDK"), f.data.allowed, f.data.budget);
            rejectedVersion = f.current().current(); task = store.journal().running().getFirst().taskId(); assertFalse(handle.result().toCompletableFuture().isDone()); assertEquals(1, queue.size());
        }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); assertEquals(1, session.recovered().generations()); assertEquals(1, session.recovered().regenerations());
            assertEquals(rejectedVersion, f.current().current()); assertEquals(Draft.State.REJECTED, f.current().state()); assertTrue(f.current().pendingRegeneration().isEmpty());
            var record = f.store.journal().find(task, f.data.scope).orElseThrow(); assertEquals(GenerationRecord.State.INTERRUPTED, record.state()); assertTrue(record.result().isEmpty());
            assertTrue(f.store.generations().find(task, f.data.scope).isEmpty());
            var current = f.current(); var regenerated = f.reviews.regenerate(current.id(), f.data.scope, current.revision(), 1, f.data.allowed, f.data.budget).result().toCompletableFuture().join();
            assertTrue(regenerated.newVersionAdded()); assertEquals(2, f.current().versions().size()); assertTrue(f.current().approval().isEmpty());
        }
    }
    @Test void cancelledGenerationIsJournalledWithoutSuccessfulResult() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store, command -> { }, store.journal()); var handle = f.start(); handle.cancel().run();
            assertEquals(GenerationRecord.State.CANCELLED, store.journal().find(handle.taskId(), f.data.scope).orElseThrow().state());
            assertTrue(store.generations().find(handle.taskId(), f.data.scope).isEmpty()); assertEquals(0, f.calls.get());
        }
    }
    @Test void liveExclusiveSessionCannotBeRecoveredByAnotherSession() {
        UUID task;
        try (var first = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(first.store(), command -> { }, first.store().journal()); task = f.start().taskId();
            assertEquals(PersistenceFailure.Code.ALREADY_OPEN, assertThrows(PersistenceFailure.class, () -> new SqliteSession(file(), clock)).code());
            assertEquals(GenerationRecord.State.RUNNING, first.store().journal().find(task, f.data.scope).orElseThrow().state());
        }
        try (var second = new SqliteSession(file(), clock)) { assertEquals(1, second.recovered().generations()); }
    }
    @Test void unapprovedAndForeignScopeCannotSendThroughDurableUseCase() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var draft = f.create();
            assertThrows(IllegalArgumentException.class, () -> f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1));
            var approved = f.approve(draft);
            assertThrows(IllegalArgumentException.class, () -> f.delivery.send(draft.id(), ClientScope.client("other"), approved.revision(), 1));
            assertTrue(store.simulatedSender().entries(f.data.scope).isEmpty());
        }
    }
    @Test void corruptDraftIsRefusedWithoutInventingApprovedState() throws Exception {
        try (var store = new SqliteStore(file())) { var f = new DurableTestFixture(store); f.create(); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) { statement.executeUpdate("UPDATE drafts SET payload=replace(payload,'\"state\":\"GENERATED\"','\"state\":\"APPROVED\"')"); }
        try (var store = new SqliteStore(file())) {
            assertEquals(PersistenceFailure.Code.CORRUPT_DATA, assertThrows(PersistenceFailure.class, () -> store.drafts().findByMessage("message")).code());
        }
    }
    @Test void recoveryFailureRollsBackDraftConversationAndJournal() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var pending = pendingSend(f); var running = GenerationRecord.running(UUID.randomUUID(), store.decisions().find(f.data.routeId).orElseThrow(), f.data.specialist, f.settings, clock.instant()); store.journal().start(running);
            assertThrows(PersistenceFailure.class, () -> new RecoverInterruptedWorkUseCase(store.drafts(), f.failSavingConversation(), store.journal(), store, clock).execute());
            assertEquals(pending.snapshot(), f.current().snapshot()); assertEquals(GenerationRecord.State.RUNNING, store.journal().find(running.taskId(), f.data.scope).orElseThrow().state());
            assertFalse(store.conversations().findById("thread").orElseThrow().history().stream().anyMatch(e -> e.type() == HistoryEvent.Type.SEND_UNKNOWN));
        }
    }
    @Test void migratesVersionTwoWithoutLosingCompletedGeneration() throws Exception {
        UUID task;
        try (var store = new SqliteStore(file())) { var f = new DurableTestFixture(store); task = f.start().result().toCompletableFuture().join().taskId(); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
            for (var table : List.of("retired_demo_content", "mailbox_flags", "configuration_snapshots", "simulated_outbox", "drafts", "generation_work")) { statement.execute("DROP TABLE " + table); }
            statement.execute("PRAGMA user_version=2");
        }
        try (var session = new SqliteSession(file(), clock)) {
            assertEquals(6, session.store().summary().schema()); assertTrue(session.store().generations().find(task, ClientScope.client("client-a")).isPresent());
            assertTrue(session.store().drafts().findByMessage("message").isEmpty()); assertEquals(0, session.recovered().generations());
        }
    }

    @Test void resultCommitFailureLeavesPendingAndRecoveryPreventsDuplicateOutboxEntry() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var approved = f.approve(f.create()); var delegate = store.conversations();
            ConversationRepository failTerminal = new ConversationRepository() {
                public Optional<Conversation> findById(String id) { return delegate.findById(id); }
                public Optional<EmailMessage> findMessage(String id) { return delegate.findMessage(id); }
                public void save(Conversation conversation) {
                    delegate.save(conversation);
                    if (conversation.history().stream().anyMatch(e -> e.type() == HistoryEvent.Type.SEND_SIMULATED)) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
                }
            };
            assertThrows(PersistenceFailure.class, () -> f.delivery(failTerminal, store.simulatedSender()).send(approved.id(), f.data.scope, approved.revision(), 1));
            assertEquals(DeliveryStatus.PENDING, f.current().sendAttempts().getLast().status()); assertEquals(1, store.simulatedSender().entries(f.data.scope).size());
            assertFalse(store.conversations().findById("thread").orElseThrow().history().stream().anyMatch(e -> e.type() == HistoryEvent.Type.SEND_SIMULATED));
        }
        try (var session = new SqliteSession(file(), clock)) {
            var f = new DurableTestFixture(session.store()); var draft = f.current();
            assertEquals(DeliveryStatus.UNKNOWN, f.delivery.send(draft.id(), f.data.scope, draft.revision(), 1).status());
            assertEquals(1, session.store().simulatedSender().entries(f.data.scope).size());
        }
    }
    @Test void journalCommitFailureRollsBackResultEventAndTerminalStatus() {
        try (var store = new SqliteStore(file())) {
            GenerationJournal broken = new GenerationJournal() {
                public void start(GenerationRecord record) { store.journal().start(record); }
                public void finish(UUID id, AgentResult result, Instant at) { store.journal().finish(id, result, at); throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
                public void interrupt(UUID id, Instant at) { store.journal().interrupt(id, at); }
                public Optional<GenerationRecord> find(UUID id, ClientScope scope) { return store.journal().find(id, scope); }
                public List<GenerationRecord> running() { return store.journal().running(); }
            };
            var f = new DurableTestFixture(store, Runnable::run, broken); var handle = f.start(); var result = handle.result().toCompletableFuture().join();
            assertEquals(Optional.of(GenerationFailure.STORAGE_ERROR), result.failure()); assertTrue(result.reply().isEmpty());
            assertEquals(GenerationRecord.State.RUNNING, store.journal().find(handle.taskId(), f.data.scope).orElseThrow().state());
            assertTrue(store.generations().find(handle.taskId(), f.data.scope).isEmpty());
            assertEquals(1, store.conversations().findById("thread").orElseThrow().history().size());
        }
        try (var session = new SqliteSession(file(), clock)) { assertEquals(1, session.recovered().generations()); }
    }
    @Test void simulatedAdapterCannotAcceptEnvelopeWithoutReservation() {
        try (var store = new SqliteStore(file())) {
            var f = new DurableTestFixture(store); var draft = f.approve(f.create());
            var forged = new ApprovedEmail(UUID.randomUUID(), draft.id(), draft.messageId(), draft.conversationId(), draft.scope(), new EmailAddress("support@paybridge.example"), draft.approval().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> store.simulatedSender().send(forged)); assertTrue(store.simulatedSender().entries(f.data.scope).isEmpty());
        }
    }
}
