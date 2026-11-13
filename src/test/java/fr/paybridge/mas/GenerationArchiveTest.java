package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.InMemoryCompletedGenerationRepository;
import fr.paybridge.mas.application.usecase.FindReusableGenerationUseCase;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationArchiveTest {
    private final DraftTestFixture fixture = new DraftTestFixture();
    private final InMemoryCompletedGenerationRepository repository = new InMemoryCompletedGenerationRepository();
    private final GenerationSettings settings = new GenerationSettings("test-model", 8192, 1024, 0.2, 42, Duration.ofSeconds(5));
    private final FindReusableGenerationUseCase finder = new FindReusableGenerationUseCase(fixture.registry, fixture.decisions, repository);
    private StoredGeneration save() {
        var stored = new StoredGeneration(fixture.generate(), settings, fixture.clock.instant()); repository.add(stored); return stored;
    }
    private Optional<StoredGeneration> reusable() { return finder.execute("message", fixture.scope, fixture.allowed, Set.of(fixture.alice), settings, true); }

    @Test void unknownTaskIsAbsent() { assertTrue(repository.find(UUID.randomUUID(), fixture.scope).isEmpty()); assertTrue(reusable().isEmpty()); }
    @Test void successfulProposalRemainsUnapprovedAndCanBeFoundInScope() {
        var stored = save(); assertEquals(stored, repository.find(stored.result().taskId(), fixture.scope).orElseThrow()); assertEquals(Optional.of(stored), reusable());
        assertTrue(fixture.drafts.findByMessage("message").isEmpty());
    }
    @Test void scopesAreIsolated() {
        var stored = save(); var other = ClientScope.client("other");
        assertTrue(repository.find(stored.result().taskId(), other).isEmpty()); assertTrue(repository.latest("message", other).isEmpty());
    }
    @Test void sameTaskIsIdempotentButConflictingContentIsRefused() {
        var stored = save(); repository.add(stored);
        assertThrows(IllegalArgumentException.class, () -> repository.add(new StoredGeneration(stored.result(), settings, stored.completedAt().plusSeconds(1))));
        assertEquals(stored, repository.latest("message", fixture.scope).orElseThrow());
    }
    @Test void latestMeansMostRecentlyPublishedEvenWhenClockDoesNotAdvance() {
        var first = save(); var second = save();
        assertNotEquals(first.result().taskId(), second.result().taskId()); assertEquals(Optional.of(second), reusable());
    }
    @Test void changedRecipientsAndPermissionsBlockReuse() {
        save();
        assertTrue(finder.execute("message", fixture.scope, Set.of("Generic"), Set.of(fixture.alice), settings, true).isEmpty());
        assertTrue(finder.execute("message", fixture.scope, fixture.allowed, Set.of(new EmailAddress("bob@client-b.example")), settings, true).isEmpty());
    }
    @Test void simulationCannotBeReusedAsRealGeneration() {
        save(); assertTrue(finder.execute("message", fixture.scope, fixture.allowed, Set.of(fixture.alice), settings, false).isEmpty());
    }
    @Test void changedParametersBlockReuse() {
        save(); var changed = new GenerationSettings("test-model", 8192, 1024, 0.3, 42, Duration.ofSeconds(5));
        assertTrue(finder.execute("message", fixture.scope, fixture.allowed, Set.of(fixture.alice), changed, true).isEmpty());
    }
    @Test void correctedRoutingBlocksReuseOfOldProposal() {
        save(); var route = fixture.decisions.find(fixture.routeId).orElseThrow();
        fixture.decisions.replace(route, route.corrected("Generic", "Autre choix", fixture.clock.instant()));
        assertTrue(reusable().isEmpty());
    }
    @Test void changedRegistryBlocksReuseOfOldProposal() {
        save(); var old = fixture.specialist;
        fixture.registry.replaceAll(List.of(new fr.paybridge.mas.domain.agent.AgentDefinition(old.id(), 2, old.role(), old.categories(), "Prompt modifié", old.contextSources(), true, false), fixture.generic));
        assertTrue(reusable().isEmpty());
    }
    @Test void failedAndMismatchedResultsCannotBeArchived() {
        var failed = new AgentResult(UUID.randomUUID(), "Tech", AgentResult.Status.FAILED, Optional.empty(), Optional.of(GenerationFailure.TIMEOUT), List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new StoredGeneration(failed, settings, Instant.EPOCH));
        var result = fixture.generate();
        assertThrows(IllegalArgumentException.class, () -> new StoredGeneration(result, new GenerationSettings("other", 8192, 1024, 0.2, 42, Duration.ofSeconds(5)), Instant.EPOCH));
    }
}
