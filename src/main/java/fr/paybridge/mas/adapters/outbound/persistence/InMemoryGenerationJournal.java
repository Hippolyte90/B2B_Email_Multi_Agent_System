package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.GenerationJournal;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.time.Instant;
import java.util.*;

public final class InMemoryGenerationJournal implements GenerationJournal {
    private final Map<UUID, GenerationRecord> records = new LinkedHashMap<>();
    public synchronized void start(GenerationRecord record) {
        if (record.state() != GenerationRecord.State.RUNNING || records.putIfAbsent(record.taskId(), record) != null) { throw new IllegalArgumentException("Tâche déjà présente ou non démarrable"); }
    }
    public synchronized void finish(UUID id, AgentResult result, Instant at) { records.put(id, required(id).finish(result, at)); }
    public synchronized void interrupt(UUID id, Instant at) { records.put(id, required(id).interrupt(at)); }
    public synchronized Optional<GenerationRecord> find(UUID id, ClientScope scope) { return Optional.ofNullable(records.get(id)).filter(r -> r.routing().scope().equals(scope)); }
    public synchronized List<GenerationRecord> running() { return records.values().stream().filter(r -> r.state() == GenerationRecord.State.RUNNING).toList(); }
    private GenerationRecord required(UUID id) { return Optional.ofNullable(records.get(id)).orElseThrow(() -> new IllegalArgumentException("Tâche absente")); }
}
