package fr.paybridge.mas;

import com.google.gson.*;
import fr.paybridge.mas.adapters.outbound.gmail.*;
import fr.paybridge.mas.domain.draft.DraftContent;
import fr.paybridge.mas.domain.mail.EmailAddress;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GmailClientTest {
    final GmailTestTransport wire=new GmailTestTransport();
    final GmailClient client=new GmailClient(wire);
    final EmailAddress account=new EmailAddress("operator@example.test"),alice=new EmailAddress("alice@example.test");
    final DraftContent content=new DraftContent("Réponse à votre demande","Bonjour,\nTexte exact approuvé.",List.of(alice));
    JsonObject message(){return GmailTestTransport.message("a1","Alice <alice@example.test>",account.value(),"Question","Contenu UTF-8 é", "INBOX","UNREAD");}
    @Test void parsesProfileAndTextMessage(){wire.messages.put("a1",message());assertEquals(account,client.profile());var m=client.messages(200).getFirst();assertEquals(alice,m.sender());assertEquals("Contenu UTF-8 é",m.body());assertTrue(m.labels().contains("UNREAD"));}
    @Test void paginationDeduplicatesIdsAndEscapesTokens(){
        wire.pages.put("/messages?includeSpamTrash=true&maxResults=100",GmailTestTransport.object("{\"messages\":[{\"id\":\"a1\"}],\"nextPageToken\":\"next &token\"}"));
        wire.pages.put("/messages?includeSpamTrash=true&maxResults=100&pageToken=next+%26token",GmailTestTransport.object("{\"messages\":[{\"id\":\"a1\"},{\"id\":\"a2\"}]}"));
        wire.messages.put("a1",message());wire.messages.put("a2",GmailTestTransport.message("a2",alice.value(),account.value(),"Deux","Autre"));assertEquals(2,client.messages(200).size());
    }
    @Test void repeatedPaginationTokenIsRejected(){wire.pages.put("/messages?includeSpamTrash=true&maxResults=100",GmailTestTransport.object("{\"nextPageToken\":\"again\"}"));wire.pages.put("/messages?includeSpamTrash=true&maxResults=100&pageToken=again",GmailTestTransport.object("{\"nextPageToken\":\"again\"}"));assertThrows(GmailFailure.class,()->client.messages(200));}
    @Test void maximumCapsFetchesAndInvalidLimitsAreRejected(){wire.messages.put("a1",message());wire.messages.put("a2",message());assertEquals(1,client.messages(1).size());assertThrows(IllegalArgumentException.class,()->client.messages(0));assertThrows(IllegalArgumentException.class,()->client.messages(1001));}
    @Test void htmlIsRenderedAsTextAndScriptsAreNotExecutedOrDisplayed(){var m=message();var p=m.getAsJsonObject("payload");p.addProperty("mimeType","text/html");p.getAsJsonObject("body").addProperty("data",Base64.getUrlEncoder().encodeToString("<script>secret()</script><p>Bonjour &amp; merci</p>".getBytes(StandardCharsets.UTF_8)));wire.messages.put("a1",m);assertEquals("Bonjour & merci",client.message("a1").body().strip());}
    @Test void multipartPrefersPlainTextAndSkipsAttachment(){
        var m=message();var p=m.getAsJsonObject("payload");p.remove("body");p.addProperty("mimeType","multipart/mixed");var parts=new JsonArray();var attachment=message().getAsJsonObject("payload").deepCopy();attachment.addProperty("filename","confidentiel.txt");parts.add(attachment);var plain=message().getAsJsonObject("payload");parts.add(plain);p.add("parts",parts);wire.messages.put("a1",m);assertEquals("Contenu UTF-8 é",client.message("a1").body());
    }
    @Test void respectsPartCharsetAndEncodedSubject(){var m=message();var p=m.getAsJsonObject("payload");GmailTestTransport.header(p.getAsJsonArray("headers"),"Content-Type","text/plain; charset=ISO-8859-1");p.getAsJsonObject("body").addProperty("data",Base64.getUrlEncoder().encodeToString("été".getBytes(StandardCharsets.ISO_8859_1)));for(var h:p.getAsJsonArray("headers")){if(h.getAsJsonObject().get("name").getAsString().equals("Subject")){h.getAsJsonObject().addProperty("value","=?UTF-8?Q?R=C3=A9ponse?=");}}wire.messages.put("a1",m);assertEquals("été",client.message("a1").body());assertEquals("Réponse",client.message("a1").subject());}
    @Test void malformedMessageDoesNotExposeRawPayload(){wire.messages.put("a1",GmailTestTransport.object("{\"private\":\"PRIVATE_SYNTHETIC\"}"));assertFalse(assertThrows(GmailFailure.class,()->client.message("a1")).toString().contains("PRIVATE"));}
    @Test void flagsPreserveUnrelatedLabels(){wire.messages.put("a1",message());client.flags("a1",false,true,false,true,true);var sent=wire.calls.getLast().body();assertTrue(sent.getAsJsonArray("addLabelIds").asList().contains(new JsonPrimitive("SPAM")));assertTrue(sent.getAsJsonArray("removeLabelIds").asList().contains(new JsonPrimitive("UNREAD")));assertFalse(sent.toString().contains("SENT"));}
    @Test void exportedDraftContainsExactMimeAndAssociation(){String id=UUID.randomUUID().toString();assertEquals("remote123",client.saveDraft("","thread123",account,content,"<incoming@example.test>",id));var call=wire.calls.getLast();assertEquals("/drafts",call.path());var mime=GmailTestTransport.mime(call.body());assertTrue(mime.contains("To: "+alice.value()));assertTrue(mime.contains("X-MAS-Draft-Id: "+id));assertTrue(mime.contains("In-Reply-To: <incoming@example.test>"));assertTrue(mime.contains(Base64.getMimeEncoder().encodeToString(content.body().getBytes(StandardCharsets.UTF_8))));}
    @Test void updatingDraftUsesStableRemoteId(){client.saveDraft("remote123","thread123",account,content,"",UUID.randomUUID().toString());assertEquals("PUT",wire.calls.getLast().method());assertEquals("/drafts/remote123",wire.calls.getLast().path());}
    @Test void sendingUsesExplicitPostAndFoldsLongUnicodeSubject(){var c=new DraftContent("É漢".repeat(80),"Corps",List.of(alice));assertEquals("sent123",client.send("thread123",account,c,""));var call=wire.calls.getLast();assertEquals("POST",call.method());assertEquals("/messages/send",call.path());var mime=GmailTestTransport.mime(call.body());for(var line:mime.split("\r\n")){if(line.contains("=?UTF-8?B?")){assertTrue(line.length()<78);}}}
    @Test void sendExistingDraftIncludesNewExactApprovedMime(){client.sendDraft("remote123","thread123",account,content,"<incoming@example.test>");assertEquals("/drafts/send",wire.calls.getLast().path());assertEquals("remote123",wire.calls.getLast().body().get("id").getAsString());assertTrue(GmailTestTransport.mime(wire.calls.getLast().body()).contains("To: "+alice.value()));}
    @Test void invalidReferencesCannotInjectMimeHeadersOrPaths(){assertThrows(IllegalArgumentException.class,()->client.send("thread123",account,content,"<x>\r\nBcc: other@example.test"));assertThrows(GmailFailure.class,()->client.message("../profile"));assertTrue(wire.calls.isEmpty());}
    @Test void readsAllDraftPagesUsingStableIds(){var m=message();var draft=new JsonObject();draft.addProperty("id","remote123");draft.add("message",m);wire.drafts.put("remote123",draft);assertEquals("remote123",client.drafts().getFirst().remoteDraftId());}
}
