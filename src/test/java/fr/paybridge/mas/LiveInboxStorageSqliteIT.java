package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.adapters.inbound.fixtures.LiveDemoCatalog;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveInboxStorageSqliteIT {
    @TempDir Path directory;
    Path file(){return directory.resolve("flow.sqlite");}
    ReviewWorkspaceService workspace(DurableTestFixture f){
        var store=f.store;var routing=new RouteEmailUseCase(f.registry,store.conversations(),request->new RoutingOutcome("test",1,RoutingOutcome.Status.SELECTED,List.of(new RoutingCandidate("Tech",3,List.of())),Optional.of("Tech")),store.decisions(),f.data.clock,UUID::randomUUID);
        return new ReviewWorkspaceService(Map.of(),store.conversations(),store.drafts(),f.registry,routing,new CorrectRoutingUseCase(f.registry,store.decisions(),f.data.clock),store.decisions(),f.router,store.simulatedSender(),store,new ReloadAgentsUseCase(f.registry,()->f.registry.snapshot().agents()),List::of,f.settings,f.data.budget,true,new EmailAddress("support@paybridge.example"),f.data.clock).withMailboxFlags(store.mailbox());
    }
    @Test void twentyArrivalsCreateDurableUnapprovedRepliesWithoutSendingAndReopenCleanly()throws Exception{
        try(var store=new SqliteStore(file())){
            var f=new DurableTestFixture(store);var workspace=workspace(f);var timers=new ArrayDeque<Runnable>();var worker=new ArrayDeque<Runnable>();
            var seeds=LiveDemoCatalog.campaign(LiveDemoCatalog.load(Path.of("data/demo/live")),new UUID(0,1),f.data.clock,new Random(41));var events=new ArrayList<LiveInboxFlow.Event>();
            var importer=new ImportEmailUseCase(store.conversations(),sender->ClientScope.unknown(),store);
            try(var flow=new LiveInboxFlow(seeds,command->{importer.execute(command);workspace.authorizeImportedMessage(store.conversations().findMessage(command.id()).orElseThrow());},workspace,(delay,action)->{timers.add(action);return ()->timers.remove(action);},()->java.time.Duration.ofSeconds(60),worker::add,f.data.clock,events::add)){
                flow.start();assertTrue(store.messagesWithPrefix("demo-live-").isEmpty());
                while(!timers.isEmpty()){timers.removeFirst().run();while(!worker.isEmpty()){worker.removeFirst().run();}}
                var mailbox=new MailboxService(workspace::inbox,store.drafts(),store.mailbox());assertEquals(20,mailbox.entries().stream().filter(MailboxWorkspace.Entry::reviewRequired).count());assertEquals(20,store.messagesWithPrefix("demo-live-").size());
                for(var message:store.messagesWithPrefix("demo-live-")){assertTrue(store.drafts().findByMessage(message.id()).orElseThrow().approval().isEmpty());}
                assertEquals(20,events.getLast().drafted());assertEquals(0,events.getLast().failed());
            }
        }
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store);var workspace=workspace(f);for(var message:store.messagesWithPrefix("demo-live-")){workspace.authorizeImportedMessage(message);}var mailbox=new MailboxService(workspace::inbox,store.drafts(),store.mailbox());assertEquals(20,mailbox.entries().stream().filter(MailboxWorkspace.Entry::reviewRequired).count());assertEquals(0,f.calls.get());}
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM simulated_outbox")){assertEquals(0,rows.getInt(1));}
    }
    @Test void prefixInventoryDoesNotExposeForeignNamespacesOrTreatInputAsSql(){try(var store=new SqliteStore(file())){new DurableTestFixture(store);assertEquals(1,store.messagesWithPrefix("message").size());assertTrue(store.messagesWithPrefix("gmail-").isEmpty());assertTrue(store.messagesWithPrefix("%' OR 1=1 --").isEmpty());assertThrows(IllegalArgumentException.class,()->store.messagesWithPrefix(""));}}
}
