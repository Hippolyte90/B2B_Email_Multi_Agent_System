package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.config.PropertiesContextSourceCatalog;
import fr.paybridge.mas.adapters.outbound.context.LocalTextSourceReader;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.domain.mail.ClientScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContextSourcesTest {
    @TempDir Path root;
    private static final String METADATA = """
            id=public
            version=1
            reference=context/public.md
            provenance=Documentation synthétique approuvée
            clientId=
            projectId=
            visibility=PUBLIC_APPROVED
            agents=Tech
            recipients=
            """;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(root.resolve("sources"));
        Files.createDirectories(root.resolve("context"));
        Files.writeString(root.resolve("sources/public.properties"), METADATA);
        Files.writeString(root.resolve("context/public.md"), "Intégration UTF-8 : échec SDK.");
    }

    @Test
    void loadsMetadataAndReadsUtf8DocumentSeparately() throws IOException {
        var source = new PropertiesContextSourceCatalog(root).sources().getFirst();
        assertEquals("Documentation synthétique approuvée", source.provenance());
        assertEquals("Intégration UTF-8 : échec SDK.", new LocalTextSourceReader(root).read(source));
    }

    @Test
    void catalogDoesNotOpenOrRequireReferencedTextFiles() throws IOException {
        Files.delete(root.resolve("context/public.md"));
        var source = new PropertiesContextSourceCatalog(root).sources().getFirst();
        assertThrows(IOException.class, () -> new LocalTextSourceReader(root).read(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version=0", "version=bad", "agents=", "agents=Tech,,Other", "visibility=SECRET", "clientId=client-a", "projectId=orphan"})
    void rejectsMalformedSourceMetadata(String replacement) throws IOException {
        var key = replacement.substring(0, replacement.indexOf('='));
        Files.writeString(root.resolve("sources/public.properties"), METADATA.replaceAll("(?m)^" + key + "=.*$", replacement));
        assertThrows(IllegalArgumentException.class, () -> new PropertiesContextSourceCatalog(root).sources());
    }

    @Test
    void clientShareableMetadataRequiresClientAndApprovedRecipients() throws IOException {
        Files.writeString(root.resolve("sources/public.properties"), METADATA.replace("PUBLIC_APPROVED", "CLIENT_SHAREABLE"));
        assertThrows(IllegalArgumentException.class, () -> new PropertiesContextSourceCatalog(root).sources());
        Files.writeString(root.resolve("sources/public.properties"), METADATA.replace("PUBLIC_APPROVED", "CLIENT_SHAREABLE")
                .replace("clientId=", "clientId=client-a").replace("recipients=", "recipients=alice@client-a.example"));
        assertEquals(ContextSource.Visibility.CLIENT_SHAREABLE, new PropertiesContextSourceCatalog(root).sources().getFirst().visibility());
    }

    @Test
    void rejectsDuplicateSourceIdsOrReferences() throws IOException {
        Files.writeString(root.resolve("sources/copy.properties"), METADATA);
        assertThrows(IllegalArgumentException.class, () -> new PropertiesContextSourceCatalog(root).sources());
        Files.writeString(root.resolve("sources/copy.properties"), METADATA.replace("id=public", "id=copy"));
        assertThrows(IllegalArgumentException.class, () -> new PropertiesContextSourceCatalog(root).sources());
    }

    @Test
    void rejectsUnknownMissingAndDuplicateProperties() throws IOException {
        for (var invalid : java.util.List.of(METADATA + "extra=1\n", METADATA.replace("agents=Tech\n", ""), METADATA + "version=2\n")) {
            Files.writeString(root.resolve("sources/public.properties"), invalid);
            assertThrows(IllegalArgumentException.class, () -> new PropertiesContextSourceCatalog(root).sources());
        }
    }

    private ContextSource reference(String reference) {
        return new ContextSource("test", 1, reference, "Fixture", ClientScope.unknown(),
                ContextSource.Visibility.PUBLIC_APPROVED, Set.of("Tech"), Set.of());
    }

    @Test
    void readerRejectsOutsideRootAndUnsupportedFormats() throws IOException {
        var reader = new LocalTextSourceReader(root);
        assertThrows(IllegalArgumentException.class, () -> reader.read(reference("../outside.md")));
        assertThrows(IllegalArgumentException.class, () -> reader.read(reference(root.resolve("context/public.md").toString())));
        Files.writeString(root.resolve("context/file.pdf"), "Pas un texte importable");
        assertThrows(IllegalArgumentException.class, () -> reader.read(reference("context/file.pdf")));
    }

    @Test
    void malformedUtf8FileIsRejected() throws IOException {
        Files.write(root.resolve("context/public.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> new LocalTextSourceReader(root).read(reference("context/public.md")));
    }
}
