package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.util.*;

/** Boîte simulée durable, sans réseau ; ne retourne jamais CONFIRMED. */
public final class SqliteSimulatedEmailSender implements EmailSender {
    private final SqliteStore store;
    SqliteSimulatedEmailSender(SqliteStore store) { this.store = store; }
    @Override public TransportMode mode() { return TransportMode.SIMULATED; }
    @Override public DeliveryResult send(ApprovedEmail email) {
        return store.inTransaction(() -> {
            var existing = store.payload("SELECT payload FROM simulated_outbox WHERE attempt_id=?", email.attemptId().toString()).map(payload -> store.decode(payload, ApprovedEmail.class));
            if (existing.isPresent()) {
                if (!existing.get().equals(email)) { throw new IllegalArgumentException("Identifiant de tentative réutilisé"); }
            } else {
                var draft = store.drafts().find(email.draftId()).orElseThrow();
                if (draft.sendAttempts().stream().noneMatch(a -> a.email().equals(email) && a.mode() == TransportMode.SIMULATED && a.status() == DeliveryStatus.PENDING)) {
                    throw new IllegalArgumentException("Enveloppe sans réservation approuvée");
                }
                store.write("INSERT INTO simulated_outbox(attempt_id,draft_id,message_id,payload) VALUES(?,?,?,?)", email.attemptId().toString(), email.draftId().toString(), email.messageId(), store.encode(email));
                store.audit("OUTBOX_SIMULATED", email.attemptId().toString(), 1);
            }
            return new DeliveryResult(DeliveryStatus.SIMULATED, Optional.of("simulated:" + email.attemptId()), "LOCAL_OUTBOX_ONLY");
        });
    }
    public List<ApprovedEmail> entries(ClientScope scope) {
        return store.inTransaction(() -> {
            var entries = new ArrayList<ApprovedEmail>();
            for (var id : store.payloads("SELECT attempt_id FROM simulated_outbox ORDER BY rowid")) {
                var messageId = store.payload("SELECT message_id FROM simulated_outbox WHERE attempt_id=?", id).orElseThrow();
                if (store.conversations().findMessage(messageId).filter(message -> message.scope().equals(scope)).isEmpty()) { continue; }
                var email = store.decode(store.payload("SELECT payload FROM simulated_outbox WHERE attempt_id=?", id).orElseThrow(), ApprovedEmail.class);
                if (!email.scope().equals(scope) || !email.attemptId().toString().equals(id) || !email.messageId().equals(messageId)) {
                    throw new fr.paybridge.mas.application.port.PersistenceFailure(fr.paybridge.mas.application.port.PersistenceFailure.Code.CORRUPT_DATA);
                }
                entries.add(email);
            }
            return List.copyOf(entries);
        });
    }
}
