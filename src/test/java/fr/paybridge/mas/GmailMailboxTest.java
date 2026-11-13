package fr.paybridge.mas;

import com.google.gson.*;
import fr.paybridge.mas.adapters.outbound.gmail.*;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryMailboxFlagsRepository;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GmailMailboxTest {
    final ReviewWorkspaceServiceTest backend=new ReviewWorkspaceServiceTest();
    final DraftTestFixture f=backend.f;
    final GmailTestTransport wire=new GmailTestTransport();
    final GmailClient client=new GmailClient(wire);
    final EmailAddress account=new EmailAddress("operator@example.test");
    final InMemoryMailboxFlagsRepository flags=new InMemoryMailboxFlagsRepository();
    final MailboxService local=new MailboxService(backend.workspace::inbox,f.drafts,flags);
    final GmailMailbox mailbox=new GmailMailbox(client,account,new ImportEmailUseCase(f.conversations,new ApprovedSenderDirectory(Map.of())),f.conversations,f.drafts,flags,local,backend.workspace::authorizeImportedMessage);
    GmailMailboxTest(){wire.messages.put("a1",GmailTestTransport.message("a1",f.alice.value(),account.value(),"SDK","Question technique","INBOX","UNREAD"));backend.workspace.withMailboxFlags(flags);}
    String id(){return mailbox.localId("a1");}
    @Test void syncImportsWithoutCallingModelAndDoesNotPromoteUnknownClient(){mailbox.synchronize();assertTrue(mailbox.connected());assertEquals(1,mailbox.entries().size());assertEquals(ClientScope.unknown(),f.conversations.findMessage(id()).orElseThrow().scope());assertTrue(f.requests.isEmpty());assertTrue(f.drafts.findByMessage(id()).isEmpty());}
    @Test void repeatedSyncDeduplicatesMessagesAndKeepsIdentity(){mailbox.synchronize();var first=mailbox.entries().getFirst();mailbox.synchronize();assertEquals(first,mailbox.entries().getFirst());assertEquals(1,f.conversations.findById(f.conversations.findMessage(id()).orElseThrow().conversationId()).orElseThrow().messages().size());}
    @Test void accountNamespacesPreventCrossAccountLookup(){assertNotEquals(GmailMailbox.namespace(account),GmailMailbox.namespace(new EmailAddress("other@example.test")));assertThrows(IllegalArgumentException.class,()->mailbox.source(GmailMailbox.namespace(new EmailAddress("other@example.test"))+"a1"));assertTrue(wire.calls.isEmpty());}
    @Test void flagsAreWrittenRemotelyThenReflectedLocally(){mailbox.synchronize();var before=flags.find(id());mailbox.update(before,MailboxFlags.Location.SPAM,true,true);var entry=mailbox.entries().getFirst();assertEquals(MailboxFlags.Location.SPAM,entry.flags().location());assertTrue(entry.flags().read());assertTrue(entry.flags().starred());assertTrue(wire.messages.get("a1").getAsJsonArray("labelIds").asList().contains(new JsonPrimitive("SPAM")));}
    @Test void staleClassificationDoesNotSendMutation(){mailbox.synchronize();var old=flags.find(id());mailbox.update(old,MailboxFlags.Location.ARCHIVE,true,false);wire.calls.clear();assertThrows(IllegalArgumentException.class,()->mailbox.update(old,MailboxFlags.Location.TRASH,false,false));assertTrue(wire.calls.isEmpty());}
    @Test void networkFailureDoesNotInventUpdatedLocalFlags(){mailbox.synchronize();var old=flags.find(id());wire.failureStatus=0;wire.failPath="/messages/a1/modify";assertThrows(GmailFailure.class,()->mailbox.update(old,MailboxFlags.Location.TRASH,false,false));assertEquals(old,flags.find(id()));}
    @Test void sentAndNativeDraftsAreReadOnlyAndNeverImportedAsReceived(){wire.messages.put("s1",GmailTestTransport.message("s1",account.value(),f.alice.value(),"Envoyé","Déjà envoyé","SENT"));var draft=new JsonObject();draft.add("message",GmailTestTransport.message("d1",account.value(),f.alice.value(),"Brouillon Gmail","À terminer","DRAFT"));wire.drafts.put("remote123",draft);mailbox.synchronize();assertEquals(3,mailbox.entries().size());assertTrue(mailbox.entries().stream().filter(e->e.kind()!=MailboxWorkspace.Kind.RECEIVED).noneMatch(MailboxWorkspace.Entry::canAssist));assertTrue(f.conversations.findMessage(mailbox.localId("s1")).isEmpty());}
    @Test void generatedLocalDraftCanBeExplicitlyExportedWithoutSending(){mailbox.synchronize();var draft=backend.workspace.generate(id()).result().toCompletableFuture().join();wire.calls.clear();mailbox.exportDraft("draft:"+draft.id());assertTrue(wire.calls.stream().anyMatch(c->c.method().equals("POST")&&c.path().equals("/drafts")));assertFalse(wire.calls.stream().anyMatch(c->c.path().endsWith("/send")));}
    @Test void modifiedRemoteDraftIsNotSilentlyOverwritten(){
        mailbox.synchronize();var draft=backend.workspace.generate(id()).result().toCompletableFuture().join();var message=GmailTestTransport.message("d1",account.value(),f.alice.value(),"Remote","Texte","DRAFT");GmailTestTransport.header(message.getAsJsonObject("payload").getAsJsonArray("headers"),"X-MAS-Draft-Id",draft.id().toString());var remote=new JsonObject();remote.add("message",message);wire.drafts.put("remote123",remote);mailbox.synchronize();
        message.getAsJsonObject("payload").getAsJsonObject("body").addProperty("data",Base64.getUrlEncoder().encodeToString("Edité dans Gmail".getBytes(java.nio.charset.StandardCharsets.UTF_8)));wire.calls.clear();assertThrows(IllegalArgumentException.class,()->mailbox.exportDraft("draft:"+draft.id()));assertFalse(wire.calls.stream().anyMatch(c->c.method().equals("PUT")));
    }
    @Test void spamMustBeRestoredBeforeGeneration(){wire.messages.get("a1").getAsJsonArray("labelIds").add("SPAM");mailbox.synchronize();assertThrows(IllegalArgumentException.class,()->backend.workspace.generate(id()));assertTrue(f.requests.isEmpty());}
    @Test void disappearingMessageLeavesNoStaleInboxEntry(){mailbox.synchronize();wire.messages.clear();mailbox.synchronize();assertTrue(mailbox.entries().isEmpty());assertTrue(f.conversations.findMessage(id()).isPresent());}
}
