package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.generation.StoredGeneration;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.util.*;

final class SqliteCompletedGenerationRepository implements CompletedGenerationRepository {
    private final SqliteStore store;
    SqliteCompletedGenerationRepository(SqliteStore store) { this.store = store; }

    @Override public void add(StoredGeneration generation) {
        store.inTransaction(() -> {
            var result = generation.result();
            var receipt = result.receipt().orElseThrow();
            var route = receipt.routing();
            var existing = find(result.taskId(), route.scope());
            if (existing.isPresent()) {
                if (!existing.get().equals(generation)) { throw new IllegalArgumentException("Identifiant de génération déjà utilisé"); }
                return null;
            }
            if (store.decisions().find(route.id()).filter(route::equals).isEmpty()
                    || store.agents().load().filter(archive -> archive.snapshot().revision() == route.registryRevision()
                        && archive.snapshot().activeAgents().contains(receipt.agent())).isEmpty()) {
                throw new IllegalArgumentException("Décision ou définition de génération absente ou obsolète");
            }
            var conversation = store.conversations().findById(route.conversationId()).orElseThrow();
            if (!conversation.scope().equals(route.scope()) || conversation.history().stream().noneMatch(event ->
                    event.id().equals("generated:" + result.taskId()) && event.messageId().equals(route.messageId())
                    && event.type() == fr.paybridge.mas.domain.conversation.HistoryEvent.Type.GENERATED)) {
                throw new IllegalArgumentException("Événement de génération absent");
            }
            store.write("INSERT INTO completed_generations(task_id,message_id,payload) VALUES(?,?,?)", result.taskId().toString(), route.messageId(), store.encode(generation));
            store.audit("GENERATION_STORED", result.taskId().toString(), 1);
            return null;
        });
    }

    @Override public Optional<StoredGeneration> find(UUID taskId, ClientScope scope) {
        return store.inTransaction(() -> {
            var messageId = store.payload("SELECT message_id FROM completed_generations WHERE task_id=?", taskId.toString());
            if (messageId.isEmpty() || store.conversations().findMessage(messageId.get()).filter(message -> message.scope().equals(scope)).isEmpty()) {
                return Optional.empty();
            }
            return store.payload("SELECT payload FROM completed_generations WHERE task_id=?", taskId.toString())
                    .map(payload -> store.decode(payload, StoredGeneration.class)).map(generation -> {
                        var routing = generation.result().receipt().orElseThrow().routing();
                        if (!generation.result().taskId().equals(taskId) || !routing.messageId().equals(messageId.get()) || !routing.scope().equals(scope)) {
                            throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA);
                        }
                        return generation;
                    });
        });
    }

    @Override public Optional<StoredGeneration> latest(String messageId, ClientScope scope) {
        return store.inTransaction(() -> {
            if (store.conversations().findMessage(messageId).filter(message -> message.scope().equals(scope)).isEmpty()) { return Optional.empty(); }
            return store.payload("SELECT task_id FROM completed_generations WHERE message_id=? ORDER BY sequence DESC LIMIT 1", messageId)
                    .flatMap(id -> find(UUID.fromString(id), scope));
        });
    }
}
