package fr.paybridge.mas.adapters.inbound.console;

import fr.paybridge.mas.application.draft.DraftRegenerationHandle;
import fr.paybridge.mas.application.draft.DraftReviewService;
import fr.paybridge.mas.application.delivery.SendApprovedReplyUseCase;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.domain.delivery.SendAttempt;
import fr.paybridge.mas.domain.delivery.TransportMode;
import fr.paybridge.mas.domain.context.ContextBudget;
import fr.paybridge.mas.domain.draft.Draft;
import fr.paybridge.mas.domain.draft.DraftContent;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.mail.EmailMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Interface console : toute action est liée au dernier instantané effectivement affiché. */
public final class DraftReviewConsole {
    private final DraftReviewService service;
    private final EmailMessage message;
    private final Set<String> allowedAgents;
    private final ContextBudget budget;
    private final BufferedReader input;
    private final PrintWriter output;
    private final Optional<SendApprovedReplyUseCase> delivery;
    private final String storageDescription;
    private Draft shown;
    private DraftRegenerationHandle pending;
    private boolean viewingHistorical;

    public DraftReviewConsole(DraftReviewService service, EmailMessage message, Draft initial, Set<String> allowedAgents,
                               ContextBudget budget, BufferedReader input, PrintWriter output) {
        this(service, message, initial, allowedAgents, budget, input, output, Optional.empty());
    }

    public DraftReviewConsole(DraftReviewService service, EmailMessage message, Draft initial, Set<String> allowedAgents,
                               ContextBudget budget, BufferedReader input, PrintWriter output, Optional<SendApprovedReplyUseCase> delivery) {
        this(service, message, initial, allowedAgents, budget, input, output, delivery, "Données en mémoire.");
    }

    public DraftReviewConsole(DraftReviewService service, EmailMessage message, Draft initial, Set<String> allowedAgents,
                               ContextBudget budget, BufferedReader input, PrintWriter output, Optional<SendApprovedReplyUseCase> delivery, String storageDescription) {
        this.service = service; this.message = message; this.shown = initial; this.allowedAgents = Set.copyOf(allowedAgents);
        this.budget = budget; this.input = input; this.output = output;
        this.delivery = delivery;
        this.storageDescription = storageDescription;
    }

    public void run() throws IOException {
        output.println("Relecture : transport=" + delivery.map(s -> s.mode().name()).orElse("NON_CONFIGURE") + ". " + storageDescription);
        if (delivery.isPresent()) {
            output.println("La commande approuver déclenche la transmission de la version exacte à ce transport.");
            if (delivery.get().mode() == TransportMode.SIMULATED) { output.println("Envoi simulé uniquement : aucun email réel transmis."); }
        } else { output.println("Aucun transport connecté ; aucune transmission après approbation."); }
        output.println("Message reçu : " + message.subject());
        output.println(message.body());
        help();
        display(shown);
        try {
            String line;
            while ((line = input.readLine()) != null) {
                var parts = line.strip().split("\\s+", 2);
                var argument = parts.length > 1 ? parts[1] : "";
                try {
                    if (!argument.isEmpty() && Set.of("voir", "historique", "approuver", "regenerer", "attendre", "annuler", "aide", "quitter", "envoyer", "reessayer-envoi", "boite-sortie").contains(parts[0])) {
                        throw new IllegalArgumentException("Cette commande n'accepte pas d'argument");
                    }
                    if (viewingHistorical && Set.of("approuver", "editer", "objet", "destinataires", "rejeter", "regenerer", "envoyer", "reessayer-envoi").contains(parts[0])) {
                        throw new IllegalArgumentException("Affichez la version courante avec voir avant de décider");
                    }
                    switch (parts[0]) {
                        case "voir" -> display(service.find(shown.id(), message.scope()));
                        case "historique" -> history();
                        case "version" -> {
                            int number = Integer.parseInt(argument);
                            var current = service.find(shown.id(), message.scope());
                            if (number < 1 || number > current.versions().size()) { throw new IllegalArgumentException("Version inconnue"); }
                            var version = current.versions().get(number - 1);
                            output.println("Ancienne version consultée v" + number + " : " + version.content().subject());
                            output.println(version.content().body());
                            viewingHistorical = true;
                            output.println("Cette consultation n'autorise pas l'approbation d'une ancienne version.");
                        }
                        case "editer" -> {
                            var body = Files.readString(Path.of(required(argument)), StandardCharsets.UTF_8);
                            edit(new DraftContent(shown.current().content().subject(), body, shown.current().content().recipients()));
                        }
                        case "objet" -> edit(new DraftContent(required(argument), shown.current().content().body(), shown.current().content().recipients()));
                        case "destinataires" -> {
                            var recipients = Arrays.stream(required(argument).split(",", -1)).map(EmailAddress::new).toList();
                            edit(new DraftContent(shown.current().content().subject(), shown.current().content().body(), recipients));
                        }
                        case "approuver" -> {
                            var approved = service.approve(shown.id(), message.scope(), shown.revision(), shown.current().number());
                            display(approved);
                            output.println("Approbation du contenu exact enregistrée.");
                            if (delivery.isPresent()) { transmit(false); }
                            else { output.println("Aucun email envoyé."); }
                        }
                        case "envoyer" -> transmit(false);
                        case "reessayer-envoi" -> transmit(true);
                        case "boite-sortie" -> {
                            var sender = delivery.orElseThrow(() -> new IllegalArgumentException("Aucun transport configuré"));
                            output.println("Tentatives du brouillon courant :");
                            sender.attempts(shown.id(), message.scope()).forEach(this::displayAttempt);
                        }
                        case "rejeter" -> {
                            pending = service.rejectAndRegenerate(shown.id(), message.scope(), shown.revision(), shown.current().number(),
                                    Optional.of(argument).filter(s -> !s.isBlank()), allowedAgents, budget);
                            output.println("Version rejetée conservée ; régénération demandée. Tapez attendre ou annuler.");
                        }
                        case "regenerer" -> {
                            pending = service.regenerate(shown.id(), message.scope(), shown.revision(), shown.current().number(), allowedAgents, budget);
                            output.println("Nouvelle tentative demandée. Tapez attendre ou annuler.");
                        }
                        case "attendre" -> await();
                        case "annuler" -> {
                            if (pending == null) { throw new IllegalArgumentException("Aucune tentative dans cette console"); }
                            pending.cancel().run();
                            await();
                        }
                        case "aide" -> help();
                        case "quitter" -> { output.println("Session terminée ; les statuts de transmission restent ceux affichés."); return; }
                        case "" -> { }
                        default -> output.println("Commande inconnue. Tapez aide.");
                    }
                } catch (IllegalArgumentException | IOException error) {
                    output.println("Action refusée : " + error.getMessage());
                    output.println("Tapez voir pour afficher le dernier état avant de décider.");
                } catch (PersistenceFailure error) {
                    output.println("Écriture ou lecture locale impossible : " + error.code() + ". Aucune nouvelle transmission automatique ; tapez voir avant toute action.");
                }
                output.flush();
            }
        } finally {
            if (pending != null && !pending.result().toCompletableFuture().isDone()) { pending.cancel().run(); }
            output.flush();
        }
    }

    private void edit(DraftContent content) {
        var updated = service.edit(shown.id(), message.scope(), shown.revision(), shown.current().number(), content);
        display(updated);
        output.println("Toute modification effective exige une nouvelle approbation.");
    }

    private void transmit(boolean retry) {
        var sender = delivery.orElseThrow(() -> new IllegalArgumentException("Aucun transport configuré"));
        var before = shown;
        var attempt = retry ? sender.retryFailed(before.id(), message.scope(), before.revision(), before.current().number())
                : sender.send(before.id(), message.scope(), before.revision(), before.current().number());
        displayAttempt(attempt);
        var current = service.find(before.id(), message.scope());
        if (current.current().number() == before.current().number() && current.current().content().equals(before.current().content())) {
            shown = current;
        } else {
            output.println("Le brouillon a changé ; tapez voir avant une nouvelle décision.");
        }
    }

    private void displayAttempt(SendAttempt attempt) {
        output.printf("Transmission v%d | tentative=%s | mode=%s | statut=%s | code=%s%n", attempt.email().approval().version(),
                attempt.email().attemptId(), attempt.mode(), attempt.status(), attempt.result().map(r -> r.code()).orElse("IN_PROGRESS"));
        if (attempt.status() == fr.paybridge.mas.domain.delivery.DeliveryStatus.SIMULATED) { output.println("Envoi simulé enregistré en boîte locale ; aucun email réel livré."); }
        if (attempt.status() == fr.paybridge.mas.domain.delivery.DeliveryStatus.UNKNOWN) { output.println("Résultat inconnu : aucune relance ni modification permise ; vérification manuelle requise."); }
        if (attempt.status() == fr.paybridge.mas.domain.delivery.DeliveryStatus.FAILED) { output.println("Échec certain : reessayer-envoi constitue une nouvelle tentative manuelle ; aucune relance automatique."); }
    }

    private void await() {
        if (pending == null) { throw new IllegalArgumentException("Aucune tentative dans cette console"); }
        try {
            var outcome = pending.result().toCompletableFuture().get(610, TimeUnit.SECONDS);
            output.println("Régénération : " + outcome.generation().status() + " ; nouvelle version=" + outcome.newVersionAdded());
            outcome.generation().failure().ifPresent(f -> output.println("Motif : " + f));
            if (!outcome.newVersionAdded()) { output.println("La version rejetée reste consultable ; aucun nouveau texte approuvé."); }
            display(service.find(shown.id(), message.scope()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); pending.cancel().run();
            throw new IllegalArgumentException("Attente interrompue");
        } catch (ExecutionException | TimeoutException error) {
            pending.cancel().run();
            throw new IllegalArgumentException("Régénération non disponible ; la version précédente est conservée");
        }
    }

    private void display(Draft draft) {
        shown = draft;
        viewingHistorical = false;
        var version = draft.current();
        var receipt = version.receipt();
        output.printf("Brouillon v%d | révision=%d | état=%s | approuvé=%s | régénération en cours=%s%n",
                version.number(), draft.revision(), draft.state(), draft.approval().isPresent(), draft.pendingRegeneration().isPresent());
        output.println("Agent de génération : " + receipt.agent().id() + "/v" + receipt.agent().version());
        receipt.routing().initialOutcome().candidates().forEach(c -> output.println("Routage : " + c.agentId() + " score=" + c.score() + " " + c.reasons()));
        receipt.routing().corrections().forEach(c -> output.println("Correction humaine du routage : " + c.agentId()));
        output.println("Sources du contexte de génération : " + receipt.context().excerpts().stream()
                .map(e -> e.sourceId() + "/v" + e.sourceVersion() + " (" + e.provenance() + ")").distinct().toList());
        output.println("Objet : " + version.content().subject());
        output.println("Destinataires : " + version.content().recipients().stream().map(EmailAddress::value).toList());
        output.println("Simulation du modèle d'origine : " + version.generation().simulated());
        output.println("--- TEXTE À RELIRE ---");
        output.println(version.content().body());
        output.println("--- FIN DU TEXTE ---");
        draft.sendAttempts().forEach(this::displayAttempt);
        output.flush();
    }

    private void history() {
        var current = service.find(shown.id(), message.scope());
        current.versions().forEach(v -> output.println("Version " + v.number() + " | " + v.origin() + " | tâche=" + v.generation().taskId()));
        current.reviews().forEach(e -> output.println("Décision " + e.type() + " | version=" + e.version() + " | " + e.at()));
        output.println("Approbations historiques : " + current.approvals().stream().map(a -> a.version()).toList());
    }

    private void help() {
        output.println("Commandes : voir | version N | historique | editer fichier-UTF8 | objet texte | destinataires email,email");
        output.println("approuver | rejeter [commentaire] (régénère) | regenerer (réessaie après échec) | attendre | annuler | aide | quitter");
        if (delivery.isPresent()) { output.println("approuver transmet la version validée ; envoyer (idempotent) | boite-sortie | reessayer-envoi (échec certain uniquement)"); }
    }
    private static String required(String value) {
        if (value.isBlank()) { throw new IllegalArgumentException("Argument obligatoire"); }
        return value;
    }
}
