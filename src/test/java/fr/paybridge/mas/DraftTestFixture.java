package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.draft.DraftReviewService;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import fr.paybridge.mas.domain.routing.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Assemblage de tests exclusivement en mémoire, sans réseau ni horloge réelle. */
final class DraftTestFixture {
    final ClientScope scope = ClientScope.client("client-a");
    final EmailAddress alice = new EmailAddress("alice@client-a.example");
    final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    final AgentDefinition specialist = new AgentDefinition("Tech", 1, "Technique", Set.of("technical"), "Réponds en français", Set.of(), true, false);
    final AgentDefinition generic = new AgentDefinition("Generic", 1, "Accueil", Set.of("general"), "Clarification", Set.of(), true, true);
    final AgentRegistry registry = new AgentRegistry(List.of(specialist, generic));
    final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
    final InMemoryRoutingDecisionRepository decisions = new InMemoryRoutingDecisionRepository();
    final InMemoryDraftRepository drafts = new InMemoryDraftRepository();
    final ContextBudget budget = new ContextBudget(8192, 1024, 64);
    final Set<String> allowed = Set.of("Tech", "Generic");
    final List<GenerationRequest> requests = new ArrayList<>();
    final AtomicReference<Executor> executor = new AtomicReference<>(Runnable::run);
    final AtomicReference<LocalLlmGateway> provider = new AtomicReference<>((request, cancellation) ->
            new GeneratedReply(request.taskId(), "Brouillon synthétique " + request.taskId(), request.settings().model(), true,
                    Duration.ZERO, OptionalLong.empty(), OptionalLong.empty()));
    final RoutingAgent router;
    final DraftReviewService service;
    final UUID routeId = new UUID(1, 1);

    DraftTestFixture() {
        new ImportEmailUseCase(conversations, new ApprovedSenderDirectory(Map.of(alice, scope))).execute(new ImportEmailCommand(
                "message", "thread", alice, List.of(new EmailAddress("support@paybridge.example")), "Question SDK", "Aide pour notre intégration",
                OffsetDateTime.parse("2026-11-13T10:00:00Z")));
        decisions.add(new RoutingDecision(routeId, "message", "thread", scope, registry.snapshot().revision(), clock.instant(),
                new RoutingOutcome("rules", 1, RoutingOutcome.Status.SELECTED, List.of(new RoutingCandidate("Tech", 3, List.of("Signal technique"))), Optional.of("Tech")), List.of()));
        var context = new PrepareContextUseCase(registry, conversations, decisions, new ContextService(List::of, source -> "",
                new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12)));
        var sequence = new AtomicLong();
        java.util.function.Supplier<UUID> ids = () -> new UUID(0, sequence.incrementAndGet());
        router = new RoutingAgent(registry, conversations, decisions, context, definition -> new ConfiguredSpecialistAgent(definition, (request, cancellation) -> {
            requests.add(request);
            return provider.get().generate(request, cancellation);
        }), new GenerationSettings("test-model", 8192, 1024, 0.2, 42, Duration.ofSeconds(5)),
                command -> executor.get().execute(command), clock, ids);
        service = new DraftReviewService(drafts, conversations, router, clock, ids);
    }

    AgentResult generate() {
        return router.start(routeId, scope, allowed, Set.of(alice), Optional.empty(), budget).result().toCompletableFuture().join();
    }
    Draft create() {
        var result = generate();
        return service.capture("message", scope, result.taskId());
    }
    Draft reject(Draft draft, Optional<String> feedback) {
        return service.reject(draft.id(), scope, draft.revision(), draft.current().number(), feedback);
    }
}
