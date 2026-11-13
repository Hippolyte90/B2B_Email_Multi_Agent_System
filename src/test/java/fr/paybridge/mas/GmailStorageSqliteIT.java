package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.gmail.*;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.routing.*;
import fr.paybridge.mas.domain.delivery.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQLite réel, modèle et fournisseur Gmail factices, aucune donnée de compte réelle. */
class GmailStorageSqliteIT {
    @TempDir Path directory;
    Path file(){return directory.resolve("gmail.sqlite");}
    final GmailTestTransport wire=new GmailTestTransport();
    final GmailClient client=new GmailClient(wire);
    final EmailAddress account=new EmailAddress("operator@example.test");
    GmailStorageSqliteIT(){wire.messages.put("a1",GmailTestTransport.message("a1","alice@example.test",account.value(),"SDK","Question technique","INBOX","UNREAD"));}
    record Workbench(ReviewWorkspaceService workspace,GmailMailbox mailbox){}
    Workbench setup(SqliteStore store,EmailAddress account){
        var f=new DurableTestFixture(store);
        var routing=new RouteEmailUseCase(f.registry,store.conversations(),request->new RoutingOutcome("test",1,RoutingOutcome.Status.SELECTED,List.of(new RoutingCandidate("Tech",3,List.of("SDK"))),Optional.of("Tech")),store.decisions(),f.data.clock,UUID::randomUUID);
        var sender=new GmailEmailSender(client,id->{var namespace=GmailMailbox.namespace(account);if(!id.startsWith(namespace)){throw new IllegalArgumentException("Autre compte");}return client.message(id.substring(namespace.length()));});
        var workspace=new ReviewWorkspaceService(Map.of(),store.conversations(),store.drafts(),f.registry,routing,new CorrectRoutingUseCase(f.registry,store.decisions(),f.data.clock),store.decisions(),f.router,sender,store,new ReloadAgentsUseCase(f.registry,()->f.registry.snapshot().agents()),List::of,f.settings,f.data.budget,true,account,f.data.clock).withMailboxFlags(store.mailbox());
        var local=new MailboxService(workspace::inbox,store.drafts(),store.mailbox());
        var mailbox=new GmailMailbox(client,account,new ImportEmailUseCase(store.conversations(),new ApprovedSenderDirectory(Map.of()),store),store.conversations(),store.drafts(),store.mailbox(),local,workspace::authorizeImportedMessage);
        return new Workbench(workspace,mailbox);
    }
    long sends(){return wire.calls.stream().filter(c->c.path().endsWith("/send")).count();}
    @Test void importedMailGeneratesEditedApprovedExactSendAndDurableConfirmation(){
        UUID draftId;
        try(var store=new SqliteStore(file())){
            var app=setup(store,account);app.mailbox.synchronize();String id=app.mailbox.localId("a1");assertEquals(0,sends());
            var draft=app.workspace.generate(id).result().toCompletableFuture().join();var edited=app.workspace.save(draft,new DraftContent("Objet corrigé","Texte approuvé exactement",List.of(new EmailAddress("alice@example.test"))));
            var sent=app.workspace.approveAndSend(edited);draftId=sent.id();assertEquals(DeliveryStatus.CONFIRMED,sent.sendAttempts().getFirst().status());assertEquals(1,sends());
            assertTrue(store.conversations().findById(sent.conversationId()).orElseThrow().confirmedSent().getFirst().content().contains("Texte approuvé exactement"));
            assertThrows(IllegalArgumentException.class,()->app.workspace.approveAndSend(edited));assertEquals(1,sends());
        }
        try(var store=new SqliteStore(file())){assertEquals(DeliveryStatus.CONFIRMED,store.drafts().find(draftId).orElseThrow().sendAttempts().getFirst().status());assertEquals(1,sends());}
    }
    @Test void uncertainSendSurvivesRestartAndCannotBeRetried(){
        UUID draftId;
        try(var store=new SqliteStore(file())){var app=setup(store,account);app.mailbox.synchronize();var draft=app.workspace.generate(app.mailbox.localId("a1")).result().toCompletableFuture().join();wire.failureStatus=0;wire.failPath="/messages/send";var uncertain=app.workspace.approveAndSend(draft);draftId=uncertain.id();assertEquals(DeliveryStatus.UNKNOWN,uncertain.sendAttempts().getFirst().status());assertThrows(IllegalArgumentException.class,()->app.workspace.retryFailed(uncertain));assertEquals(1,sends());}
        try(var store=new SqliteStore(file())){assertEquals(DeliveryStatus.UNKNOWN,store.drafts().find(draftId).orElseThrow().sendAttempts().getFirst().status());assertEquals(1,sends());}
    }
    @Test void resyncAfterRestartDoesNotDuplicateImportsAndKeepsFlags(){
        try(var store=new SqliteStore(file())){var app=setup(store,account);app.mailbox.synchronize();var flags=store.mailbox().find(app.mailbox.localId("a1"));app.mailbox.update(flags,MailboxFlags.Location.ARCHIVE,true,true);}
        try(var store=new SqliteStore(file())){var app=setup(store,account);app.mailbox.synchronize();assertEquals(2,store.summary().messages());var flags=store.mailbox().find(app.mailbox.localId("a1"));assertEquals(MailboxFlags.Location.ARCHIVE,flags.location());assertTrue(flags.starred());assertTrue(flags.read());assertEquals(0,sends());}
    }
    @Test void anotherAccountCannotExposeTheFirstAccountsStoredMail(){
        try(var store=new SqliteStore(file())){var first=setup(store,account);first.mailbox.synchronize();String firstId=first.mailbox.localId("a1");var second=setup(store,new EmailAddress("other@example.test"));second.mailbox.synchronize();assertNotEquals(firstId,second.mailbox.localId("a1"));assertEquals(1,second.workspace.inbox().size());assertThrows(IllegalArgumentException.class,()->second.workspace.open(firstId));}
    }
}
