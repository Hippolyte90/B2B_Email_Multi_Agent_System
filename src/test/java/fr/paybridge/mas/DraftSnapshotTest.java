package fr.paybridge.mas;

import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftSnapshotTest {
    private final DraftTestFixture f = new DraftTestFixture();
    private Draft roundTrip(Draft draft) { var copy = Draft.restore(draft.snapshot()); assertEquals(draft.snapshot(), copy.snapshot()); return copy; }
    @ParameterizedTest @EnumSource(Draft.State.class)
    void restoresReviewStatesExactly(Draft.State state) {
        var draft = f.create();
        draft = switch (state) {
            case GENERATED -> draft;
            case APPROVED -> draft.approve(1, f.clock.instant());
            case REJECTED -> draft.reject(1, Optional.of("Retour humain"), f.clock.instant());
            case EDITED -> draft.edit(1, new DraftContent("Objet revu", "Texte revu", draft.current().content().recipients()), f.clock.instant());
        };
        assertEquals(state, roundTrip(draft).state());
    }
    @ParameterizedTest @EnumSource(DeliveryStatus.class)
    void restoresAllDeliveryStatesWithoutPromotingThem(DeliveryStatus status) {
        var draft = f.create().approve(1, f.clock.instant()); var id = UUID.randomUUID();
        draft = draft.reserveSend(1, id, new EmailAddress("support@paybridge.example"), status == DeliveryStatus.CONFIRMED ? TransportMode.REAL : TransportMode.SIMULATED, false, f.clock.instant());
        if (status != DeliveryStatus.PENDING) { draft = draft.completeSend(id, new DeliveryResult(status, status == DeliveryStatus.CONFIRMED || status == DeliveryStatus.SIMULATED ? Optional.of("reference") : Optional.empty(), "TEST"), f.clock.instant()); }
        assertEquals(status, roundTrip(draft).sendAttempts().getFirst().status());
    }
    @Test void retainsOldApprovalsButInvalidatesApprovalAfterEdit() {
        var draft = f.create().approve(1, f.clock.instant());
        draft = draft.edit(1, new DraftContent("Revu", "Contenu modifié", draft.current().content().recipients()), f.clock.instant());
        var copy = roundTrip(draft); assertTrue(copy.approval().isEmpty()); assertEquals(1, copy.approvals().size());
    }
    @Test void restoresPendingRegenerationAndRejectedComment() {
        var draft = f.create().reject(1, Optional.of("Restez en français"), f.clock.instant()).reserveRegeneration(1, UUID.randomUUID(), f.clock.instant());
        var copy = roundTrip(draft); assertTrue(copy.pendingRegeneration().isPresent()); assertEquals(Optional.of("Restez en français"), copy.rejectionComment());
    }
    @Test void restoresSuccessfulRegenerationAndPreviousRejectedVersion() {
        var attempt = UUID.randomUUID(); var draft = f.create().reject(1, Optional.of("SDK"), f.clock.instant()).reserveRegeneration(1, attempt, f.clock.instant());
        draft = draft.completeRegeneration(attempt, f.generate(), f.clock.instant());
        var copy = roundTrip(draft); assertEquals(2, copy.versions().size()); assertEquals(Draft.State.GENERATED, copy.state()); assertTrue(copy.approval().isEmpty());
    }
    @ParameterizedTest @EnumSource(value = DraftReview.Type.class, names = {"REGENERATION_FAILED", "REGENERATION_CANCELLED", "REGENERATION_SUPERSEDED"})
    void restoresFailedRegenerationWithoutInventingANewVersion(DraftReview.Type type) {
        var attempt = UUID.randomUUID(); var draft = f.create().reject(1, Optional.empty(), f.clock.instant()).reserveRegeneration(1, attempt, f.clock.instant());
        var copy = roundTrip(draft.failRegeneration(attempt, type, "PROCESS_INTERRUPTED", f.clock.instant()));
        assertEquals(1, copy.versions().size()); assertTrue(copy.pendingRegeneration().isEmpty());
    }
    @Test void refusesInventedApprovalState() {
        var s = f.create().snapshot();
        assertThrows(IllegalArgumentException.class, () -> Draft.restore(new Draft.Snapshot(s.id(), s.messageId(), s.conversationId(), s.scope(), s.versions(), s.reviews(), Draft.State.APPROVED, s.revision(), s.pendingRegeneration(), s.sendAttempts())));
    }
    @Test void refusesForeignScopeInSnapshot() {
        var s = f.create().snapshot();
        assertThrows(IllegalArgumentException.class, () -> Draft.restore(new Draft.Snapshot(s.id(), s.messageId(), s.conversationId(), ClientScope.client("other"), s.versions(), s.reviews(), s.state(), s.revision(), s.pendingRegeneration(), s.sendAttempts())));
    }
    @Test void rejectsMissingReviewAndRevisionMismatch() {
        var s = f.create().approve(1, f.clock.instant()).snapshot();
        assertThrows(IllegalArgumentException.class, () -> new Draft.Snapshot(s.id(), s.messageId(), s.conversationId(), s.scope(), s.versions(), s.reviews(), s.state(), 1, s.pendingRegeneration(), s.sendAttempts()));
    }
    @Test void snapshotDefensivelyCopiesVersions() {
        var s = f.create().snapshot(); var versions = new ArrayList<>(s.versions());
        var copy = new Draft.Snapshot(s.id(), s.messageId(), s.conversationId(), s.scope(), versions, s.reviews(), s.state(), s.revision(), s.pendingRegeneration(), s.sendAttempts());
        versions.clear(); assertEquals(1, copy.versions().size()); assertThrows(UnsupportedOperationException.class, () -> copy.versions().clear());
    }
}
