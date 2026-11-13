package fr.paybridge.mas.bootstrap;

import fr.paybridge.mas.adapters.inbound.fixtures.PropertiesEmailReader;
import fr.paybridge.mas.adapters.outbound.context.ApprovedSenderDirectory;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryConversationRepository;
import fr.paybridge.mas.application.usecase.FindConversationUseCase;
import fr.paybridge.mas.application.usecase.ImportEmailUseCase;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Point d’entrée de l’interface graphique et des commandes de diagnostic. */
public final class DemoApplication {
    private DemoApplication() { }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--gui")) {
            try { GuiBootstrap.run(java.util.Arrays.copyOfRange(args, 1, args.length)); }
            catch (Exception | LinkageError error) {
                System.err.println("Interface indisponible : " + fr.paybridge.mas.adapters.inbound.presentation.PresentationErrors.message(error));
                System.err.println("Usage : --gui [--fake] [--db fichier.sqlite] [--config fichier-inference] [--gmail-credentials fichier-oauth-bureau.json] [--static-demo | --demo-fast]");
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--storage-demo")) {
            try {
                if (args.length > 2) { throw new IllegalArgumentException("Trop d'options"); }
                StorageDemo.run(args.length == 2 ? Path.of(args[1]) : Path.of("data/runtime/storage-demo.sqlite"));
            } catch (IOException | IllegalArgumentException | fr.paybridge.mas.application.port.PersistenceFailure exception) {
                System.err.println("Stockage impossible : vérifiez le chemin, les droits et la version du schéma. Usage : --storage-demo [fichier.sqlite]");
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--review")) {
            try {
                GenerationDemo.run(args);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                if (exception instanceof fr.paybridge.mas.application.port.PersistenceFailure storage) { System.err.println("Stockage : " + storage.code() + " (ALREADY_OPEN : fermez l'autre session de cette base)."); }
                System.err.println("Relecture impossible : vérifiez la configuration, le moteur et les options. Usage : --review [--fake] [--demo | --memory | --db fichier.sqlite] [--message id] [--choose agent] [--config fichier]. Un brouillon existant est repris ; utilisez la console pour le régénérer.");
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--generate")) {
            try {
                GenerationDemo.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                System.err.println("Génération impossible : vérifiez le moteur local, la configuration et le choix d'agent. "
                        + "Usage : --generate [--fake] [--message id] [--choose agent] [--config fichier] [--db fichier.sqlite] [--fresh]");
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--context")) {
            try {
                ContextDemo.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            } catch (IOException | IllegalArgumentException exception) {
                System.err.println("Contexte impossible : vérifiez les sources, les droits et le budget. "
                        + "Usage : --context [--attack] [--budget capacité]");
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--route")) {
            try {
                RoutingDemo.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            } catch (IOException | IllegalArgumentException exception) {
                System.err.println("Routage impossible : vérifiez les fixtures, la configuration et le choix d'agent. "
                        + "Usage : --route [dossier-fixtures] [--choose message-id agent-id]");
                System.exit(1);
            }
            return;
        }
        boolean agents = args.length > 0 && args[0].equals("--agents");
        if (args.length > (agents ? 2 : 1)) {
            System.err.println("Usage : java -jar target/mas-email-assistant-0.1.0-SNAPSHOT.jar [dossier-fixtures] | --agents [dossier-config]");
            System.exit(2);
        }
        try {
            if (agents) {
                AgentDemo.run(args.length == 2 ? Path.of(args[1]) : Path.of("config"));
                return;
            }
            run(args.length == 0 ? Path.of("data/demo/emails") : Path.of(args[0]));
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("Opération impossible : vérifiez le dossier et le format des fixtures ou des agents. "
                    + "Aucune génération ni aucun envoi n'ont été effectués.");
            System.exit(1);
        }
    }

    private static void run(Path directory) throws IOException {
        var repository = new InMemoryConversationRepository();
        var directoryService = new ApprovedSenderDirectory(Map.of(
                new EmailAddress("alice@client-a.example"), ClientScope.client("client-a"),
                new EmailAddress("bob@client-b.example"), ClientScope.client("client-b")));
        var importer = new ImportEmailUseCase(repository, directoryService);
        var finder = new FindConversationUseCase(repository);
        var reader = new PropertiesEmailReader();
        System.out.println("PayBridge - import de données synthétiques en mémoire");
        try (var files = Files.list(directory)) {
            var fixtures = files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList();
            if (fixtures.isEmpty()) {
                throw new IllegalArgumentException("Aucune fixture trouvée");
            }
            for (var file : fixtures) {
                var command = reader.read(file);
                var result = importer.execute(command);
                var scope = directoryService.resolve(command.sender());
                var conversation = finder.execute(command.conversationId(), scope).orElseThrow();
                // Métadonnées synthétiques uniquement ; ni corps ni adresses dans la sortie.
                System.out.printf("%s | message=%s | fil=%s | messages=%d | client=%s%n",
                        result, command.id(), conversation.id(), conversation.messages().size(),
                        scope.clientId().orElse("inconnu"));
            }
        }
        System.out.println("Aucun LLM connecté. Aucun email envoyé. Les données disparaissent à la fermeture.");
    }
}
