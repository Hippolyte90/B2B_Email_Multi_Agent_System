package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.RoutingDecisionRepository;
import fr.paybridge.mas.application.routing.RoutingStrategy;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.routing.RoutingDecision;
import fr.paybridge.mas.domain.routing.RoutingRequest;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class RouteEmailUseCase {
    private final AgentRegistry registry;
    private final ConversationRepository conversations;
    private final RoutingStrategy strategy;
    private final RoutingDecisionRepository decisions;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public RouteEmailUseCase(AgentRegistry registry, ConversationRepository conversations, RoutingStrategy strategy,
                            RoutingDecisionRepository decisions, Clock clock, Supplier<UUID> identifiers) {
        this.registry = Objects.requireNonNull(registry);
        this.conversations = Objects.requireNonNull(conversations);
        this.strategy = Objects.requireNonNull(strategy);
        this.decisions = Objects.requireNonNull(decisions);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    /** Les autorisations sont issues de l'application, jamais du contenu d'un email. */
    public RoutingDecision execute(String messageId, ClientScope authorizedScope, Set<String> allowedAgentIds) {
        Objects.requireNonNull(authorizedScope);
        var allowed = Set.copyOf(allowedAgentIds);
        var message = conversations.findMessage(messageId).filter(m -> m.scope().equals(authorizedScope))
                .orElseThrow(() -> new IllegalArgumentException("Message absent ou hors périmètre"));
        var conversation = conversations.findById(message.conversationId()).orElseThrow();
        var previous = conversation.messages().stream().takeWhile(m -> !m.id().equals(messageId)).toList();
        var snapshot = registry.snapshot();
        var eligible = snapshot.activeAgents().stream().filter(a -> allowed.contains(a.id())).toList();
        var request = new RoutingRequest(message, previous, eligible, snapshot.revision());
        var outcome = strategy.route(request);
        // Le port de stratégie ne peut pas élargir les autorisations du cas d'usage.
        var eligibleIds = eligible.stream().map(a -> a.id()).collect(java.util.stream.Collectors.toSet());
        if (outcome.candidates().stream().anyMatch(c -> !eligibleIds.contains(c.agentId()))) {
            throw new IllegalArgumentException("La stratégie a proposé un agent non autorisé");
        }
        var decision = new RoutingDecision(identifiers.get(), message.id(), message.conversationId(), message.scope(),
                snapshot.revision(), clock.instant(), outcome, List.of());
        decisions.add(decision);
        return decision;
    }
}
