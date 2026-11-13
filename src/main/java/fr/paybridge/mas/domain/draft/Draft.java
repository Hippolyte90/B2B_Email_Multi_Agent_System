package fr.paybridge.mas.domain.draft;

import fr.paybridge.mas.domain.generation.AgentResult;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.delivery.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Agrégat immuable : contenu versionné, décisions de relecture et tentative en cours. */
public final class Draft {
    public enum State { GENERATED, EDITED, APPROVED, REJECTED }
    private final UUID id;
    private final String messageId;
    private final String conversationId;
    private final ClientScope scope;
    private final List<DraftVersion> versions;
    private final List<DraftReview> reviews;
    private final State state;
    private final long revision;
    private final Optional<UUID> pendingRegeneration;
    private final List<SendAttempt> sendAttempts;

    private Draft(UUID id, String messageId, String conversationId, ClientScope scope, List<DraftVersion> versions,
                  List<DraftReview> reviews, State state, long revision, Optional<UUID> pending, List<SendAttempt> sendAttempts) {
        this.id = id; this.messageId = messageId; this.conversationId = conversationId; this.scope = scope;
        this.versions = List.copyOf(versions); this.reviews = List.copyOf(reviews); this.state = state;
        this.revision = revision; this.pendingRegeneration = pending;
        this.sendAttempts = List.copyOf(sendAttempts);
    }

    public static Draft generated(UUID id, String subject, AgentResult result, Instant at) {
        Objects.requireNonNull(id);
        var version = generatedVersion(1, subject, result, at);
        var route = version.receipt().routing();
        return new Draft(id, route.messageId(), route.conversationId(), route.scope(), List.of(version),
                List.of(new DraftReview(DraftReview.Type.GENERATED, 1, at, Optional.empty(), Optional.of(result.taskId()))),
                State.GENERATED, 1, Optional.empty(), List.of());
    }

    public Draft edit(int expectedVersion, DraftContent content, Instant at) {
        requireVersion(expectedVersion);
        requireReviewable();
        if (current().content().equals(content)) { return this; }
        var updated = new ArrayList<>(versions);
        updated.add(new DraftVersion(Math.addExact(current().number(), 1), content, DraftVersion.Origin.EDITED, at,
                current().generation(), current().receipt()));
        return transition(updated, new DraftReview(DraftReview.Type.EDITED, updated.getLast().number(), at, Optional.empty(), Optional.empty()), State.EDITED, Optional.empty());
    }

    public Draft approve(int expectedVersion, Instant at) {
        requireVersion(expectedVersion);
        requireReviewable();
        if (state == State.APPROVED) { return this; }
        return transition(versions, new DraftReview(DraftReview.Type.APPROVED, expectedVersion, at, Optional.empty(), Optional.empty()), State.APPROVED, Optional.empty());
    }

    public Draft reject(int expectedVersion, Optional<String> comment, Instant at) {
        requireVersion(expectedVersion);
        if (state != State.GENERATED && state != State.EDITED) { throw new IllegalArgumentException("Seule une proposition en relecture peut être rejetée"); }
        var useful = Objects.requireNonNull(comment).map(String::strip).filter(s -> !s.isEmpty());
        return transition(versions, new DraftReview(DraftReview.Type.REJECTED, expectedVersion, at, useful, Optional.empty()), State.REJECTED, Optional.empty());
    }

    public Draft reserveRegeneration(int expectedVersion, UUID attemptId, Instant at) {
        requireVersion(expectedVersion);
        Objects.requireNonNull(attemptId);
        if (state != State.REJECTED || pendingRegeneration.isPresent()) { throw new IllegalArgumentException("Une version rejetée sans tentative en cours est requise"); }
        return transition(versions, new DraftReview(DraftReview.Type.REGENERATION_REQUESTED, expectedVersion, at, Optional.empty(), Optional.of(attemptId)),
                State.REJECTED, Optional.of(attemptId));
    }

    public Draft completeRegeneration(UUID attemptId, AgentResult result, Instant at) {
        requireAttempt(attemptId);
        var receipt = result.receipt().orElseThrow(() -> new IllegalArgumentException("Aucune proposition valide"));
        if (!receipt.routing().messageId().equals(messageId) || !receipt.routing().scope().equals(scope)
                || !receipt.routing().conversationId().equals(conversationId)) { throw new IllegalArgumentException("Résultat d'un autre message ou périmètre"); }
        if (versions.stream().anyMatch(v -> v.generation().taskId().equals(result.taskId()))) {
            throw new IllegalArgumentException("Proposition déjà intégrée");
        }
        var updated = new ArrayList<>(versions);
        updated.add(generatedVersion(Math.addExact(current().number(), 1), current().content().subject(), result, at));
        return transition(updated, new DraftReview(DraftReview.Type.GENERATED, updated.getLast().number(), at, Optional.empty(), Optional.of(result.taskId())), State.GENERATED, Optional.empty());
    }

    public Draft failRegeneration(UUID attemptId, DraftReview.Type type, String code, Instant at) {
        requireAttempt(attemptId);
        if (type != DraftReview.Type.REGENERATION_FAILED && type != DraftReview.Type.REGENERATION_CANCELLED && type != DraftReview.Type.REGENERATION_SUPERSEDED) {
            throw new IllegalArgumentException("Type de fin de tentative invalide");
        }
        return transition(versions, new DraftReview(type, current().number(), at, Optional.of(code), Optional.of(attemptId)), State.REJECTED, Optional.empty());
    }

    public Optional<DraftApproval> approval() {
        return state == State.APPROVED ? Optional.of(approvals().getLast()) : Optional.empty();
    }

    public Draft reserveSend(int expectedVersion, UUID attemptId, EmailAddress sender, TransportMode mode, boolean retry, Instant at) {
        requireVersion(expectedVersion);
        requireNoUnresolvedDelivery();
        var approved = approval().filter(a -> a.version() == expectedVersion && a.content().equals(current().content()))
                .orElseThrow(() -> new IllegalArgumentException("Le contenu courant doit être explicitement approuvé"));
        var previous = lastAttempt(expectedVersion);
        if ((!retry && previous.isPresent()) || (retry && (previous.isEmpty() || previous.get().status() != DeliveryStatus.FAILED))) {
            throw new IllegalArgumentException("Tentative existante ; seule une reprise explicite après échec certain est permise");
        }
        if (sendAttempts.stream().anyMatch(a -> a.email().attemptId().equals(attemptId))) { throw new IllegalArgumentException("Identifiant de tentative dupliqué"); }
        var envelope = new ApprovedEmail(attemptId, id, messageId, conversationId, scope, sender, approved);
        var attempts = new ArrayList<>(sendAttempts);
        attempts.add(SendAttempt.pending(envelope, mode, at));
        return deliveryTransition(attempts, new DraftReview(DraftReview.Type.SEND_REQUESTED, expectedVersion, at, Optional.empty(), Optional.of(attemptId)));
    }

    public Draft completeSend(UUID attemptId, DeliveryResult result, Instant at) {
        var attempts = new ArrayList<>(sendAttempts);
        int index = -1;
        for (int i = 0; i < attempts.size(); i++) { if (attempts.get(i).email().attemptId().equals(attemptId)) { index = i; break; } }
        if (index < 0) { throw new IllegalArgumentException("Tentative absente"); }
        var attempt = attempts.get(index);
        if (attempt.email().approval().version() != current().number() || !attempt.email().approval().content().equals(current().content())) {
            throw new IllegalArgumentException("Le contenu réservé ne correspond plus à la version courante");
        }
        var completed = attempt.complete(result, at);
        attempts.set(index, completed);
        var type = switch (completed.status()) {
            case SIMULATED -> DraftReview.Type.SEND_SIMULATED;
            case CONFIRMED -> DraftReview.Type.SEND_CONFIRMED;
            case FAILED -> DraftReview.Type.SEND_FAILED;
            case UNKNOWN -> DraftReview.Type.SEND_UNKNOWN;
            case PENDING -> throw new IllegalArgumentException("Un résultat terminal est requis");
        };
        return deliveryTransition(attempts, new DraftReview(type, current().number(), at, Optional.of(result.code()), Optional.of(attemptId)));
    }

    public Optional<SendAttempt> lastAttempt(int version) {
        return sendAttempts.stream().filter(a -> a.email().approval().version() == version).reduce((a, b) -> b);
    }

    private Draft deliveryTransition(List<SendAttempt> attempts, DraftReview event) {
        var events = new ArrayList<>(reviews); events.add(event);
        return new Draft(id, messageId, conversationId, scope, versions, events, state, Math.addExact(revision, 1), pendingRegeneration, attempts);
    }

    private void requireNoUnresolvedDelivery() {
        if (sendAttempts.stream().anyMatch(a -> a.status() == DeliveryStatus.PENDING || a.status() == DeliveryStatus.UNKNOWN)) {
            throw new IllegalArgumentException("Transmission en cours ou résultat inconnu ; vérification requise avant toute nouvelle modification ou tentative");
        }
    }

    public List<DraftApproval> approvals() {
        return reviews.stream().filter(r -> r.type() == DraftReview.Type.APPROVED)
                .map(r -> new DraftApproval(r.version(), versions.get(r.version() - 1).content(), r.at())).toList();
    }

    public Optional<String> rejectionComment() {
        return reviews.stream().filter(r -> r.type() == DraftReview.Type.REJECTED && r.version() == current().number())
                .reduce((a, b) -> b).flatMap(DraftReview::comment);
    }

    private static DraftVersion generatedVersion(int number, String subject, AgentResult result, Instant at) {
        if (result.status() != AgentResult.Status.COMPLETED) { throw new IllegalArgumentException("Génération non réussie"); }
        var reply = result.reply().orElseThrow();
        var receipt = result.receipt().orElseThrow();
        var recipients = receipt.recipients().stream().sorted(Comparator.comparing(r -> r.value())).toList();
        return new DraftVersion(number, new DraftContent(subject, reply.text(), recipients), DraftVersion.Origin.GENERATED, at, reply, receipt);
    }

    private void requireVersion(int expected) {
        if (current().number() != expected) { throw new IllegalArgumentException("Version obsolète ; rechargez le brouillon"); }
    }
    private void requireReviewable() {
        requireNoUnresolvedDelivery();
        if (state == State.REJECTED || pendingRegeneration.isPresent()) { throw new IllegalArgumentException("Régénérez d'abord la proposition rejetée"); }
    }
    private void requireAttempt(UUID attempt) {
        if (state != State.REJECTED || pendingRegeneration.filter(attempt::equals).isEmpty()) { throw new IllegalArgumentException("Tentative de régénération absente ou obsolète"); }
    }
    private Draft transition(List<DraftVersion> newVersions, DraftReview event, State newState, Optional<UUID> pending) {
        var events = new ArrayList<>(reviews); events.add(event);
        return new Draft(id, messageId, conversationId, scope, newVersions, events, newState, Math.addExact(revision, 1), pending, sendAttempts);
    }
    public UUID id() { return id; }
    public String messageId() { return messageId; }
    public String conversationId() { return conversationId; }
    public ClientScope scope() { return scope; }
    public List<DraftVersion> versions() { return versions; }
    public DraftVersion current() { return versions.getLast(); }
    public List<DraftReview> reviews() { return reviews; }
    public State state() { return state; }
    public long revision() { return revision; }
    public Optional<UUID> pendingRegeneration() { return pendingRegeneration; }
    public List<SendAttempt> sendAttempts() { return sendAttempts; }

    public record Snapshot(UUID id, String messageId, String conversationId, ClientScope scope,
                           List<DraftVersion> versions, List<DraftReview> reviews, State state, long revision,
                           Optional<UUID> pendingRegeneration, List<SendAttempt> sendAttempts) {
        public Snapshot {
            Objects.requireNonNull(id); Objects.requireNonNull(scope); Objects.requireNonNull(state);
            Objects.requireNonNull(pendingRegeneration);
            versions = List.copyOf(versions); reviews = List.copyOf(reviews); sendAttempts = List.copyOf(sendAttempts);
            if (messageId == null || messageId.isBlank() || conversationId == null || conversationId.isBlank()
                    || versions.isEmpty() || reviews.isEmpty() || revision != reviews.size()) {
                throw new IllegalArgumentException("Instantané de brouillon invalide");
            }
        }
    }

    public Snapshot snapshot() { return new Snapshot(id, messageId, conversationId, scope, versions, reviews, state, revision, pendingRegeneration, sendAttempts); }

    /** Rejoue les transitions publiques : un JSON ne peut pas inventer une approbation ou un envoi. */
    public static Draft restore(Snapshot snapshot) {
        var first = snapshot.versions().getFirst();
        var draft = generated(snapshot.id(), first.content().subject(), resultOf(first), first.createdAt());
        if (!draft.reviews().getFirst().equals(snapshot.reviews().getFirst())) { throw new IllegalArgumentException("Création de brouillon incohérente"); }
        for (var event : snapshot.reviews().subList(1, snapshot.reviews().size())) {
            var current = draft;
            draft = switch (event.type()) {
                case EDITED -> current.edit(current.current().number(), snapshot.versions().get(event.version() - 1).content(), event.at());
                case APPROVED -> current.approve(event.version(), event.at());
                case REJECTED -> current.reject(event.version(), event.comment(), event.at());
                case REGENERATION_REQUESTED -> current.reserveRegeneration(event.version(), event.reference().orElseThrow(), event.at());
                case GENERATED -> current.completeRegeneration(current.pendingRegeneration().orElseThrow(), resultOf(snapshot.versions().get(event.version() - 1)), event.at());
                case REGENERATION_FAILED, REGENERATION_CANCELLED, REGENERATION_SUPERSEDED -> current.failRegeneration(event.reference().orElseThrow(), event.type(), event.comment().orElseThrow(), event.at());
                case SEND_REQUESTED -> {
                    var attempt = snapshot.sendAttempts().stream().filter(a -> event.reference().filter(a.email().attemptId()::equals).isPresent()).findFirst().orElseThrow();
                    yield current.reserveSend(event.version(), attempt.email().attemptId(), attempt.email().sender(), attempt.mode(), current.lastAttempt(event.version()).isPresent(), event.at());
                }
                case SEND_SIMULATED, SEND_CONFIRMED, SEND_FAILED, SEND_UNKNOWN -> {
                    var attempt = snapshot.sendAttempts().stream().filter(a -> event.reference().filter(a.email().attemptId()::equals).isPresent()).findFirst().orElseThrow();
                    yield current.completeSend(attempt.email().attemptId(), attempt.result().orElseThrow(), event.at());
                }
            };
        }
        if (!draft.snapshot().equals(snapshot)) { throw new IllegalArgumentException("Historique ou état de brouillon incohérent"); }
        return draft;
    }

    private static AgentResult resultOf(DraftVersion version) {
        return new AgentResult(version.generation().taskId(), version.receipt().agent().id(), AgentResult.Status.COMPLETED,
                Optional.of(version.generation()), Optional.empty(), List.of(), Optional.of(version.receipt()));
    }
}
