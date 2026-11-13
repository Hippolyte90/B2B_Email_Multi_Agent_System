package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.generation.StoredGeneration;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.util.Optional;
import java.util.UUID;

public interface CompletedGenerationRepository {
    void add(StoredGeneration generation);
    Optional<StoredGeneration> find(UUID taskId, ClientScope scope);
    Optional<StoredGeneration> latest(String messageId, ClientScope scope);

    static CompletedGenerationRepository disabled() {
        return new CompletedGenerationRepository() {
            @Override public void add(StoredGeneration generation) { }
            @Override public Optional<StoredGeneration> find(UUID taskId, ClientScope scope) { return Optional.empty(); }
            @Override public Optional<StoredGeneration> latest(String messageId, ClientScope scope) { return Optional.empty(); }
        };
    }
}
