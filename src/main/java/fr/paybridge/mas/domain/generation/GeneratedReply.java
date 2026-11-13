package fr.paybridge.mas.domain.generation;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** Proposition non approuvée ; une réponse du modèle n'est pas une preuve d'exactitude. */
public record GeneratedReply(UUID taskId, String text, String model, boolean simulated,
                              Duration elapsed, OptionalLong inputTokens, OptionalLong outputTokens) {
    public GeneratedReply {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(elapsed);
        Objects.requireNonNull(inputTokens);
        Objects.requireNonNull(outputTokens);
        if (text == null || text.isBlank() || model == null || model.isBlank() || elapsed.isNegative()
                || (inputTokens.isPresent() && inputTokens.getAsLong() < 0) || (outputTokens.isPresent() && outputTokens.getAsLong() < 0)) {
            throw new IllegalArgumentException("Proposition de génération invalide");
        }
    }
}
