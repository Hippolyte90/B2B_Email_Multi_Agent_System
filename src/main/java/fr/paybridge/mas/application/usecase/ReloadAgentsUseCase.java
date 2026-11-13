package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.AgentConfigurationLoader;
import fr.paybridge.mas.domain.agent.AgentRegistrySnapshot;

import java.io.IOException;
import java.util.Objects;

public final class ReloadAgentsUseCase {
    private final AgentRegistry registry;
    private final AgentConfigurationLoader loader;

    public ReloadAgentsUseCase(AgentRegistry registry, AgentConfigurationLoader loader) {
        this.registry = Objects.requireNonNull(registry);
        this.loader = Objects.requireNonNull(loader);
    }

    public AgentRegistrySnapshot execute() throws IOException {
        // Lecture/validation intégrales avant de publier : un échec conserve l'ancien registre.
        return registry.replaceAll(loader.load());
    }
}
