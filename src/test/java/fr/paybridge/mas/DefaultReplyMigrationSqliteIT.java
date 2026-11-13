package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.domain.draft.DraftContent;
import fr.paybridge.mas.domain.generation.GeneratedReply;
import fr.paybridge.mas.application.port.PersistenceFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultReplyMigrationSqliteIT {
    @TempDir Path directory;
    Path file(){return directory.resolve("legacy.sqlite");}
    final String oldText="[SIMULATION : texte fixe, aucun modèle appelé]\nAncienne réponse de démonstration.";
    void sql(String command)throws Exception{try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement()){s.execute(command);}}
    void downgrade()throws Exception{sql("DROP TABLE retired_demo_content");sql("PRAGMA user_version=5");}
    int count(String table)throws Exception{try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){return r.getInt(1);}}
    @Test void retiresUntouchedRepliesAndPreservesReceivedMail()throws Exception{
        try(var store=new SqliteStore(file())){new DurableTestFixture(store,oldText).create();}
        downgrade();
        try(var store=new SqliteStore(file())){
            assertEquals(6,store.summary().schema());assertEquals(1,store.summary().messages());
            assertTrue(store.drafts().findByMessage("message").isEmpty());
        }
        assertEquals(0,count("completed_generations"));assertEquals(2,count("retired_demo_content"));
        try(var store=new SqliteStore(file())){assertEquals(6,store.summary().schema());}
        assertEquals(2,count("retired_demo_content"));
    }
    @Test void retiresSimulatedSentDefaultsTogetherWithTheirOutbox()throws Exception{
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store,oldText);var d=f.approve(f.create());f.delivery.send(d.id(),f.data.scope,d.revision(),1);}
        assertEquals(1,count("simulated_outbox"));downgrade();
        try(var store=new SqliteStore(file())){assertTrue(store.drafts().findByMessage("message").isEmpty());}
        assertEquals(0,count("simulated_outbox"));assertEquals(3,count("retired_demo_content"));
    }
    @Test void preservesHumanEditsAndTheirGenerationReceipts()throws Exception{
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store,oldText);var d=f.create();var c=d.current().content();f.reviews.edit(d.id(),f.data.scope,d.revision(),1,new DraftContent(c.subject(),"Texte rédigé par une personne",c.recipients()));}
        downgrade();
        try(var store=new SqliteStore(file())){assertEquals("Texte rédigé par une personne",store.drafts().findByMessage("message").orElseThrow().current().content().body());}
        assertEquals(1,count("completed_generations"));assertEquals(0,count("retired_demo_content"));
    }
    @Test void preservesOtherGeneratedReplies()throws Exception{
        try(var store=new SqliteStore(file())){new DurableTestFixture(store).create();}downgrade();
        try(var store=new SqliteStore(file())){assertTrue(store.drafts().findByMessage("message").isPresent());}
        assertEquals(0,count("retired_demo_content"));
    }
    @Test void preservesRealTransportHistoryUsingOnlyAnInMemorySender()throws Exception{
        try(var store=new SqliteStore(file())){
            var f=new DurableTestFixture(store,oldText);var d=f.approve(f.create());
            var sender=new fr.paybridge.mas.application.port.EmailSender(){
                public fr.paybridge.mas.domain.delivery.TransportMode mode(){return fr.paybridge.mas.domain.delivery.TransportMode.REAL;}
                public fr.paybridge.mas.domain.delivery.DeliveryResult send(fr.paybridge.mas.domain.delivery.ApprovedEmail email){return new fr.paybridge.mas.domain.delivery.DeliveryResult(fr.paybridge.mas.domain.delivery.DeliveryStatus.CONFIRMED,Optional.of("memory-only"),"TEST_CONFIRMED");}
            };
            f.delivery(store.conversations(),sender).send(d.id(),f.data.scope,d.revision(),1);
        }
        downgrade();try(var store=new SqliteStore(file())){assertTrue(store.drafts().findByMessage("message").isPresent());}
        assertEquals(1,count("completed_generations"));assertEquals(0,count("retired_demo_content"));
    }
    @Test void retiresGenerationEvenWhenItHasNoDraft()throws Exception{
        try(var store=new SqliteStore(file())){new DurableTestFixture(store,oldText).start().result().toCompletableFuture().join();}
        downgrade();try(var store=new SqliteStore(file())){assertEquals(6,store.summary().schema());}
        assertEquals(0,count("completed_generations"));assertEquals(1,count("retired_demo_content"));
    }
    @Test void rollsBackEntireMigrationWhenDeletionFails()throws Exception{
        try(var store=new SqliteStore(file())){new DurableTestFixture(store,oldText).create();}downgrade();
        sql("CREATE TRIGGER deny_delete BEFORE DELETE ON drafts BEGIN SELECT RAISE(ABORT,'TEST_FAILURE'); END");
        assertThrows(PersistenceFailure.class,()->new SqliteStore(file()));assertEquals(1,count("drafts"));assertEquals(1,count("completed_generations"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){assertEquals(5,r.getInt(1));}
        assertThrows(SQLException.class,()->count("retired_demo_content"));
    }
}
