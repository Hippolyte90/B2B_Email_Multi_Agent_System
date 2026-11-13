package fr.paybridge.mas.domain.draft;

import fr.paybridge.mas.domain.mail.EmailAddress;

import java.util.List;

public record DraftContent(String subject, String body, List<EmailAddress> recipients) {
    public DraftContent {
        if (subject == null || subject.isBlank() || subject.contains("\r") || subject.contains("\n")
                || body == null || body.isBlank()) {
            throw new IllegalArgumentException("Sujet et corps non vides requis ; le sujet doit rester sur une ligne");
        }
        recipients = List.copyOf(recipients);
        if (recipients.isEmpty() || recipients.stream().distinct().count() != recipients.size()) {
            throw new IllegalArgumentException("Destinataires non vides et uniques requis");
        }
    }
}
