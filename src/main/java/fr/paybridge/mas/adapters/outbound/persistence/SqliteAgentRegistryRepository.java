package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.AgentRegistryRepository;
import fr.paybridge.mas.domain.agent.AgentRegistryArchive;
import java.util.Optional;

final class SqliteAgentRegistryRepository implements AgentRegistryRepository {
    private final SqliteStore store;
    SqliteAgentRegistryRepository(SqliteStore store) { this.store = store; }

    @Override public Optional<AgentRegistryArchive> load() {
        return store.inTransaction(() -> store.payload("SELECT payload FROM registry_archive WHERE singleton=1")
                .map(payload -> store.decode(payload, AgentRegistryArchive.class)));
    }

    @Override public void save(long expectedRevision, AgentRegistryArchive archive) {
        store.inTransaction(() -> {
            var current = load();
            if (current.isEmpty()) {
                if (expectedRevision != 0 || archive.snapshot().revision() != 1
                        || archive.history().values().stream().anyMatch(versions -> versions.size() != 1)) {
                    throw new IllegalArgumentException("Initialisation de registre invalide");
                }
                store.write("INSERT INTO registry_archive(singleton,revision,payload) VALUES(1,?,?)", archive.snapshot().revision(), store.encode(archive));
            } else {
                var before = current.get();
                if (before.snapshot().revision() != expectedRevision) { throw new IllegalArgumentException("Registre modifié entre-temps"); }
                if (before.equals(archive)) { return null; }
                var candidate = AgentRegistry.restore(before);
                candidate.replaceAll(archive.snapshot().agents());
                if (!candidate.archive().equals(archive)) { throw new IllegalArgumentException("Historique du registre réécrit"); }
                store.write("UPDATE registry_archive SET revision=?,payload=? WHERE singleton=1 AND revision=?", archive.snapshot().revision(), store.encode(archive), expectedRevision);
            }
            store.audit("REGISTRY_SAVED", "registry", archive.snapshot().revision());
            return null;
        });
    }
}
