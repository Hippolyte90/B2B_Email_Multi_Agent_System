package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.configuration.*;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.application.draft.DraftReviewService;
import fr.paybridge.mas.application.generation.RoutingAgent;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.RoutingDecision;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Façade des parcours graphiques : les invariants restent dans les cas d'usage partagés. */
public final class ReviewWorkspaceService implements ReviewWorkspace {
    private final Map<String, ClientScope> authorized;
    private final ConversationRepository conversations;
    private final DraftRepository drafts;
    private final AgentRegistry registry;
    private final RouteEmailUseCase routing;
    private final CorrectRoutingUseCase correction;
    private final RoutingDecisionRepository decisions;
    private final RoutingAgent router;
    private final DraftReviewService reviews;
    private final SendApprovedReplyUseCase delivery;
    private final ReloadAgentsUseCase reload;
    private final ContextSourceCatalog catalog;
    private final GenerationSettings settings;
    private final ContextBudget budget;
    private final boolean simulated;
    private ConfigurationEditor editor;
    private MailboxFlagsRepository mailbox;
    private final Map<String, RoutingDecision> routes = new ConcurrentHashMap<>();
    private final Map<String,Work> generating = new ConcurrentHashMap<>();

    public ReviewWorkspaceService(Map<String, ClientScope> authorized, ConversationRepository conversations, DraftRepository drafts,
                                  AgentRegistry registry, RouteEmailUseCase routing, CorrectRoutingUseCase correction,
                                  RoutingDecisionRepository decisions, RoutingAgent router, EmailSender sender,
                                  TransactionRunner transactions, ReloadAgentsUseCase reload, ContextSourceCatalog catalog,
                                  GenerationSettings settings, ContextBudget budget, boolean simulated, EmailAddress senderAddress, Clock clock) {
        this.authorized = new ConcurrentHashMap<>(authorized); this.conversations = conversations; this.drafts = drafts; this.registry = registry;
        this.routing = routing; this.correction = correction; this.decisions = decisions; this.router = router;
        this.reload = reload; this.catalog = catalog; this.settings = settings; this.budget = budget; this.simulated = simulated;
        reviews = new DraftReviewService(drafts, conversations, router, clock, UUID::randomUUID, transactions, draft -> currentRoute(draft.messageId()).id());
        delivery = new SendApprovedReplyUseCase(drafts, conversations, sender, senderAddress, clock, UUID::randomUUID, transactions);
    }
    public ReviewWorkspaceService withConfigurationEditor(ConfigurationEditor editor) { this.editor = Objects.requireNonNull(editor); return this; }
    /** Assemblage/import de confiance uniquement ; le périmètre doit déjà exister au dépôt. */
    public void authorizeImportedMessage(EmailMessage imported) {
        var stored=conversations.findMessage(imported.id()).filter(imported::equals).orElseThrow(() -> new IllegalArgumentException("Import absent"));
        var previous=authorized.putIfAbsent(stored.id(),stored.scope());
        if(previous!=null&&!previous.equals(stored.scope())){throw new IllegalArgumentException("Périmètre différent");}
    }
    public ReviewWorkspaceService withMailboxFlags(MailboxFlagsRepository mailbox) { this.mailbox = Objects.requireNonNull(mailbox); return this; }
    private void requireActionable(String id) {
        message(id);
        if (mailbox != null) {
            var location = mailbox.find(id).location();
            if (location == MailboxFlags.Location.SPAM || location == MailboxFlags.Location.TRASH) { throw new IllegalArgumentException("Restaurez ce message avant génération ou envoi"); }
        }
    }
    private Set<String> allowed() { return registry.snapshot().activeAgents().stream().map(AgentDefinition::id).collect(Collectors.toUnmodifiableSet()); }
    private EmailMessage message(String id) {
        var scope = Optional.ofNullable(authorized.get(id)).orElseThrow(() -> new IllegalArgumentException("Message non autorisé"));
        return conversations.findMessage(id).filter(message -> message.scope().equals(scope)).orElseThrow(() -> new IllegalArgumentException("Message absent ou hors périmètre"));
    }
    private void authorize(Draft displayed) {
        var message = message(displayed.messageId());
        if (!message.scope().equals(displayed.scope()) || !message.conversationId().equals(displayed.conversationId())) { throw new IllegalArgumentException("Brouillon hors périmètre"); }
    }
    private RoutingDecision currentRoute(String messageId) {
        var message = message(messageId);
        return routes.compute(messageId, (id, previous) -> previous != null && previous.registryRevision() == registry.snapshot().revision()
                && decisions.find(previous.id()).filter(previous::equals).isPresent() ? previous : routing.execute(id, message.scope(), allowed()));
    }
    @Override public List<EmailMessage> inbox() {
        return authorized.keySet().stream().map(this::message).sorted(Comparator.comparing(EmailMessage::receivedAt).thenComparing(EmailMessage::id)).toList();
    }
    @Override public MessageView open(String id) {
        var message = message(id);
        return new MessageView(message, conversations.findById(message.conversationId()).filter(c -> c.scope().equals(message.scope())).orElseThrow(), currentRoute(id),
                drafts.findByMessage(id).filter(d -> d.scope().equals(message.scope())));
    }
    @Override public MessageView choose(String id, String agentId) {
        var route = currentRoute(id);
        routes.put(id, correction.execute(route.id(), agentId, "Choix explicite dans l'interface", message(id).scope(), allowed()));
        return open(id);
    }
    @Override public Work generate(String id) {
        synchronized(generating){
        requireActionable(id);
        if(generating.containsKey(id)){return generating.get(id);}
        var view = open(id);
        if (view.draft().isPresent()) { throw new IllegalArgumentException("Un brouillon existe : utilisez le rejet et la régénération"); }
        var handle = router.start(view.routing().id(), view.message().scope(), allowed(), Set.of(view.message().sender()), Optional.empty(), budget);
        var result = handle.result().thenApply(generation -> {
            if (generation.status() != AgentResult.Status.COMPLETED) { throw new WorkspaceGenerationFailure(generation.failure().orElseThrow()); }
            return reviews.capture(id, view.message().scope(), generation.taskId());
        });
        var work=new Work(result, handle.cancel());generating.put(id,work);
        result.whenComplete((draft,failure)->generating.remove(id,work));return work;
        }
    }
    @Override public Work regenerate(Draft displayed, Optional<String> feedback) {
        requireActionable(displayed.messageId());
        authorize(displayed);
        // L'ambiguïté se résout avant le rejet, pour ne pas modifier le brouillon sur un clic impossible.
        if (currentRoute(displayed.messageId()).effectiveAgentId().isEmpty()) { throw new IllegalArgumentException("Choisissez un spécialiste avant de régénérer"); }
        var handle = displayed.state() == Draft.State.REJECTED
                ? reviews.regenerate(displayed.id(), displayed.scope(), displayed.revision(), displayed.current().number(), allowed(), budget)
                : reviews.rejectAndRegenerate(displayed.id(), displayed.scope(), displayed.revision(), displayed.current().number(), feedback, allowed(), budget);
        return new Work(handle.result().thenApply(outcome -> {
            if (!outcome.newVersionAdded()) { throw new WorkspaceGenerationFailure(outcome.generation().failure().orElse(GenerationFailure.STALE_CONTEXT)); }
            return outcome.draft().orElseThrow();
        }), handle.cancel());
    }
    @Override public Draft save(Draft displayed, DraftContent content) {
        authorize(displayed); return reviews.edit(displayed.id(), displayed.scope(), displayed.revision(), displayed.current().number(), content);
    }
    @Override public Draft approveAndSend(Draft displayed) {
        requireActionable(displayed.messageId());
        authorize(displayed);
        var approved = reviews.approve(displayed.id(), displayed.scope(), displayed.revision(), displayed.current().number());
        delivery.send(approved.id(), approved.scope(), approved.revision(), approved.current().number());
        return reviews.find(approved.id(), approved.scope());
    }
    @Override public Draft retryFailed(Draft displayed) {
        requireActionable(displayed.messageId());
        authorize(displayed); delivery.retryFailed(displayed.id(), displayed.scope(), displayed.revision(), displayed.current().number()); return reviews.find(displayed.id(), displayed.scope());
    }
    @Override public Configuration configuration() throws java.io.IOException { return new Configuration(registry.archive(), catalog.sources(), settings, simulated, Optional.ofNullable(editor).map(ConfigurationEditor::current)); }
    @Override public Configuration reloadAgents() throws java.io.IOException { reload.execute(); routes.clear(); return configuration(); }
    private Configuration configured() { routes.clear(); try { return configuration(); } catch (java.io.IOException failure) { throw new IllegalArgumentException("Configuration illisible"); } }
    private ConfigurationEditor editor() { return Objects.requireNonNull(editor, "Édition non disponible"); }
    @Override public Configuration saveAgent(long revision, AgentDefinition definition, boolean creating) { editor().saveAgent(revision, definition, creating); return configured(); }
    @Override public String sourceText(String id) throws java.io.IOException { return editor().readSource(id); }
    @Override public Configuration saveSource(long revision, ContextSource source, String text, boolean creating) throws java.io.IOException { editor().saveSource(revision, source, text, creating); return configured(); }
    @Override public Configuration saveInference(long revision, LocalInferenceSettings inference) { editor().saveInference(revision, inference); return configured(); }
}
