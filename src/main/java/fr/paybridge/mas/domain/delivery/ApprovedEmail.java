package fr.paybridge.mas.domain.delivery;

import fr.paybridge.mas.domain.draft.DraftApproval;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.util.Objects;
import java.util.UUID;

/** Enveloppe figée : le transport reçoit le contenu exact de l'approbation. */
public record ApprovedEmail(UUID attemptId, UUID draftId, String messageId, String conversationId,
                             ClientScope scope, EmailAddress sender, DraftApproval approval) {
    public ApprovedEmail {
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(draftId);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(sender);
        Objects.requireNonNull(approval);
        if (messageId == null || messageId.isBlank() || conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Références d'enveloppe obligatoires");
        }
    }
}
