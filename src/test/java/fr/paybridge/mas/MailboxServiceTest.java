package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.adapters.inbound.presentation.MailboxState;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.workspace.MailboxWorkspace.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MailboxServiceTest {
    final DraftTestFixture f = new DraftTestFixture();
    final InMemoryMailboxFlagsRepository flags = new InMemoryMailboxFlagsRepository();
    final MailboxService mailbox = new MailboxService(() -> List.of(f.conversations.findMessage("message").orElseThrow()), f.drafts, flags);
    List<Entry> folder(Folder folder) { return new MailboxState(mailbox.entries(), folder, "", Optional.empty(), false, "").visible(); }
    Draft attempt(DeliveryStatus status) {
        var original = f.create(); var approved = original.approve(1, f.clock.instant()); f.drafts.replace(original, approved);
        var id = UUID.randomUUID(); var pending = approved.reserveSend(1,id,new EmailAddress("support@paybridge.example"),status == DeliveryStatus.SIMULATED ? TransportMode.SIMULATED : TransportMode.REAL,false,f.clock.instant()); f.drafts.replace(approved,pending);
        if (status == DeliveryStatus.PENDING) { return pending; }
        var sent = pending.completeSend(id,new DeliveryResult(status,status == DeliveryStatus.SIMULATED || status == DeliveryStatus.CONFIRMED ? Optional.of("fake-reference") : Optional.empty(),"TEST"),f.clock.instant()); f.drafts.replace(pending,sent); return sent;
    }
    @Test void listingNeverRoutesGeneratesOrSends() { assertEquals(1,folder(Folder.INBOX).size()); assertTrue(f.requests.isEmpty()); assertTrue(f.drafts.findByMessage("message").isEmpty()); }
    @Test void spamTrashAndArchiveAreExclusiveAndReversible() {
        for (var location : List.of(MailboxFlags.Location.SPAM,MailboxFlags.Location.TRASH,MailboxFlags.Location.ARCHIVE)) {
            var before=flags.find("message");mailbox.update(before,location,false,false);assertTrue(folder(Folder.INBOX).isEmpty());
            assertEquals(1,folder(Folder.valueOf(location.name())).size());
        }
        mailbox.update(flags.find("message"),MailboxFlags.Location.INBOX,true,false);assertEquals(1,folder(Folder.INBOX).size());
    }
    @Test void spamAndTrashAreExcludedFromAllAndFavorites() {
        mailbox.update(flags.find("message"),MailboxFlags.Location.SPAM,false,true);assertTrue(folder(Folder.ALL).isEmpty());assertTrue(folder(Folder.STARRED).isEmpty());
        mailbox.update(flags.find("message"),MailboxFlags.Location.ARCHIVE,true,true);assertEquals(1,folder(Folder.ALL).size());assertEquals(1,folder(Folder.STARRED).size());
    }
    @Test void staleUpdateCannotOverwriteNewFlags() {
        var original=flags.find("message");mailbox.update(original,MailboxFlags.Location.ARCHIVE,true,true);
        assertThrows(IllegalArgumentException.class,()->mailbox.update(original,MailboxFlags.Location.INBOX,false,false)); assertTrue(flags.find("message").starred());
    }
    @Test void noOpDoesNotAdvanceRevisionAndInitialFlagsAreValidated() {
        mailbox.update(flags.find("message"),MailboxFlags.Location.INBOX,false,false);assertEquals(0,flags.find("message").revision());
        assertThrows(IllegalArgumentException.class,()->new MailboxFlags("message",0,MailboxFlags.Location.SPAM,false,false));
    }
    @Test void foreignMessageCannotBeModifiedOrListed() {
        assertThrows(IllegalArgumentException.class,()->mailbox.update(MailboxFlags.initial("foreign"),MailboxFlags.Location.SPAM,false,false));
        var empty=new MailboxService(List::of,f.drafts,flags);f.create();assertTrue(empty.entries().isEmpty());
    }
    @Test void currentEditedDraftIsVisibleWithExactRecipientsAndText() {
        var original=f.create();var edited=f.service.edit(original.id(),f.scope,original.revision(),1,new DraftContent("Objet humain","Texte humain",List.of(f.alice)));
        var entry=folder(Folder.DRAFTS).getFirst();assertEquals(edited.current().content().body(),entry.body());assertEquals(List.of(f.alice),entry.recipients());
    }
    @Test void simulationNeverAppearsInSentAndDoesNotRemainCurrentDraft() {
        attempt(DeliveryStatus.SIMULATED);assertEquals(1,folder(Folder.SIMULATED).size());assertTrue(folder(Folder.SENT).isEmpty());assertTrue(folder(Folder.DRAFTS).isEmpty());
    }
    @Test void confirmedSendIsAnImmutableSnapshotWhileNextEditIsADraft() {
        var sent=attempt(DeliveryStatus.CONFIRMED);var text=sent.current().content().body();
        var edited=sent.edit(1,new DraftContent("Autre objet","Autre texte",List.of(f.alice)),f.clock.instant());f.drafts.replace(sent,edited);
        assertEquals(text,folder(Folder.SENT).getFirst().body());assertEquals("Autre texte",folder(Folder.DRAFTS).getFirst().body());
    }
    @Test void uncertainOrPendingDeliveryIsOutboxAndNeverSentOrEditableDraft() {
        attempt(DeliveryStatus.UNKNOWN);assertEquals(1,folder(Folder.OUTBOX).size());assertTrue(folder(Folder.SENT).isEmpty());assertTrue(folder(Folder.DRAFTS).isEmpty());
    }
    @Test void failedDeliveryRemainsAvailableForExplicitCorrection() { attempt(DeliveryStatus.FAILED);assertEquals(1,folder(Folder.OUTBOX).size());assertEquals(1,folder(Folder.DRAFTS).size()); }
    @Test void searchCombinesTermsIgnoringCaseAndAccents() {
        var state=new MailboxState(mailbox.entries(),Folder.INBOX,"INTEGRATION alice",Optional.empty(),false,"");assertEquals(1,state.visible().size());
        assertTrue(new MailboxState(mailbox.entries(),Folder.INBOX,"absent",Optional.empty(),false,"").visible().isEmpty());
    }
    @Test void spamBlocksGenerationAndTransmissionEvenThroughApplicationApi() {
        var backend=new ReviewWorkspaceServiceTest();backend.workspace.withMailboxFlags(flags);var draft=backend.generate();
        mailbox.update(flags.find("message"),MailboxFlags.Location.SPAM,false,false);
        assertThrows(IllegalArgumentException.class,()->backend.workspace.approveAndSend(draft));
        assertThrows(IllegalArgumentException.class,()->backend.workspace.regenerate(draft,Optional.empty()));assertTrue(backend.sender.entries().isEmpty());
    }
}
