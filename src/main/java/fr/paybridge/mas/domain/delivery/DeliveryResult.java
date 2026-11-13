package fr.paybridge.mas.domain.delivery;

import java.util.Objects;
import java.util.Optional;

/** FAILED signifie que le transport garantit l'absence de livraison ; sinon UNKNOWN. */
public record DeliveryResult(DeliveryStatus status, Optional<String> transportReference, String code) {
    public DeliveryResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(transportReference);
        if (status == DeliveryStatus.PENDING || code == null || !code.matches("[A-Z0-9_]{1,80}")
                || transportReference.filter(s -> s.isBlank() || s.contains("\r") || s.contains("\n")).isPresent()
                || ((status == DeliveryStatus.SIMULATED || status == DeliveryStatus.CONFIRMED) && transportReference.isEmpty())) {
            throw new IllegalArgumentException("Résultat de transport invalide");
        }
    }
}
