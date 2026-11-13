package fr.paybridge.mas;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.agent.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentArchiveTest {
    private final AgentDefinition initial = agent(3, "Initial");
    private static AgentDefinition agent(long version, String prompt) {
        return new AgentDefinition("Generic", version, "Accueil", Set.of("general"), prompt, Set.of(), true, true);
    }
    private static final class Repository implements AgentRegistryRepository {
        AgentRegistryArchive state;
        boolean fail;
        @Override public Optional<AgentRegistryArchive> load() { return Optional.ofNullable(state); }
        @Override public void save(long revision, AgentRegistryArchive archive) {
            if (fail) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
            if ((state == null ? 0 : state.snapshot().revision()) != revision) { throw new IllegalArgumentException("Révision obsolète"); }
            state = archive;
        }
    }

    @Test void restoresKnownVersionsWithoutInventingEarlierVersions() {
        var registry = new AgentRegistry(List.of(initial));
        registry.replaceAll(List.of(agent(4, "Modifié")));
        var restored = AgentRegistry.restore(registry.archive());
        assertEquals(registry.archive(), restored.archive());
        assertEquals(List.of(3L, 4L), restored.history("Generic").stream().map(AgentDefinition::version).toList());
    }
    @Test void durableRegistryContinuesFromSavedRevision() {
        var repository = new Repository();
        var first = new AgentRegistry(List.of(initial), repository);
        first.replaceAll(List.of(agent(4, "Modifié")));
        var second = new AgentRegistry(List.of(agent(5, "Nouveau")), repository);
        assertEquals(3, second.snapshot().revision()); assertEquals(3, second.history("Generic").size());
    }
    @Test void failedSaveDoesNotPublishNewConfiguration() {
        var repository = new Repository(); var registry = new AgentRegistry(List.of(initial), repository);
        var before = registry.archive(); repository.fail = true;
        assertThrows(PersistenceFailure.class, () -> registry.replaceAll(List.of(agent(4, "Modifié"))));
        assertEquals(before, registry.archive()); assertEquals(before, repository.state);
    }
    @Test void staleRegistryCannotOverwriteAnotherSession() {
        var repository = new Repository(); var first = new AgentRegistry(List.of(initial), repository);
        var stale = new AgentRegistry(List.of(initial), repository);
        first.replaceAll(List.of(agent(4, "Premier")));
        assertThrows(IllegalArgumentException.class, () -> stale.replaceAll(List.of(agent(4, "Second"))));
        assertEquals(1, stale.snapshot().revision());
    }
    @Test void refusesHistoryWithoutCurrentDefinition() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistryArchive(new AgentRegistrySnapshot(1, List.of(initial)), Map.of()));
    }
    @Test void refusesRewrittenHistoryAndVersionGaps() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistryArchive(new AgentRegistrySnapshot(2, List.of(agent(5, "Modifié"))), Map.of("Generic", List.of(initial, agent(5, "Modifié")))));
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistryArchive(new AgentRegistrySnapshot(2, List.of(initial)), Map.of("Generic", List.of(agent(4, "Autre")))));
    }
    @Test void refusesNonpositiveRevision() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistryArchive(new AgentRegistrySnapshot(0, List.of(initial)), Map.of("Generic", List.of(initial))));
    }
    @Test void archiveDefensivelyCopiesHistories() {
        var versions = new ArrayList<>(List.of(initial)); var history = new HashMap<String, List<AgentDefinition>>(); history.put("Generic", versions);
        var archive = new AgentRegistryArchive(new AgentRegistrySnapshot(1, List.of(initial)), history);
        versions.clear(); history.clear();
        assertEquals(List.of(initial), archive.history().get("Generic"));
        assertThrows(UnsupportedOperationException.class, () -> archive.history().clear());
    }
}
