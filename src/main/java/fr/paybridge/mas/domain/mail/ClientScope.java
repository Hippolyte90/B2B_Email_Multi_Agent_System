package fr.paybridge.mas.domain.mail;

import java.util.Objects;
import java.util.Optional;

/** Périmètre fourni par une association approuvée, jamais par le corps de l'email. */
public record ClientScope(Optional<String> clientId, Optional<String> projectId) {
    public ClientScope {
        Objects.requireNonNull(clientId, "clientId obligatoire");
        Objects.requireNonNull(projectId, "projectId obligatoire");
        if (clientId.filter(String::isBlank).isPresent() || projectId.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("Identifiant de périmètre vide");
        }
        if (projectId.isPresent() && clientId.isEmpty()) {
            throw new IllegalArgumentException("Un projet nécessite un client");
        }
    }

    public static ClientScope unknown() {
        return new ClientScope(Optional.empty(), Optional.empty());
    }

    public static ClientScope client(String clientId) {
        return new ClientScope(Optional.of(clientId), Optional.empty());
    }
}
