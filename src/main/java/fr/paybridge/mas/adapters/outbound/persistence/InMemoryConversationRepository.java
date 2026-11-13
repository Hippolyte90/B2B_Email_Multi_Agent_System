package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Dépôt éphémère de conversations, sans accès disque. */
public final class InMemoryConversationRepository implements ConversationRepository {
    private final Map<String, Conversation> conversations = new HashMap<>();

    @Override
    public synchronized Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    @Override
    public synchronized Optional<EmailMessage> findMessage(String messageId) {
        return conversations.values().stream().flatMap(c -> c.messages().stream())
                .filter(m -> m.id().equals(messageId)).findFirst();
    }

    @Override
    public synchronized void save(Conversation conversation) {
        Objects.requireNonNull(conversation);
        var current = conversations.get(conversation.id());
        if (current != null && !current.scope().equals(conversation.scope())) {
            throw new IllegalArgumentException("Le périmètre du fil ne peut pas changer");
        }
        for (var other : conversations.values()) {
            if (!other.id().equals(conversation.id()) && other.messages().stream()
                    .anyMatch(m -> conversation.messages().stream().anyMatch(n -> n.id().equals(m.id())))) {
                throw new IllegalArgumentException("Identifiant message présent dans un autre fil");
            }
        }
        conversations.put(conversation.id(), conversation);
    }
}
