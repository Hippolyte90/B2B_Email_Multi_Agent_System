package fr.paybridge.mas.application.usecase;

import fr.paybridge.mas.domain.mail.EmailAddress;

import java.time.OffsetDateTime;
import java.util.List;

/** Aucune portée client fournie par le message entrant. */
public record ImportEmailCommand(String id, String conversationId, EmailAddress sender,
                                 List<EmailAddress> recipients, String subject,
                                 String body, OffsetDateTime receivedAt) {
    public ImportEmailCommand {
        recipients = List.copyOf(recipients);
    }
}
