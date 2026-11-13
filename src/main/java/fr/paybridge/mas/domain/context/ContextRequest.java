package fr.paybridge.mas.domain.context;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ContextRequest(EmailMessage message, AgentDefinition agent, Set<EmailAddress> replyRecipients,
                             List<MemoryEntry> history, Optional<String> userFeedback, ContextBudget budget) {
    public ContextRequest {
        Objects.requireNonNull(message);
        Objects.requireNonNull(agent);
        replyRecipients = Set.copyOf(replyRecipients);
        history = List.copyOf(history);
        Objects.requireNonNull(userFeedback);
        Objects.requireNonNull(budget);
        if (!agent.active() || replyRecipients.isEmpty() || userFeedback.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("Agent, destinataires ou commentaire utilisateur invalides");
        }
    }
}
