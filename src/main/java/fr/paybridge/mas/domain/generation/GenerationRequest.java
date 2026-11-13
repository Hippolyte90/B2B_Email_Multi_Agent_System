package fr.paybridge.mas.domain.generation;

import fr.paybridge.mas.domain.context.ContextBundle;

import java.util.Objects;
import java.util.UUID;

public record GenerationRequest(UUID taskId, ContextBundle context, GenerationSettings settings) {
    public GenerationRequest {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(context);
        Objects.requireNonNull(settings);
        if (context.usage().capacity() != settings.contextCapacity() || context.usage().outputReserve() < settings.outputLimit()) {
            throw new IllegalArgumentException("Budget de contexte incompatible avec la génération");
        }
    }
}
