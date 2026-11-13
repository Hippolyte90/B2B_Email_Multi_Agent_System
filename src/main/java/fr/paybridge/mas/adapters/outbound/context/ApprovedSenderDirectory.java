package fr.paybridge.mas.adapters.outbound.context;

import fr.paybridge.mas.application.port.SenderDirectory;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.util.Map;

/** Associations injectées par l'assemblage, séparées du contenu des emails. */
public final class ApprovedSenderDirectory implements SenderDirectory {
    private final Map<EmailAddress, ClientScope> associations;

    public ApprovedSenderDirectory(Map<EmailAddress, ClientScope> associations) {
        this.associations = Map.copyOf(associations);
    }

    @Override
    public ClientScope resolve(EmailAddress sender) {
        return associations.getOrDefault(sender, ClientScope.unknown());
    }
}
