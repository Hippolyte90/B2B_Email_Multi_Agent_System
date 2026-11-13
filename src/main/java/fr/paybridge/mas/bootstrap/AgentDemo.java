package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.outbound.config.PropertiesAgentConfigurationLoader;
import fr.paybridge.mas.application.agent.AgentRegistry;

import java.io.IOException;
import java.nio.file.Path;

final class AgentDemo {
    private AgentDemo() { }

    static void run(Path configRoot) throws IOException {
        var registry = new AgentRegistry(new PropertiesAgentConfigurationLoader(configRoot).load());
        System.out.println("PayBridge - registre des agents (configuration externe)");
        for (var agent : registry.snapshot().agents()) {
            System.out.printf("%s | version=%d | actif=%s | repli=%s | catégories=%s | sources=%d%n",
                    agent.id(), agent.version(), agent.active(), agent.fallback(),
                    agent.categories().stream().sorted().toList(), agent.contextSources().size());
        }
        System.out.println("Définitions chargées. Utilisez --route pour le routage ou --generate pour la génération locale.");
    }
}
