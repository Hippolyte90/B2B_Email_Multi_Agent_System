package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.inbound.fixtures.LiveDemoCatalog;
import fr.paybridge.mas.adapters.inbound.javafx.ReviewWindow;
import fr.paybridge.mas.adapters.outbound.config.*;
import fr.paybridge.mas.adapters.outbound.context.*;
import fr.paybridge.mas.adapters.outbound.llm.*;
import fr.paybridge.mas.adapters.outbound.persistence.SqliteSession;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.configuration.*;
import fr.paybridge.mas.application.context.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.application.workspace.ReviewWorkspaceService;
import fr.paybridge.mas.application.workspace.MailboxService;
import fr.paybridge.mas.application.workspace.MailboxWorkspace;
import fr.paybridge.mas.application.workspace.LiveInboxFlow;
import fr.paybridge.mas.adapters.outbound.gmail.*;
import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.mail.*;
import javafx.application.Platform;
import javafx.stage.Stage;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Assemblage et cycle de vie du poste graphique ; seul ce package connaît tous les adaptateurs. */
final class GuiBootstrap {
    private GuiBootstrap() { }
    static void run(String[] args) throws Exception {
        if (System.getProperty("javafx.cachedir") == null) {
            var cache = Path.of("data/runtime/javafx-cache").toAbsolutePath().normalize();
            Files.createDirectories(cache);
            System.setProperty("javafx.cachedir", cache.toString());
        }
        boolean fake = false;
        Path database = Path.of("data/runtime/paybridge.sqlite");
        Path inference = Path.of("config/application/inference.properties");
        Path gmailCredentials=null;
        boolean explicitDatabase=false;
        boolean staticDemo=false,fastDemo=false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fake" -> fake = true;
                case "--static-demo" -> staticDemo=true;
                case "--demo-fast" -> fastDemo=true;
                case "--db" -> { if (++i >= args.length) { throw new IllegalArgumentException("Base requise"); } database = Path.of(args[i]); explicitDatabase=true; }
                case "--gmail-credentials" -> { if (++i >= args.length) { throw new IllegalArgumentException("Fichier OAuth bureau requis"); } gmailCredentials=Path.of(args[i]); }
                case "--config" -> { if (++i >= args.length) { throw new IllegalArgumentException("Configuration requise"); } inference = Path.of(args[i]); }
                default -> throw new IllegalArgumentException("Option graphique inconnue");
            }
        }
        if(fastDemo&&(staticDemo||gmailCredentials!=null)){throw new IllegalArgumentException("Le flux accéléré est réservé à la démonstration locale dynamique");}
        GmailClient gmail=null;EmailAddress account=null;
        if(gmailCredentials!=null){
            var oauth=GoogleDesktopOAuth.connect(gmailCredentials);gmail=new GmailClient(new GmailHttpTransport(oauth::token));account=gmail.profile();
            if(!explicitDatabase){database=Path.of("data/runtime/"+GmailMailbox.namespace(account)+"mail.sqlite");}
        }
        var clock = Clock.systemUTC(); var config = Path.of("config");
        var session = new SqliteSession(database, clock);
        var modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("paybridge-ui-work-", 0).factory());
        var arrivalTimer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("paybridge-arrivals").factory());
        var flowReference=new java.util.concurrent.atomic.AtomicReference<LiveInboxFlow>();
        var startupReference=new java.util.concurrent.atomic.AtomicReference<ModelStartup>();
        try {
            var store = session.store();
            var savedConfiguration = store.configuration().load();
            var inferenceConfig = savedConfiguration.isPresent()
                    ? new PropertiesInferenceConfiguration(new LoopbackEndpoint(java.net.URI.create(savedConfiguration.get().inference().endpoint())), savedConfiguration.get().inference().generation(), savedConfiguration.get().inference().framingOverhead())
                    : PropertiesInferenceConfiguration.load(inference);
            var loader = new PropertiesAgentConfigurationLoader(config);
            var registry = new AgentRegistry(savedConfiguration.isPresent() ? savedConfiguration.get().agents() : loader.load(), store.agents());
            var directory = new ApprovedSenderDirectory(gmail==null?Map.of(new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"), new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")):Map.of());
            var importer = new ImportEmailUseCase(store.conversations(), directory, store); var reader = new PropertiesEmailReader();
            var authorized = new HashMap<String, ClientScope>();
            if(gmail==null) {
                for(var message:store.messagesWithPrefix("demo-live-")){authorized.put(message.id(),message.scope());}
                if(staticDemo){for(var message:store.messagesWithPrefix("route-")){authorized.put(message.id(),message.scope());}}
            }
            if(gmail==null&&staticDemo) { try (var files = Files.list(Path.of("data/demo/routing"))) {
                for (var file : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                    var command = reader.read(file); importer.execute(command); authorized.put(command.id(), directory.resolve(command.sender()));
                }
            } }
            var fileCatalog = new PropertiesContextSourceCatalog(config); var fileReader = new LocalTextSourceReader(config);
            var baseline = savedConfiguration.isPresent() ? savedConfiguration.get() : new EditableConfiguration(0, registry.snapshot().agents(), fileCatalog.sources(), Map.of(),
                    new LocalInferenceSettings(inferenceConfig.endpoint().base().toString(), inferenceConfig.settings(), inferenceConfig.framingOverhead()));
            var editor = new ConfigurationEditor(registry, store.configuration(), fileReader, baseline);
            var activeSources = new ConfigurationSources(editor::current, fileReader);
            var catalog = new ArchivingContextSourceCatalog(activeSources, store.sources());
            var context = new ContextService(catalog, new ArchivingSourceReader(activeSources, store.sources()), new ConservativeTokenEstimator(), new LexicalContextSelectionPolicy(), new RecentConversationMemoryPolicy(12));
            LocalLlmGateway gateway = fake ? new DisabledLlmGateway() : new OllamaLocalLlmGateway(inferenceConfig.endpoint());
            var settings = inferenceConfig.settings(); var budget = new ContextBudget(settings.contextCapacity(), settings.outputLimit(), inferenceConfig.framingOverhead());
            var router = new RoutingAgent(registry, store.conversations(), store.decisions(), new PrepareContextUseCase(registry, store.conversations(), store.decisions(), context),
                    definition -> new ConfiguredSpecialistAgent(definition, gateway), settings, modelExecutor, clock, UUID::randomUUID, store.generations(), store, store.journal());
            var route = new RouteEmailUseCase(registry, store.conversations(), new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(config).load()), store.decisions(), clock, UUID::randomUUID);
            final var connectedGmail=gmail;final var senderAccount=account;
            EmailSender sender=gmail==null?store.simulatedSender():new GmailEmailSender(gmail,id->{
                String namespace=GmailMailbox.namespace(senderAccount);if(!id.startsWith(namespace)){throw new IllegalArgumentException("Autre compte Gmail");}return connectedGmail.message(id.substring(namespace.length()));
            });
            var workspace = new ReviewWorkspaceService(authorized, store.conversations(), store.drafts(), registry, route, new CorrectRoutingUseCase(registry, store.decisions(), clock), store.decisions(), router,
                    sender, store, new ReloadAgentsUseCase(registry, () -> { editor.reloadFiles(loader, fileCatalog); return editor.current().agents(); }), catalog, settings, budget, fake, gmail==null?new EmailAddress("support@paybridge.example"):account, clock).withConfigurationEditor(editor).withMailboxFlags(store.mailbox());
            var localMailbox = new MailboxService(workspace::inbox, store.drafts(), store.mailbox());
            MailboxWorkspace mailbox=gmail==null?localMailbox:new GmailMailbox(gmail,account,importer,store.conversations(),store.drafts(),store.mailbox(),localMailbox,workspace::authorizeImportedMessage);
            String mode = fake ? "Rédaction désactivée (--fake) : aucune réponse prédéfinie" : "Génération par un LLM local";
            boolean liveDemo=gmail==null&&!staticDemo,accelerated=fastDemo;
            var seeds=liveDemo?LiveDemoCatalog.campaign(LiveDemoCatalog.load(Path.of("data/demo/live")),UUID.randomUUID(),clock,new Random()):List.<java.util.function.Supplier<ImportEmailCommand>>of();
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                var window=new ReviewWindow(workspace, mailbox, worker, mode);
                window.show(new Stage(), () -> Thread.ofVirtual().name("paybridge-close").start(() -> {
                    try {var startup=startupReference.get();if(startup!=null){startup.close();}var flow=flowReference.get();if(flow!=null){flow.close();}arrivalTimer.shutdownNow(); modelExecutor.close(); worker.close(); session.close(); }
                    finally { Platform.exit(); }
                }));
                if(liveDemo){
                    var flow=new LiveInboxFlow(seeds,command->{importer.execute(command);workspace.authorizeImportedMessage(store.conversations().findMessage(command.id()).orElseThrow());},workspace,
                            (delay,action)->{var future=arrivalTimer.schedule(action,delay.toMillis(),TimeUnit.MILLISECONDS);return ()->future.cancel(false);},
                            LiveInboxFlow.randomDelays(new Random(),accelerated),worker,clock,event->Platform.runLater(()->window.flowEvent(event,accelerated)));
                    flowReference.set(flow);
                }
                if(gateway instanceof fr.paybridge.mas.application.port.LocalModelLoader modelLoader){
                    var startup=new ModelStartup(new ManagedOllamaLoader(Path.of("."),inferenceConfig.endpoint(),modelLoader),settings,modelExecutor,
                            ()->{var flow=flowReference.get();if(flow!=null){flow.start();}},
                            event->Platform.runLater(()->window.modelStartupEvent(event)));
                    startupReference.set(startup);window.onModelRetry(startup::start);startup.start();
                }else if(liveDemo){flowReference.get().start();}
            });
        } catch (Exception | LinkageError failure) {
            arrivalTimer.shutdownNow();modelExecutor.close(); worker.close(); session.close(); throw failure;
        }
    }
}
