package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

final class SqliteGenerationJournal implements GenerationJournal {
    private final SqliteStore store;
    SqliteGenerationJournal(SqliteStore store) { this.store = store; }
    @Override public void start(GenerationRecord record) {
        store.inTransaction(() -> {
            if (record.state() != GenerationRecord.State.RUNNING || store.decisions().find(record.routing().id()).filter(record.routing()::equals).isEmpty()) {
                throw new IllegalArgumentException("Début de tâche incohérent");
            }
            if (load(record.taskId()).isPresent()) { throw new IllegalArgumentException("Identifiant de tâche déjà utilisé"); }
            store.write("INSERT INTO generation_work(task_id,message_id,state,payload) VALUES(?,?,?,?)", record.taskId().toString(), record.routing().messageId(), record.state().name(), store.encode(record));
            store.audit("GENERATION_STARTED", record.taskId().toString(), 1); return null;
        });
    }
    private Optional<GenerationRecord> load(UUID id) {
        return store.payload("SELECT payload FROM generation_work WHERE task_id=?", id.toString()).map(payload -> {
            var record = store.decode(payload, GenerationRecord.class);
            if (!record.taskId().equals(id)) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
            return record;
        });
    }
    private void update(UUID id, UnaryOperator<GenerationRecord> action) {
        store.inTransaction(() -> {
            var current = load(id).orElseThrow(() -> new IllegalArgumentException("Tâche absente"));
            var updated = action.apply(current);
            if (updated.state() == GenerationRecord.State.COMPLETED && store.generations().find(id, current.routing().scope()).filter(g -> g.result().equals(updated.result().orElseThrow())).isEmpty()) {
                throw new IllegalArgumentException("Résultat réussi non archivé");
            }
            store.write("UPDATE generation_work SET state=?,payload=? WHERE task_id=? AND state='RUNNING'", updated.state().name(), store.encode(updated), id.toString());
            store.audit("GENERATION_" + updated.state().name(), id.toString(), 2); return null;
        });
    }
    @Override public void finish(UUID id, AgentResult result, Instant at) { update(id, record -> record.finish(result, at)); }
    @Override public void interrupt(UUID id, Instant at) { update(id, record -> record.interrupt(at)); }
    @Override public Optional<GenerationRecord> find(UUID id, ClientScope scope) {
        return store.inTransaction(() -> {
            var messageId = store.payload("SELECT message_id FROM generation_work WHERE task_id=?", id.toString());
            if (messageId.isEmpty() || store.conversations().findMessage(messageId.get()).filter(m -> m.scope().equals(scope)).isEmpty()) { return Optional.empty(); }
            return load(id).filter(record -> record.routing().scope().equals(scope));
        });
    }
    @Override public List<GenerationRecord> running() {
        return store.inTransaction(() -> store.payloads("SELECT task_id FROM generation_work WHERE state='RUNNING' ORDER BY task_id").stream()
                .map(id -> load(UUID.fromString(id)).orElseThrow()).toList());
    }
}
