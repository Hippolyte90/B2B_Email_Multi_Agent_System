package fr.paybridge.mas.domain.agent;

import java.util.List;

public record AgentRegistrySnapshot(long revision, List<AgentDefinition> agents) {
    public AgentRegistrySnapshot {
        agents = List.copyOf(agents);
    }

    public List<AgentDefinition> activeAgents() {
        return agents.stream().filter(AgentDefinition::active).toList();
    }
}
