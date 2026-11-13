package fr.paybridge.mas.domain.context;

public record ContextBudget(int capacity, int outputReserve, int framingOverhead) {
    public ContextBudget {
        if (capacity < 1 || outputReserve < 1 || framingOverhead < 0
                || (long) outputReserve + framingOverhead >= capacity) {
            throw new IllegalArgumentException("Budget de contexte invalide");
        }
    }
}
