package fr.paybridge.mas;

import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.mail.EmailAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DraftTest {
    @Test
    void createsImmutableInitialVersionWithActualGenerationEvidence() {
        var fixture = new DraftTestFixture();
        var draft = fixture.create();
        assertEquals(Draft.State.GENERATED, draft.state());
        assertEquals(1, draft.current().number());
        assertEquals("Re: Question SDK", draft.current().content().subject());
        assertEquals("Tech", draft.current().receipt().agent().id());
        assertEquals(fixture.routeId, draft.current().receipt().routing().id());
        assertThrows(UnsupportedOperationException.class, () -> draft.versions().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.reviews().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.current().content().recipients().clear());
    }

    @Test
    void approvalBindsExactContentSubjectRecipientsAndVersion() {
        var fixture = new DraftTestFixture();
        var original = fixture.create();
        var approved = original.approve(1, fixture.clock.instant());
        var approval = approved.approval().orElseThrow();
        assertEquals(original.current().content(), approval.content());
        assertEquals(1, approval.version());
        assertTrue(original.approval().isEmpty());
        assertSame(approved, approved.approve(1, fixture.clock.instant()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "subject", "recipients"})
    void anyEffectiveContentChangeInvalidatesApprovalButRetainsHistory(String field) {
        var fixture = new DraftTestFixture();
        var approved = fixture.create().approve(1, fixture.clock.instant());
        var content = approved.current().content();
        var updatedContent = new DraftContent(field.equals("subject") ? "Nouvel objet" : content.subject(),
                field.equals("body") ? "Texte corrigé" : content.body(), field.equals("recipients") ? List.of(new EmailAddress("other@client-a.example")) : content.recipients());
        var updated = approved.edit(1, updatedContent, fixture.clock.instant());
        assertEquals(2, updated.current().number());
        assertEquals(Draft.State.EDITED, updated.state());
        assertTrue(updated.approval().isEmpty());
        assertEquals(content, updated.approvals().getFirst().content());
        assertEquals(1, updated.approvals().getFirst().version());
        assertEquals(content, updated.versions().getFirst().content());
    }

    @Test
    void unchangedSaveIsIdempotentAndKeepsApproval() {
        var fixture = new DraftTestFixture();
        var approved = fixture.create().approve(1, fixture.clock.instant());
        assertSame(approved, approved.edit(1, approved.current().content(), fixture.clock.instant()));
    }

    @Test
    void oldVersionCannotBeApprovedOrEdited() {
        var fixture = new DraftTestFixture();
        var original = fixture.create();
        var updated = original.edit(1, new DraftContent("Objet", "Correction", original.current().content().recipients()), fixture.clock.instant());
        assertThrows(IllegalArgumentException.class, () -> updated.approve(1, fixture.clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> updated.edit(1, updated.current().content(), fixture.clock.instant()));
    }

    @Test
    void rejectionPreservesVersionAndFeedbackWithoutAllowingApprovalOrEditing() {
        var fixture = new DraftTestFixture();
        var original = fixture.create();
        var rejected = original.reject(1, Optional.of("  Demandez la version du SDK.  "), fixture.clock.instant());
        assertEquals(1, rejected.versions().size());
        assertEquals(original.current(), rejected.current());
        assertEquals(Optional.of("Demandez la version du SDK."), rejected.rejectionComment());
        assertThrows(IllegalArgumentException.class, () -> rejected.approve(1, fixture.clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> rejected.edit(1, rejected.current().content(), fixture.clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> rejected.reject(1, Optional.empty(), fixture.clock.instant()));
    }

    @Test
    void illegalTransitionsAndDuplicatePendingAttemptAreRejected() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        assertThrows(IllegalArgumentException.class, () -> initial.reserveRegeneration(1, UUID.randomUUID(), fixture.clock.instant()));
        var approved = initial.approve(1, fixture.clock.instant());
        assertThrows(IllegalArgumentException.class, () -> approved.reject(1, Optional.empty(), fixture.clock.instant()));
        var pending = initial.reject(1, Optional.empty(), fixture.clock.instant()).reserveRegeneration(1, UUID.randomUUID(), fixture.clock.instant());
        assertThrows(IllegalArgumentException.class, () -> pending.reserveRegeneration(1, UUID.randomUUID(), fixture.clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> pending.approve(1, fixture.clock.instant()));
        assertThrows(IllegalArgumentException.class, () -> pending.completeRegeneration(UUID.randomUUID(), fixture.generate(), fixture.clock.instant()));
    }

    @Test
    void recipientsAreDefensivelyCopiedAndHeaderInjectionIsRejected() {
        var recipients = new ArrayList<>(List.of(new EmailAddress("alice@example.com")));
        var content = new DraftContent("Sujet", "Texte", recipients);
        recipients.clear();
        assertEquals(1, content.recipients().size());
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet\r\nBcc: other@example.com", "Texte", content.recipients()));
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet", " ", content.recipients()));
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet", "Texte", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DraftContent("Sujet", "Texte", List.of(content.recipients().getFirst(), content.recipients().getFirst())));
    }
}
