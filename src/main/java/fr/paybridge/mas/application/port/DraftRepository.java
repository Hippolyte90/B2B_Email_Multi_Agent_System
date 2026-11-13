package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.draft.Draft;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface DraftRepository {
    Optional<Draft> find(UUID id);
    Optional<Draft> findByMessage(String messageId);
    void add(Draft draft);
    void replace(Draft expected, Draft updated);
    /** Réservé à la récupération au démarrage sous session exclusive, jamais à une vue client. */
    List<Draft> unfinished();
}
