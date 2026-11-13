package fr.paybridge.mas.domain.generation;

import java.time.Duration;
import java.util.Objects;

public record GenerationSettings(String model, int contextCapacity, int outputLimit, double temperature,
                                  long seed, Duration timeout) {
    public GenerationSettings {
        if (model == null || model.isBlank() || contextCapacity < 1 || outputLimit < 1 || outputLimit >= contextCapacity
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Paramètres de génération invalides");
        }
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Le délai doit être positif et au plus dix minutes");
        }
    }
}
