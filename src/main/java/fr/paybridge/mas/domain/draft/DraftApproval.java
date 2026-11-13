package fr.paybridge.mas.domain.draft;

import java.time.Instant;
import java.util.Objects;

/** Approbation du contenu exact et des destinataires ; aucune preuve d'envoi. */
public record DraftApproval(int version, DraftContent content, Instant at) {
    public DraftApproval {
        if (version < 1) { throw new IllegalArgumentException("Version invalide"); }
        Objects.requireNonNull(content);
        Objects.requireNonNull(at);
    }
}
