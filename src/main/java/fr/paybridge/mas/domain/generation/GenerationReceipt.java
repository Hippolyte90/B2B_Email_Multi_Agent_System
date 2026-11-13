package fr.paybridge.mas.domain.generation;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBundle;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.RoutingDecision;

import java.util.Objects;
import java.util.Set;

/** Instantanés réellement utilisés, conservés pour la relecture et la traçabilité. */
public record GenerationReceipt(RoutingDecision routing, AgentDefinition agent, ContextBundle context,
                                 Set<EmailAddress> recipients) {
    public GenerationReceipt {
        Objects.requireNonNull(routing);
        Objects.requireNonNull(agent);
        Objects.requireNonNull(context);
        recipients = Set.copyOf(recipients);
        if (recipients.isEmpty() || !routing.effectiveAgentId().filter(agent.id()::equals).isPresent()) {
            throw new IllegalArgumentException("Justificatif de génération incohérent");
        }
    }
}
