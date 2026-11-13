package fr.paybridge.mas.application.delivery;

import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.DraftRepository;
import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Garde applicative de transmission, indépendante de l'activation des commandes de l'interface. */
public final class SendApprovedReplyUseCase {
    private final DraftRepository drafts;
    private final ConversationRepository conversations;
    private final EmailSender sender;
    private final EmailAddress from;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final TransactionRunner transactions;

    public SendApprovedReplyUseCase(DraftRepository drafts, ConversationRepository conversations, EmailSender sender,
                                     EmailAddress from, Clock clock, Supplier<UUID> identifiers) {
        this(drafts, conversations, sender, from, clock, identifiers, TransactionRunner.direct());
    }

    public SendApprovedReplyUseCase(DraftRepository drafts, ConversationRepository conversations, EmailSender sender,
                                     EmailAddress from, Clock clock, Supplier<UUID> identifiers, TransactionRunner transactions) {
        this.drafts = Objects.requireNonNull(drafts);
        this.conversations = Objects.requireNonNull(conversations);
        this.sender = Objects.requireNonNull(sender);
        this.from = Objects.requireNonNull(from);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public TransportMode mode() { return sender.mode(); }

    public SendAttempt send(UUID id, ClientScope scope, long expectedRevision, int expectedVersion) {
        return execute(id, scope, expectedRevision, expectedVersion, false);
    }

    public SendAttempt retryFailed(UUID id, ClientScope scope, long expectedRevision, int expectedVersion) {
        return execute(id, scope, expectedRevision, expectedVersion, true);
    }

    public List<SendAttempt> attempts(UUID id, ClientScope scope) {
        return transactions.inTransaction(() -> { synchronized (drafts) { return authorized(id, scope).sendAttempts(); } });
    }

    private SendAttempt execute(UUID id, ClientScope scope, long expectedRevision, int expectedVersion, boolean retry) {
        var reservation = transactions.inTransaction(() -> {
        synchronized (drafts) {
            var draft = authorized(id, scope);
            if (draft.current().number() != expectedVersion || draft.approval().filter(a -> a.version() == expectedVersion
                    && a.content().equals(draft.current().content())).isEmpty()) {
                throw new IllegalArgumentException("La version courante et son contenu exact doivent être approuvés");
            }
            var previous = draft.lastAttempt(expectedVersion);
            if (!retry && previous.isPresent()) { return new Reservation(previous.get(), false); }
            if (draft.revision() != expectedRevision) { throw new IllegalArgumentException("Relecture obsolète ; affichez le brouillon avant de transmettre"); }
            var message = conversations.findMessage(draft.messageId()).filter(m -> m.scope().equals(scope)).orElseThrow();
            if (!message.recipients().contains(from)) { throw new IllegalArgumentException("L'expéditeur configuré n'est pas destinataire du message reçu"); }
            var updated = draft.reserveSend(expectedVersion, identifiers.get(), from, sender.mode(), retry, clock.instant());
            var reserved = updated.lastAttempt(expectedVersion).orElseThrow();
            save(draft, updated, reserved);
            return new Reservation(reserved, true);
        }
        });
        if (!reservation.fresh()) { return reservation.attempt(); }
        var reserved = reservation.attempt();
        // Aucun verrou applicatif conservé pendant le transport. Une mutation voit PENDING et échoue.
        DeliveryResult result;
        try {
            result = sender.send(reserved.email());
            if (result == null || (reserved.mode() == TransportMode.SIMULATED && result.status() == DeliveryStatus.CONFIRMED)
                    || (reserved.mode() == TransportMode.REAL && result.status() == DeliveryStatus.SIMULATED)) {
                result = unknown("INVALID_TRANSPORT_RESULT");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result = unknown("INTERRUPTED");
        } catch (Exception error) {
            // Une exception ne prouve pas qu'aucun message n'a été livré. Ne pas journaliser sa réponse brute.
            result = unknown("TRANSPORT_EXCEPTION");
        }
        var outcome = result;
        return transactions.inTransaction(() -> {
            synchronized (drafts) {
                var current = authorized(id, scope);
                var updated = current.completeSend(reserved.email().attemptId(), outcome, clock.instant());
                var completed = updated.lastAttempt(expectedVersion).orElseThrow();
                save(current, updated, completed);
                return completed;
            }
        });
    }

    private record Reservation(SendAttempt attempt, boolean fresh) { }

    private static DeliveryResult unknown(String code) { return new DeliveryResult(DeliveryStatus.UNKNOWN, Optional.empty(), code); }

    private Draft authorized(UUID id, ClientScope scope) {
        return drafts.find(id).filter(d -> d.scope().equals(scope))
                .orElseThrow(() -> new IllegalArgumentException("Brouillon absent ou hors périmètre"));
    }

    private void save(Draft current, Draft updated, SendAttempt attempt) {
        synchronized (conversations) {
            var conversation = conversations.findById(updated.conversationId()).orElseThrow().recordDelivery(attempt);
            drafts.replace(current, updated);
            conversations.save(conversation);
        }
    }
}
