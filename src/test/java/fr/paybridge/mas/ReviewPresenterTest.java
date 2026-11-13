package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.presentation.*;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewPresenterTest {
    final DraftTestFixture data = new DraftTestFixture();
    final Draft original = data.create();
    final class Queue implements java.util.concurrent.Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable action) { tasks.addLast(action); }
        void drain() { while (!tasks.isEmpty()) { tasks.removeFirst().run(); } }
    }
    final class Backend implements ReviewWorkspace {
        final Map<String, MessageView> views = new HashMap<>();
        CompletableFuture<Draft> pending = new CompletableFuture<>();
        int calls, generations, approvals, cancellations;
        boolean failSave;
        Backend() {
            views.put("message", new MessageView(data.conversations.findMessage("message").orElseThrow(), data.conversations.findById("thread").orElseThrow(), data.decisions.find(data.routeId).orElseThrow(), Optional.of(original)));
            var m = data.conversations.findMessage("message").orElseThrow();
            var second = new EmailMessage("second", "second-thread", m.sender(), m.recipients(), "Autre sujet", "Autre contenu", m.receivedAt(), m.scope());
            views.put("second", new MessageView(second, Conversation.start(second), new RoutingDecision(UUID.randomUUID(), second.id(), second.conversationId(), second.scope(), 1, data.clock.instant(), views.get("message").routing().initialOutcome(), List.of()), Optional.empty()));
        }
        public List<EmailMessage> inbox() { calls++; return views.values().stream().map(MessageView::message).toList(); }
        public MessageView open(String id) { calls++; return views.get(id); }
        public MessageView choose(String id, String agent) { var v = views.get(id); views.put(id, new MessageView(v.message(), v.conversation(), v.routing().corrected(agent, "Choix", data.clock.instant()), v.draft())); return open(id); }
        public Work generate(String id) { generations++; return new Work(pending, () -> cancellations++); }
        public Work regenerate(Draft displayed, Optional<String> feedback) { return generate(displayed.messageId()); }
        void store(String id, Draft draft) { var v = views.get(id); views.put(id, new MessageView(v.message(), v.conversation(), v.routing(), Optional.of(draft))); }
        public Draft save(Draft displayed, DraftContent content) { if (failSave) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); } var updated = displayed.edit(displayed.current().number(), content, data.clock.instant()); store(displayed.messageId(), updated); return updated; }
        public Draft approveAndSend(Draft displayed) { approvals++; var next = displayed.approve(displayed.current().number(), data.clock.instant()); store(displayed.messageId(), next); return next; }
        public Draft retryFailed(Draft displayed) { throw new AssertionError("Aucune reprise attendue"); }
        public Configuration configuration() { return new Configuration(data.registry.archive(), List.of(), new GenerationSettings("test-model", 8192, 1024, .2, 42, Duration.ofSeconds(5)), true); }
        public Configuration reloadAgents() { return configuration(); }
    }
    final Backend backend = new Backend();
    final Queue queue = new Queue();
    final List<ReviewState> rendered = new ArrayList<>();
    final ReviewPresenter presenter = new ReviewPresenter(backend, queue, Runnable::run, rendered::add);
    void start(String id) { presenter.start(); queue.drain(); presenter.select(id); queue.drain(); }
    @Test void initializationDoesNotRunIoOnPresentationThread() {
        presenter.start(); assertEquals(0, backend.calls); assertTrue(presenter.state().busy()); queue.drain(); assertFalse(presenter.state().busy()); assertEquals(2, presenter.state().inbox().size());
    }
    @Test void openingDraftNeverApprovesOrGenerates() {
        start("message"); assertTrue(presenter.state().canApprove()); assertEquals(0, backend.approvals); assertEquals(0, backend.generations);
    }
    @Test void editingImmediatelyBlocksApprovalUntilSaved() {
        start("message"); presenter.edit(new ReplyEditor("Modifié", "Texte humain", "alice@client-a.example"));
        assertTrue(presenter.state().dirty()); assertTrue(presenter.state().canSave()); assertFalse(presenter.state().canApprove());
        presenter.approve(); assertEquals(0, backend.approvals); presenter.save(); queue.drain();
        assertEquals(2, presenter.state().draft().orElseThrow().current().number()); assertFalse(presenter.state().dirty()); assertTrue(presenter.state().canApprove());
    }
    @Test void invalidRecipientsDisableSaveAndApproval() {
        start("message"); presenter.edit(new ReplyEditor("Sujet", "Texte", "pas-une-adresse"));
        assertFalse(presenter.state().canSave()); assertFalse(presenter.state().canApprove()); assertTrue(presenter.state().notice().contains("Destinataires invalides"));
    }
    @Test void blankBodyAndMultilineSubjectAreRejected() {
        assertFalse(new ReplyEditor("Sujet", "", "alice@client-a.example").valid());
        assertFalse(new ReplyEditor("Sujet\ninjecté", "Texte", "alice@client-a.example").valid());
        assertFalse(new ReplyEditor("Sujet", "Texte", "alice@client-a.example,alice@client-a.example").valid());
    }
    @Test void historicalVersionCannotBeEditedOrApproved() {
        start("message"); presenter.edit(new ReplyEditor("Nouveau", "Nouveau", "alice@client-a.example")); presenter.save(); queue.drain(); presenter.showVersion(1);
        assertTrue(presenter.state().historical()); assertFalse(presenter.state().canEdit()); assertFalse(presenter.state().canApprove()); presenter.approve(); assertEquals(0, backend.approvals);
        presenter.showVersion(2); assertTrue(presenter.state().canApprove());
    }
    @Test void dirtyTextBlocksChangingMessage() {
        start("message"); presenter.edit(new ReplyEditor("Modifié", "Texte", "alice@client-a.example")); presenter.select("second"); queue.drain();
        assertEquals("message", presenter.state().view().orElseThrow().message().id()); assertTrue(presenter.state().dirty());
    }
    @Test void failedSaveRetainsUnsavedHumanText() {
        start("message"); var editor = new ReplyEditor("Humain", "Texte important", "alice@client-a.example"); presenter.edit(editor); backend.failSave = true;
        presenter.save(); queue.drain(); assertEquals(editor, presenter.state().editor()); assertTrue(presenter.state().dirty()); assertFalse(presenter.state().canApprove());
    }
    @Test void cancellationBeforeWorkerStartsAvoidsBackendGeneration() {
        start("second"); presenter.generate(); presenter.cancel(); queue.drain(); assertEquals(0, backend.generations); assertFalse(presenter.state().busy()); assertTrue(presenter.state().notice().contains("annulée"));
    }
    @Test void runningCancellationIsDeliveredOnceOutsidePresentationCall() {
        start("second"); presenter.generate(); queue.drain(); presenter.cancel(); presenter.cancel(); assertEquals(0, backend.cancellations); queue.drain(); assertEquals(1, backend.cancellations);
    }
    @Test void oldCompletionCannotReplaceNewlySelectedMessage() {
        start("message"); var old = backend.views.get("message"); backend.views.put("message", new ReviewWorkspace.MessageView(old.message(), old.conversation(), old.routing(), Optional.empty())); presenter.select("message"); queue.drain();
        presenter.generate(); queue.drain(); presenter.select("second"); queue.drain();
        backend.store("message", original); backend.pending.complete(original); queue.drain();
        assertEquals("second", presenter.state().view().orElseThrow().message().id()); assertTrue(presenter.state().draft().isEmpty());
    }
    @Test void ambiguousRoutingDisablesGenerationUntilChosen() {
        var old = backend.views.get("second"); var ambiguous = new RoutingOutcome("rules", 1, RoutingOutcome.Status.AMBIGUOUS, List.of(new RoutingCandidate("Tech", 3, List.of()), new RoutingCandidate("Generic", 3, List.of())), Optional.empty());
        backend.views.put("second", new ReviewWorkspace.MessageView(old.message(), old.conversation(), new RoutingDecision(UUID.randomUUID(), "second", "second-thread", data.scope, 1, data.clock.instant(), ambiguous, List.of()), Optional.empty()));
        start("second"); assertFalse(presenter.state().canGenerate()); presenter.choose("Tech"); queue.drain(); assertTrue(presenter.state().canGenerate());
    }
    @Test void closingIgnoresFutureUiUpdatesAndCancelsWork() {
        start("second"); presenter.generate(); queue.drain(); int count = rendered.size(); presenter.close(); backend.pending.completeExceptionally(new WorkspaceGenerationFailure(GenerationFailure.TIMEOUT)); queue.drain();
        assertEquals(count, rendered.size()); assertEquals(1, backend.cancellations);
    }
    @Test void generationErrorsAreUsefulAndDoNotRevealRawProviderData() {
        start("second"); presenter.generate(); queue.drain(); backend.pending.completeExceptionally(new WorkspaceGenerationFailure(GenerationFailure.UNAVAILABLE)); queue.drain();
        assertTrue(presenter.state().notice().contains("moteur local est indisponible"));
        assertFalse(PresentationErrors.message(new RuntimeException("SECRET DU FOURNISSEUR")).contains("SECRET"));
    }
}
