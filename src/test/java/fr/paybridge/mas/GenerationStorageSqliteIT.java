package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.*;
import fr.paybridge.mas.domain.context.*;
import fr.paybridge.mas.domain.conversation.HistoryEvent;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationStorageSqliteIT {
    @TempDir Path directory;
    private Path file() { return directory.resolve("generation.sqlite"); }
    private final DraftTestFixture fixture = new DraftTestFixture();
    private final GenerationSettings settings = new GenerationSettings("test-model", 8192, 1024, 0.2, 42, Duration.ofSeconds(5));
    private final ContextSource source = new ContextSource("source", 1, "context/source.md", "Référence synthétique", ClientScope.unknown(), ContextSource.Visibility.PUBLIC_APPROVED, Set.of("Tech"), Set.of());
    private AgentDefinition next(AgentDefinition old, String prompt) {
        return new AgentDefinition(old.id(), old.version() + 1, old.role(), old.categories(), prompt, old.contextSources(), old.active(), old.fallback());
    }
    private void seed(SqliteStore store) {
        store.agents().save(0, fixture.registry.archive());
        store.conversations().save(fixture.conversations.findById("thread").orElseThrow());
        store.decisions().add(fixture.decisions.find(fixture.routeId).orElseThrow());
    }
    private StoredGeneration stored() { return new StoredGeneration(fixture.generate(), settings, fixture.clock.instant()); }
    private void save(SqliteStore store, StoredGeneration generation) {
        store.inTransaction(() -> {
            var conversation = store.conversations().findById("thread").orElseThrow();
            var task = generation.result().taskId();
            store.conversations().save(conversation.record(new HistoryEvent("generated:" + task, "message", HistoryEvent.Type.GENERATED, generation.completedAt(), task.toString())));
            store.generations().add(generation); return null;
        });
    }
    private RoutingAgent router(SqliteStore store, CompletedGenerationRepository completed) {
        var context = new PrepareContextUseCase(fixture.registry, store.conversations(), store.decisions(), new ContextService(List::of, s -> fail("Aucune source"),
                new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12)));
        return new RoutingAgent(fixture.registry, store.conversations(), store.decisions(), context,
                def -> new ConfiguredSpecialistAgent(def, (request, cancellation) -> new GeneratedReply(request.taskId(), "Réponse synthétique privée", settings.model(), true,
                        Duration.ofMillis(17), OptionalLong.of(120), OptionalLong.of(12))), settings, Runnable::run, fixture.clock, UUID::randomUUID, completed, store);
    }
    private AgentResult generate(RoutingAgent router) {
        return router.start(fixture.routeId, fixture.scope, fixture.allowed, Set.of(fixture.alice), Optional.empty(), fixture.budget).result().toCompletableFuture().join();
    }

    @Test void registryAndPromptHistorySurviveRestart() {
        var changed = next(fixture.specialist, "Prompt technique suivant");
        try (var store = new SqliteStore(file())) {
            var registry = new AgentRegistry(List.of(fixture.specialist, fixture.generic), store.agents());
            registry.replaceAll(List.of(changed, fixture.generic));
        }
        try (var store = new SqliteStore(file())) {
            var registry = new AgentRegistry(List.of(changed, fixture.generic), store.agents());
            assertEquals(2, registry.snapshot().revision()); assertEquals(List.of(fixture.specialist, changed), registry.history("Tech"));
        }
    }
    @Test void staleRegistryCannotOverwriteSavedHistory() {
        try (var first = new SqliteStore(file()); var second = new SqliteStore(file())) {
            var a = new AgentRegistry(List.of(fixture.specialist, fixture.generic), first.agents());
            var b = new AgentRegistry(List.of(fixture.specialist, fixture.generic), second.agents());
            a.replaceAll(List.of(next(fixture.specialist, "A"), fixture.generic));
            assertThrows(IllegalArgumentException.class, () -> b.replaceAll(List.of(next(fixture.specialist, "B"), fixture.generic)));
            assertEquals(1, b.snapshot().revision()); assertEquals(a.archive(), second.agents().load().orElseThrow());
        }
    }
    @Test void oldConfigurationCannotRollBackDurableRegistryAtStartup() {
        try (var store = new SqliteStore(file())) {
            var registry = new AgentRegistry(List.of(fixture.specialist, fixture.generic), store.agents());
            registry.replaceAll(List.of(next(fixture.specialist, "Nouveau"), fixture.generic));
        }
        try (var store = new SqliteStore(file())) {
            assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(fixture.specialist, fixture.generic), store.agents()));
            assertEquals(2, store.agents().load().orElseThrow().snapshot().revision());
        }
    }
    @Test void registryAndAuditRollBackTogetherOnFailure() {
        try (var store = new SqliteStore(file())) {
            assertThrows(IllegalStateException.class, () -> store.inTransaction(() -> { store.agents().save(0, fixture.registry.archive()); throw new IllegalStateException("injecté"); }));
            assertTrue(store.agents().load().isEmpty()); assertEquals(0, store.summary().auditEvents());
        }
    }
    @Test void sourceMetadataAndExactTextSurviveRestart() {
        try (var store = new SqliteStore(file())) { store.sources().rememberText(source, "Texte synthétique exact\nÉté"); }
        try (var store = new SqliteStore(file())) {
            assertEquals(source, store.sources().metadata("source", 1).orElseThrow());
            assertEquals("Texte synthétique exact\nÉté", store.sources().text("source", 1).orElseThrow());
            assertTrue(store.sources().text("source", 2).isEmpty());
        }
    }
    @Test void metadataDoesNotReadOrInventText() {
        try (var store = new SqliteStore(file())) {
            store.sources().rememberMetadata(List.of(source));
            assertTrue(store.sources().text(source.id(), source.version()).isEmpty());
            assertEquals(1, store.summary().auditEvents());
        }
    }
    @Test void sameSourceVersionIsImmutableAndIdempotent() {
        try (var store = new SqliteStore(file())) {
            store.sources().rememberText(source, "Texte"); store.sources().rememberText(source, "Texte");
            assertEquals(2, store.summary().auditEvents());
            assertEquals(PersistenceFailure.Code.VERSION_CONFLICT, assertThrows(PersistenceFailure.class, () -> store.sources().rememberText(source, "Autre texte")).code());
            assertEquals("Texte", store.sources().text("source", 1).orElseThrow());
            var changed = new ContextSource(source.id(), 1, source.reference(), "Autre provenance", source.scope(), source.visibility(), source.agentIds(), source.allowedRecipients());
            assertThrows(PersistenceFailure.class, () -> store.sources().rememberMetadata(List.of(changed)));
            assertEquals(source, store.sources().metadata("source", 1).orElseThrow());
        }
    }
    @Test void differentSourceVersionsCoexist() {
        try (var store = new SqliteStore(file())) {
            store.sources().rememberText(source, "Ancien");
            var next = new ContextSource(source.id(), 2, source.reference(), "Version 2", source.scope(), source.visibility(), source.agentIds(), source.allowedRecipients());
            store.sources().rememberText(next, "Nouveau");
            assertEquals("Ancien", store.sources().text("source", 1).orElseThrow()); assertEquals("Nouveau", store.sources().text("source", 2).orElseThrow());
        }
    }
    @Test void catalogBatchConflictDoesNotPersistHalfOfMetadata() {
        try (var store = new SqliteStore(file())) {
            store.sources().rememberMetadata(List.of(source));
            var fresh = new ContextSource("fresh", 1, "fresh.md", "Nouvelle", source.scope(), source.visibility(), source.agentIds(), source.allowedRecipients());
            var conflict = new ContextSource("source", 1, source.reference(), "Modifiée", source.scope(), source.visibility(), source.agentIds(), source.allowedRecipients());
            assertThrows(PersistenceFailure.class, () -> store.sources().rememberMetadata(List.of(fresh, conflict)));
            assertTrue(store.sources().metadata("fresh", 1).isEmpty()); assertEquals(1, store.summary().auditEvents());
        }
    }
    @Test void completedGenerationRetainsReceiptParametersTextAndEmptyTokenMeasurements() {
        var generation = stored();
        try (var store = new SqliteStore(file())) { seed(store); save(store, generation); }
        try (var store = new SqliteStore(file())) {
            assertEquals(generation, store.generations().find(generation.result().taskId(), fixture.scope).orElseThrow());
            assertTrue(store.generations().find(generation.result().taskId(), ClientScope.client("other")).isEmpty());
            assertTrue(store.generations().latest("message", ClientScope.client("other")).isEmpty());
            assertTrue(store.generations().find(UUID.randomUUID(), fixture.scope).isEmpty());
        }
    }
    @Test void coordinatorPublishesOnlyAfterDurableResultAndEventAreWritten() {
        try (var store = new SqliteStore(file())) {
            seed(store); var router = router(store, store.generations());
            var result = generate(router);
            assertEquals(AgentResult.Status.COMPLETED, result.status());
            var persisted = store.generations().find(result.taskId(), fixture.scope).orElseThrow();
            assertEquals(result, persisted.result()); assertEquals(OptionalLong.of(120), persisted.result().reply().orElseThrow().inputTokens());
            assertEquals(Duration.ofMillis(17), persisted.result().reply().orElseThrow().elapsed());
            assertTrue(store.conversations().findById("thread").orElseThrow().history().stream().anyMatch(event -> event.id().equals("generated:" + result.taskId())));
            assertEquals(result, router.latest("message", fixture.scope).orElseThrow());
        }
    }
    @Test void archiveFailureRollsBackSuccessfulEventAndReturnsStorageFailure() {
        try (var store = new SqliteStore(file())) {
            seed(store); var count = store.summary().auditEvents();
            CompletedGenerationRepository broken = new CompletedGenerationRepository() {
                @Override public void add(StoredGeneration generation) { store.generations().add(generation); throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
                @Override public Optional<StoredGeneration> find(UUID id, ClientScope scope) { return store.generations().find(id, scope); }
                @Override public Optional<StoredGeneration> latest(String message, ClientScope scope) { return store.generations().latest(message, scope); }
            };
            var router = router(store, broken); var result = generate(router);
            assertEquals(AgentResult.Status.FAILED, result.status()); assertEquals(Optional.of(GenerationFailure.STORAGE_ERROR), result.failure()); assertTrue(result.reply().isEmpty());
            assertTrue(store.generations().latest("message", fixture.scope).isEmpty());
            assertEquals(1, store.conversations().findById("thread").orElseThrow().history().size()); assertEquals(count, store.summary().auditEvents());
        }
    }
    @Test void sameTaskCannotBeRewrittenAfterRestart() {
        var generation = stored();
        try (var store = new SqliteStore(file())) { seed(store); save(store, generation); }
        try (var store = new SqliteStore(file())) {
            int before = store.summary().auditEvents(); store.generations().add(generation); assertEquals(before, store.summary().auditEvents());
            assertThrows(IllegalArgumentException.class, () -> store.generations().add(new StoredGeneration(generation.result(), settings, generation.completedAt().plusSeconds(1))));
            assertEquals(generation, store.generations().find(generation.result().taskId(), fixture.scope).orElseThrow());
        }
    }
    @Test void latestResultSurvivesRestartAndCanBeReusedWithoutModelCall() {
        StoredGeneration expected;
        try (var store = new SqliteStore(file())) {
            seed(store); var router = router(store, store.generations()); generate(router); var second = generate(router);
            expected = store.generations().find(second.taskId(), fixture.scope).orElseThrow();
        }
        try (var store = new SqliteStore(file())) {
            assertEquals(expected, new FindReusableGenerationUseCase(AgentRegistry.restore(store.agents().load().orElseThrow()), store.decisions(), store.generations())
                    .execute("message", fixture.scope, fixture.allowed, Set.of(fixture.alice), settings, true).orElseThrow());
        }
    }
    @Test void generationCannotBeStoredWithoutItsEventOrDecision() {
        try (var store = new SqliteStore(file())) {
            seed(store); var generation = stored();
            assertThrows(IllegalArgumentException.class, () -> store.generations().add(generation)); assertTrue(store.generations().latest("message", fixture.scope).isEmpty());
        }
    }
    @Test void auditDoesNotExposePromptReplyOrContext() throws Exception {
        try (var store = new SqliteStore(file())) { seed(store); generate(router(store, store.generations())); store.sources().rememberText(source, "TEXTE_CONFIDENTIEL_SYNTHETIQUE"); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM audit")) {
            while (rows.next()) {
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    var value = rows.getString(column);
                    assertFalse(value.contains("Réponse synthétique privée")); assertFalse(value.contains(fixture.specialist.systemPrompt())); assertFalse(value.contains("TEXTE_CONFIDENTIEL"));
                }
            }
        }
    }
    @Test void migratesVersionOneKeepingMessagesAndDecisions() throws Exception {
        try (var store = new SqliteStore(file())) { seed(store); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
            for (var table : List.of("retired_demo_content", "mailbox_flags", "configuration_snapshots", "simulated_outbox", "drafts", "generation_work", "completed_generations", "source_text", "source_metadata", "registry_archive")) { statement.execute("DROP TABLE " + table); }
            statement.execute("PRAGMA user_version=1");
        }
        try (var store = new SqliteStore(file())) {
            assertEquals(6, store.summary().schema()); assertEquals(1, store.summary().messages()); assertEquals(1, store.summary().decisions());
            assertTrue(store.agents().load().isEmpty()); store.agents().save(0, fixture.registry.archive());
            assertTrue(store.decisions().find(fixture.routeId).isPresent());
        }
    }
    @Test void unauthorizedScopeCannotReadOrDecodeCorruptGenerationPayload() throws Exception {
        var generation = stored();
        try (var store = new SqliteStore(file())) { seed(store); save(store, generation); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.prepareStatement("UPDATE completed_generations SET payload=?")) {
            statement.setString(1, "TEXTE_SYNTHETIQUE_INVALIDE"); statement.executeUpdate();
        }
        try (var store = new SqliteStore(file())) {
            assertTrue(store.generations().find(generation.result().taskId(), ClientScope.client("other")).isEmpty());
            var failure = assertThrows(PersistenceFailure.class, () -> store.generations().find(generation.result().taskId(), fixture.scope));
            assertEquals(PersistenceFailure.Code.CORRUPT_DATA, failure.code()); assertFalse(failure.toString().contains("SYNTHETIQUE"));
        }
    }

    @Test void migrationFailureLeavesVersionOneAndExistingDataIntact() throws Exception {
        try (var store = new SqliteStore(file())) { seed(store); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
            for (var table : List.of("retired_demo_content", "mailbox_flags", "configuration_snapshots", "simulated_outbox", "drafts", "generation_work", "completed_generations", "source_text", "source_metadata", "registry_archive")) { statement.execute("DROP TABLE " + table); }
            statement.execute("CREATE TABLE source_metadata(conflict TEXT)"); statement.execute("PRAGMA user_version=1");
        }
        assertThrows(PersistenceFailure.class, () -> new SqliteStore(file()));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file()); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) { assertEquals(1, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT count(*) FROM messages")) { assertEquals(1, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='registry_archive'")) { assertEquals(0, rows.getInt(1)); }
        }
    }
}
