package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.configuration.EditableConfiguration;
import fr.paybridge.mas.application.port.ConfigurationRepository;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.domain.agent.AgentRegistryArchive;
import java.util.*;

final class SqliteConfigurationRepository implements ConfigurationRepository {
    private final SqliteStore store;
    SqliteConfigurationRepository(SqliteStore store) { this.store = store; }
    @Override public Optional<EditableConfiguration> load() {
        return store.inTransaction(() -> {
            var revision = store.payload("SELECT revision FROM configuration_snapshots ORDER BY revision DESC LIMIT 1");
            if (revision.isEmpty()) { return Optional.empty(); }
            return store.payload("SELECT payload FROM configuration_snapshots WHERE revision=?", revision.get()).map(payload -> {
                var configuration = store.decode(payload, EditableConfiguration.class);
                if (configuration.revision() != Long.parseLong(revision.get()) || configuration.revision() < 1) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
                return configuration;
            });
        });
    }
    @Override public void save(long expected, EditableConfiguration configuration, AgentRegistryArchive before, AgentRegistryArchive next) {
        store.inTransaction(() -> {
            if (load().map(EditableConfiguration::revision).orElse(0L) != expected || configuration.revision() != expected + 1
                    || !configuration.agents().equals(next.snapshot().agents()) || store.agents().load().filter(before::equals).isEmpty()) {
                throw new IllegalArgumentException("Configuration modifiée entre-temps");
            }
            var planned = AgentRegistry.restore(before); planned.replaceAll(configuration.agents());
            if (!planned.archive().equals(next)) { throw new IllegalArgumentException("Versions du registre incohérentes"); }
            store.agents().save(before.snapshot().revision(), next);
            store.sources().rememberMetadata(configuration.sources());
            for (var source : configuration.sources()) {
                var text = configuration.textOverrides().get(source.id());
                if (text != null) { store.sources().rememberText(source, text); }
            }
            store.write("INSERT INTO configuration_snapshots(revision,payload) VALUES(?,?)", configuration.revision(), store.encode(configuration));
            store.audit("CONFIGURATION_SAVED", "workspace", configuration.revision()); return null;
        });
    }
}
