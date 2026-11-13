package fr.paybridge.mas.application.context;

import fr.paybridge.mas.domain.context.ContextRequest;
import fr.paybridge.mas.domain.context.MemoryEntry;

import java.util.Comparator;
import java.util.List;

public final class RecentConversationMemoryPolicy implements ConversationMemoryPolicy {
    private final int limit;

    public RecentConversationMemoryPolicy(int limit) {
        if (limit < 0) { throw new IllegalArgumentException("Limite de mémoire négative"); }
        this.limit = limit;
    }

    @Override
    public List<MemoryEntry> select(ContextRequest request, List<MemoryEntry> history) {
        var sorted = history.stream().sorted(Comparator.comparing(MemoryEntry::occurredAt).thenComparing(MemoryEntry::id)).toList();
        return sorted.subList(Math.max(0, sorted.size() - limit), sorted.size());
    }
}
