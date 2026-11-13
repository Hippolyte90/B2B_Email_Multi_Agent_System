package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.mail.MailboxFlags;

public interface MailboxFlagsRepository {
    MailboxFlags find(String messageId);
    void replace(MailboxFlags expected, MailboxFlags updated);
}
