package fr.paybridge.mas.adapters.outbound.config;

import fr.paybridge.mas.adapters.outbound.llm.LoopbackEndpoint;
import fr.paybridge.mas.domain.generation.GenerationSettings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;

public record PropertiesInferenceConfiguration(LoopbackEndpoint endpoint, GenerationSettings settings, int framingOverhead) {
    public static PropertiesInferenceConfiguration load(Path file) throws IOException {
        var properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) { throw new IllegalArgumentException("Propriété d'inférence dupliquée"); }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        if (!properties.stringPropertyNames().equals(Set.of("endpoint", "model", "capacity", "outputLimit", "temperature", "seed", "timeoutSeconds", "framingOverhead"))) {
            throw new IllegalArgumentException("Champs d'inférence absents ou inconnus");
        }
        var settings = new GenerationSettings(properties.getProperty("model"), Integer.parseInt(properties.getProperty("capacity")),
                Integer.parseInt(properties.getProperty("outputLimit")), Double.parseDouble(properties.getProperty("temperature")),
                Long.parseLong(properties.getProperty("seed")), Duration.ofSeconds(Long.parseLong(properties.getProperty("timeoutSeconds"))));
        int overhead = Integer.parseInt(properties.getProperty("framingOverhead"));
        if (overhead < 0 || (long) overhead + settings.outputLimit() >= settings.contextCapacity()) {
            throw new IllegalArgumentException("Marge de protocole invalide");
        }
        return new PropertiesInferenceConfiguration(new LoopbackEndpoint(URI.create(properties.getProperty("endpoint"))), settings, overhead);
    }
}
