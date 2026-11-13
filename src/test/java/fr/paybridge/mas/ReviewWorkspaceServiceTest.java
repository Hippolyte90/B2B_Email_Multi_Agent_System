package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.mail.SimulatedEmailSender;
import fr.paybridge.mas.application.port.TransactionRunner;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.GenerationSettings;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewWorkspaceServiceTest {
    final DraftTestFixture f = new DraftTestFixture();
    final SimulatedEmailSender sender = new SimulatedEmailSender();
    final AtomicBoolean ambiguous = new AtomicBoolean();
    final AtomicReference<List<AgentDefinition>> definitions = new AtomicReference<>(f.registry.snapshot().agents());
    final ReviewWorkspaceService workspace = new ReviewWorkspaceService(Map.of("message", f.scope), f.conversations, f.drafts, f.registry,
            new RouteEmailUseCase(f.registry, f.conversations, request -> new RoutingOutcome("test", 1, ambiguous.get() ? RoutingOutcome.Status.AMBIGUOUS : RoutingOutcome.Status.SELECTED,
                    List.of(new RoutingCandidate("Tech", 3, List.of("Technique")), new RoutingCandidate("Generic", 3, List.of("Accueil"))), ambiguous.get() ? Optional.empty() : Optional.of("Tech")), f.decisions, f.clock, UUID::randomUUID),
            new CorrectRoutingUseCase(f.registry, f.decisions, f.clock), f.decisions, f.router, sender, TransactionRunner.direct(), new ReloadAgentsUseCase(f.registry, definitions::get), List::of,
            new GenerationSettings("test-model", 8192, 1024, .2, 42, Duration.ofSeconds(5)), f.budget, true, new EmailAddress("support@paybridge.example"), f.clock);
    Draft generate() { return workspace.generate("message").result().toCompletableFuture().join(); }
    @Test void openingOnlyReadsAndRoutesWithoutGeneratingOrSending() {
        assertEquals(1, workspace.inbox().size()); var view = workspace.open("message");
        assertTrue(view.draft().isEmpty()); assertEquals(Optional.of("Tech"), view.routing().effectiveAgentId()); assertTrue(f.requests.isEmpty()); assertTrue(sender.entries().isEmpty());
        assertEquals(view.routing(), workspace.open("message").routing());
    }
    @Test void unregisteredMessageIsDenied() {
        assertThrows(IllegalArgumentException.class, () -> workspace.open("other")); assertThrows(IllegalArgumentException.class, () -> workspace.generate("other")); assertTrue(f.requests.isEmpty());
    }
    @Test void ambiguityRequiresExplicitChoiceBeforeAnyGeneration() {
        ambiguous.set(true); assertTrue(workspace.open("message").routing().effectiveAgentId().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.generate("message")); assertTrue(f.requests.isEmpty());
        var chosen = workspace.choose("message", "Generic"); assertEquals(Optional.of("Generic"), chosen.routing().effectiveAgentId());
        var draft = generate(); assertEquals("Generic", draft.current().receipt().agent().id()); assertEquals(RoutingOutcome.Status.AMBIGUOUS, draft.current().receipt().routing().initialOutcome().status());
    }
    @Test void generatingCapturesUnapprovedVersionAndRefusesOverwrite() {
        var draft = generate(); assertEquals(Draft.State.GENERATED, draft.state()); assertTrue(draft.approval().isEmpty()); assertTrue(sender.entries().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.generate("message"));
    }
    @Test void editsInvalidateApprovalAndStaleDisplayedSnapshotIsRejected() {
        var draft = generate(); var approved = workspace.approveAndSend(draft);
        var edited = workspace.save(approved, new DraftContent("Nouvel objet", "Nouveau texte", approved.current().content().recipients()));
        assertEquals(2, edited.current().number()); assertTrue(edited.approval().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.approveAndSend(draft)); assertEquals(1, sender.entries().size());
    }
    @Test void approvalTransmitsExactEditedContentAndIsIdempotent() {
        var draft = generate(); var edited = workspace.save(draft, new DraftContent("Objet humain", "Réponse humaine", draft.current().content().recipients()));
        var sent = workspace.approveAndSend(edited); assertEquals(edited.current().content(), sender.entries().getFirst().approval().content());
        workspace.approveAndSend(sent); assertEquals(1, sender.entries().size()); assertTrue(f.conversations.findById("thread").orElseThrow().confirmedSent().isEmpty());
    }
    @Test void regenerationRetainsRejectedVersionAndUsesFeedback() {
        var first = generate(); var next = workspace.regenerate(first, Optional.of("Demander le SDK")).result().toCompletableFuture().join();
        assertEquals(2, next.versions().size()); assertEquals(first.current(), next.versions().getFirst()); assertTrue(next.approval().isEmpty());
        assertEquals(Optional.of("Demander le SDK"), f.requests.getLast().context().userFeedback());
    }
    @Test void reloadingConfigurationPreservesOriginAndInvalidatesPreparedRouting() throws Exception {
        var draft = generate(); var oldRoute = workspace.open("message").routing(); var old = f.specialist;
        definitions.set(List.of(new AgentDefinition(old.id(), 2, old.role(), old.categories(), "Prompt suivant", old.contextSources(), true, false), f.generic));
        assertEquals(2, workspace.reloadAgents().registry().snapshot().revision()); var view = workspace.open("message");
        assertNotEquals(oldRoute.id(), view.routing().id()); assertEquals(1, view.draft().orElseThrow().current().receipt().agent().version());
        var next = workspace.regenerate(draft, Optional.empty()).result().toCompletableFuture().join(); assertEquals(2, next.current().receipt().agent().version());
    }
    @Test void configurationFailureLeavesPreviousRegistryUsable() {
        definitions.set(List.of(f.specialist)); assertThrows(IllegalArgumentException.class, workspace::reloadAgents);
        assertEquals(1, f.registry.snapshot().revision()); assertEquals(Draft.State.GENERATED, generate().state());
    }
}
