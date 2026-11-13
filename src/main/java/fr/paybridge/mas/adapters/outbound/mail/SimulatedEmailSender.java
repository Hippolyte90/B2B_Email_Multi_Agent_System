package fr.paybridge.mas.adapters.outbound.mail;

import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.domain.delivery.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Boîte de sortie locale en mémoire : aucun réseau, aucun fournisseur SMTP. */
public final class SimulatedEmailSender implements EmailSender {
    private final Map<UUID, ApprovedEmail> outbox = new LinkedHashMap<>();

    @Override public TransportMode mode() { return TransportMode.SIMULATED; }

    @Override public synchronized DeliveryResult send(ApprovedEmail email) {
        var previous = outbox.putIfAbsent(email.attemptId(), email);
        if (previous != null && !previous.equals(email)) {
            throw new IllegalArgumentException("Identifiant de tentative réutilisé pour une autre enveloppe");
        }
        return new DeliveryResult(DeliveryStatus.SIMULATED, Optional.of("simulated:" + email.attemptId()), "LOCAL_OUTBOX_ONLY");
    }

    public synchronized List<ApprovedEmail> entries() { return List.copyOf(outbox.values()); }
}
