package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.mail.ClientScope;

import java.util.Objects;
import java.util.Optional;

public final class FindConversationUseCase {
    private final ConversationRepository repository;

    public FindConversationUseCase(ConversationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** Le périmètre est fourni par l'application, pas par un champ libre de l'email. */
    public Optional<Conversation> execute(String conversationId, ClientScope authorizedScope) {
        Objects.requireNonNull(authorizedScope, "Périmètre autorisé obligatoire");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Identifiant de fil obligatoire");
        }
        return repository.findById(conversationId).filter(c -> c.scope().equals(authorizedScope));
    }
}
