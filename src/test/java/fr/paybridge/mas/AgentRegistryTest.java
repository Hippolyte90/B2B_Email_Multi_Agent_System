package fr.paybridge.mas;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.usecase.ReloadAgentsUseCase;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {
    private AgentDefinition definition(String id, long version, String prompt, boolean active, boolean fallback) {
        return new AgentDefinition(id, version, "Rôle de test", Set.of("categorie-libre"),
                prompt, Set.of("context/public.md"), active, fallback);
    }

    private AgentDefinition generic() {
        return definition("Fallback", 1, "Demande une clarification", true, true);
    }

    @Test
    void createsNewSpecialistWithArbitraryIdAndCategoryWithoutCoreChange() throws IOException {
        var registry = new AgentRegistry(List.of(generic()));
        var custom = new AgentDefinition("LogisticsAgent", 1, "Coordination logistique", Set.of("livraison"),
                "Ne confirme aucun délai sans source", Set.of(), true, false);
        var snapshot = new ReloadAgentsUseCase(registry, () -> List.of(generic(), custom)).execute();
        assertEquals(List.of("Fallback", "LogisticsAgent"), snapshot.activeAgents().stream().map(AgentDefinition::id).toList());
        assertEquals(Set.of("livraison"), snapshot.agents().getLast().categories());
        assertEquals(2, snapshot.revision());
    }

    @Test
    void duplicateIdRejectedDuringInitialization() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(generic(), generic())));
    }

    @Test
    void duplicateIdRejectedOnReloadWithoutChangingState() {
        var registry = new AgentRegistry(List.of(generic()));
        var previous = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(generic(), generic())));
        assertSame(previous, registry.snapshot());
        assertEquals(1, registry.history("Fallback").size());
    }

    @Test
    void requiresActiveFallbackFromInitialization() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(
                definition("Specialist", 1, "Prompt", true, false))));
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(List.of(
                definition("Fallback", 1, "Prompt", false, true))));
    }

    @Test
    void cannotDisableLastFallbackAndKeepsSnapshot() {
        var registry = new AgentRegistry(List.of(generic()));
        var previous = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(
                definition("Fallback", 2, "Demande une clarification", false, true))));
        assertSame(previous, registry.snapshot());
    }

    @Test
    void canDisableOneFallbackIfAnotherRemainsActive() {
        var second = definition("OtherFallback", 1, "Autre repli", true, true);
        var registry = new AgentRegistry(List.of(generic(), second));
        registry.replaceAll(List.of(definition("Fallback", 2, "Demande une clarification", false, true), second));
        assertEquals(List.of(second), registry.snapshot().activeAgents());
    }

    @Test
    void disablingSpecialistExcludesItFromActiveListAndRetainsVersions() {
        var specialist = definition("Technical", 1, "Prompt initial", true, false);
        var registry = new AgentRegistry(List.of(generic(), specialist));
        registry.replaceAll(List.of(generic(), definition("Technical", 2, "Prompt initial", false, false)));
        assertEquals(List.of(generic()), registry.snapshot().activeAgents());
        assertEquals(2, registry.history("Technical").size());
        assertTrue(registry.history("Technical").getFirst().active());
        assertFalse(registry.history("Technical").getLast().active());
    }

    @Test
    void updatesPromptButOldSnapshotAndHistoryRemainUnchanged() {
        var registry = new AgentRegistry(List.of(generic()));
        var old = registry.snapshot();
        registry.replaceAll(List.of(definition("Fallback", 2, "Nouvelles instructions", true, true)));
        assertEquals("Demande une clarification", old.agents().getFirst().systemPrompt());
        assertEquals("Nouvelles instructions", registry.snapshot().agents().getFirst().systemPrompt());
        assertEquals(List.of(1L, 2L), registry.history("Fallback").stream().map(AgentDefinition::version).toList());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3, 0})
    void modificationRequiresExactlyNextVersion(long version) {
        var registry = new AgentRegistry(List.of(generic()));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(
                definition("Fallback", version, "Prompt modifié", true, true))));
        assertEquals(1, registry.snapshot().revision());
    }

    @Test
    void backwardVersionRejected() {
        var registry = new AgentRegistry(List.of(definition("Fallback", 7, "Prompt", true, true)));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(
                definition("Fallback", 6, "Ancien prompt", true, true))));
        assertEquals(List.of(7L), registry.history("Fallback").stream().map(AgentDefinition::version).toList());
    }

    @Test
    void unchangedReloadDoesNotCreateRevisionOrHistory() {
        var registry = new AgentRegistry(List.of(generic()));
        var original = registry.snapshot();
        assertSame(original, registry.replaceAll(List.of(generic())));
        assertEquals(1, registry.history("Fallback").size());
    }

    @Test
    void cannotDeleteKnownAgentByRemovingItsConfiguration() {
        var registry = new AgentRegistry(List.of(generic(), definition("Specialist", 1, "Prompt", true, false)));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(generic())));
        assertEquals(2, registry.snapshot().agents().size());
    }

    @Test
    void invalidBatchDoesNotPartiallyApplyAnOtherwiseValidChange() {
        var registry = new AgentRegistry(List.of(generic(), definition("Specialist", 1, "Prompt", true, false)));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceAll(List.of(
                definition("Fallback", 2, "Modification valide", true, true),
                definition("Specialist", 1, "Modification sans version", true, false))));
        assertEquals(1, registry.history("Fallback").size());
        assertEquals("Demande une clarification", registry.snapshot().agents().getFirst().systemPrompt());
    }

    @Test
    void loadFailurePreservesCurrentConfiguration() {
        var registry = new AgentRegistry(List.of(generic()));
        var previous = registry.snapshot();
        var reload = new ReloadAgentsUseCase(registry, () -> { throw new IOException("Fichier indisponible"); });
        assertThrows(IOException.class, reload::execute);
        assertSame(previous, registry.snapshot());
    }

    @Test
    void snapshotsDefinitionsAndHistoryAreImmutable() {
        var categories = new HashSet<>(Set.of("initial"));
        var definition = new AgentDefinition("Fallback", 1, "Rôle", categories, "Prompt", Set.of(), true, true);
        categories.add("injection");
        var registry = new AgentRegistry(List.of(definition));
        assertEquals(Set.of("initial"), definition.categories());
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().agents().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().activeAgents().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.history("Fallback").clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.categories().clear());
    }

    @Test
    void rejectsEmptyPromptRoleAndCategories() {
        assertThrows(IllegalArgumentException.class, () -> definition("Agent", 1, " ", true, false));
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition(
                "Agent", 1, " ", Set.of("x"), "Prompt", Set.of(), true, false));
        assertThrows(IllegalArgumentException.class, () -> new AgentDefinition(
                "Agent", 1, "Rôle", Set.of(), "Prompt", Set.of(), true, false));
    }
}
