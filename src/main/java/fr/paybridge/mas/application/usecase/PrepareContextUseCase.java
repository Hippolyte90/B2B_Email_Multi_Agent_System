package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.ContextService;
import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.RoutingDecisionRepository;
import fr.paybridge.mas.domain.context.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PrepareContextUseCase {
    private final AgentRegistry registry;
    private final ConversationRepository conversations;
    private final RoutingDecisionRepository decisions;
    private final ContextService context;

    public PrepareContextUseCase(AgentRegistry registry, ConversationRepository conversations,
                                 RoutingDecisionRepository decisions, ContextService context) {
        this.registry = Objects.requireNonNull(registry);
        this.conversations = Objects.requireNonNull(conversations);
        this.decisions = Objects.requireNonNull(decisions);
        this.context = Objects.requireNonNull(context);
    }

    public ContextBundle execute(UUID decisionId, ClientScope scope, Set<String> allowedAgentIds,
                                  Set<EmailAddress> replyRecipients, Optional<String> feedback, ContextBudget budget) throws IOException {
        var decision = decisions.find(decisionId).filter(d -> d.scope().equals(scope))
                .orElseThrow(() -> new IllegalArgumentException("Décision absente ou hors périmètre"));
        var agentId = decision.effectiveAgentId().orElseThrow(() -> new IllegalArgumentException("Résolvez l'ambiguïté avant de préparer le contexte"));
        var snapshot = registry.snapshot();
        if (snapshot.revision() != decision.registryRevision() || !allowedAgentIds.contains(agentId)) {
            throw new IllegalArgumentException("Décision obsolète ou agent non autorisé ; effectuez un nouveau routage");
        }
        var agent = snapshot.activeAgents().stream().filter(a -> a.id().equals(agentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Agent inactif ou absent"));
        var message = conversations.findMessage(decision.messageId()).filter(m -> m.scope().equals(scope)
                        && m.conversationId().equals(decision.conversationId()))
                .orElseThrow(() -> new IllegalArgumentException("Message absent ou hors périmètre"));
        var conversation = conversations.findById(message.conversationId()).orElseThrow();
        var received = conversation.messages().stream().takeWhile(m -> !m.id().equals(message.id())).map(MemoryEntry::received);
        var sent = conversation.confirmedSent().stream().filter(m -> !m.occurredAt().isAfter(message.receivedAt().toInstant()));
        var history = java.util.stream.Stream.concat(received, sent).toList();
        return context.prepare(new ContextRequest(message, agent, replyRecipients, history, feedback, budget));
    }
}
