package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesAgentConfigurationLoader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesRoutingPolicyLoader;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.application.usecase.RouteEmailUseCase;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Vérifie la persistance et l’idempotence des imports et décisions de routage. */
final class StorageDemo {
    private StorageDemo() { }

    static void run(Path file) throws IOException {
        var registry = new AgentRegistry(new PropertiesAgentConfigurationLoader(Path.of("config")).load());
        var strategy = new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(Path.of("config")).load());
        var directory = new ApprovedSenderDirectory(Map.of(
                new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
        var allowed = registry.snapshot().activeAgents().stream().map(AgentDefinition::id).collect(Collectors.toSet());
        try (var store = new SqliteStore(file)) {
            var importer = new ImportEmailUseCase(store.conversations(), directory, store);
            var router = new RouteEmailUseCase(registry, store.conversations(), strategy, store.decisions(), Clock.systemUTC(), UUID::randomUUID);
            var reader = new PropertiesEmailReader();
            System.out.println("PayBridge - persistance des conversations et du routage (données synthétiques)");
            System.out.println("Base : " + file.toAbsolutePath().normalize());
            try (var files = Files.list(Path.of("data/demo/routing"))) {
                for (var fixture : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                    var command = reader.read(fixture);
                    var result = store.inTransaction(() -> {
                        var imported = importer.execute(command);
                        if (imported == ImportEmailUseCase.Result.IMPORTED) {
                            router.execute(command.id(), directory.resolve(command.sender()), allowed);
                        }
                        return imported;
                    });
                    var restored = store.conversations().findMessage(command.id()).orElseThrow();
                    System.out.printf("%s | message=%s | fil=%s%n", result, restored.id(), restored.conversationId());
                }
            }
            var summary = store.summary();
            System.out.printf("Schéma=%d | fils=%d | messages=%d | décisions=%d | audit=%d%n", summary.schema(),
                    summary.conversations(), summary.messages(), summary.decisions(), summary.auditEvents());
            System.out.println("Relancez la même commande : ALREADY_PRESENT, sans nouvelle décision ni doublon.");
            System.out.println("Ce diagnostic n’appelle aucun modèle et n’effectue aucun envoi.");
        }
    }
}
