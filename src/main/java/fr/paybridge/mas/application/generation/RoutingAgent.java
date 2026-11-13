package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.RoutingDecisionRepository;
import fr.paybridge.mas.application.port.CompletedGenerationRepository;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.application.port.GenerationJournal;
import fr.paybridge.mas.application.usecase.PrepareContextUseCase;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.conversation.HistoryEvent;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coordinateur : délégation asynchrone, retour corrélé et contrôle des résultats obsolètes. */
public final class RoutingAgent {
    private static final class Job {
        final UUID id;
        final RoutingDecision decision;
        final AgentDefinition agent;
        final CancellationSignal cancellation = new CancellationSignal();
        final CompletableFuture<AgentResult> result = new CompletableFuture<>();
        GenerationReceipt receipt;
        Job(UUID id, RoutingDecision decision, AgentDefinition agent) { this.id = id; this.decision = decision; this.agent = agent; }
    }

    private final AgentRegistry registry;
    private final ConversationRepository conversations;
    private final RoutingDecisionRepository decisions;
    private final PrepareContextUseCase contexts;
    private final Function<AgentDefinition, SpecialistAgent> specialists;
    private final GenerationSettings settings;
    private final Executor executor;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final CompletedGenerationRepository completed;
    private final TransactionRunner transactions;
    private final GenerationJournal journal;
    private final Map<String, Job> active = new HashMap<>();
    private final Map<String, AgentResult> published = new HashMap<>();

    public RoutingAgent(AgentRegistry registry, ConversationRepository conversations, RoutingDecisionRepository decisions,
                         PrepareContextUseCase contexts, Function<AgentDefinition, SpecialistAgent> specialists,
                         GenerationSettings settings, Executor executor, Clock clock, Supplier<UUID> identifiers) {
        this(registry, conversations, decisions, contexts, specialists, settings, executor, clock, identifiers,
                CompletedGenerationRepository.disabled(), TransactionRunner.direct());
    }

    public RoutingAgent(AgentRegistry registry, ConversationRepository conversations, RoutingDecisionRepository decisions,
                         PrepareContextUseCase contexts, Function<AgentDefinition, SpecialistAgent> specialists,
                         GenerationSettings settings, Executor executor, Clock clock, Supplier<UUID> identifiers,
                         CompletedGenerationRepository completed, TransactionRunner transactions) {
        this(registry, conversations, decisions, contexts, specialists, settings, executor, clock, identifiers, completed, transactions, GenerationJournal.disabled());
    }

    public RoutingAgent(AgentRegistry registry, ConversationRepository conversations, RoutingDecisionRepository decisions,
                         PrepareContextUseCase contexts, Function<AgentDefinition, SpecialistAgent> specialists,
                         GenerationSettings settings, Executor executor, Clock clock, Supplier<UUID> identifiers,
                         CompletedGenerationRepository completed, TransactionRunner transactions, GenerationJournal journal) {
        this.registry = Objects.requireNonNull(registry);
        this.conversations = Objects.requireNonNull(conversations);
        this.decisions = Objects.requireNonNull(decisions);
        this.contexts = Objects.requireNonNull(contexts);
        this.specialists = Objects.requireNonNull(specialists);
        this.settings = Objects.requireNonNull(settings);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.completed = Objects.requireNonNull(completed);
        this.transactions = Objects.requireNonNull(transactions);
        this.journal = Objects.requireNonNull(journal);
    }

    public synchronized GenerationHandle start(UUID decisionId, ClientScope scope, Set<String> allowedAgentIds,
                                                 Set<EmailAddress> recipients, Optional<String> feedback, ContextBudget budget) {
        var allowed = Set.copyOf(allowedAgentIds);
        var replyTo = Set.copyOf(recipients);
        Objects.requireNonNull(feedback);
        if (budget.capacity() != settings.contextCapacity() || budget.outputReserve() < settings.outputLimit()) {
            throw new IllegalArgumentException("Budget incompatible avec le moteur configuré");
        }
        var decision = decisions.find(decisionId).filter(d -> d.scope().equals(scope))
                .orElseThrow(() -> new IllegalArgumentException("Décision absente ou hors périmètre"));
        var agentId = decision.effectiveAgentId().orElseThrow(() -> new IllegalArgumentException("Résolvez l'ambiguïté avant de générer"));
        var snapshot = registry.snapshot();
        if (snapshot.revision() != decision.registryRevision() || !allowed.contains(agentId)) {
            throw new IllegalArgumentException("Décision obsolète ou agent non autorisé");
        }
        var definition = snapshot.activeAgents().stream().filter(a -> a.id().equals(agentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Agent inactif ou absent"));
        var job = new Job(identifiers.get(), decision, definition);
        journal.start(GenerationRecord.running(job.id, decision, definition, settings, clock.instant()));
        var previous = active.put(decision.messageId(), job);
        published.remove(decision.messageId());
        if (previous != null) { terminate(previous, AgentResult.Status.SUPERSEDED); }
        try {
            executor.execute(() -> run(job, scope, allowed, replyTo, feedback, budget));
        } catch (RejectedExecutionException error) {
            finish(job, null, GenerationFailure.UNAVAILABLE, List.of(AgentResult.Step.ROUTER_RECEIVED));
        }
        return new GenerationHandle(job.id, job.result.minimalCompletionStage(), () -> cancel(job));
    }

    public synchronized Optional<AgentResult> latest(String messageId, ClientScope scope) {
        if (conversations.findMessage(messageId).filter(m -> m.scope().equals(scope)).isEmpty()) { return Optional.empty(); }
        return Optional.ofNullable(published.get(messageId));
    }

    /** Réinstalle seulement une proposition déjà durable et toujours courante, sans nouvel événement. */
    public synchronized void restoreCompleted(StoredGeneration generation, ClientScope scope) {
        var result = generation.result(); var route = result.receipt().orElseThrow().routing();
        if (!route.scope().equals(scope) || active.containsKey(route.messageId())
                || completed.latest(route.messageId(), scope).filter(generation::equals).isEmpty()
                || registry.snapshot().revision() != route.registryRevision()
                || decisions.find(route.id()).filter(route::equals).isEmpty()) {
            throw new IllegalArgumentException("Proposition archivée obsolète ou hors périmètre");
        }
        published.put(route.messageId(), result);
    }

    /** Promotion atomique du dernier résultat : le callback ne doit pas appeler le routeur. */
    public synchronized <T> T withCurrentResult(String messageId, ClientScope scope, UUID taskId, Function<AgentResult, T> action) {
        var result = latest(messageId, scope).filter(r -> r.taskId().equals(taskId) && r.status() == AgentResult.Status.COMPLETED)
                .orElseThrow(() -> new IllegalArgumentException("Résultat absent, obsolète ou sans proposition valide"));
        var receipt = result.receipt().orElseThrow();
        if (registry.snapshot().revision() != receipt.routing().registryRevision()
                || decisions.find(receipt.routing().id()).filter(receipt.routing()::equals).isEmpty()) {
            throw new IllegalArgumentException("La configuration ou le routage du résultat a changé");
        }
        return action.apply(result);
    }

    private void run(Job job, ClientScope scope, Set<String> allowed, Set<EmailAddress> recipients,
                     Optional<String> feedback, ContextBudget budget) {
        var trace = new ArrayList<>(List.of(AgentResult.Step.ROUTER_RECEIVED));
        try {
            job.cancellation.check();
            var bundle = contexts.execute(job.decision.id(), scope, allowed, recipients, feedback, budget);
            if (feedback.isPresent() && !bundle.userFeedback().equals(feedback)) {
                throw new GenerationException(GenerationFailure.CONTEXT_ERROR);
            }
            trace.add(AgentResult.Step.CONTEXT_PREPARED);
            if (!currentConfiguration(job)) { throw new GenerationException(GenerationFailure.STALE_CONTEXT); }
            job.cancellation.check();
            var task = new AgentTask(new GenerationRequest(job.id, bundle, settings), job.decision, job.agent);
            job.receipt = new GenerationReceipt(job.decision, job.agent, bundle, recipients);
            trace.add(AgentResult.Step.SPECIALIST_DELEGATED);
            var reply = specialists.apply(job.agent).handle(task, job.cancellation);
            trace.add(AgentResult.Step.LLM_RETURNED);
            if (reply == null || !reply.taskId().equals(job.id) || !reply.model().equals(settings.model())) {
                throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
            }
            finish(job, reply, null, trace);
        } catch (GenerationException error) {
            finish(job, null, error.code(), trace);
        } catch (IOException | IllegalArgumentException error) {
            finish(job, null, GenerationFailure.CONTEXT_ERROR, trace);
        } catch (PersistenceFailure error) {
            finish(job, null, GenerationFailure.STORAGE_ERROR, trace);
        } catch (RuntimeException error) {
            finish(job, null, GenerationFailure.INTERNAL_ERROR, trace);
        }
    }

    private boolean currentConfiguration(Job job) {
        return registry.snapshot().revision() == job.decision.registryRevision()
                && decisions.find(job.decision.id()).filter(job.decision::equals).isPresent();
    }

    private synchronized void finish(Job job, GeneratedReply reply, GenerationFailure failure, List<AgentResult.Step> steps) {
        if (job.result.isDone()) { return; }
        if (active.get(job.decision.messageId()) != job) { terminate(job, AgentResult.Status.SUPERSEDED); return; }
        if (job.cancellation.cancelled()) { terminate(job, AgentResult.Status.CANCELLED); return; }
        try {
            if (!currentConfiguration(job)) { reply = null; failure = GenerationFailure.STALE_CONTEXT; }
        } catch (PersistenceFailure storageError) { reply = null; failure = GenerationFailure.STORAGE_ERROR; }
        var trace = new ArrayList<>(steps);
        trace.add(AgentResult.Step.ROUTER_RETURNED);
        var result = new AgentResult(job.id, job.agent.id(), reply == null ? AgentResult.Status.FAILED : AgentResult.Status.COMPLETED,
                Optional.ofNullable(reply), Optional.ofNullable(failure), trace, reply == null ? Optional.empty() : Optional.of(job.receipt));
        var candidate = result;
        var at = clock.instant();
        try {
            transactions.inTransaction(() -> {
                if (candidate.status() == AgentResult.Status.COMPLETED) {
                    var stored = new StoredGeneration(candidate, settings, at);
                    synchronized (conversations) {
                        var conversation = conversations.findById(job.decision.conversationId()).orElseThrow();
                        conversations.save(conversation.record(new HistoryEvent("generated:" + job.id, job.decision.messageId(),
                                HistoryEvent.Type.GENERATED, stored.completedAt(), job.id.toString())));
                        completed.add(stored);
                    }
                }
                journal.finish(job.id, candidate, at);
                return null;
            });
        } catch (RuntimeException storageError) {
            result = new AgentResult(job.id, job.agent.id(), AgentResult.Status.FAILED, Optional.empty(),
                    Optional.of(GenerationFailure.STORAGE_ERROR), trace, Optional.empty());
        }
        active.remove(job.decision.messageId(), job);
        published.put(job.decision.messageId(), result);
        job.result.complete(result);
    }

    private synchronized void cancel(Job job) {
        if (!job.result.isDone()) {
            terminate(job, AgentResult.Status.CANCELLED);
            active.remove(job.decision.messageId(), job);
        }
    }

    private void terminate(Job job, AgentResult.Status status) {
        job.cancellation.cancel();
        var result = new AgentResult(job.id, job.agent.id(), status, Optional.empty(), Optional.of(GenerationFailure.CANCELLED),
                List.of(AgentResult.Step.ROUTER_RECEIVED, AgentResult.Step.ROUTER_RETURNED), Optional.empty());
        try { journal.finish(job.id, result, clock.instant()); }
        catch (RuntimeException storageError) {
            result = new AgentResult(job.id, job.agent.id(), AgentResult.Status.FAILED, Optional.empty(), Optional.of(GenerationFailure.STORAGE_ERROR), result.trace(), Optional.empty());
        }
        job.result.complete(result);
    }
}
