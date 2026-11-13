package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.delivery.DeliveryStatus;
import java.util.*;

final class SqliteDraftRepository implements DraftRepository {
    private final SqliteStore store;
    SqliteDraftRepository(SqliteStore store) { this.store = store; }
    @Override public Optional<Draft> find(UUID id) {
        return store.inTransaction(() -> store.payload("SELECT payload FROM drafts WHERE id=?", id.toString()).map(payload -> {
            var draft = store.decodeDraft(payload);
            if (!draft.id().equals(id)) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
            return draft;
        }));
    }
    @Override public Optional<Draft> findByMessage(String messageId) {
        return store.inTransaction(() -> store.payload("SELECT id FROM drafts WHERE message_id=?", messageId).flatMap(id -> find(UUID.fromString(id))));
    }
    @Override public void add(Draft draft) {
        store.inTransaction(() -> {
            if (find(draft.id()).isPresent() || findByMessage(draft.messageId()).isPresent()) { throw new IllegalArgumentException("Un brouillon existe déjà pour ce message"); }
            validate(draft);
            store.write("INSERT INTO drafts(id,message_id,revision,unfinished,payload) VALUES(?,?,?,?,?)", draft.id().toString(), draft.messageId(), draft.revision(), unfinished(draft), store.encode(draft.snapshot()));
            store.audit("DRAFT_CREATED", draft.id().toString(), draft.revision()); return null;
        });
    }
    @Override public void replace(Draft expected, Draft updated) {
        store.inTransaction(() -> {
            var actual = find(expected.id()).orElseThrow(() -> new IllegalArgumentException("Brouillon absent"));
            if (!actual.snapshot().equals(expected.snapshot()) || !expected.id().equals(updated.id()) || !expected.scope().equals(updated.scope())
                    || !expected.messageId().equals(updated.messageId()) || !expected.conversationId().equals(updated.conversationId())
                    || updated.revision() != expected.revision() + 1
                    || !updated.reviews().subList(0, expected.reviews().size()).equals(expected.reviews())) {
                throw new IllegalArgumentException("Brouillon modifié entre-temps ; rechargez-le");
            }
            validate(updated);
            store.write("UPDATE drafts SET revision=?,unfinished=?,payload=? WHERE id=? AND revision=?", updated.revision(), unfinished(updated), store.encode(updated.snapshot()), updated.id().toString(), expected.revision());
            store.audit("DRAFT_UPDATED", updated.id().toString(), updated.revision()); return null;
        });
    }
    private void validate(Draft draft) {
        var message = store.conversations().findMessage(draft.messageId()).orElseThrow();
        if (!message.conversationId().equals(draft.conversationId()) || !message.scope().equals(draft.scope())) { throw new IllegalArgumentException("Brouillon d'un autre fil ou périmètre"); }
        for (var version : draft.versions()) {
            var generation = store.generations().find(version.generation().taskId(), draft.scope()).orElseThrow(() -> new IllegalArgumentException("Génération du brouillon non archivée"));
            if (!generation.result().reply().orElseThrow().equals(version.generation()) || !generation.result().receipt().orElseThrow().equals(version.receipt())) {
                throw new IllegalArgumentException("Origine du brouillon incohérente");
            }
        }
    }
    private static int unfinished(Draft draft) {
        return draft.pendingRegeneration().isPresent() || draft.sendAttempts().stream().anyMatch(a -> a.status() == DeliveryStatus.PENDING) ? 1 : 0;
    }
    @Override public List<Draft> unfinished() {
        return store.inTransaction(() -> store.payloads("SELECT id FROM drafts WHERE unfinished=1 ORDER BY id").stream()
                .map(id -> find(UUID.fromString(id)).orElseThrow()).toList());
    }
}
