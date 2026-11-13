package fr.paybridge.mas;

import fr.paybridge.mas.application.configuration.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.agent.*;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.domain.generation.GenerationSettings;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationEditingTest {
    final DraftTestFixture f = new DraftTestFixture();
    final LocalInferenceSettings inference = new LocalInferenceSettings("http://127.0.0.1:11435/", new GenerationSettings("test-model",8192,1024,.2,42,Duration.ofSeconds(5)),64);
    final class Repository implements ConfigurationRepository {
        EditableConfiguration saved; boolean fail;
        public Optional<EditableConfiguration> load() { return Optional.ofNullable(saved); }
        public void save(long expected, EditableConfiguration next, AgentRegistryArchive before, AgentRegistryArchive after) {
            if (fail) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
            if ((saved == null ? 0 : saved.revision()) != expected) { throw new IllegalArgumentException("Conflit"); } saved = next;
        }
    }
    final Repository repository = new Repository();
    final ConfigurationEditor editor = new ConfigurationEditor(f.registry, repository, source -> "Texte initial", new EditableConfiguration(0,f.registry.snapshot().agents(),List.of(),Map.of(),inference));
    AgentDefinition tech(String prompt) { var old=editor.current().agents().stream().filter(a->a.id().equals("Tech")).findFirst().orElseThrow(); return new AgentDefinition(old.id(),1,old.role(),old.categories(),prompt,old.contextSources(),old.active(),old.fallback()); }
    ContextSource source() { return new ContextSource("public",1,"context/public.md","Provenance",ClientScope.unknown(),ContextSource.Visibility.PUBLIC_APPROVED,Set.of("Tech"),Set.of()); }
    @Test void changesPromptWithAutomaticNextVersion() {
        var saved=editor.saveAgent(0,tech("Prompt suivant"),false);
        assertEquals(1,saved.revision()); assertEquals(2,f.registry.history("Tech").size()); assertEquals(2,f.registry.snapshot().revision()); assertEquals("Prompt suivant",f.registry.history("Tech").getLast().systemPrompt());
    }
    @Test void unchangedAgentIsNoOp() { var before=f.registry.archive(); assertSame(editor.current(),editor.saveAgent(0,tech(f.specialist.systemPrompt()),false)); assertEquals(before,f.registry.archive()); assertNull(repository.saved); }
    @Test void createsAgentAtVersionOneWithoutCodeChange() {
        var added=new AgentDefinition("NewAgent",9,"Nouveau rôle",Set.of("new"),"Instructions",Set.of(),true,false);
        editor.saveAgent(0,added,true); assertEquals(1,f.registry.history("NewAgent").getFirst().version());
    }
    @Test void duplicateAndRenamedIdsAreRefused() {
        assertThrows(IllegalArgumentException.class,()->editor.saveAgent(0,tech("Autre"),true));
        assertThrows(IllegalArgumentException.class,()->editor.saveAgent(0,new AgentDefinition("Absent",1,"Rôle",Set.of("x"),"Prompt",Set.of(),true,false),false));
    }
    @Test void lastActiveFallbackCannotBeDisabled() {
        var g=f.generic; var disabled=new AgentDefinition(g.id(),1,g.role(),g.categories(),g.systemPrompt(),g.contextSources(),false,true);
        assertThrows(IllegalArgumentException.class,()->editor.saveAgent(0,disabled,false)); assertNull(repository.saved); assertEquals(1,f.registry.snapshot().revision());
    }
    @Test void missingSourceReferenceIsRefusedBeforeWriting() {
        assertThrows(IllegalArgumentException.class,()->editor.saveAgent(0,new AgentDefinition("Tech",1,"Rôle",Set.of("x"),"Prompt",Set.of("context/absent.md"),true,false),false)); assertNull(repository.saved);
    }
    @Test void staleFormDoesNotOverwriteNewPrompt() {
        editor.saveAgent(0,tech("Premier"),false); assertThrows(IllegalArgumentException.class,()->editor.saveAgent(0,tech("Ancien écran"),false)); assertEquals("Premier",f.registry.history("Tech").getLast().systemPrompt());
    }
    @Test void saveFailureLeavesMemoryAndActiveConfigurationUntouched() {
        var before=f.registry.archive(); var config=editor.current(); repository.fail=true;
        assertThrows(PersistenceFailure.class,()->editor.saveAgent(0,tech("Échec"),false)); assertEquals(before,f.registry.archive()); assertSame(config,editor.current());
    }
    @Test void sourceCreationAndModificationPreserveVersionsAndInvalidateOldRouting() throws Exception {
        editor.saveSource(0,source(),"Premier texte",true); assertEquals(2,f.registry.snapshot().revision());
        var changed=editor.saveSource(1,source(),"Texte suivant",false); assertEquals(2,changed.sources().getFirst().version()); assertEquals("Texte suivant",editor.readSource("public")); assertEquals(3,f.registry.snapshot().revision());
    }
    @Test void identicalSourceIsNoOp() throws Exception {
        var saved=editor.saveSource(0,source(),"Premier",true); assertSame(saved,editor.saveSource(1,source(),"Premier",false));
    }
    @Test void sourceReferencesAndPermissionsAreValidated() {
        var foreign=new ContextSource("public",1,"../secret.md","Provenance",ClientScope.unknown(),ContextSource.Visibility.PUBLIC_APPROVED,Set.of("Tech"),Set.of());
        assertThrows(IllegalArgumentException.class,()->editor.saveSource(0,foreign,"Texte",true));
        var unknown=new ContextSource("public",1,"context/public.md","Provenance",ClientScope.unknown(),ContextSource.Visibility.PUBLIC_APPROVED,Set.of("Unknown"),Set.of());
        assertThrows(IllegalArgumentException.class,()->editor.saveSource(0,unknown,"Texte",true)); assertNull(repository.saved);
    }
    @Test void endpointRemainsLocalAndBudgetConsistent() {
        assertThrows(IllegalArgumentException.class,()->new LocalInferenceSettings("https://example.com/",inference.generation(),64));
        assertThrows(IllegalArgumentException.class,()->new LocalInferenceSettings("http://127.0.0.1:11435/path",inference.generation(),64));
        assertThrows(IllegalArgumentException.class,()->new LocalInferenceSettings(inference.endpoint(),inference.generation(),8000));
    }
    @Test void savingInferencePreservesRuntimeInputsAndAdvancesConfiguration() {
        var replacement=new LocalInferenceSettings("http://localhost:11435/",new GenerationSettings("next-model",8192,512,.3,42,Duration.ofSeconds(8)),64);
        var saved=editor.saveInference(0,replacement); assertEquals(replacement,saved.inference()); assertEquals("test-model",inference.generation().model()); assertEquals(2,f.registry.snapshot().revision());
    }
    @Test void sourceCatalogDoesNotReadAnyTextUntilRequested() throws Exception {
        editor.saveSource(0,source(),"Texte",true); var reads=new java.util.concurrent.atomic.AtomicInteger();
        var sources=new ConfigurationSources(editor::current,s->{ reads.incrementAndGet();return "Fichier"; });
        assertEquals(1,sources.sources().size()); assertEquals(0,reads.get()); assertEquals("Texte",sources.read(sources.sources().getFirst())); assertEquals(0,reads.get());
        assertThrows(java.io.IOException.class,()->sources.read(new ContextSource("other",1,"context/other.md","Provenance",ClientScope.unknown(),ContextSource.Visibility.PUBLIC_APPROVED,Set.of("Tech"),Set.of())));
    }
    @Test void sourceSnapshotCannotContainUnknownOrEmptyOverrides() {
        assertThrows(IllegalArgumentException.class,()->new EditableConfiguration(0,f.registry.snapshot().agents(),List.of(),Map.of("unknown","Texte"),inference));
        assertThrows(IllegalArgumentException.class,()->new EditableConfiguration(0,f.registry.snapshot().agents(),List.of(source()),Map.of("public",""),inference));
    }
}
