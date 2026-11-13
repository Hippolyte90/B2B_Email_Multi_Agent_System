package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.application.usecase.FindConversationUseCase;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Vérifie l’import des exemples synthétiques sans serveur, base persistante ou modèle. */
class DemoFixturesTest {
    @Test
    void importsAllSyntheticFixturesAndCanReimportWithoutDuplicates() throws Exception {
        var repository = new InMemoryConversationRepository();
        var directory = new ApprovedSenderDirectory(Map.of(
                new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
        var importer = new ImportEmailUseCase(repository, directory);
        var reader = new PropertiesEmailReader();
        try (var paths = Files.list(Path.of("data/demo/emails"))) {
            var fixtures = paths.filter(p -> p.toString().endsWith(".properties")).sorted().toList();
            assertEquals(5, fixtures.size());
            for (var path : fixtures) {
                assertEquals(ImportEmailUseCase.Result.IMPORTED, importer.execute(reader.read(path)));
                assertEquals(ImportEmailUseCase.Result.ALREADY_PRESENT, importer.execute(reader.read(path)));
            }
        }
        var finder = new FindConversationUseCase(repository);
        assertEquals(2, finder.execute("thread-integration-a", ClientScope.client("client-a"))
                .orElseThrow().messages().size());
        assertTrue(finder.execute("thread-onsite-b", ClientScope.client("client-a")).isEmpty());
        assertTrue(finder.execute("thread-unknown", ClientScope.unknown()).isPresent());
    }
}
