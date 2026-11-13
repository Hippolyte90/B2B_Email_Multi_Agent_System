package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.domain.draft.DraftContent;
import fr.paybridge.mas.domain.mail.EmailAddress;
import java.util.*;

public record ReplyEditor(String subject, String body, String recipients) {
    public ReplyEditor { Objects.requireNonNull(subject); Objects.requireNonNull(body); Objects.requireNonNull(recipients); }
    public static ReplyEditor empty() { return new ReplyEditor("", "", ""); }
    public static ReplyEditor of(DraftContent content) { return new ReplyEditor(content.subject(), content.body(), String.join(", ", content.recipients().stream().map(EmailAddress::value).toList())); }
    public DraftContent content() { return new DraftContent(subject, body, Arrays.stream(recipients.split(",", -1)).map(String::strip).map(EmailAddress::new).toList()); }
    public boolean valid() { try { content(); return true; } catch (IllegalArgumentException | NullPointerException invalid) { return false; } }
    public String validationMessage() {
        if (subject.isBlank() || subject.contains("\n") || subject.contains("\r")) { return "Saisissez un objet non vide sur une seule ligne."; }
        if (body.isBlank()) { return "Le texte de réponse ne peut pas être vide."; }
        return valid() ? "Modifications non enregistrées : une nouvelle approbation sera nécessaire." : "Destinataires invalides, vides ou dupliqués : corrigez la liste avant d'enregistrer.";
    }
}
