package fr.paybridge.mas;

import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DraftReviewServiceTest {
    @Test
    void captureIsIdempotentAndDoesNotEraseUserEdits() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var edited = fixture.service.edit(initial.id(), fixture.scope, initial.revision(), 1,
                new DraftContent("Objet corrigé", "Texte corrigé", initial.current().content().recipients()));
        assertSame(edited, fixture.service.capture("message", fixture.scope, initial.current().generation().taskId()));
    }

    @Test
    void cannotCaptureUnsuccessfulOrSupersededGeneration() {
        var fixture = new DraftTestFixture();
        var previous = fixture.generate();
        fixture.generate();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.capture("message", fixture.scope, previous.taskId()));
        fixture.provider.set((r, c) -> { throw new GenerationException(GenerationFailure.UNAVAILABLE); });
        var failed = fixture.generate();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.capture("message", fixture.scope, failed.taskId()));
        assertTrue(fixture.drafts.findByMessage("message").isEmpty());
    }

    @Test
    void scopeAndStaleRevisionPreventUnauthorizedOrUnseenApproval() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.find(initial.id(), ClientScope.client("client-b")));
        var edited = fixture.service.edit(initial.id(), fixture.scope, initial.revision(), 1,
                new DraftContent("Objet", "Autre texte", initial.current().content().recipients()));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.approve(initial.id(), fixture.scope, initial.revision(), 1));
        assertThrows(IllegalArgumentException.class, () -> fixture.service.approve(initial.id(), fixture.scope, edited.revision(), 1));
        assertTrue(fixture.service.find(initial.id(), fixture.scope).approval().isEmpty());
    }

    @Test
    void reviewEventsAreRecordedWithoutAnySendEvent() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var approved = fixture.service.approve(initial.id(), fixture.scope, initial.revision(), 1);
        var edited = fixture.service.edit(initial.id(), fixture.scope, approved.revision(), 1,
                new DraftContent("Objet", "Correction", approved.current().content().recipients()));
        fixture.reject(edited, Optional.of("Préciser le SDK"));
        var types = fixture.conversations.findById("thread").orElseThrow().history().stream().map(e -> e.type().name()).toList();
        assertTrue(types.containsAll(List.of("RECEIVED", "GENERATED", "APPROVED", "EDITED", "REJECTED")));
        assertTrue(types.stream().noneMatch(t -> t.startsWith("SEND")));
    }

    @Test
    void rejectionRegeneratesWithExactCommentAndPreservesOldVersion() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        String comment = "Restez en français et demandez la version du SDK.";
        var outcome = fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.of(comment), fixture.allowed, fixture.budget).result().toCompletableFuture().join();
        assertTrue(outcome.newVersionAdded());
        var current = outcome.draft().orElseThrow();
        assertEquals(2, current.current().number());
        assertEquals(Draft.State.GENERATED, current.state());
        assertEquals(initial.current(), current.versions().getFirst());
        assertEquals(Optional.of(comment), fixture.requests.getLast().context().userFeedback());
        assertFalse(fixture.requests.getLast().context().userData().contains(initial.current().content().body()));
        assertTrue(current.approval().isEmpty());
        assertEquals(Optional.of(comment), current.reviews().stream().filter(r -> r.type() == DraftReview.Type.REJECTED).findFirst().orElseThrow().comment());
    }

    @Test
    void rejectionWithoutCommentRequestsANewVariant() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var outcome = fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.empty(), fixture.allowed, fixture.budget).result().toCompletableFuture().join();
        assertTrue(outcome.newVersionAdded());
        assertTrue(fixture.requests.getLast().context().userFeedback().orElseThrow().contains("nouvelle variante"));
        assertTrue(outcome.draft().orElseThrow().reviews().stream().filter(r -> r.type() == DraftReview.Type.REJECTED).findFirst().orElseThrow().comment().isEmpty());
    }

    @Test
    void generationFailureKeepsRejectedVersionAndAllowsRetry() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var successfulProvider = fixture.provider.get();
        fixture.provider.set((r, c) -> { throw new GenerationException(GenerationFailure.TIMEOUT); });
        var outcome = fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.of("Demander le SDK"), fixture.allowed, fixture.budget).result().toCompletableFuture().join();
        assertFalse(outcome.newVersionAdded());
        var retained = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.REJECTED, retained.state());
        assertEquals(1, retained.versions().size());
        assertEquals(initial.current(), retained.current());
        assertEquals(Optional.of("Demander le SDK"), retained.rejectionComment());
        assertTrue(retained.pendingRegeneration().isEmpty());
        fixture.provider.set(successfulProvider);
        assertTrue(fixture.service.regenerate(retained.id(), fixture.scope, retained.revision(), 1, fixture.allowed, fixture.budget)
                .result().toCompletableFuture().join().newVersionAdded());
    }

    @Test
    void oversizedFeedbackIsNotSilentlyIgnoredByTheGenerator() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        fixture.provider.set((r, c) -> fail("Le commentaire ne tient pas dans le budget"));
        var outcome = fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.of("x".repeat(20000)), fixture.allowed, fixture.budget).result().toCompletableFuture().join();
        assertFalse(outcome.newVersionAdded());
        assertEquals(Optional.of(GenerationFailure.CONTEXT_ERROR), outcome.generation().failure());
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void duplicateRegenerationIsBlockedAndCancellationPreservesReviewHistory() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var queue = new ArrayList<Runnable>();
        fixture.executor.set(queue::add);
        var pending = fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.of("Demander le SDK"), fixture.allowed, fixture.budget);
        var reserved = fixture.service.find(initial.id(), fixture.scope);
        assertThrows(IllegalArgumentException.class, () -> fixture.service.regenerate(initial.id(), fixture.scope, reserved.revision(), 1, fixture.allowed, fixture.budget));
        pending.cancel().run();
        assertEquals(AgentResult.Status.CANCELLED, pending.result().toCompletableFuture().join().generation().status());
        queue.getFirst().run();
        var retained = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.REJECTED, retained.state());
        assertTrue(retained.pendingRegeneration().isEmpty());
        assertEquals(DraftReview.Type.REGENERATION_CANCELLED, retained.reviews().getLast().type());
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void startFailureDoesNotLeaveAStuckPendingRegeneration() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        assertThrows(IllegalArgumentException.class, () -> fixture.service.rejectAndRegenerate(initial.id(), fixture.scope, initial.revision(), 1,
                Optional.empty(), java.util.Set.of("Generic"), fixture.budget));
        var retained = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.REJECTED, retained.state());
        assertTrue(retained.pendingRegeneration().isEmpty());
        assertEquals(DraftReview.Type.REGENERATION_FAILED, retained.reviews().getLast().type());
    }

    @Test
    void repositoryRejectsObsoleteUpdatesAndDuplicateMessageDrafts() {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var approved = fixture.service.approve(initial.id(), fixture.scope, initial.revision(), 1);
        assertThrows(IllegalArgumentException.class, () -> fixture.drafts.replace(initial, initial.reject(1, Optional.empty(), fixture.clock.instant())));
        assertThrows(IllegalArgumentException.class, () -> fixture.drafts.add(initial));
        assertSame(approved, fixture.drafts.find(initial.id()).orElseThrow());
    }
}
