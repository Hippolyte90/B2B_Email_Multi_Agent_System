package fr.paybridge.mas.application.configuration;

import fr.paybridge.mas.domain.generation.GenerationSettings;
import java.net.URI;
import java.util.*;

/** Paramètres éditables ; validation sans réseau ni résolution DNS. */
public record LocalInferenceSettings(String endpoint, GenerationSettings generation, int framingOverhead) {
    public LocalInferenceSettings {
        Objects.requireNonNull(generation);
        var uri = URI.create(Objects.requireNonNull(endpoint));
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getPort() < 1 || uri.getPort() > 65535
                || framingOverhead < 0 || (long) framingOverhead + generation.outputLimit() >= generation.contextCapacity()) {
            throw new IllegalArgumentException("Endpoint loopback et budget cohérent obligatoires");
        }
    }
}
