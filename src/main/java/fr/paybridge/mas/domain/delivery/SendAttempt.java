package fr.paybridge.mas.domain.delivery;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SendAttempt(ApprovedEmail email, TransportMode mode, DeliveryStatus status, Instant startedAt,
                           Optional<Instant> completedAt, Optional<DeliveryResult> result) {
    public SendAttempt {
        Objects.requireNonNull(email);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(completedAt);
        Objects.requireNonNull(result);
        if ((status == DeliveryStatus.PENDING) == completedAt.isPresent()
                || (status == DeliveryStatus.PENDING) == result.isPresent()
                || result.filter(r -> r.status() != status).isPresent()
                || (mode == TransportMode.SIMULATED && status == DeliveryStatus.CONFIRMED)
                || (mode == TransportMode.REAL && status == DeliveryStatus.SIMULATED)) {
            throw new IllegalArgumentException("État de tentative incohérent");
        }
    }

    public static SendAttempt pending(ApprovedEmail email, TransportMode mode, Instant at) {
        return new SendAttempt(email, mode, DeliveryStatus.PENDING, at, Optional.empty(), Optional.empty());
    }

    public SendAttempt complete(DeliveryResult outcome, Instant at) {
        if (status != DeliveryStatus.PENDING) { throw new IllegalArgumentException("Tentative déjà terminée"); }
        return new SendAttempt(email, mode, outcome.status(), startedAt, Optional.of(at), Optional.of(outcome));
    }
}
