package fr.paybridge.mas.application.draft;

import fr.paybridge.mas.application.generation.GenerationHandle;
import fr.paybridge.mas.application.generation.RoutingAgent;
import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.DraftRepository;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.conversation.HistoryEvent;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.AgentResult;
import fr.paybridge.mas.domain.mail.ClientScope;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Cas d'usage de relecture. Aucun transport email ni approbation automatique. */
public final class DraftReviewService {
    private final DraftRepository drafts;
    private final ConversationRepository conversations;
    private final RoutingAgent router;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final TransactionRunner transactions;
    private final Function<Draft, UUID> regenerationRoute;

    public DraftReviewService(DraftRepository drafts, ConversationRepository conversations, RoutingAgent router, Clock clock, Supplier<UUID> identifiers) {
        this(drafts, conversations, router, clock, identifiers, TransactionRunner.direct(), draft -> draft.current().receipt().routing().id());
    }

    public DraftReviewService(DraftRepository drafts, ConversationRepository conversations, RoutingAgent router, Clock clock, Supplier<UUID> identifiers,
                              TransactionRunner transactions, Function<Draft, UUID> regenerationRoute) {
        this.drafts = Objects.requireNonNull(drafts);
        this.conversations = Objects.requireNonNull(conversations);
        this.router = Objects.requireNonNull(router);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.transactions = Objects.requireNonNull(transactions);
        this.regenerationRoute = Objects.requireNonNull(regenerationRoute);
    }

    public Draft capture(String messageId, ClientScope scope, UUID generationTaskId) {
        // Ordre des verrous : routeur -> dépôt de brouillons -> conversations ; jamais l'inverse.
        return router.withCurrentResult(messageId, scope, generationTaskId, result -> transactions.inTransaction(() -> {
            synchronized (drafts) {
                var existing = drafts.findByMessage(messageId);
                if (existing.isPresent()) {
                    var draft = authorized(existing.get().id(), scope);
                    if (draft.versions().stream().anyMatch(v -> v.generation().taskId().equals(generationTaskId))) { return draft; }
                    throw new IllegalArgumentException("Utilisez la régénération du brouillon existant");
                }
                var message = conversations.findMessage(messageId).filter(m -> m.scope().equals(scope)).orElseThrow();
                var subject = message.subject().toLowerCase(Locale.ROOT).startsWith("re:") ? message.subject() : "Re: " + message.subject();
                var draft = Draft.generated(identifiers.get(), subject, result, clock.instant());
                drafts.add(draft);
                return draft;
            }
        }));
    }

    public Draft find(UUID id, ClientScope scope) { return transactions.inTransaction(() -> { synchronized (drafts) { return authorized(id, scope); } }); }

    public Draft edit(UUID id, ClientScope scope, long expectedRevision, int expectedVersion, DraftContent content) {
        return mutate(id, scope, expectedRevision, d -> d.edit(expectedVersion, content, clock.instant()));
    }
    public Draft approve(UUID id, ClientScope scope, long expectedRevision, int expectedVersion) {
        return mutate(id, scope, expectedRevision, d -> d.approve(expectedVersion, clock.instant()));
    }
    public Draft reject(UUID id, ClientScope scope, long expectedRevision, int expectedVersion, Optional<String> comment) {
        return mutate(id, scope, expectedRevision, d -> d.reject(expectedVersion, comment, clock.instant()));
    }

    public DraftRegenerationHandle rejectAndRegenerate(UUID id, ClientScope scope, long expectedRevision, int expectedVersion,
                                                        Optional<String> comment, Set<String> allowedAgents, ContextBudget budget) {
        var rejected = reject(id, scope, expectedRevision, expectedVersion, comment);
        return regenerate(id, scope, rejected.revision(), rejected.current().number(), allowedAgents, budget);
    }

    public DraftRegenerationHandle regenerate(UUID id, ClientScope scope, long expectedRevision, int expectedVersion,
                                               Set<String> allowedAgents, ContextBudget budget) {
        var allowed = Set.copyOf(allowedAgents);
        Objects.requireNonNull(budget);
        var attempt = identifiers.get();
        var reserved = mutate(id, scope, expectedRevision, d -> d.reserveRegeneration(expectedVersion, attempt, clock.instant()));
        var comment = reserved.rejectionComment().orElse("Propose une nouvelle variante de réponse, sans inventer de nouveaux faits.");
        GenerationHandle handle;
        try {
            handle = router.start(regenerationRoute.apply(reserved), scope, allowed,
                    Set.copyOf(reserved.current().content().recipients()), Optional.of(comment), budget);
        } catch (RuntimeException error) {
            failAttempt(id, scope, attempt, DraftReview.Type.REGENERATION_FAILED, "START_REJECTED");
            throw error;
        }
        var result = handle.result().thenApply(generation -> {
            if (generation.status() == AgentResult.Status.COMPLETED) {
                try {
                    return router.withCurrentResult(reserved.messageId(), scope, generation.taskId(), current -> transactions.inTransaction(() -> {
                        synchronized (drafts) {
                            var draft = authorized(id, scope);
                            if (draft.pendingRegeneration().filter(attempt::equals).isEmpty()) {
                                return new DraftRegenerationHandle.Outcome(current, false, Optional.empty());
                            }
                            var updated = draft.completeRegeneration(attempt, current, clock.instant());
                            save(draft, updated);
                            return new DraftRegenerationHandle.Outcome(current, true, Optional.of(updated));
                        }
                    }));
                } catch (IllegalArgumentException error) {
                    var retained = failAttempt(id, scope, attempt, DraftReview.Type.REGENERATION_SUPERSEDED, "STALE_RESULT");
                    return new DraftRegenerationHandle.Outcome(generation, false, retained);
                }
            }
            var type = switch (generation.status()) {
                case CANCELLED -> DraftReview.Type.REGENERATION_CANCELLED;
                case SUPERSEDED -> DraftReview.Type.REGENERATION_SUPERSEDED;
                default -> DraftReview.Type.REGENERATION_FAILED;
            };
            var retained = failAttempt(id, scope, attempt, type, generation.failure().orElseThrow().name());
            return new DraftRegenerationHandle.Outcome(generation, false, retained);
        });
        return new DraftRegenerationHandle(attempt, result, handle.cancel());
    }

    private Optional<Draft> failAttempt(UUID id, ClientScope scope, UUID attempt, DraftReview.Type type, String code) {
        return transactions.inTransaction(() -> {
            synchronized (drafts) {
                var draft = authorized(id, scope);
                if (draft.pendingRegeneration().filter(attempt::equals).isEmpty()) { return Optional.empty(); }
                var updated = draft.failRegeneration(attempt, type, code, clock.instant());
                save(draft, updated);
                return Optional.of(updated);
            }
        });
    }

    private Draft mutate(UUID id, ClientScope scope, long expectedRevision, Function<Draft, Draft> action) {
        return transactions.inTransaction(() -> {
            synchronized (drafts) {
                var current = authorized(id, scope);
                if (current.revision() != expectedRevision) { throw new IllegalArgumentException("Relecture obsolète ; affichez de nouveau le brouillon"); }
                var updated = action.apply(current);
                if (updated != current) { save(current, updated); }
                return updated;
            }
        });
    }

    private Draft authorized(UUID id, ClientScope scope) {
        return drafts.find(id).filter(d -> d.scope().equals(scope))
                .orElseThrow(() -> new IllegalArgumentException("Brouillon absent ou hors périmètre"));
    }

    private void save(Draft current, Draft updated) {
        var event = updated.reviews().getLast();
        var type = switch (event.type()) {
            case EDITED -> HistoryEvent.Type.EDITED;
            case APPROVED -> HistoryEvent.Type.APPROVED;
            case REJECTED -> HistoryEvent.Type.REJECTED;
            default -> null;
        };
        synchronized (conversations) {
            var conversation = conversations.findById(updated.conversationId()).orElseThrow();
            if (type != null) {
                conversation = conversation.record(new HistoryEvent("draft:" + updated.id() + ":" + updated.revision(), updated.messageId(),
                        type, event.at(), updated.id() + ":v" + event.version()));
            }
            drafts.replace(current, updated);
            if (type != null) { conversations.save(conversation); }
        }
    }
}
