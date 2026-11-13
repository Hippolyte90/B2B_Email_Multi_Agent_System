package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesEmailReaderTest {
    @TempDir Path temporary;
    private final PropertiesEmailReader reader = new PropertiesEmailReader();
    private static final String VALID = """
            id=m1
            conversationId=local-thread
            sender=alice@client.example
            recipients=support@paybridge.example,team@paybridge.example
            subject=Intégration de paiement
            body=Échec constaté, merci de vérifier.
            receivedAt=2026-11-12T10:00:00+01:00
            """;

    private Path file(String content) throws IOException {
        return Files.writeString(temporary.resolve("email.properties"), content, StandardCharsets.UTF_8);
    }

    @Test
    void readsUtf8RecipientsAndExplicitLocalThread() throws IOException {
        var message = reader.read(file(VALID));
        assertEquals("Intégration de paiement", message.subject());
        assertEquals("Échec constaté, merci de vérifier.", message.body());
        assertEquals("local-thread", message.conversationId());
        assertEquals(2, message.recipients().size());
        assertEquals(3600, message.receivedAt().getOffset().getTotalSeconds());
    }

    @Test
    void missingBodyProducesUsefulValidationError() throws IOException {
        var path = file(VALID.replace("body=Échec constaté, merci de vérifier.\n", ""));
        var error = assertThrows(IllegalArgumentException.class, () -> reader.read(path));
        assertTrue(error.getMessage().contains("body"));
    }

    @Test
    void dateWithoutOffsetIsRejected() throws IOException {
        var path = file(VALID.replace("2026-11-12T10:00:00+01:00", "2026-11-12T10:00:00"));
        assertThrows(DateTimeParseException.class, () -> reader.read(path));
    }

    @Test
    void callerCannotInjectScopeThroughFixtureField() throws IOException {
        var path = file(VALID + "clientId=client-b\n");
        assertThrows(IllegalArgumentException.class, () -> reader.read(path));
    }

    @Test
    void emptyRecipientBetweenCommasIsRejected() throws IOException {
        var path = file(VALID.replace(",team@paybridge.example", ",,team@paybridge.example"));
        assertThrows(IllegalArgumentException.class, () -> reader.read(path));
    }

    @Test
    void missingFileProducesIoError() {
        assertThrows(IOException.class, () -> reader.read(temporary.resolve("missing.properties")));
    }
}
