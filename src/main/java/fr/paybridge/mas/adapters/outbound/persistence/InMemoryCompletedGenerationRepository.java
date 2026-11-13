package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.CompletedGenerationRepository;
import fr.paybridge.mas.domain.generation.StoredGeneration;
import fr.paybridge.mas.domain.mail.ClientScope;
import java.util.*;

public final class InMemoryCompletedGenerationRepository implements CompletedGenerationRepository {
    private final Map<UUID, StoredGeneration> entries = new LinkedHashMap<>();
    @Override public synchronized void add(StoredGeneration generation) {
        var old = entries.putIfAbsent(generation.result().taskId(), generation);
        if (old != null && !old.equals(generation)) { throw new IllegalArgumentException("Identifiant de génération déjà utilisé"); }
    }
    @Override public synchronized Optional<StoredGeneration> find(UUID id, ClientScope scope) {
        return Optional.ofNullable(entries.get(id)).filter(generation -> generation.result().receipt().orElseThrow().routing().scope().equals(scope));
    }
    @Override public synchronized Optional<StoredGeneration> latest(String messageId, ClientScope scope) {
        return entries.values().stream().filter(generation -> {
            var route = generation.result().receipt().orElseThrow().routing();
            return route.messageId().equals(messageId) && route.scope().equals(scope);
        }).reduce((first, second) -> second);
    }
}
