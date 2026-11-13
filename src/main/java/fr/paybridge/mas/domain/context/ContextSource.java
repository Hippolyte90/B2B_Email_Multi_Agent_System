package fr.paybridge.mas.domain.context;

import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.util.Objects;
import java.util.Set;

public record ContextSource(String id, long version, String reference, String provenance,
                            ClientScope scope, Visibility visibility, Set<String> agentIds,
                            Set<EmailAddress> allowedRecipients) {
    public enum Visibility { PUBLIC_APPROVED, CLIENT_SHAREABLE, INTERNAL_ONLY }

    public ContextSource {
        if (id == null || id.isBlank() || version < 1 || reference == null || reference.isBlank()
                || provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("Métadonnées de source invalides");
        }
        Objects.requireNonNull(scope);
        Objects.requireNonNull(visibility);
        agentIds = Set.copyOf(agentIds);
        allowedRecipients = Set.copyOf(allowedRecipients);
        if (agentIds.isEmpty() || agentIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Agents autorisés de la source obligatoires");
        }
        if (visibility == Visibility.PUBLIC_APPROVED && scope.clientId().isPresent()) {
            throw new IllegalArgumentException("Une source publique ne doit pas contenir de dossier client");
        }
        if (visibility == Visibility.CLIENT_SHAREABLE && (scope.clientId().isEmpty() || allowedRecipients.isEmpty())) {
            throw new IllegalArgumentException("Une source client exige un client et des destinataires approuvés");
        }
    }
}
