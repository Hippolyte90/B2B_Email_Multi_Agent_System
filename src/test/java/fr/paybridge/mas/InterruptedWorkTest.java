package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.InMemoryGenerationJournal;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.application.usecase.RecoverInterruptedWorkUseCase;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InterruptedWorkTest {
    private final DraftTestFixture f = new DraftTestFixture();
    private final InMemoryGenerationJournal journal = new InMemoryGenerationJournal();
    private final GenerationSettings settings = new GenerationSettings("test-model", 8192, 1024, .2, 42, Duration.ofSeconds(5));
    private GenerationRecord task(UUID id) { return GenerationRecord.running(id, f.decisions.find(f.routeId).orElseThrow(), f.specialist, settings, f.clock.instant()); }
    private RecoverInterruptedWorkUseCase recover() { return new RecoverInterruptedWorkUseCase(f.drafts, f.conversations, journal, TransactionRunner.direct(), f.clock); }
    @Test void interruptedTaskHasNoSuccessfulResult() {
        var task = task(UUID.randomUUID()); journal.start(task);
        assertEquals(1, recover().execute().generations());
        var record = journal.find(task.taskId(), f.scope).orElseThrow(); assertEquals(GenerationRecord.State.INTERRUPTED, record.state()); assertTrue(record.result().isEmpty());
        assertEquals(new RecoverInterruptedWorkUseCase.Summary(0, 0, 0), recover().execute());
    }
    @Test void journalFinishesOnlyOnceWithCorrelatedResult() {
        var result = f.generate(); var task = task(result.taskId()); journal.start(task); journal.finish(task.taskId(), result, f.clock.instant());
        assertEquals(GenerationRecord.State.COMPLETED, journal.find(task.taskId(), f.scope).orElseThrow().state());
        assertThrows(IllegalArgumentException.class, () -> journal.interrupt(task.taskId(), f.clock.instant()));
    }
    @Test void journalRejectsDuplicateAndForeignTaskResult() {
        var task = task(UUID.randomUUID()); journal.start(task);
        assertThrows(IllegalArgumentException.class, () -> journal.start(task));
        assertThrows(IllegalArgumentException.class, () -> journal.finish(task.taskId(), f.generate(), f.clock.instant()));
        assertTrue(journal.find(task.taskId(), ClientScope.client("other")).isEmpty()); assertTrue(journal.find(UUID.randomUUID(), f.scope).isEmpty());
    }
    @Test void pendingRegenerationIsClosedAndRejectedTextRetained() {
        var draft = f.create(); var rejected = f.reject(draft, Optional.of("Corriger"));
        var pending = rejected.reserveRegeneration(1, UUID.randomUUID(), f.clock.instant()); f.drafts.replace(rejected, pending);
        assertEquals(1, recover().execute().regenerations());
        var restored = f.drafts.find(draft.id()).orElseThrow(); assertTrue(restored.pendingRegeneration().isEmpty()); assertEquals(pending.current(), restored.current()); assertTrue(restored.approval().isEmpty());
    }
    @Test void pendingSendBecomesUnknownAndNeverConfirmed() {
        var draft = f.create(); var approved = f.service.approve(draft.id(), f.scope, draft.revision(), 1);
        var pending = approved.reserveSend(1, UUID.randomUUID(), new EmailAddress("support@paybridge.example"), TransportMode.SIMULATED, false, f.clock.instant()); f.drafts.replace(approved, pending);
        assertEquals(1, recover().execute().deliveries());
        var restored = f.drafts.find(draft.id()).orElseThrow(); assertEquals(DeliveryStatus.UNKNOWN, restored.sendAttempts().getFirst().status());
        assertEquals(approved.approval(), restored.approval()); assertTrue(f.conversations.findById("thread").orElseThrow().confirmedSent().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> restored.reserveSend(1, UUID.randomUUID(), new EmailAddress("support@paybridge.example"), TransportMode.SIMULATED, true, f.clock.instant()));
    }
    @Test void completedAttemptIsUnchangedByRecovery() {
        var draft = f.create(); var approved = f.service.approve(draft.id(), f.scope, draft.revision(), 1); var attempt = UUID.randomUUID();
        var pending = approved.reserveSend(1, attempt, new EmailAddress("support@paybridge.example"), TransportMode.SIMULATED, false, f.clock.instant()); f.drafts.replace(approved, pending);
        var done = pending.completeSend(attempt, new DeliveryResult(DeliveryStatus.SIMULATED, Optional.of("simulated"), "TEST"), f.clock.instant()); f.drafts.replace(pending, done);
        assertEquals(0, recover().execute().deliveries()); assertSame(done, f.drafts.find(draft.id()).orElseThrow());
    }
}
