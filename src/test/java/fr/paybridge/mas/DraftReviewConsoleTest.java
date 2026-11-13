package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.console.DraftReviewConsole;
import fr.paybridge.mas.domain.draft.Draft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DraftReviewConsoleTest {
    @TempDir Path temporary;

    private String run(DraftTestFixture fixture, Draft initial, String commands) throws Exception {
        var output = new StringWriter();
        new DraftReviewConsole(fixture.service, fixture.conversations.findMessage("message").orElseThrow(), initial, fixture.allowed, fixture.budget,
                new BufferedReader(new StringReader(commands)), new PrintWriter(output, true)).run();
        return output.toString();
    }

    @Test
    void rejectionRegenerationAndApprovalRequireNewlyDisplayedVersion() throws Exception {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var output = run(fixture, initial, "rejeter Demander le SDK\napprouver\nattendre\napprouver\nhistorique\nquitter\n");
        assertTrue(output.contains("Action refusée : Relecture obsolète"));
        assertTrue(output.contains("nouvelle version=true"));
        var current = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.APPROVED, current.state());
        assertEquals(2, current.current().number());
        assertEquals(2, current.approval().orElseThrow().version());
        assertTrue(output.contains("Aucun email envoyé"));
    }

    @Test
    void editingFromUtf8FileInvalidatesApprovalAndShowsNewText() throws Exception {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var path = temporary.resolve("réponse française.txt");
        Files.writeString(path, "Bonjour, pourriez-vous préciser la version du SDK ?");
        var output = run(fixture, initial, "approuver\nediter " + path + "\nhistorique\nquitter\n");
        var current = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.EDITED, current.state());
        assertTrue(current.approval().isEmpty());
        assertEquals("Bonjour, pourriez-vous préciser la version du SDK ?", current.current().content().body());
        assertTrue(output.contains("Approbations historiques : [1]"));
    }

    @Test
    void historicalViewCannotBeApprovedAndArgumentsCannotSelectAnOldVersion() throws Exception {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var output = run(fixture, initial, "version 1\napprouver\nvoir\napprouver 1\nquitter\n");
        assertTrue(output.contains("Affichez la version courante"));
        assertTrue(output.contains("Cette commande n'accepte pas d'argument"));
        assertTrue(fixture.service.find(initial.id(), fixture.scope).approval().isEmpty());
    }

    @Test
    void leavingSessionCancelsPendingRegenerationWithoutDiscardingRejection() throws Exception {
        var fixture = new DraftTestFixture();
        var initial = fixture.create();
        var queue = new java.util.ArrayList<Runnable>();
        fixture.executor.set(queue::add);
        run(fixture, initial, "rejeter Demander le SDK\nquitter\n");
        queue.getFirst().run();
        var current = fixture.service.find(initial.id(), fixture.scope);
        assertEquals(Draft.State.REJECTED, current.state());
        assertTrue(current.pendingRegeneration().isEmpty());
        assertEquals(1, current.versions().size());
    }
}
