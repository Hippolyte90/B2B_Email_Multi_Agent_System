package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryRoutingDecisionRepository;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.context.MemoryEntry;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrepareContextUseCaseTest {
    private final EmailAddress alice = new EmailAddress("alice@client-a.example");
    private final ClientScope scope = ClientScope.client("client-a");
    private final AgentRegistry registry = new AgentRegistry(List.of(agent("Tech", false), agent("Fallback", true)));
    private final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
    private final InMemoryRoutingDecisionRepository decisions = new InMemoryRoutingDecisionRepository();
    private final ContextBudget budget = new ContextBudget(8192, 512, 64);
    private final UUID decisionId = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static AgentDefinition agent(String id, boolean fallback) {
        return new AgentDefinition(id, 1, "Rôle", Set.of("technical"), "Prépare un brouillon", Set.of(), true, fallback);
    }

    @BeforeEach
    void setup() {
        var importer = new ImportEmailUseCase(conversations, new ApprovedSenderDirectory(Map.of(alice, scope)));
        for (int i = 1; i <= 3; i++) {
            importer.execute(new ImportEmailCommand("m" + i, "thread", alice, List.of(new EmailAddress("support@paybridge.example")),
                    "Sujet " + i, "Notifications SDK " + i, OffsetDateTime.parse("2026-11-12T08:00:00Z").plusHours(i - 1)));
        }
    }

    private void decision(boolean ambiguous) {
        var candidates = List.of(new RoutingCandidate("Tech", 3, List.of()), new RoutingCandidate("Fallback", 3, List.of()));
        var outcome = new RoutingOutcome("test", 1, ambiguous ? RoutingOutcome.Status.AMBIGUOUS : RoutingOutcome.Status.SELECTED,
                candidates, ambiguous ? Optional.empty() : Optional.of("Tech"));
        decisions.add(new RoutingDecision(decisionId, "m2", "thread", scope, registry.snapshot().revision(), Instant.EPOCH, outcome, List.of()));
    }

    private PrepareContextUseCase usecase() {
        return new PrepareContextUseCase(registry, conversations, decisions, new ContextService(List::of, s -> fail("Aucune source"),
                new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12)));
    }

    @Test
    void usesSelectedAgentAndOnlyEarlierConversationMessages() throws Exception {
        decision(false);
        var bundle = usecase().execute(decisionId, scope, Set.of("Tech"), Set.of(alice), Optional.of("Restez concis"), budget);
        assertEquals(List.of("m1"), bundle.history().stream().map(MemoryEntry::id).toList());
        assertTrue(bundle.userFeedback().isPresent());
        assertTrue(bundle.systemInstructions().startsWith("Prépare un brouillon"));
    }

    @Test
    void unresolvedAmbiguityCannotPrepareContext() {
        decision(true);
        assertThrows(IllegalArgumentException.class, () -> usecase().execute(decisionId, scope, Set.of("Tech"), Set.of(alice), Optional.empty(), budget));
    }

    @Test
    void incorrectScopeOrAgentPermissionCannotPrepareContext() {
        decision(false);
        assertThrows(IllegalArgumentException.class, () -> usecase().execute(decisionId, ClientScope.client("b"), Set.of("Tech"), Set.of(alice), Optional.empty(), budget));
        assertThrows(IllegalArgumentException.class, () -> usecase().execute(decisionId, scope, Set.of("Fallback"), Set.of(alice), Optional.empty(), budget));
    }

    @Test
    void changedRegistryInvalidatesContextPreparation() {
        decision(false);
        var updated = new java.util.ArrayList<>(registry.snapshot().agents());
        updated.add(agent("NewAgent", false));
        registry.replaceAll(updated);
        assertThrows(IllegalArgumentException.class, () -> usecase().execute(decisionId, scope, Set.of("Tech"), Set.of(alice), Optional.empty(), budget));
    }
}
