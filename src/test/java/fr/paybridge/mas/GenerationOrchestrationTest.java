package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.conversation.HistoryEvent;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GenerationOrchestrationTest {
    private final ClientScope scope = ClientScope.client("client-a");
    private final EmailAddress alice = new EmailAddress("alice@client-a.example");
    private final AgentDefinition specialist = new AgentDefinition("Tech", 1, "Rôle technique", Set.of("technical"), "Instructions techniques privées au rôle", Set.of(), true, false);
    private final AgentDefinition fallback = new AgentDefinition("Generic", 1, "Accueil", Set.of("general"), "Clarification", Set.of(), true, true);
    private final AgentRegistry registry = new AgentRegistry(List.of(specialist, fallback));
    private final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
    private final InMemoryRoutingDecisionRepository decisions = new InMemoryRoutingDecisionRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);
    private final GenerationSettings settings = new GenerationSettings("test-model", 8192, 512, 0.2, 42, Duration.ofSeconds(5));
    private final ContextBudget budget = new ContextBudget(8192, 512, 64);

    private UUID setup(boolean ambiguous) {
        var importer = new ImportEmailUseCase(conversations, new ApprovedSenderDirectory(Map.of(alice, scope)));
        importer.execute(new ImportEmailCommand("message", "thread", alice, List.of(new EmailAddress("support@paybridge.example")),
                "Notifications SDK", "Demande technique synthétique", OffsetDateTime.parse("2026-11-12T12:00:00Z")));
        var id = UUID.randomUUID();
        var outcome = new RoutingOutcome("test-rules", 1, ambiguous ? RoutingOutcome.Status.AMBIGUOUS : RoutingOutcome.Status.SELECTED,
                List.of(new RoutingCandidate("Tech", 3, List.of()), new RoutingCandidate("Generic", 3, List.of())), ambiguous ? Optional.empty() : Optional.of("Tech"));
        decisions.add(new RoutingDecision(id, "message", "thread", scope, registry.snapshot().revision(), clock.instant(), outcome, List.of()));
        return id;
    }

    private RoutingAgent coordinator(LocalLlmGateway gateway, Executor executor) {
        var context = new PrepareContextUseCase(registry, conversations, decisions, new ContextService(List::of, s -> fail("Aucune source"),
                new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12)));
        return new RoutingAgent(registry, conversations, decisions, context, def -> new ConfiguredSpecialistAgent(def, gateway),
                settings, executor, clock, UUID::randomUUID);
    }

    private GeneratedReply reply(GenerationRequest request, String text) {
        return new GeneratedReply(request.taskId(), text, settings.model(), true, Duration.ZERO, OptionalLong.empty(), OptionalLong.empty());
    }

    private GenerationHandle start(RoutingAgent coordinator, UUID id) {
        return coordinator.start(id, scope, Set.of("Tech", "Generic"), Set.of(alice), Optional.empty(), budget);
    }

    @Test
    void traversesRouterSpecialistGatewayRouterWithCorrectRoleAndContext() {
        var id = setup(false);
        var coordinator = coordinator((request, cancellation) -> {
            assertTrue(request.context().systemInstructions().startsWith(specialist.systemPrompt()));
            assertTrue(request.context().userData().contains("Demande technique synthétique"));
            assertEquals(settings, request.settings());
            return reply(request, "Proposition de test");
        }, Runnable::run);
        var handle = start(coordinator, id);
        var result = handle.result().toCompletableFuture().join();
        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(handle.taskId(), result.reply().orElseThrow().taskId());
        assertEquals(List.of(AgentResult.Step.ROUTER_RECEIVED, AgentResult.Step.CONTEXT_PREPARED,
                AgentResult.Step.SPECIALIST_DELEGATED, AgentResult.Step.LLM_RETURNED, AgentResult.Step.ROUTER_RETURNED), result.trace());
        assertEquals(result, coordinator.latest("message", scope).orElseThrow());
        assertEquals(1, conversations.findById("thread").orElseThrow().history().stream().filter(e -> e.type() == HistoryEvent.Type.GENERATED).count());
        assertTrue(coordinator.latest("message", ClientScope.client("b")).isEmpty());
    }

    @Test
    void ambiguityBlocksBeforeAnyModelCall() {
        var id = setup(true);
        var coordinator = coordinator((r, c) -> fail("Aucune génération permise"), Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> start(coordinator, id));
    }

    @ParameterizedTest
    @EnumSource(value = GenerationFailure.class, names = {"UNAVAILABLE", "TIMEOUT", "INVALID_RESPONSE", "CONTEXT_LIMIT"})
    void gatewayErrorsReturnFailureWithoutAValidDraft(GenerationFailure failure) {
        var id = setup(false);
        var coordinator = coordinator((r, c) -> { throw new GenerationException(failure); }, Runnable::run);
        var result = start(coordinator, id).result().toCompletableFuture().join();
        assertEquals(AgentResult.Status.FAILED, result.status());
        assertEquals(Optional.of(failure), result.failure());
        assertTrue(result.reply().isEmpty());
        assertEquals(1, conversations.findById("thread").orElseThrow().history().size());
    }

    @Test
    void wrongTaskCorrelationAndNullReplyAreRejected() {
        var id = setup(false);
        for (LocalLlmGateway gateway : List.<LocalLlmGateway>of((r, c) -> new GeneratedReply(UUID.randomUUID(), "Incorrect", settings.model(), true,
                Duration.ZERO, OptionalLong.empty(), OptionalLong.empty()), (r, c) -> null)) {
            assertEquals(Optional.of(GenerationFailure.INVALID_RESPONSE), start(coordinator(gateway, Runnable::run), id)
                    .result().toCompletableFuture().join().failure());
        }
    }

    @Test
    void canCancelQueuedTaskBeforeCallingGateway() {
        var id = setup(false);
        var queue = new ArrayList<Runnable>();
        var coordinator = coordinator((r, c) -> fail("Annulée avant exécution"), queue::add);
        var handle = start(coordinator, id);
        handle.cancel().run();
        queue.getFirst().run();
        assertEquals(AgentResult.Status.CANCELLED, handle.result().toCompletableFuture().join().status());
        assertTrue(coordinator.latest("message", scope).isEmpty());
    }

    @Test
    void lateReplyFromSupersededGenerationCannotReplaceNewerReply() {
        var id = setup(false);
        var holder = new AtomicReference<RoutingAgent>();
        var latestHandle = new AtomicReference<GenerationHandle>();
        var calls = new AtomicInteger();
        var coordinator = coordinator((r, c) -> {
            if (calls.getAndIncrement() == 0) {
                latestHandle.set(start(holder.get(), id));
                return reply(r, "Ancien résultat tardif");
            }
            return reply(r, "Nouveau résultat");
        }, Runnable::run);
        holder.set(coordinator);
        var previous = start(coordinator, id);
        assertEquals(AgentResult.Status.SUPERSEDED, previous.result().toCompletableFuture().join().status());
        assertEquals("Nouveau résultat", latestHandle.get().result().toCompletableFuture().join().reply().orElseThrow().text());
        assertEquals("Nouveau résultat", coordinator.latest("message", scope).orElseThrow().reply().orElseThrow().text());
        assertEquals(2, conversations.findById("thread").orElseThrow().history().size());
    }

    @Test
    void routingCorrectionChangedDuringGenerationInvalidatesReply() {
        var id = setup(false);
        var coordinator = coordinator((r, c) -> {
            new CorrectRoutingUseCase(registry, decisions, clock).execute(id, "Generic", "Nouveau choix", scope, Set.of("Generic"));
            return reply(r, "Résultat devenu obsolète");
        }, Runnable::run);
        var result = start(coordinator, id).result().toCompletableFuture().join();
        assertEquals(Optional.of(GenerationFailure.STALE_CONTEXT), result.failure());
        assertTrue(result.reply().isEmpty());
    }

    @Test
    void gatewayCanBeReplacedWithoutChangingAgentOrCoordinator() {
        var id = setup(false);
        for (var text : List.of("Fournisseur A", "Fournisseur B")) {
            var result = start(coordinator((r, c) -> reply(r, text), Runnable::run), id).result().toCompletableFuture().join();
            assertEquals(text, result.reply().orElseThrow().text());
        }
    }

    @Test
    void registryChangedDuringGenerationInvalidatesReply() {
        var id = setup(false);
        var coordinator = coordinator((r, c) -> {
            var updated = new ArrayList<>(registry.snapshot().agents());
            updated.add(new AgentDefinition("NewAgent", 1, "Autre rôle", Set.of("other"), "Instructions", Set.of(), true, false));
            registry.replaceAll(updated);
            return reply(r, "Ancien registre");
        }, Runnable::run);
        assertEquals(Optional.of(GenerationFailure.STALE_CONTEXT), start(coordinator, id).result().toCompletableFuture().join().failure());
    }

    @Test
    void wrongScopePermissionsAndIncompatibleBudgetAreRejected() {
        var id = setup(false);
        var coordinator = coordinator((r, c) -> fail("Pas de génération"), Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(id, ClientScope.client("b"), Set.of("Tech"), Set.of(alice), Optional.empty(), budget));
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(id, scope, Set.of("Generic"), Set.of(alice), Optional.empty(), budget));
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(id, scope, Set.of("Tech"), Set.of(alice), Optional.empty(), new ContextBudget(4096, 512, 64)));
    }
}
