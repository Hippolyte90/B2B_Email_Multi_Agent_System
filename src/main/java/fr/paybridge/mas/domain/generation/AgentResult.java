package fr.paybridge.mas.domain.generation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AgentResult(UUID taskId, String agentId, Status status, Optional<GeneratedReply> reply,
                           Optional<GenerationFailure> failure, List<Step> trace, Optional<GenerationReceipt> receipt) {
    public enum Status { COMPLETED, FAILED, CANCELLED, SUPERSEDED }
    public enum Step { ROUTER_RECEIVED, CONTEXT_PREPARED, SPECIALIST_DELEGATED, LLM_RETURNED, ROUTER_RETURNED }

    public AgentResult {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(reply);
        Objects.requireNonNull(failure);
        Objects.requireNonNull(receipt);
        trace = List.copyOf(trace);
        if (agentId == null || agentId.isBlank() || (status == Status.COMPLETED) != reply.isPresent()
                || (status == Status.COMPLETED) == failure.isPresent()
                || (status == Status.COMPLETED) != receipt.isPresent()
                || receipt.filter(r -> !r.agent().id().equals(agentId)).isPresent()
                || reply.filter(r -> !r.taskId().equals(taskId)).isPresent()) {
            throw new IllegalArgumentException("Résultat interagents incohérent");
        }
    }
}
