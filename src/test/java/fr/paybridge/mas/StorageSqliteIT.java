package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.conversation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageSqliteIT {
    @TempDir Path directory;
    private Path file() { return directory.resolve("test.sqlite"); }
    private final EmailMessage message = new EmailMessage("m", "c", new EmailAddress("alice@client-a.example"),
            List.of(new EmailAddress("support@paybridge.example")), "Sujet synthÃ©tique", "CORPS_A_NE_PAS_JOURNALISER",
            OffsetDateTime.parse("2026-11-13T12:00:00+01:00"), ClientScope.client("client-a"));
    private Conversation conversation() { return Conversation.start(message); }
    private RoutingDecision decision() {
        return new RoutingDecision(UUID.randomUUID(), "m", "c", message.scope(), 1, Instant.EPOCH,
                new RoutingOutcome("rules", 2, RoutingOutcome.Status.SELECTED,
                        List.of(new RoutingCandidate("specialist", 3, List.of("signal"))), Optional.of("specialist")), List.of());
    }

    @Test void initializesAndReopensWithoutLosingMessagesOrTimeZone() {
        try (var store = new SqliteStore(file())) { store.conversations().save(conversation()); assertEquals(6, store.summary().schema()); }
        try (var store = new SqliteStore(file())) {
            assertEquals(message, store.conversations().findMessage("m").orElseThrow());
            assertEquals(conversation().snapshot(), store.conversations().findById("c").orElseThrow().snapshot());
            assertEquals(1, store.summary().auditEvents());
        }
    }

    @Test void sameConversationSaveIsIdempotent() {
        try (var store = new SqliteStore(file())) {
            store.conversations().save(conversation()); store.conversations().save(conversation());
            assertEquals(1, store.summary().auditEvents()); assertEquals(1, store.summary().messages());
        }
    }

    @Test void persistsCorrectionAndInitialDecisionAcrossReopen() {
        var initial = decision();
        var corrected = initial.corrected("other", "Choix humain synthÃ©tique", Instant.EPOCH.plusSeconds(2));
        try (var store = new SqliteStore(file())) {
            store.conversations().save(conversation()); store.decisions().add(initial); store.decisions().replace(initial, corrected);
        }
        try (var store = new SqliteStore(file())) { assertEquals(corrected, store.decisions().find(initial.id()).orElseThrow()); }
    }

    @Test void rollsBackConversationDecisionAndAuditTogether() {
        try (var store = new SqliteStore(file())) {
            assertThrows(IllegalStateException.class, () -> store.inTransaction(() -> {
                store.conversations().save(conversation()); store.decisions().add(decision());
                throw new IllegalStateException("Ã©chec injectÃ©");
            }));
            assertEquals(new SqliteStore.Summary(6, 0, 0, 0, 0), store.summary());
        }
        try (var store = new SqliteStore(file())) { assertTrue(store.conversations().findMessage("m").isEmpty()); }
    }

    @Test void swallowedNestedFailureStillForcesRollback() {
        try (var store = new SqliteStore(file())) {
            var failure = assertThrows(PersistenceFailure.class, () -> store.inTransaction(() -> {
                store.conversations().save(conversation());
                try { store.inTransaction(() -> { throw new IllegalArgumentException("injectÃ©"); }); }
                catch (IllegalArgumentException ignored) { }
                return null;
            }));
            assertEquals(PersistenceFailure.Code.ROLLBACK_ONLY, failure.code());
            assertEquals(0, store.summary().messages());
        }
    }

    @Test void rejectsGlobalMessageCollisionAndKeepsOriginal() {
        try (var store = new SqliteStore(file())) {
            store.conversations().save(conversation());
            var other = new EmailMessage("m", "other", message.sender(), message.recipients(), message.subject(), message.body(), message.receivedAt(), message.scope());
            assertThrows(PersistenceFailure.class, () -> store.conversations().save(Conversation.start(other)));
            assertEquals(1, store.summary().conversations()); assertEquals(1, store.summary().auditEvents());
            assertEquals(message, store.conversations().findMessage("m").orElseThrow());
        }
    }

    @Test void rejectsStaleConversationUpdateFromSecondConnection() {
        try (var first = new SqliteStore(file()); var second = new SqliteStore(file())) {
            first.conversations().save(conversation());
            var stale = second.conversations().findById("c").orElseThrow();
            first.conversations().save(conversation().record(new HistoryEvent("g1", "m", HistoryEvent.Type.GENERATED, Instant.EPOCH, "task1")));
            assertThrows(IllegalArgumentException.class, () -> second.conversations().save(stale.record(new HistoryEvent("g2", "m", HistoryEvent.Type.GENERATED, Instant.EPOCH, "task2"))));
            assertEquals(2, second.conversations().findById("c").orElseThrow().history().size());
        }
    }

    @Test void rejectsStaleCorrectionAndRewrittenIdentity() {
        try (var store = new SqliteStore(file())) {
            store.conversations().save(conversation()); var initial = decision(); store.decisions().add(initial);
            var updated = initial.corrected("other", "Premier choix", Instant.EPOCH);
            store.decisions().replace(initial, updated);
            assertThrows(IllegalArgumentException.class, () -> store.decisions().replace(initial, initial.corrected("third", "Ancien Ã©cran", Instant.EPOCH)));
            var changed = new RoutingDecision(updated.id(), "m", "c", message.scope(), 9, updated.decidedAt(), updated.initialOutcome(), updated.corrected("x", "Choix", Instant.EPOCH).corrections());
            assertThrows(IllegalArgumentException.class, () -> store.decisions().replace(updated, changed));
            assertEquals(updated, store.decisions().find(initial.id()).orElseThrow());
        }
    }

    @Test void decisionMustBelongToStoredMessageAndScope() {
        try (var store = new SqliteStore(file())) {
            assertThrows(IllegalArgumentException.class, () -> store.decisions().add(decision()));
            store.conversations().save(conversation());
            var initial = decision();
            var foreign = new RoutingDecision(initial.id(), "m", "c", ClientScope.client("other"), 1, Instant.EPOCH, initial.initialOutcome(), List.of());
            assertThrows(IllegalArgumentException.class, () -> store.decisions().add(foreign));
            assertEquals(0, store.summary().decisions());
        }
    }

    @Test void rejectsFutureSchemaWithoutResettingIt() throws Exception {
        try (var store = new SqliteStore(file())) { store.conversations().save(conversation()); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=999"); }
        assertEquals(PersistenceFailure.Code.UNSUPPORTED_SCHEMA, assertThrows(PersistenceFailure.class, () -> new SqliteStore(file())).code());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM messages")) {
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test void refusesUnrelatedDatabase() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) { statement.execute("CREATE TABLE unrelated(value TEXT)"); }
        assertEquals(PersistenceFailure.Code.UNSUPPORTED_SCHEMA, assertThrows(PersistenceFailure.class, () -> new SqliteStore(file())).code());
    }

    @Test void corruptPayloadFailsWithoutLeakingContent() throws Exception {
        try (var store = new SqliteStore(file())) { store.conversations().save(conversation()); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.prepareStatement("UPDATE conversations SET payload=?")) {
            statement.setString(1, "CORPS_CONFIDENTIEL_INVALIDE"); statement.executeUpdate();
        }
        try (var store = new SqliteStore(file())) {
            var failure = assertThrows(PersistenceFailure.class, () -> store.conversations().findById("c"));
            assertEquals(PersistenceFailure.Code.CORRUPT_DATA, failure.code());
            assertFalse(failure.toString().contains("CONFIDENTIEL")); assertNull(failure.getCause());
        }
    }

    @Test void auditContainsOnlyMetadata() throws Exception {
        try (var store = new SqliteStore(file())) { store.conversations().save(conversation()); store.decisions().add(decision()); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM audit")) {
            int count = 0;
            while (rows.next()) {
                count++;
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    assertFalse(rows.getString(i).contains(message.body()));
                    assertFalse(rows.getString(i).contains(message.sender().value()));
                }
            }
            assertEquals(2, count);
        }
    }

    @Test void importIsIdempotentAfterRestart() {
        var command = new ImportEmailCommand(message.id(), message.conversationId(), message.sender(), message.recipients(), message.subject(), message.body(), message.receivedAt());
        try (var store = new SqliteStore(file())) {
            assertEquals(ImportEmailUseCase.Result.IMPORTED, new ImportEmailUseCase(store.conversations(), sender -> message.scope(), store).execute(command));
        }
        try (var store = new SqliteStore(file())) {
            assertEquals(ImportEmailUseCase.Result.ALREADY_PRESENT, new ImportEmailUseCase(store.conversations(), sender -> message.scope(), store).execute(command));
            assertEquals(1, store.summary().messages());
        }
    }

    @Test void concurrentImportsThroughSeparateConnectionsDoNotDuplicate() throws Exception {
        var command = new ImportEmailCommand(message.id(), message.conversationId(), message.sender(), message.recipients(), message.subject(), message.body(), message.receivedAt());
        try (var first = new SqliteStore(file()); var second = new SqliteStore(file()); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CountDownLatch(1);
            Callable<ImportEmailUseCase.Result> a = () -> { barrier.await(); return new ImportEmailUseCase(first.conversations(), sender -> message.scope(), first).execute(command); };
            Callable<ImportEmailUseCase.Result> b = () -> { barrier.await(); return new ImportEmailUseCase(second.conversations(), sender -> message.scope(), second).execute(command); };
            var fa = executor.submit(a); var fb = executor.submit(b); barrier.countDown();
            assertEquals(Set.of(ImportEmailUseCase.Result.IMPORTED, ImportEmailUseCase.Result.ALREADY_PRESENT), Set.of(fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS)));
            assertEquals(1, first.summary().messages()); assertEquals(1, first.summary().auditEvents());
        }
    }

    @Test void closedStoreFailsExplicitlyAndDoubleCloseIsSafe() {
        var store = new SqliteStore(file()); store.close(); store.close();
        assertEquals(PersistenceFailure.Code.CLOSED, assertThrows(PersistenceFailure.class, store::summary).code());
    }
}
