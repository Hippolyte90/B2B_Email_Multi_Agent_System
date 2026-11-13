package fr.paybridge.mas;

import com.google.gson.JsonObject;
import fr.paybridge.mas.adapters.outbound.gmail.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.mail.EmailAddress;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GmailEmailSenderTest {
    final DraftTestFixture f=new DraftTestFixture();
    final GmailTestTransport wire=new GmailTestTransport();
    final GmailClient client=new GmailClient(wire);
    final EmailAddress account=new EmailAddress("operator@example.test");
    final fr.paybridge.mas.domain.draft.Draft draft=f.create().approve(1,f.clock.instant());
    final ApprovedEmail email=new ApprovedEmail(UUID.randomUUID(),draft.id(),draft.messageId(),draft.conversationId(),draft.scope(),account,draft.approval().orElseThrow());
    final GmailEmailSender sender=new GmailEmailSender(client,id->client.message("a1"));
    GmailEmailSenderTest(){wire.messages.put("a1",GmailTestTransport.message("a1",f.alice.value(),account.value(),"SDK","Question","INBOX"));}
    long posts(){return wire.calls.stream().filter(c->c.method().equals("POST")&&(c.path().equals("/messages/send")||c.path().equals("/drafts/send"))).count();}
    @Test void successfulAcknowledgmentIsRealAndContainsExactApproval(){var result=sender.send(email);assertEquals(TransportMode.REAL,sender.mode());assertEquals(DeliveryStatus.CONFIRMED,result.status());assertEquals(Optional.of("sent123"),result.transportReference());assertEquals(1,posts());assertTrue(GmailTestTransport.mime(wire.calls.getLast().body()).contains("To: "+f.alice.value()));}
    @Test void rejectionIsFailureWithoutRetry(){wire.failPath="/messages/send";wire.failureStatus=403;assertEquals(DeliveryStatus.FAILED,sender.send(email).status());assertEquals(1,posts());}
    @Test void timeoutIsUnknownAndNotRetried(){wire.failPath="/messages/send";wire.failureStatus=0;assertEquals(DeliveryStatus.UNKNOWN,sender.send(email).status());assertEquals(1,posts());}
    @Test void serverFailureIsUnknownAndNotRetried(){wire.failPath="/messages/send";wire.failureStatus=503;assertEquals(DeliveryStatus.UNKNOWN,sender.send(email).status());assertEquals(1,posts());}
    @Test void malformedSuccessIsUnknownInsteadOfInventingConfirmation(){wire.malformedSend=true;assertEquals(DeliveryStatus.UNKNOWN,sender.send(email).status());assertEquals(1,posts());}
    @Test void preparationFailureGuaranteesNoSend(){wire.failPath="/drafts";wire.failureStatus=401;assertEquals(DeliveryStatus.FAILED,sender.send(email).status());assertEquals(0,posts());}
    @Test void remoteSpamChangeIsCheckedAgainBeforeSending(){wire.messages.get("a1").getAsJsonArray("labelIds").add("SPAM");assertEquals(DeliveryStatus.FAILED,sender.send(email).status());assertEquals(0,posts());}
    @Test void exportedOwnedDraftIsSentWithReplacementExactMime(){
        var m=GmailTestTransport.message("draftMessage",account.value(),f.alice.value(),"Ancien objet","Ancien texte","DRAFT");GmailTestTransport.header(m.getAsJsonObject("payload").getAsJsonArray("headers"),"X-MAS-Draft-Id",draft.id().toString());var d=new JsonObject();d.add("message",m);wire.drafts.put("remote123",d);
        assertEquals(DeliveryStatus.CONFIRMED,sender.send(email).status());assertEquals("/drafts/send",wire.calls.getLast().path());assertTrue(GmailTestTransport.mime(wire.calls.getLast().body()).contains("To: "+f.alice.value()));assertEquals(1,posts());
    }
    @Test void duplicateOwnedDraftsPreventAmbiguousSending(){
        var m=GmailTestTransport.message("draftMessage",account.value(),f.alice.value(),"Objet","Texte","DRAFT");GmailTestTransport.header(m.getAsJsonObject("payload").getAsJsonArray("headers"),"X-MAS-Draft-Id",draft.id().toString());var d=new JsonObject();d.add("message",m);wire.drafts.put("remote123",d);wire.drafts.put("remote124",d);
        assertEquals(DeliveryStatus.FAILED,sender.send(email).status());assertEquals(0,posts());
    }
}
