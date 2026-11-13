package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.application.port.ConversationRepository;
import fr.paybridge.mas.application.port.SenderDirectory;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.util.Objects;

public final class ImportEmailUseCase {
    public enum Result { IMPORTED, ALREADY_PRESENT }
    private final ConversationRepository repository;
    private final SenderDirectory directory;
    private final TransactionRunner transactions;

    public ImportEmailUseCase(ConversationRepository repository, SenderDirectory directory) {
        this(repository, directory, TransactionRunner.direct());
    }

    public ImportEmailUseCase(ConversationRepository repository, SenderDirectory directory, TransactionRunner transactions) {
        this.repository = Objects.requireNonNull(repository);
        this.directory = Objects.requireNonNull(directory);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public Result execute(ImportEmailCommand command) {
        Objects.requireNonNull(command, "Commande obligatoire");
        var message = new EmailMessage(command.id(), command.conversationId(), command.sender(),
                command.recipients(), command.subject(), command.body(), command.receivedAt(),
                directory.resolve(command.sender()));
        // Le verrou reste utile en mémoire ; le stockage durable injecte sa transaction commune.
        return transactions.inTransaction(() -> {
            synchronized (repository) {
                var existing = repository.findMessage(message.id());
                if (existing.isPresent()) {
                    if (!existing.get().equals(message)) {
                        throw new IllegalArgumentException("Identifiant message déjà utilisé pour un contenu différent");
                    }
                    return Result.ALREADY_PRESENT;
                }
                var conversation = repository.findById(message.conversationId())
                        .map(current -> current.append(message)).orElseGet(() -> Conversation.start(message));
                repository.save(conversation);
                return Result.IMPORTED;
            }
        });
    }
}
