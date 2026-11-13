package fr.paybridge.mas;

import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.context.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceArchivingTest {
    private final ContextSource source = new ContextSource("source", 1, "context/source.md", "Synthétique", ClientScope.unknown(), ContextSource.Visibility.PUBLIC_APPROVED, Set.of("Tech"), Set.of());
    private static final class Archive implements SourceArchive {
        final List<ContextSource> metadata = new ArrayList<>(); final Map<String, String> texts = new HashMap<>();
        @Override public void rememberMetadata(Collection<ContextSource> sources) { metadata.addAll(sources); }
        @Override public void rememberText(ContextSource source, String text) { texts.put(source.id(), text); }
        @Override public Optional<ContextSource> metadata(String id, long version) { return metadata.stream().filter(s -> s.id().equals(id) && s.version() == version).findFirst(); }
        @Override public Optional<String> text(String id, long version) { return Optional.ofNullable(texts.get(id)); }
    }
    @Test void catalogArchivesOnlyMetadata() throws Exception {
        var archive = new Archive();
        assertEquals(List.of(source), new ArchivingContextSourceCatalog(() -> List.of(source), archive).sources());
        assertEquals(List.of(source), archive.metadata); assertTrue(archive.texts.isEmpty());
    }
    @Test void successfulReadArchivesExactText() throws Exception {
        var archive = new Archive(); var reader = new ArchivingSourceReader(s -> "Texte synthétique exact", archive);
        assertEquals("Texte synthétique exact", reader.read(source)); assertEquals("Texte synthétique exact", archive.texts.get(source.id()));
    }
    @Test void readFailureDoesNotFallBackToOldArchive() {
        var archive = new Archive(); archive.texts.put(source.id(), "Ancien texte");
        var reader = new ArchivingSourceReader(s -> { throw new IOException("Fichier retiré"); }, archive);
        assertThrows(IOException.class, () -> reader.read(source)); assertEquals("Ancien texte", archive.texts.get(source.id()));
    }
    @Test void contextRejectsForeignAndInternalSourcesBeforeReadingOrArchivingText() throws Exception {
        var archive = new Archive();
        var foreign = new ContextSource("foreign", 1, "foreign.md", "Autre client", ClientScope.client("client-b"), ContextSource.Visibility.CLIENT_SHAREABLE, Set.of("Tech"), Set.of(new EmailAddress("bob@client-b.example")));
        var internal = new ContextSource("internal", 1, "internal.md", "Interne", ClientScope.unknown(), ContextSource.Visibility.INTERNAL_ONLY, Set.of("Tech"), Set.of());
        var agent = new AgentDefinition("Tech", 1, "Technique", Set.of("technical"), "Instructions", Set.of("foreign.md", "internal.md"), true, false);
        var alice = new EmailAddress("alice@client-a.example");
        var message = new EmailMessage("m", "c", alice, List.of(new EmailAddress("support@paybridge.example")), "SDK", "Aide SDK", OffsetDateTime.parse("2026-11-13T12:00:00Z"), ClientScope.client("client-a"));
        var service = new ContextService(new ArchivingContextSourceCatalog(() -> List.of(foreign, internal), archive),
                new ArchivingSourceReader(s -> fail("Texte interdit ouvert"), archive), new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12));
        service.prepare(new ContextRequest(message, agent, Set.of(alice), List.of(), Optional.empty(), new ContextBudget(8192, 1024, 64)));
        assertEquals(2, archive.metadata.size()); assertTrue(archive.texts.isEmpty());
    }
}
