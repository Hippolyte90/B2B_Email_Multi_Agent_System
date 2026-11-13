package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.util.Optional;

/** Port interne ; l'interface utilisateur passe par les cas d'usage autorisés. */
public interface ConversationRepository {
    Optional<Conversation> findById(String id);
    Optional<EmailMessage> findMessage(String messageId);
    void save(Conversation conversation);
}
