package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

public interface SenderDirectory {
    ClientScope resolve(EmailAddress sender);
}
