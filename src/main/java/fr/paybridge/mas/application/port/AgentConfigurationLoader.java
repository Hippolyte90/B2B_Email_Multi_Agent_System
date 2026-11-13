package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.agent.AgentDefinition;

import java.io.IOException;
import java.util.List;

public interface AgentConfigurationLoader {
    List<AgentDefinition> load() throws IOException;
}
