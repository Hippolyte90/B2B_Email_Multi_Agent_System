package fr.paybridge.mas.domain.routing;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.util.List;
import java.util.Objects;

public record RoutingRequest(EmailMessage message, List<EmailMessage> previousMessages,
                             List<AgentDefinition> eligibleAgents, long registryRevision) {
    public RoutingRequest {
        Objects.requireNonNull(message);
        previousMessages = List.copyOf(previousMessages);
        eligibleAgents = List.copyOf(eligibleAgents);
        if (registryRevision < 1 || eligibleAgents.stream().noneMatch(a -> a.active() && a.fallback())
                || eligibleAgents.stream().anyMatch(a -> !a.active())
                || eligibleAgents.stream().map(AgentDefinition::id).distinct().count() != eligibleAgents.size()) {
            throw new IllegalArgumentException("Agents autorisés invalides ou aucun repli actif autorisé");
        }
        for (var previous : previousMessages) {
            if (!previous.conversationId().equals(message.conversationId()) || !previous.scope().equals(message.scope())
                    || previous.id().equals(message.id()) || previous.receivedAt().toInstant().isAfter(message.receivedAt().toInstant())) {
                throw new IllegalArgumentException("Historique de routage hors fil, hors périmètre ou futur");
            }
        }
    }
}
