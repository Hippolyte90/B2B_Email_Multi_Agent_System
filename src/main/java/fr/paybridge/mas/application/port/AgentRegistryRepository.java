package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.agent.AgentRegistryArchive;
import java.util.Optional;

public interface AgentRegistryRepository {
    Optional<AgentRegistryArchive> load();
    /** Révision attendue 0 pour une première insertion. */
    void save(long expectedRevision, AgentRegistryArchive archive);
}
