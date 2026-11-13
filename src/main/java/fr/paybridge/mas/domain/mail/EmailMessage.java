package fr.paybridge.mas.domain.mail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record EmailMessage(String id, String conversationId, EmailAddress sender,
                           List<EmailAddress> recipients, String subject, String body,
                           OffsetDateTime receivedAt, ClientScope scope) {
    public EmailMessage {
        requireText(id, "Identifiant message");
        requireText(conversationId, "Identifiant de fil");
        Objects.requireNonNull(sender, "Expéditeur obligatoire");
        recipients = List.copyOf(Objects.requireNonNull(recipients, "Destinataires obligatoires"));
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("Au moins un destinataire est requis");
        }
        requireText(subject, "Sujet");
        requireText(body, "Corps");
        Objects.requireNonNull(receivedAt, "Date avec fuseau obligatoire");
        Objects.requireNonNull(scope, "Périmètre obligatoire");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " obligatoire");
        }
    }
}
