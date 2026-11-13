package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.generation.*;

import java.util.Objects;

public final class ConfiguredSpecialistAgent implements SpecialistAgent {
    private final AgentDefinition definition;
    private final LocalLlmGateway gateway;

    public ConfiguredSpecialistAgent(AgentDefinition definition, LocalLlmGateway gateway) {
        this.definition = Objects.requireNonNull(definition);
        this.gateway = Objects.requireNonNull(gateway);
    }

    @Override
    public GeneratedReply handle(AgentTask task, CancellationSignal cancellation) throws GenerationException {
        cancellation.check();
        if (!task.agent().equals(definition) || !task.request().context().systemInstructions().startsWith(definition.systemPrompt())) {
            throw new GenerationException(GenerationFailure.STALE_CONTEXT);
        }
        var response = gateway.generate(task.request(), cancellation);
        cancellation.check();
        if (response == null || !response.taskId().equals(task.request().taskId())
                || !response.model().equals(task.request().settings().model())) {
            throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
        }
        return response;
    }
}
