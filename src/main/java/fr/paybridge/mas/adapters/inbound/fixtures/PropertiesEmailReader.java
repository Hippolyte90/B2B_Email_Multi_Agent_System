package fr.paybridge.mas.adapters.inbound.fixtures;

import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

/** Format de fixture interne, volontairement distinct d'un import RFC/IMAP. */
public final class PropertiesEmailReader {
    private static final Set<String> FIELDS = Set.of("id", "conversationId", "sender", "recipients", "subject", "body", "receivedAt");

    public ImportEmailCommand read(Path file) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!FIELDS.containsAll(properties.stringPropertyNames())) {
            throw new IllegalArgumentException("Champ de fixture inconnu ; le périmètre client n'est pas importable");
        }
        return new ImportEmailCommand(required(properties, "id"), required(properties, "conversationId"),
                new EmailAddress(required(properties, "sender")),
                Arrays.stream(required(properties, "recipients").split(",", -1)).map(EmailAddress::new).toList(),
                required(properties, "subject"), required(properties, "body"),
                OffsetDateTime.parse(required(properties, "receivedAt")));
    }

    private String required(Properties properties, String field) {
        var value = properties.getProperty(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ de fixture obligatoire : " + field);
        }
        return value;
    }
}
