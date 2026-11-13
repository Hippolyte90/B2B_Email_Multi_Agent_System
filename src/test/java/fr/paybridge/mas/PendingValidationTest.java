package fr.paybridge.mas;

import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryMailboxFlagsRepository;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingValidationTest {
    @Test void generatedAndEditedDraftsWaitForHumanValidation(){var backend=new ReviewWorkspaceServiceTest();var mailbox=new MailboxService(backend.workspace::inbox,backend.f.drafts,new InMemoryMailboxFlagsRepository());backend.generate();assertEquals(1,mailbox.entries().stream().filter(MailboxWorkspace.Entry::reviewRequired).count());assertTrue(backend.sender.entries().isEmpty());}
    @Test void validatedReplyLeavesPendingListAndIsNotSentTwice(){var backend=new ReviewWorkspaceServiceTest();var mailbox=new MailboxService(backend.workspace::inbox,backend.f.drafts,new InMemoryMailboxFlagsRepository());var draft=backend.generate();backend.workspace.approveAndSend(draft);assertTrue(mailbox.entries().stream().noneMatch(MailboxWorkspace.Entry::reviewRequired));assertEquals(1,backend.sender.entries().size());}
    @Test void rejectedDraftDoesNotAppearAsReadyForValidation(){var f=new DraftTestFixture();var draft=f.create();var rejected=draft.reject(1,java.util.Optional.of("Revoir"),f.clock.instant());f.drafts.replace(draft,rejected);var mailbox=new MailboxService(()->java.util.List.of(f.conversations.findMessage("message").orElseThrow()),f.drafts,new InMemoryMailboxFlagsRepository());assertTrue(mailbox.entries().stream().noneMatch(MailboxWorkspace.Entry::reviewRequired));}
}
