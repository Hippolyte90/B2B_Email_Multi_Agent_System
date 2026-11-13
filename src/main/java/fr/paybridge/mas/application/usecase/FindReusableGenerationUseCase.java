package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import java.util.*;

/** Reprise explicite d'une proposition, jamais d'une approbation ou d'un envoi. */
public final class FindReusableGenerationUseCase {
    private final AgentRegistry registry;
    private final RoutingDecisionRepository decisions;
    private final CompletedGenerationRepository generations;
    public FindReusableGenerationUseCase(AgentRegistry registry, RoutingDecisionRepository decisions, CompletedGenerationRepository generations) {
        this.registry = Objects.requireNonNull(registry); this.decisions = Objects.requireNonNull(decisions); this.generations = Objects.requireNonNull(generations);
    }

    public Optional<StoredGeneration> execute(String messageId, ClientScope scope, Set<String> allowedAgents,
                                             Set<EmailAddress> recipients, GenerationSettings settings, boolean simulated) {
        var snapshot = registry.snapshot();
        return generations.latest(messageId, scope).filter(generation -> {
            var result = generation.result();
            var receipt = result.receipt().orElseThrow();
            return receipt.routing().messageId().equals(messageId) && receipt.routing().scope().equals(scope)
                    && generation.settings().equals(settings) && result.reply().orElseThrow().simulated() == simulated
                    && receipt.recipients().equals(recipients) && allowedAgents.contains(result.agentId())
                    && snapshot.revision() == receipt.routing().registryRevision() && snapshot.activeAgents().contains(receipt.agent())
                    && decisions.find(receipt.routing().id()).filter(receipt.routing()::equals).isPresent();
        });
    }
}
