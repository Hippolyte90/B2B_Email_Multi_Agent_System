package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesAgentConfigurationLoader;
import fr.paybridge.mas.adapters.outbound.config.PropertiesRoutingPolicyLoader;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryRoutingDecisionRepository;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.CorrectRoutingUseCase;
import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.application.usecase.RouteEmailUseCase;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class RoutingDemo {
    private RoutingDemo() { }

    static void run(String[] args) throws IOException {
        int index = 0;
        var fixtureDirectory = Path.of("data/demo/routing");
        if (args.length > 0 && !args[0].equals("--choose")) {
            fixtureDirectory = Path.of(args[index++]);
        }
        String chosenMessage = null;
        String chosenAgent = null;
        if (args.length - index == 3 && args[index].equals("--choose")) {
            chosenMessage = args[index + 1];
            chosenAgent = args[index + 2];
        } else if (args.length != index) {
            throw new IllegalArgumentException("Usage : --route [dossier-fixtures] [--choose message-id agent-id]");
        }

        var registry = new AgentRegistry(new PropertiesAgentConfigurationLoader(Path.of("config")).load());
        var strategy = new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(Path.of("config")).load());
        var conversations = new InMemoryConversationRepository();
        var decisions = new InMemoryRoutingDecisionRepository();
        var directory = new ApprovedSenderDirectory(Map.of(
                new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
        var importer = new ImportEmailUseCase(conversations, directory);
        var reader = new PropertiesEmailReader();
        var messages = new ArrayList<ImportEmailCommand>();
        try (var files = Files.list(fixtureDirectory)) {
            for (var file : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                var command = reader.read(file);
                importer.execute(command);
                messages.add(command);
            }
        }
        var requestedMessage = chosenMessage;
        if (messages.isEmpty() || (requestedMessage != null && messages.stream().noneMatch(m -> m.id().equals(requestedMessage)))) {
            throw new IllegalArgumentException("Aucune fixture ou message de correction absent");
        }
        var allowed = registry.snapshot().activeAgents().stream().map(AgentDefinition::id).collect(Collectors.toSet());
        var router = new RouteEmailUseCase(registry, conversations, strategy, decisions, Clock.systemUTC(), UUID::randomUUID);
        var corrector = new CorrectRoutingUseCase(registry, decisions, Clock.systemUTC());
        System.out.println("PayBridge - routage explicable (scores de correspondance)");
        for (var message : messages) {
            var scope = directory.resolve(message.sender());
            var decision = router.execute(message.id(), scope, allowed);
            var outcome = decision.initialOutcome();
            System.out.printf("%s | %s | choix=%s | stratégie=%s/v%d%n", message.id(), outcome.status(),
                    decision.effectiveAgentId().orElse("attente de choix humain"), outcome.strategyId(), outcome.strategyVersion());
            for (var candidate : outcome.candidates()) {
                System.out.printf("  %s : score=%d ; %s%n", candidate.agentId(), candidate.score(), String.join(" / ", candidate.reasons()));
            }
            if (message.id().equals(chosenMessage)) {
                var updated = corrector.execute(decision.id(), chosenAgent, "Choix explicite en console", scope, allowed);
                System.out.printf("  Correction humaine : %s ; décision initiale conservée (%s)%n",
                        updated.effectiveAgentId().orElseThrow(), updated.initialOutcome().status());
            }
        }
        System.out.println("Aucune génération ni aucun envoi. Les décisions et corrections restent en mémoire.");
    }
}
