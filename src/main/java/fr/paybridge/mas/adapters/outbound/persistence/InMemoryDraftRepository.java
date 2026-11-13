package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.DraftRepository;
import fr.paybridge.mas.domain.draft.Draft;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public final class InMemoryDraftRepository implements DraftRepository {
    private final Map<UUID, Draft> drafts = new HashMap<>();

    @Override public synchronized Optional<Draft> find(UUID id) { return Optional.ofNullable(drafts.get(id)); }
    @Override public synchronized Optional<Draft> findByMessage(String messageId) {
        return drafts.values().stream().filter(d -> d.messageId().equals(messageId)).findFirst();
    }
    @Override public synchronized void add(Draft draft) {
        if (drafts.containsKey(draft.id()) || findByMessage(draft.messageId()).isPresent()) {
            throw new IllegalArgumentException("Un brouillon existe déjà pour cet identifiant ou ce message");
        }
        drafts.put(draft.id(), draft);
    }
    @Override public synchronized void replace(Draft expected, Draft updated) {
        var actual = drafts.get(expected.id());
        if (actual != expected || !expected.id().equals(updated.id()) || !expected.scope().equals(updated.scope())
                || !expected.messageId().equals(updated.messageId()) || !expected.conversationId().equals(updated.conversationId())
                || updated.revision() != expected.revision() + 1) {
            throw new IllegalArgumentException("Brouillon modifié entre-temps ou mise à jour incohérente");
        }
        drafts.put(updated.id(), updated);
    }
    @Override public synchronized List<Draft> unfinished() {
        return drafts.values().stream().filter(d -> d.pendingRegeneration().isPresent()
                || d.sendAttempts().stream().anyMatch(a -> a.status() == fr.paybridge.mas.domain.delivery.DeliveryStatus.PENDING)).toList();
    }
}
