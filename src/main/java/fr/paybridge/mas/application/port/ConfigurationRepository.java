package fr.paybridge.mas.application.port;

import fr.paybridge.mas.application.configuration.EditableConfiguration;
import fr.paybridge.mas.domain.agent.AgentRegistryArchive;
import java.util.Optional;

public interface ConfigurationRepository {
    Optional<EditableConfiguration> load();
    /** Commit commun de la configuration, du registre et des archives de sources. */
    void save(long expectedRevision, EditableConfiguration configuration, AgentRegistryArchive expectedRegistry, AgentRegistryArchive nextRegistry);
}
