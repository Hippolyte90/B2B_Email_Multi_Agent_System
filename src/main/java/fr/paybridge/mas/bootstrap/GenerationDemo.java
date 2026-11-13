package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.inbound.console.DraftReviewConsole;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryDraftRepository;
import fr.paybridge.mas.application.draft.DraftReviewService;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.adapters.outbound.mail.SimulatedEmailSender;
import fr.paybridge.mas.adapters.outbound.config.*;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.context.LocalTextSourceReader;
import fr.paybridge.mas.adapters.outbound.llm.*;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryRoutingDecisionRepository;
import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.adapters.outbound.persistence.SqliteSession;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.configuration.*;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.generation.AgentResult;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class GenerationDemo {
    private GenerationDemo() { }

    static void run(String[] args) throws Exception {
        boolean fake = false;
        boolean review = false;
        boolean demo = false;
        boolean fresh = false;
        boolean memory = false;
        Path database = null;
        String messageId = "route-002";
        String choice = null;
        Path inference = Path.of("config/application/inference.properties");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fake" -> fake = true;
                case "--review" -> review = true;
                case "--demo" -> demo = true;
                case "--fresh" -> fresh = true;
                case "--memory" -> memory = true;
                case "--db" -> { if (++i >= args.length) { throw new IllegalArgumentException("Base requise"); } database = Path.of(args[i]); }
                case "--message" -> { if (++i >= args.length) { throw new IllegalArgumentException("Identifiant requis"); } messageId = args[i]; }
                case "--choose" -> { if (++i >= args.length) { throw new IllegalArgumentException("Agent requis"); } choice = args[i]; }
                case "--config" -> { if (++i >= args.length) { throw new IllegalArgumentException("Fichier requis"); } inference = Path.of(args[i]); }
                default -> throw new IllegalArgumentException("Option de génération inconnue");
            }
        }
        if (demo && (!fake || !review)) { throw new IllegalArgumentException("La démonstration de relecture exige --review --fake --demo"); }
        if (database != null && (memory || demo)) { throw new IllegalArgumentException("--db incompatible avec --memory ou --demo"); }
        if (review && !demo && !memory && database == null) { database = Path.of("data/runtime/paybridge.sqlite"); }
        try (var session = database == null ? null : new SqliteSession(database, Clock.systemUTC())) {
            var storage = session == null ? null : session.store();
            if (session != null) {
                var recovered = session.recovered();
                System.out.printf("Reprise locale | tâches interrompues=%d | régénérations clôturées=%d | transmissions UNKNOWN=%d%n", recovered.generations(), recovered.regenerations(), recovered.deliveries());
            }
            var config = Path.of("config");
            var savedConfiguration = storage == null ? Optional.<EditableConfiguration>empty() : storage.configuration().load();
            var settings = savedConfiguration.isPresent()
                    ? new PropertiesInferenceConfiguration(new LoopbackEndpoint(java.net.URI.create(savedConfiguration.get().inference().endpoint())), savedConfiguration.get().inference().generation(), savedConfiguration.get().inference().framingOverhead())
                    : PropertiesInferenceConfiguration.load(inference);
            var definitions = savedConfiguration.isPresent() ? savedConfiguration.get().agents() : new PropertiesAgentConfigurationLoader(config).load();
            var registry = storage == null ? new AgentRegistry(definitions) : new AgentRegistry(definitions, storage.agents());
            ConversationRepository conversations = storage == null ? new InMemoryConversationRepository() : storage.conversations();
            RoutingDecisionRepository decisions = storage == null ? new InMemoryRoutingDecisionRepository() : storage.decisions();
            var transactions = storage == null ? TransactionRunner.direct() : storage;
            var completed = storage == null ? CompletedGenerationRepository.disabled() : storage.generations();
            var directory = new ApprovedSenderDirectory(Map.of(new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                    new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
            var importer = new ImportEmailUseCase(conversations, directory, transactions);
            var reader = new PropertiesEmailReader();
            try (var files = Files.list(Path.of("data/demo/routing"))) {
                for (var file : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) { importer.execute(reader.read(file)); }
            }
            var message = conversations.findMessage(messageId).orElseThrow(() -> new IllegalArgumentException("Message absent"));
            var allowed = registry.snapshot().activeAgents().stream().map(AgentDefinition::id).collect(Collectors.toSet());
            DraftRepository draftRepository = storage == null ? new InMemoryDraftRepository() : storage.drafts();
            var existing = review ? draftRepository.findByMessage(messageId).filter(d -> d.scope().equals(message.scope())) : Optional.<fr.paybridge.mas.domain.draft.Draft>empty();
            if (existing.isPresent() && fresh) { throw new IllegalArgumentException("Un brouillon existe ; utilisez le rejet et la régénération en console"); }
            ContextSourceCatalog catalog = new PropertiesContextSourceCatalog(config);
            SourceReader sourceReader = new LocalTextSourceReader(config);
            if (savedConfiguration.isPresent()) {
                var configurationSources = new ConfigurationSources(savedConfiguration::get, sourceReader);
                catalog = configurationSources; sourceReader = configurationSources;
            }
            if (storage != null) {
                catalog = new ArchivingContextSourceCatalog(catalog, storage.sources());
                sourceReader = new ArchivingSourceReader(sourceReader, storage.sources());
            }
            var contextService = new ContextService(catalog, sourceReader,
                    new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12));
            var contexts = new PrepareContextUseCase(registry, conversations, decisions, contextService);
            LocalLlmGateway gateway = fake ? new DisabledLlmGateway() : new OllamaLocalLlmGateway(settings.endpoint());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var router = new RoutingAgent(registry, conversations, decisions, contexts, def -> new ConfiguredSpecialistAgent(def, gateway),
                        settings.settings(), executor, Clock.systemUTC(), UUID::randomUUID, completed, transactions,
                        storage == null ? GenerationJournal.disabled() : storage.journal());
                var selectedRoute = existing.isPresent() && choice != null ? Optional.of(new CorrectRoutingUseCase(registry, decisions, Clock.systemUTC()).execute(
                        new RouteEmailUseCase(registry, conversations, new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(config).load()), decisions, Clock.systemUTC(), UUID::randomUUID)
                                .execute(messageId, message.scope(), allowed).id(), choice, "Choix explicite pour les prochaines régénérations", message.scope(), allowed).id()) : Optional.<UUID>empty();
                var reviews = new DraftReviewService(draftRepository, conversations, router, Clock.systemUTC(), UUID::randomUUID, transactions,
                        draft -> selectedRoute.orElseGet(() -> draft.current().receipt().routing().id()));
                EmailSender sender = storage == null ? new SimulatedEmailSender() : storage.simulatedSender();
                var delivery = new SendApprovedReplyUseCase(draftRepository, conversations, sender,
                        new EmailAddress("support@paybridge.example"), Clock.systemUTC(), UUID::randomUUID, transactions);
                var budget = new ContextBudget(settings.settings().contextCapacity(), settings.settings().outputLimit(), settings.framingOverhead());
                if (existing.isPresent()) {
                    System.out.println("REPRISE DU BROUILLON | id=" + existing.get().id() + " | aucun appel LLM ni envoi automatique");
                    review(reviews, message, existing.get(), allowed, budget, delivery, false, storage != null);
                    return;
                }
                var reusable = fresh || choice != null ? Optional.<fr.paybridge.mas.domain.generation.StoredGeneration>empty()
                        : new FindReusableGenerationUseCase(registry, decisions, completed).execute(messageId, message.scope(), allowed,
                                Set.of(message.sender()), settings.settings(), fake);
                AgentResult result;
                if (reusable.isPresent()) {
                    result = reusable.get().result();
                    if (review) { router.restoreCompleted(reusable.get(), message.scope()); }
                    System.out.printf("REPRISE SQLITE | tâche=%s | agent=%s | aucun nouvel appel LLM%n", result.taskId(), result.agentId());
                } else {
                    var route = new RouteEmailUseCase(registry, conversations, new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(config).load()),
                            decisions, Clock.systemUTC(), UUID::randomUUID).execute(messageId, message.scope(), allowed);
                    if (choice != null) {
                        route = new CorrectRoutingUseCase(registry, decisions, Clock.systemUTC()).execute(route.id(), choice, "Choix console avant génération", message.scope(), allowed);
                    }
                    var handle = router.start(route.id(), message.scope(), allowed, Set.of(message.sender()), Optional.empty(),
                            new ContextBudget(settings.settings().contextCapacity(), settings.settings().outputLimit(), settings.framingOverhead()));
                    System.out.printf("Génération | mode=%s | tâche=%s | agent=%s%n", fake ? "RÉDACTION DÉSACTIVÉE" : "LLM LOCAL", handle.taskId(), route.effectiveAgentId().orElseThrow());
                    result = handle.result().toCompletableFuture().get(settings.settings().timeout().toSeconds() + 10, TimeUnit.SECONDS);
                }
                System.out.println("Parcours : " + result.trace());
                if (result.status() != AgentResult.Status.COMPLETED) {
                    System.out.println("Génération sans brouillon valide : " + result.status() + " / " + result.failure().orElseThrow());
                    throw new IllegalStateException("La génération a échoué");
                }
                var reply = result.reply().orElseThrow();
                if (storage != null && !review) {
                    System.out.println("Stockage SQLite : " + database.toAbsolutePath().normalize());
                    System.out.println("--- PROPOSITION ARCHIVÉE NON APPROUVÉE ; AUCUN EMAIL ENVOYÉ ---");
                    System.out.println(reply.text());
                    System.out.println("Relance identique : reprise ; --fresh : nouvelle génération ; --review --db : relecture durable.");
                    return;
                }
                var draft = reviews.capture(message.id(), message.scope(), result.taskId());
                if (review) {
                    review(reviews, message, draft, allowed, budget, delivery, demo, storage != null);
                    return;
                }
                System.out.printf("Modèle=%s | simulation=%s | durée=%d ms | tokens entrée=%s / sortie=%s%n", reply.model(), reply.simulated(),
                        reply.elapsed().toMillis(), reply.inputTokens().isPresent() ? reply.inputTokens().getAsLong() : "non mesuré",
                        reply.outputTokens().isPresent() ? reply.outputTokens().getAsLong() : "non mesuré");
                System.out.println("--- PROPOSITION NON APPROUVÉE : RELIRE ET CORRIGER ---");
                System.out.println(reply.text());
                System.out.println("--- AUCUN EMAIL ENVOYÉ ; utilisez --review pour éditer, approuver ou rejeter ---");
            }
        }
    }

    private static void review(DraftReviewService reviews, fr.paybridge.mas.domain.mail.EmailMessage message, fr.paybridge.mas.domain.draft.Draft draft,
                               Set<String> allowed, ContextBudget budget, SendApprovedReplyUseCase delivery, boolean demo, boolean durable) throws java.io.IOException {
        java.io.Reader input = demo ? new java.io.StringReader("approuver\nediter data/demo/review-body.txt\nrejeter Restez en français et demandez la version du SDK.\nattendre\napprouver\nenvoyer\nenvoyer\nboite-sortie\nhistorique\nquitter\n")
                : new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8);
        new DraftReviewConsole(reviews, message, draft, allowed, budget, new java.io.BufferedReader(input), new java.io.PrintWriter(System.out, true), Optional.of(delivery),
                durable ? "Données enregistrées localement ; reprise après fermeture." : "Données en mémoire.").run();
    }
}
