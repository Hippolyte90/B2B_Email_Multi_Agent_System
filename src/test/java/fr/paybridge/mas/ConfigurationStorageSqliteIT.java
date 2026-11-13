package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.configuration.*;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.*;
import fr.paybridge.mas.domain.context.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationStorageSqliteIT {
    @TempDir Path directory;
    Path file() { return directory.resolve("configuration.sqlite"); }
    ConfigurationEditor editor(DurableTestFixture f) { return editor(f,f.store.configuration()); }
    ConfigurationEditor editor(DurableTestFixture f, ConfigurationRepository repository) {
        return new ConfigurationEditor(f.registry,repository,source->{throw new java.io.IOException("Fichier non disponible");},
                new EditableConfiguration(0,f.registry.snapshot().agents(),List.of(),Map.of(),new LocalInferenceSettings("http://127.0.0.1:11435/",f.settings,64)));
    }
    AgentDefinition tech(ConfigurationEditor editor,String prompt,Set<String> refs) { return new AgentDefinition("Tech",1,"Technique",Set.of("technical"),prompt,refs,true,false); }
    ContextSource source() { return new ContextSource("public",1,"context/public.md","Provenance publique",ClientScope.unknown(),ContextSource.Visibility.PUBLIC_APPROVED,Set.of("Tech"),Set.of()); }
    UUID route(DurableTestFixture f) {
        return new RouteEmailUseCase(f.registry,f.store.conversations(),r->new RoutingOutcome("test",1,RoutingOutcome.Status.SELECTED,List.of(new RoutingCandidate("Tech",3,List.of("Technique"))),Optional.of("Tech")),f.store.decisions(),f.data.clock,UUID::randomUUID)
                .execute("message",f.data.scope,f.data.allowed).id();
    }
    @Test void editedPromptAndHistorySurviveRestart() {
        try(var store=new SqliteStore(file())) { var f=new DurableTestFixture(store); var editor=editor(f); editor.saveAgent(0,tech(editor,"Prompt édité",Set.of()),false); }
        try(var store=new SqliteStore(file())) {
            var configuration=store.configuration().load().orElseThrow(); assertEquals(1,configuration.revision());
            var registry=new AgentRegistry(configuration.agents(),store.agents()); assertEquals(2,registry.history("Tech").size()); assertEquals("Prompt édité",registry.history("Tech").getLast().systemPrompt());
        }
    }
    @Test void commitFailureRollsBackRegistryConfigurationAndAuditBeforeMemoryPublication() {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store); var before=f.registry.archive(); int audit=store.summary().auditEvents();
            ConfigurationRepository broken=new ConfigurationRepository() {
                public Optional<EditableConfiguration> load(){return store.configuration().load();}
                public void save(long revision,EditableConfiguration value,AgentRegistryArchive expected,AgentRegistryArchive next){store.inTransaction(()->{store.configuration().save(revision,value,expected,next);throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED);});}
            };
            var editor=editor(f,broken); assertThrows(PersistenceFailure.class,()->editor.saveAgent(0,tech(editor,"Échec",Set.of()),false));
            assertTrue(store.configuration().load().isEmpty()); assertEquals(before,f.registry.archive()); assertEquals(before,store.agents().load().orElseThrow()); assertEquals(audit,store.summary().auditEvents());
        }
    }
    @Test void staleEditorCannotOverwriteAnotherSavedConfiguration() {
        try(var first=new SqliteStore(file());var second=new SqliteStore(file())) {
            var a=new DurableTestFixture(first); var b=new DurableTestFixture(second); var ea=editor(a);var eb=editor(b);
            ea.saveAgent(0,tech(ea,"Premier",Set.of()),false);
            assertThrows(IllegalArgumentException.class,()->eb.saveAgent(0,tech(eb,"Second",Set.of()),false)); assertEquals(1,eb.current().agents().stream().filter(v->v.id().equals("Tech")).findFirst().orElseThrow().version());
            assertEquals("Premier",second.agents().load().orElseThrow().history().get("Tech").getLast().systemPrompt());
        }
    }
    @Test void sourceBodiesAndVersionsAreArchivedTransactionally() throws Exception {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store);var editor=editor(f);editor.saveSource(0,source(),"Première version",true);editor.saveSource(1,source(),"Seconde version",false);
            assertEquals("Première version",store.sources().text("public",1).orElseThrow());assertEquals("Seconde version",store.sources().text("public",2).orElseThrow());
        }
        try(var store=new SqliteStore(file())) {var f=new DurableTestFixture(store);var editor=editor(f);assertEquals("Seconde version",editor.readSource("public"));assertEquals(2,editor.current().revision());}
    }
    @Test void sourceConflictRollsBackRegistryAndConfiguration() {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store);var editor=editor(f);store.sources().rememberText(source(),"Version déjà immuable");var before=store.agents().load().orElseThrow();
            assertThrows(PersistenceFailure.class,()->editor.saveSource(0,source(),"Autre contenu v1",true));
            assertTrue(store.configuration().load().isEmpty());assertEquals(before,store.agents().load().orElseThrow());assertEquals(before,f.registry.archive());
        }
    }
    @Test void editedAgentIsUsedForNextGenerationAndOldRoutingIsRejected() {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store);var editor=editor(f);editor.saveAgent(0,tech(editor,"Nouvelle instruction",Set.of()),false);
            assertThrows(IllegalArgumentException.class,f::start);
            var result=f.router.start(route(f),f.data.scope,f.data.allowed,Set.of(f.data.alice),Optional.empty(),f.data.budget).result().toCompletableFuture().join();
            assertEquals(AgentResult.Status.COMPLETED,result.status());assertEquals("Nouvelle instruction",result.receipt().orElseThrow().agent().systemPrompt());
        }
    }
    @Test void configurationSourceOverridesStillRespectClientBoundaries() throws Exception {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store);var editor=editor(f);
            var foreign=new ContextSource("foreign",1,"context/foreign.md","Client B",ClientScope.client("client-b"),ContextSource.Visibility.CLIENT_SHAREABLE,Set.of("Tech"),Set.of(new EmailAddress("bob@client-b.example")));
            editor.saveSource(0,foreign,"DONNEE_B_SYNTHETIQUE",true);
            editor.saveAgent(1,tech(editor,"Réponds en français",Set.of(foreign.reference())),false);
            var reads=new ArrayList<String>();var sources=new ConfigurationSources(editor::current,s->fail("Aucun fichier nécessaire"));
            var service=new ContextService(sources,s->{reads.add(s.id());return sources.read(s);},new ConservativeTokenEstimator(),new LexicalContextSelectionPolicy(),new RecentConversationMemoryPolicy(12));
            var bundle=new PrepareContextUseCase(f.registry,store.conversations(),store.decisions(),service).execute(route(f),f.data.scope,f.data.allowed,Set.of(f.data.alice),Optional.empty(),f.data.budget);
            assertTrue(reads.isEmpty());assertFalse(bundle.userData().contains("DONNEE_B"));assertTrue(bundle.excerpts().isEmpty());
        }
    }
    @Test void inferenceSavedForRestartDoesNotRewritePreviousGenerationParameters() {
        try(var store=new SqliteStore(file())) {
            var f=new DurableTestFixture(store);var result=f.start().result().toCompletableFuture().join();var editor=editor(f);
            var next=new LocalInferenceSettings("http://127.0.0.1:11436/",new GenerationSettings("next-model",4096,512,.3,7,java.time.Duration.ofSeconds(10)),64);
            editor.saveInference(0,next);assertEquals(next,store.configuration().load().orElseThrow().inference());
            assertEquals("test-model",store.generations().find(result.taskId(),f.data.scope).orElseThrow().settings().model());
        }
    }
    @Test void configurationHistoryIsAppendOnly() throws Exception {
        try(var store=new SqliteStore(file())) {var f=new DurableTestFixture(store);var editor=editor(f);editor.saveAgent(0,tech(editor,"Un",Set.of()),false);editor.saveAgent(1,tech(editor,"Deux",Set.of()),false);}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM configuration_snapshots")){assertEquals(2,rows.getInt(1));}
    }
    @Test void auditNeverContainsPromptSourceBodyOrModelEndpoint() throws Exception {
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store);var editor=editor(f);editor.saveAgent(0,tech(editor,"PROMPT_SYNTHETIQUE_PRIVE",Set.of()),false);editor.saveSource(1,source(),"DOCUMENT_SYNTHETIQUE_PRIVE",true);}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM audit")){
            while(rows.next()){for(int i=1;i<=rows.getMetaData().getColumnCount();i++){assertFalse(rows.getString(i).contains("SYNTHETIQUE_PRIVE"));assertFalse(rows.getString(i).contains("http://"));}}
        }
    }
    @Test void migrationFromThreeKeepsDraftsAndProposals() throws Exception {
        UUID id;
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store);id=f.create().id();}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement()){statement.execute("DROP TABLE retired_demo_content");statement.execute("DROP TABLE mailbox_flags");statement.execute("DROP TABLE configuration_snapshots");statement.execute("PRAGMA user_version=3");}
        try(var store=new SqliteStore(file())){assertEquals(6,store.summary().schema());assertTrue(store.drafts().find(id).isPresent());assertTrue(store.configuration().load().isEmpty());assertEquals(1,store.summary().messages());}
    }
    @Test void failedMigrationDoesNotChangeSchemaThreeOrLoseMessages() throws Exception {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement()){statement.execute("PRAGMA user_version=3");}
        assertThrows(PersistenceFailure.class,()->new SqliteStore(file()));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("PRAGMA user_version")){assertEquals(3,rows.getInt(1));}
            try(var rows=statement.executeQuery("SELECT count(*) FROM messages")){assertEquals(1,rows.getInt(1));}
        }
    }
    @Test void corruptConfigurationIsRefusedWithoutLeakingStoredText() throws Exception {
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store);var editor=editor(f);editor.saveAgent(0,tech(editor,"Prompt",Set.of()),false);}
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement()){statement.executeUpdate("UPDATE configuration_snapshots SET payload='TEXTE_SYNTHETIQUE_INVALIDE'");}
        try(var store=new SqliteStore(file())){var failure=assertThrows(PersistenceFailure.class,()->store.configuration().load());assertEquals(PersistenceFailure.Code.CORRUPT_DATA,failure.code());assertFalse(failure.toString().contains("SYNTHETIQUE"));}
    }
}
