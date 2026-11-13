package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryRoutingDecisionRepository;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.routing.RoutingStrategy;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RouteEmailUseCaseTest {
    private final ClientScope scope = ClientScope.client("client-a");
    private final EmailAddress alice = new EmailAddress("alice@client.example");
    private final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
    private final InMemoryRoutingDecisionRepository decisions = new InMemoryRoutingDecisionRepository();
    private final AgentRegistry registry = new AgentRegistry(List.of(agent("Fallback", true, true), agent("Technical", true, false), agent("Inactive", false, false)));
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC);
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Set<String> allowed = Set.of("Fallback", "Technical", "Inactive");

    private static AgentDefinition agent(String id, boolean active, boolean fallback) {
        return new AgentDefinition(id, 1, "Rôle", Set.of("category"), "Prompt", Set.of(), active, fallback);
    }

    private void importMessage(String id, String time) {
        var importer = new ImportEmailUseCase(conversations, new ApprovedSenderDirectory(Map.of(alice, scope)));
        importer.execute(new ImportEmailCommand(id, "thread", alice, List.of(new EmailAddress("support@paybridge.example")),
                "Sujet", "Corps", OffsetDateTime.parse(time)));
    }

    private RoutingOutcome outcome(String selected) {
        return new RoutingOutcome("fake-strategy", 9, RoutingOutcome.Status.SELECTED,
                List.of(new RoutingCandidate(selected, 10, List.of("Motif de test"))), Optional.of(selected));
    }

    private RouteEmailUseCase router(RoutingStrategy strategy) {
        return new RouteEmailUseCase(registry, conversations, strategy, decisions, clock, () -> id);
    }

    private RoutingDecision initial() {
        importMessage("m", "2026-11-12T10:00:00+01:00");
        return router(request -> outcome("Technical")).execute("m", scope, allowed);
    }

    @Test
    void strategyIsSubstitutableAndDecisionIsStoredWithTraceMetadata() {
        var decision = initial();
        assertEquals(id, decision.id());
        assertEquals(clock.instant(), decision.decidedAt());
        assertEquals("fake-strategy", decision.initialOutcome().strategyId());
        assertEquals(9, decision.initialOutcome().strategyVersion());
        assertEquals(Optional.of(decision), decisions.find(id));
    }

    @Test
    void strategyReceivesOnlyEarlierSameThreadMessagesAndAllowedActiveAgents() {
        importMessage("old", "2026-11-12T09:00:00+01:00");
        importMessage("current", "2026-11-12T10:00:00+01:00");
        importMessage("future", "2026-11-12T11:00:00+01:00");
        router(request -> {
            assertEquals(List.of("old"), request.previousMessages().stream().map(m -> m.id()).toList());
            assertEquals(List.of("Fallback"), request.eligibleAgents().stream().map(AgentDefinition::id).toList());
            return new RoutingOutcome("fake", 1, RoutingOutcome.Status.FALLBACK,
                    List.of(new RoutingCandidate("Fallback", 0, List.of("Clarification"))), Optional.of("Fallback"));
        }).execute("current", scope, Set.of("Fallback", "Inactive"));
    }

    @Test
    void unauthorizedMessageIsRejectedBeforeStrategyIsCalled() {
        importMessage("m", "2026-11-12T10:00:00+01:00");
        assertThrows(IllegalArgumentException.class, () -> router(request -> fail("Stratégie ne doit pas être appelée"))
                .execute("m", ClientScope.client("other"), allowed));
        assertTrue(decisions.find(id).isEmpty());
    }

    @Test
    void missingAllowedFallbackIsRejectedBeforeStrategy() {
        importMessage("m", "2026-11-12T10:00:00+01:00");
        assertThrows(IllegalArgumentException.class, () -> router(request -> fail("Stratégie ne doit pas être appelée"))
                .execute("m", scope, Set.of("Technical")));
    }

    @Test
    void strategyCannotReturnAnInactiveOrUnauthorizedAgent() {
        importMessage("m", "2026-11-12T10:00:00+01:00");
        assertThrows(IllegalArgumentException.class, () -> router(request -> outcome("Inactive")).execute("m", scope, allowed));
        assertThrows(IllegalArgumentException.class, () -> router(request -> outcome("Technical")).execute("m", scope, Set.of("Fallback")));
        assertTrue(decisions.find(id).isEmpty());
    }

    @Test
    void humanCorrectionPreservesOriginalAndAllSuccessiveCorrections() {
        var initial = initial();
        var corrector = new CorrectRoutingUseCase(registry, decisions, clock);
        var first = corrector.execute(id, "Fallback", "Besoin de clarification", scope, allowed);
        var second = corrector.execute(id, "Technical", "Précision reçue", scope, allowed);
        assertEquals(initial.initialOutcome(), second.initialOutcome());
        assertEquals(Optional.of("Fallback"), first.effectiveAgentId());
        assertEquals(Optional.of("Technical"), second.effectiveAgentId());
        assertEquals(2, second.corrections().size());
        assertEquals(0, initial.corrections().size());
        assertEquals(clock.instant(), second.corrections().getLast().occurredAt());
    }

    @Test
    void ambiguousDecisionHasNoEffectiveAgentUntilCorrected() {
        importMessage("m", "2026-11-12T10:00:00+01:00");
        var decision = router(request -> new RoutingOutcome("fake", 1, RoutingOutcome.Status.AMBIGUOUS,
                List.of(new RoutingCandidate("Fallback", 3, List.of()), new RoutingCandidate("Technical", 3, List.of())), Optional.empty()))
                .execute("m", scope, allowed);
        assertTrue(decision.effectiveAgentId().isEmpty());
        var updated = new CorrectRoutingUseCase(registry, decisions, clock).execute(id, "Technical", "Choix humain", scope, allowed);
        assertEquals(Optional.of("Technical"), updated.effectiveAgentId());
        assertEquals(RoutingOutcome.Status.AMBIGUOUS, updated.initialOutcome().status());
    }

    @Test
    void correctionRejectsForeignScopeInactiveUnknownAndUnauthorizedAgents() {
        var original = initial();
        var corrector = new CorrectRoutingUseCase(registry, decisions, clock);
        assertThrows(IllegalArgumentException.class, () -> corrector.execute(id, "Technical", "Choix", ClientScope.client("b"), allowed));
        assertThrows(IllegalArgumentException.class, () -> corrector.execute(id, "Inactive", "Choix", scope, allowed));
        assertThrows(IllegalArgumentException.class, () -> corrector.execute(id, "Missing", "Choix", scope, Set.of("Missing")));
        assertThrows(IllegalArgumentException.class, () -> corrector.execute(id, "Technical", "Choix", scope, Set.of("Fallback")));
        assertEquals(original, decisions.find(id).orElseThrow());
    }

    @Test
    void changedRegistryRequiresNewRoutingBeforeCorrection() {
        initial();
        var definitions = new java.util.ArrayList<>(registry.snapshot().agents());
        definitions.add(agent("NewAgent", true, false));
        registry.replaceAll(definitions);
        assertThrows(IllegalArgumentException.class, () -> new CorrectRoutingUseCase(registry, decisions, clock)
                .execute(id, "Technical", "Choix", scope, allowed));
    }

    @Test
    void staleCorrectionCannotOverwriteMoreRecentDecision() {
        var original = initial();
        var updated = new CorrectRoutingUseCase(registry, decisions, clock).execute(id, "Fallback", "Choix", scope, allowed);
        assertThrows(IllegalArgumentException.class, () -> decisions.replace(original,
                original.corrected("Technical", "Modification obsolète", clock.instant())));
        assertEquals(updated, decisions.find(id).orElseThrow());
    }
}
