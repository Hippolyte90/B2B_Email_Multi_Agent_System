package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.config.PropertiesAgentConfigurationLoader;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.usecase.ReloadAgentsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigurationTest {
    @TempDir Path root;
    private static final String CONFIG = """
            id=GenericAgent
            version=1
            role=Accueil général
            categories=general,clarification
            prompt=prompts/generic.md
            sources=context/public.md
            active=true
            fallback=true
            """;

    @BeforeEach
    void prepare() throws IOException {
        Files.createDirectories(root.resolve("agents"));
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("context"));
        Files.writeString(root.resolve("prompts/generic.md"), "Réponds en français et demande une précision.");
        Files.writeString(root.resolve("context/public.md"), "Données générales synthétiques.");
        write(CONFIG);
    }

    private void write(String config) throws IOException {
        Files.writeString(root.resolve("agents/generic.properties"), config);
    }

    private PropertiesAgentConfigurationLoader loader() {
        return new PropertiesAgentConfigurationLoader(root);
    }

    @Test
    void loadsUtf8PromptCategoriesAndSourceReferences() throws IOException {
        var definitions = loader().load();
        var agent = definitions.getFirst();
        assertEquals(1, definitions.size());
        assertEquals("Accueil général", agent.role());
        assertEquals("Réponds en français et demande une précision.", agent.systemPrompt());
        assertEquals(Set.of("general", "clarification"), agent.categories());
        assertEquals(Set.of("context/public.md"), agent.contextSources());
    }

    @Test
    void emptySourcesAreAllowedForClarificationOnlyAgent() throws IOException {
        write(CONFIG.replace("sources=context/public.md", "sources="));
        assertTrue(loader().load().getFirst().contextSources().isEmpty());
    }

    @Test
    void missingContextSourceIsRejected() throws IOException {
        Files.delete(root.resolve("context/public.md"));
        assertThrows(IOException.class, () -> loader().load());
    }

    @Test
    void missingPromptIsRejected() throws IOException {
        Files.delete(root.resolve("prompts/generic.md"));
        assertThrows(IOException.class, () -> loader().load());
    }

    @Test
    void blankPromptIsRejected() throws IOException {
        Files.writeString(root.resolve("prompts/generic.md"), " \n\t");
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"active=yes", "version=wrong", "categories=general,,other", "categories=general,general", "sources=context/public.md,", "id=bad id"})
    void rejectsMalformedValues(String replacement) throws IOException {
        var key = replacement.substring(0, replacement.indexOf('='));
        write(CONFIG.replaceAll("(?m)^" + key + "=.*$", replacement));
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsUnknownAndMissingProperties() throws IOException {
        write(CONFIG + "unknown=true\n");
        assertThrows(IllegalArgumentException.class, () -> loader().load());
        write(CONFIG.replace("fallback=true\n", ""));
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsDuplicatePropertiesInsteadOfSilentlyUsingLastValue() throws IOException {
        write(CONFIG + "active=false\n");
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsPromptPathOutsideApprovedRoot() throws IOException {
        write(CONFIG.replace("prompt=prompts/generic.md", "prompt=../outside.md"));
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsAbsoluteSourcePath() throws IOException {
        var absolute = root.resolve("context/public.md").toString().replace('\\', '/');
        write(CONFIG.replace("sources=context/public.md", "sources=" + absolute));
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsDirectoryInPlaceOfSourceFile() throws IOException {
        write(CONFIG.replace("sources=context/public.md", "sources=context"));
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void emptyAgentDirectoryCannotInitializeRegistry() throws IOException {
        Files.delete(root.resolve("agents/generic.properties"));
        assertThrows(IllegalArgumentException.class, () -> new AgentRegistry(loader().load()));
    }

    @Test
    void externalPromptModificationRequiresVersionIncrementOnReload() throws IOException {
        var registry = new AgentRegistry(loader().load());
        var reload = new ReloadAgentsUseCase(registry, loader());
        var previous = registry.snapshot();
        Files.writeString(root.resolve("prompts/generic.md"), "Nouveau prompt, sans compilation.");
        assertThrows(IllegalArgumentException.class, reload::execute);
        assertSame(previous, registry.snapshot());
        write(CONFIG.replace("version=1", "version=2"));
        reload.execute();
        assertEquals("Nouveau prompt, sans compilation.", registry.snapshot().agents().getFirst().systemPrompt());
        assertEquals(2, registry.history("GenericAgent").size());
        assertEquals("Réponds en français et demande une précision.", previous.agents().getFirst().systemPrompt());
    }

    @Test
    void newAgentCanBeAddedAndDisabledEntirelyThroughFiles() throws IOException {
        var registry = new AgentRegistry(loader().load());
        var path = root.resolve("agents/custom.properties");
        var custom = CONFIG.replace("id=GenericAgent", "id=CustomAgent")
                .replace("categories=general,clarification", "categories=nouvelle-categorie")
                .replace("fallback=true", "fallback=false");
        Files.writeString(path, custom);
        var reload = new ReloadAgentsUseCase(registry, loader());
        reload.execute();
        assertEquals(2, registry.snapshot().activeAgents().size());
        Files.writeString(path, custom.replace("version=1", "version=2").replace("active=true", "active=false"));
        reload.execute();
        assertEquals(1, registry.snapshot().activeAgents().size());
        assertEquals(2, registry.history("CustomAgent").size());
    }

    @Test
    void duplicateAgentIdsInDifferentFilesAreRejectedWithoutChangingRegistry() throws IOException {
        var registry = new AgentRegistry(loader().load());
        var previous = registry.snapshot();
        Files.writeString(root.resolve("agents/duplicate.properties"), CONFIG);
        assertThrows(IllegalArgumentException.class, () -> new ReloadAgentsUseCase(registry, loader()).execute());
        assertSame(previous, registry.snapshot());
    }

    @Test
    void reloadWithMissingSourceKeepsPreviousRegistry() throws IOException {
        var registry = new AgentRegistry(loader().load());
        var previous = registry.snapshot();
        Files.delete(root.resolve("context/public.md"));
        assertThrows(IOException.class, () -> new ReloadAgentsUseCase(registry, loader()).execute());
        assertSame(previous, registry.snapshot());
    }

    @Test
    void publicPayBridgeConfigurationLoadsFiveDifferentAgentDefinitions() throws IOException {
        var registry = new AgentRegistry(new PropertiesAgentConfigurationLoader(Path.of("config")).load());
        var agents = registry.snapshot().activeAgents();
        assertEquals(5, agents.size());
        assertEquals(5, agents.stream().map(a -> a.systemPrompt()).distinct().count());
        assertEquals(1, agents.stream().filter(a -> a.fallback()).count());
        assertTrue(agents.stream().anyMatch(a -> a.id().equals("OnsiteSupportAgent")));
        assertEquals(5, agents.stream().map(a -> a.contextSources()).distinct().count());
    }
}
