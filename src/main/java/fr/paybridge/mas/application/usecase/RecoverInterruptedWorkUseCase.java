package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.draft.DraftReview;
import java.time.Clock;
import java.util.Optional;

/** À appeler une fois après acquisition d'une session exclusive, sans transport ni modèle. */
public final class RecoverInterruptedWorkUseCase {
    private final DraftRepository drafts;
    private final ConversationRepository conversations;
    private final GenerationJournal journal;
    private final TransactionRunner transactions;
    private final Clock clock;
    public RecoverInterruptedWorkUseCase(DraftRepository drafts, ConversationRepository conversations, GenerationJournal journal, TransactionRunner transactions, Clock clock) {
        this.drafts = drafts; this.conversations = conversations; this.journal = journal; this.transactions = transactions; this.clock = clock;
    }
    public record Summary(int generations, int regenerations, int deliveries) { }
    public Summary execute() {
        return transactions.inTransaction(() -> {
            int tasks = 0, regenerations = 0, deliveries = 0;
            for (var task : journal.running()) { journal.interrupt(task.taskId(), clock.instant()); tasks++; }
            for (var initial : drafts.unfinished()) {
                var current = initial;
                if (current.pendingRegeneration().isPresent()) {
                    var updated = current.failRegeneration(current.pendingRegeneration().orElseThrow(), DraftReview.Type.REGENERATION_FAILED, "PROCESS_INTERRUPTED", clock.instant());
                    drafts.replace(current, updated); current = updated; regenerations++;
                }
                for (var pending : initial.sendAttempts()) {
                    if (pending.status() != DeliveryStatus.PENDING) { continue; }
                    var updated = current.completeSend(pending.email().attemptId(), new DeliveryResult(DeliveryStatus.UNKNOWN, Optional.empty(), "PROCESS_INTERRUPTED"), clock.instant());
                    var completed = updated.lastAttempt(pending.email().approval().version()).orElseThrow();
                    var conversation = conversations.findById(current.conversationId()).orElseThrow().recordDelivery(completed);
                    drafts.replace(current, updated); conversations.save(conversation); current = updated; deliveries++;
                }
            }
            return new Summary(tasks, regenerations, deliveries);
        });
    }
}
