package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.time.Instant;
import java.util.*;

public interface GenerationJournal {
    void start(GenerationRecord record);
    void finish(UUID taskId, AgentResult result, Instant at);
    void interrupt(UUID taskId, Instant at);
    Optional<GenerationRecord> find(UUID taskId, ClientScope scope);
    List<GenerationRecord> running();
    static GenerationJournal disabled() {
        return new GenerationJournal() {
            public void start(GenerationRecord record) { }
            public void finish(UUID taskId, AgentResult result, Instant at) { }
            public void interrupt(UUID taskId, Instant at) { }
            public Optional<GenerationRecord> find(UUID taskId, ClientScope scope) { return Optional.empty(); }
            public List<GenerationRecord> running() { return List.of(); }
        };
    }
}
