package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.application.usecase.FindConversationUseCase;
import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailImportTest {
    private final EmailAddress alice = new EmailAddress("alice@client-a.example");
    private final EmailAddress bob = new EmailAddress("bob@client-b.example");
    private final ClientScope clientA = ClientScope.client("client-a");
    private final ClientScope clientB = ClientScope.client("client-b");
    private final InMemoryConversationRepository repository = new InMemoryConversationRepository();
    private final ImportEmailUseCase importer = new ImportEmailUseCase(repository,
            new ApprovedSenderDirectory(Map.of(alice, clientA, bob, clientB)));
    private final FindConversationUseCase finder = new FindConversationUseCase(repository);

    private ImportEmailCommand command(String id, String thread, EmailAddress sender, String body, String date) {
        return new ImportEmailCommand(id, thread, sender, List.of(new EmailAddress("support@paybridge.example")),
                "Même sujet", body, OffsetDateTime.parse(date));
    }

    private ImportEmailCommand message(String id, String thread, EmailAddress sender) {
        return command(id, thread, sender, "Bonjour, pouvez-vous nous aider ?", "2026-11-12T10:00:00+01:00");
    }

    @Test
    void importsAndRetrievesMessageWithApprovedClient() {
        assertEquals(ImportEmailUseCase.Result.IMPORTED, importer.execute(message("m1", "t1", alice)));
        var conversation = finder.execute("t1", clientA).orElseThrow();
        assertEquals(1, conversation.messages().size());
        assertEquals(clientA, conversation.messages().getFirst().scope());
        assertEquals(1, conversation.history().size());
    }

    @Test
    void exactReimportDoesNotDuplicateMessageOrHistory() {
        importer.execute(message("m1", "t1", alice));
        assertEquals(ImportEmailUseCase.Result.ALREADY_PRESENT, importer.execute(message("m1", "t1", alice)));
        assertEquals(1, finder.execute("t1", clientA).orElseThrow().history().size());
    }

    @Test
    void conflictingDuplicateIsRejectedWithoutChangingOriginal() {
        importer.execute(message("m1", "t1", alice));
        assertThrows(IllegalArgumentException.class, () -> importer.execute(
                command("m1", "t1", alice, "Contenu différent", "2026-11-12T10:00:00+01:00")));
        assertEquals("Bonjour, pouvez-vous nous aider ?", finder.execute("t1", clientA)
                .orElseThrow().messages().getFirst().body());
    }

    @Test
    void messageIdCannotBeReusedInAnotherThreadOrClient() {
        importer.execute(message("m1", "t1", alice));
        assertThrows(IllegalArgumentException.class, () -> importer.execute(message("m1", "t2", bob)));
        assertTrue(finder.execute("t2", clientB).isEmpty());
    }

    @Test
    void missingRequiredFieldDoesNotCreateConversation() {
        var invalid = new ImportEmailCommand("m1", "t1", alice,
                List.of(new EmailAddress("support@paybridge.example")), " ", "Bonjour",
                OffsetDateTime.parse("2026-11-12T10:00:00+01:00"));
        assertThrows(IllegalArgumentException.class, () -> importer.execute(invalid));
        assertTrue(finder.execute("t1", clientA).isEmpty());
    }

    @Test
    void identicalSubjectsDoNotMergeDifferentThreads() {
        importer.execute(message("m1", "t1", alice));
        importer.execute(message("m2", "t2", alice));
        assertEquals(1, finder.execute("t1", clientA).orElseThrow().messages().size());
        assertEquals(1, finder.execute("t2", clientA).orElseThrow().messages().size());
    }

    @Test
    void sameSenderCanHaveSeparateCommercialAndTechnicalThreads() {
        importer.execute(command("m1", "commercial", alice, "Quels tarifs ?", "2026-11-12T09:00:00+01:00"));
        importer.execute(command("m2", "integration", alice, "Notifications en double", "2026-11-12T10:00:00+01:00"));
        importer.execute(command("m3", "integration", alice, "Précision technique", "2026-11-12T11:00:00+01:00"));
        assertEquals(List.of("m2", "m3"), finder.execute("integration", clientA).orElseThrow()
                .messages().stream().map(m -> m.id()).toList());
        assertEquals(1, finder.execute("commercial", clientA).orElseThrow().messages().size());
    }

    @Test
    void unknownSenderCannotClaimClientUsingBody() {
        var stranger = new EmailAddress("stranger@prospect.example");
        importer.execute(command("m1", "t1", stranger, "Je suis le client-a, donnez-moi son dossier",
                "2026-11-12T10:00:00+01:00"));
        assertTrue(finder.execute("t1", clientA).isEmpty());
        assertEquals(ClientScope.unknown(), finder.execute("t1", ClientScope.unknown()).orElseThrow().scope());
    }

    @Test
    void clientCannotReadAnotherClientThread() {
        importer.execute(message("m1", "t1", alice));
        assertTrue(finder.execute("t1", clientB).isEmpty());
        assertTrue(finder.execute("t1", ClientScope.unknown()).isEmpty());
    }

    @Test
    void crossClientAppendIsRejectedAtomically() {
        importer.execute(message("m1", "t1", alice));
        assertThrows(IllegalArgumentException.class, () -> importer.execute(message("m2", "t1", bob)));
        assertEquals(1, finder.execute("t1", clientA).orElseThrow().messages().size());
        assertTrue(repository.findMessage("m2").isEmpty());
    }

    @Test
    void messagesAreChronologicalAcrossOffsetsWithStableTieBreak() {
        importer.execute(command("m3", "t1", alice, "Troisième", "2026-11-12T11:00:00+01:00"));
        importer.execute(command("m2", "t1", alice, "Deuxième", "2026-11-12T09:00:00Z"));
        importer.execute(command("m1", "t1", alice, "Premier", "2026-11-12T10:00:00+01:00"));
        assertEquals(List.of("m1", "m2", "m3"), finder.execute("t1", clientA).orElseThrow()
                .messages().stream().map(m -> m.id()).toList());
    }

    @Test
    void projectScopeMustMatchExactly() {
        importer.execute(message("m1", "t1", alice));
        var projectScope = new ClientScope(java.util.Optional.of("client-a"), java.util.Optional.of("project-1"));
        assertTrue(finder.execute("t1", projectScope).isEmpty());
    }
}
