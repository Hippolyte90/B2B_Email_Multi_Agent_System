package fr.paybridge.mas.domain.context;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ContextBundle(String systemInstructions, String userData, List<MemoryEntry> history,
                            List<ContextExcerpt> excerpts, Optional<String> userFeedback,
                            List<ContextExclusion> exclusions, Usage usage) {
    public record Usage(String counter, boolean estimated, long inputTokens, int outputReserve, int capacity) {
        public Usage {
            if (counter == null || counter.isBlank() || inputTokens < 0 || outputReserve < 1
                    || inputTokens > (long) capacity - outputReserve) {
                throw new IllegalArgumentException("Usage dépassant le budget");
            }
        }
    }

    public ContextBundle {
        Objects.requireNonNull(systemInstructions);
        Objects.requireNonNull(userData);
        history = List.copyOf(history);
        excerpts = List.copyOf(excerpts);
        Objects.requireNonNull(userFeedback);
        exclusions = List.copyOf(exclusions);
        Objects.requireNonNull(usage);
    }
}
