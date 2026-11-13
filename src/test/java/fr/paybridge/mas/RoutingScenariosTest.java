package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesAgentConfigurationLoader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesRoutingPolicyLoader;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryRoutingDecisionRepository;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.application.usecase.RouteEmailUseCase;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.routing.RoutingOutcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoutingScenariosTest {
    @Test
    void distinguishesSevenSpecifiedBusinessScenariosWithoutAnyModel() throws Exception {
        var registry = new AgentRegistry(new PropertiesAgentConfigurationLoader(Path.of("config")).load());
        var conversations = new InMemoryConversationRepository();
        var directory = new ApprovedSenderDirectory(Map.of(
                new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
        var importer = new ImportEmailUseCase(conversations, directory);
        var reader = new PropertiesEmailReader();
        try (var files = Files.list(Path.of("data/demo/routing"))) {
            for (var file : files.sorted().toList()) {
                importer.execute(reader.read(file));
            }
        }
        var router = new RouteEmailUseCase(registry, conversations,
                new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(Path.of("config")).load()),
                new InMemoryRoutingDecisionRepository(), Clock.fixed(Instant.parse("2026-11-12T14:00:00Z"), ZoneOffset.UTC), UUID::randomUUID);
        var allowed = registry.snapshot().activeAgents().stream().map(AgentDefinition::id).collect(Collectors.toSet());
        var expected = Map.of("route-001", "CommercialAgent", "route-002", "IntegrationAgent", "route-003", "OnsiteSupportAgent",
                "route-004", "AccountAdminAgent", "route-006", "GenericAgent", "route-007", "IntegrationAgent");
        for (var entry : expected.entrySet()) {
            var scope = conversations.findMessage(entry.getKey()).orElseThrow().scope();
            assertEquals(entry.getValue(), router.execute(entry.getKey(), scope, allowed).effectiveAgentId().orElseThrow(), entry.getKey());
        }
        var ambiguous = router.execute("route-005", ClientScope.client("client-b"), allowed);
        assertEquals(RoutingOutcome.Status.AMBIGUOUS, ambiguous.initialOutcome().status());
        assertTrue(ambiguous.effectiveAgentId().isEmpty());
        assertEquals(java.util.Set.of("IntegrationAgent", "OnsiteSupportAgent"), ambiguous.initialOutcome().candidates()
                .stream().map(c -> c.agentId()).collect(Collectors.toSet()));
    }
}
