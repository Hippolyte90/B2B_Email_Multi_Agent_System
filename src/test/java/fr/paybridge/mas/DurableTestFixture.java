package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.application.draft.DraftReviewService;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.PrepareContextUseCase;
import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.EmailAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/** Assemblage réel du stockage avec horloge et génération déterministes, sans réseau. */
final class DurableTestFixture {
    final DraftTestFixture data = new DraftTestFixture();
    final SqliteStore store;
    final AgentRegistry registry;
    final GenerationSettings settings = new GenerationSettings("test-model", 8192, 1024, .2, 42, Duration.ofSeconds(5));
    final AtomicInteger calls = new AtomicInteger();
    final RoutingAgent router;
    final DraftReviewService reviews;
    final SendApprovedReplyUseCase delivery;
    DurableTestFixture(SqliteStore store) { this(store, Runnable::run, store.journal()); }
    DurableTestFixture(SqliteStore store, String replyText) { this(store, Runnable::run, store.journal(), replyText); }
    DurableTestFixture(SqliteStore store, Executor executor, GenerationJournal journal) { this(store, executor, journal, null); }
    private DurableTestFixture(SqliteStore store, Executor executor, GenerationJournal journal, String replyText) {
        this.store = store;
        if (store.agents().load().isEmpty()) { store.agents().save(0, data.registry.archive()); }
        if (store.conversations().findMessage("message").isEmpty()) { store.conversations().save(data.conversations.findById("thread").orElseThrow()); }
        if (store.decisions().find(data.routeId).isEmpty()) { store.decisions().add(data.decisions.find(data.routeId).orElseThrow()); }
        registry = AgentRegistry.restore(store.agents().load().orElseThrow());
        var contexts = new PrepareContextUseCase(registry, store.conversations(), store.decisions(), new ContextService(List::of, source -> "",
                new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12)));
        router = new RoutingAgent(registry, store.conversations(), store.decisions(), contexts, definition -> new ConfiguredSpecialistAgent(definition, (request, cancellation) -> {
            calls.incrementAndGet(); return new GeneratedReply(request.taskId(), (replyText == null ? "Proposition synthétique " + calls.get() : replyText), settings.model(), true, Duration.ZERO, OptionalLong.empty(), OptionalLong.empty());
        }), settings, executor, data.clock, UUID::randomUUID, store.generations(), store, journal);
        reviews = reviewService(store.conversations());
        delivery = delivery(store.conversations(), store.simulatedSender());
    }
    DraftReviewService reviewService(ConversationRepository conversations) {
        return new DraftReviewService(store.drafts(), conversations, router, data.clock, UUID::randomUUID, store, draft -> draft.current().receipt().routing().id());
    }
    SendApprovedReplyUseCase delivery(ConversationRepository conversations, EmailSender sender) {
        return new SendApprovedReplyUseCase(store.drafts(), conversations, sender, new EmailAddress("support@paybridge.example"), data.clock, UUID::randomUUID, store);
    }
    GenerationHandle start() { return router.start(data.routeId, data.scope, data.allowed, Set.of(data.alice), Optional.empty(), data.budget); }
    Draft create() { var result = start().result().toCompletableFuture().join(); return reviews.capture("message", data.scope, result.taskId()); }
    Draft current() { return store.drafts().findByMessage("message").orElseThrow(); }
    Draft approve(Draft draft) { return reviews.approve(draft.id(), data.scope, draft.revision(), draft.current().number()); }
    ConversationRepository failSavingConversation() {
        var delegate = store.conversations();
        return new ConversationRepository() {
            public Optional<fr.paybridge.mas.domain.conversation.Conversation> findById(String id) { return delegate.findById(id); }
            public Optional<fr.paybridge.mas.domain.mail.EmailMessage> findMessage(String id) { return delegate.findMessage(id); }
            public void save(fr.paybridge.mas.domain.conversation.Conversation conversation) { delegate.save(conversation); throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
        };
    }
}
