package fr.paybridge.mas.domain.agent;

import java.util.*;

/** État complet du registre, incluant les versions réellement observées depuis son initialisation. */
public record AgentRegistryArchive(AgentRegistrySnapshot snapshot, Map<String, List<AgentDefinition>> history) {
    public AgentRegistryArchive {
        Objects.requireNonNull(snapshot);
        var copied = new HashMap<String, List<AgentDefinition>>();
        history.forEach((id, versions) -> copied.put(id, List.copyOf(versions)));
        history = Map.copyOf(copied);
        if (snapshot.revision() < 1 || snapshot.agents().isEmpty()
                || snapshot.agents().stream().map(AgentDefinition::id).distinct().count() != snapshot.agents().size()
                || snapshot.activeAgents().stream().noneMatch(AgentDefinition::fallback)
                || !history.keySet().equals(new HashSet<>(snapshot.agents().stream().map(AgentDefinition::id).toList()))) {
            throw new IllegalArgumentException("Archive de registre incohérente");
        }
        for (var definition : snapshot.agents()) {
            var versions = history.get(definition.id());
            if (versions.isEmpty() || !versions.getLast().equals(definition)) {
                throw new IllegalArgumentException("Dernière version du registre incohérente");
            }
            for (int i = 0; i < versions.size(); i++) {
                if (!versions.get(i).id().equals(definition.id()) || (i > 0
                        && (versions.get(i - 1).version() == Long.MAX_VALUE || versions.get(i).version() != versions.get(i - 1).version() + 1))) {
                    throw new IllegalArgumentException("Historique de versions incohérent");
                }
            }
        }
    }
}
