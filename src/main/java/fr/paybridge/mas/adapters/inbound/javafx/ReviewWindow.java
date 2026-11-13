package fr.paybridge.mas.adapters.inbound.javafx;

import fr.paybridge.mas.adapters.inbound.presentation.*;
import fr.paybridge.mas.application.workspace.ReviewWorkspace;
import fr.paybridge.mas.application.workspace.MailboxWorkspace;
import fr.paybridge.mas.application.workspace.LiveInboxFlow;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.mail.EmailMessage;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Screen;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Adaptateur de vue uniquement : aucune lecture fichier, JDBC, inférence ou règle métier. */
public final class ReviewWindow {
    private final ReviewPresenter presenter;
    private final ConfigurationPane configurationPane;
    private final MailboxPane mailboxPane;
    private final PendingValidationPane pendingPane;
    private final TabPane tabs = new TabPane();
    private Tab assistantTab;
    private Tab pendingTab;
    private ScrollPane reviewEditor;
    private Set<String> pendingIds=Set.of();
    private final Label flowStatus=new Label();
    private final Label modelStatus=new Label();
    private final Button modelRetry=new Button("Réessayer le chargement du modèle");
    private boolean closed;
    private final Label notice = new Label("Chargement…"), route = new Label(), versionState = new Label(), mode = new Label();
    private final TextArea incoming = readOnly(), body = new TextArea(), feedback = new TextArea(), history = readOnly(), outbox = readOnly(), config = readOnly();
    private final TextField subject = new TextField(), recipients = new TextField();
    private final ComboBox<String> agents = new ComboBox<>(), configurationAgents = new ComboBox<>();
    private final ComboBox<Integer> versions = new ComboBox<>();
    private final Button choose = new Button("Confirmer le choix"), generate = new Button("Générer une réponse"), cancel = new Button("Annuler la génération"),
            save = new Button("Enregistrer la version"), approve = new Button("Approuver et simuler l'envoi"), regenerate = new Button("Rejeter / régénérer"),
            retry = new Button("Réessayer l'envoi après échec"), revert = new Button("Revenir au texte enregistré"), refresh = new Button("Actualiser"), reload = new Button("Recharger les agents");
    private final ProgressIndicator progress = new ProgressIndicator();
    private ReviewState rendered;
    private ReviewWorkspace.Configuration configuration;
    private boolean rendering;

    public ReviewWindow(ReviewWorkspace workspace, MailboxWorkspace mailbox, Executor worker, String modeText) {
        presenter = new ReviewPresenter(workspace, worker, Platform::runLater, this::render);
        configurationPane = new ConfigurationPane(presenter, new VBox());
        pendingPane=new PendingValidationPane(()->presenter.state().busy()||presenter.state().dirty(),id->{mountEditor(true);presenter.select(id);});
        mailboxPane = new MailboxPane(mailbox, worker, () -> presenter.state().busy() || presenter.state().dirty(), id -> {
            if (presenter.state().busy() || presenter.state().dirty()) { return; }
            presenter.select(id); tabs.getSelectionModel().select(assistantTab);
        },this::updatePending);
        mode.setText(modeText + (mailbox.connected()?"  •  Gmail connecté — envoi réel après approbation":"  •  Messagerie locale — Gmail non connecté  •  Envoi simulé uniquement"));
        if(mailbox.connected()){approve.setText("Approuver et envoyer réellement");}
    }
    public void show(Stage stage, Runnable onClose) {
        var title = new Label("PayBridge"); title.getStyleClass().add("brand");
        var subtitle = new Label("Assistant de réponse aux entreprises"); subtitle.getStyleClass().add("muted");
        mode.getStyleClass().add("mode");mode.setWrapText(true);mode.setMinWidth(0);
        flowStatus.setWrapText(true);flowStatus.getStyleClass().add("mode");
        modelStatus.setWrapText(true);modelStatus.getStyleClass().add("mode");
        modelRetry.setVisible(false);modelRetry.setManaged(false);
        var header = new VBox(5, title, subtitle, mode,new FlowPane(8,8,modelStatus,modelRetry),flowStatus); header.getStyleClass().add("header");
        incoming.setPrefRowCount(4); incoming.setId("incoming"); incoming.setAccessibleText("Message reçu");
        agents.setPromptText("Choisir un spécialiste"); agents.setPrefWidth(210); agents.setAccessibleText("Agent pour la prochaine génération");
        choose.setOnAction(event -> presenter.choose(agents.getValue()));
        generate.setOnAction(event -> presenter.generate()); generate.getStyleClass().add("primary");
        cancel.setOnAction(event -> presenter.cancel());
        progress.setPrefSize(22, 22); progress.setMaxSize(22, 22);
        route.setWrapText(true); route.getStyleClass().add("route-status");
        var routingBar = new FlowPane(8, 8, agents, choose, generate, cancel, progress);
        versions.setPrefWidth(90); versions.setAccessibleText("Version du brouillon");
        versions.valueProperty().addListener((observable, old, value) -> { if (!rendering && value != null) { presenter.showVersion(value); } });
        refresh.setOnAction(event -> { if (rendered != null) { rendered.view().ifPresent(view -> presenter.select(view.message().id())); } });
        var versionBar = new FlowPane(8, 8, new Label("Version"), versions, versionState, refresh);
        subject.setPromptText("Objet de la réponse"); subject.setId("subject"); subject.setAccessibleText("Objet de la réponse");
        recipients.setPromptText("Destinataires séparés par des virgules"); recipients.setAccessibleText("Destinataires de la réponse");
        body.setPromptText(""); body.setWrapText(true); body.setPrefRowCount(8); body.setId("replyBody"); body.setAccessibleText("Texte du brouillon à relire");
        feedback.setPromptText("Retour facultatif pour une nouvelle proposition, par exemple : demandez la version du SDK."); feedback.setWrapText(true); feedback.setPrefRowCount(2); feedback.setAccessibleText("Commentaire de régénération");
        subject.textProperty().addListener((o, a, b) -> editorChanged()); recipients.textProperty().addListener((o, a, b) -> editorChanged()); body.textProperty().addListener((o, a, b) -> editorChanged());
        save.setOnAction(event -> presenter.save()); approve.setOnAction(event -> presenter.approve()); approve.getStyleClass().add("primary");
        regenerate.setOnAction(event -> presenter.regenerate(feedback.getText())); retry.setOnAction(event -> presenter.retry()); revert.setOnAction(event -> presenter.discardEdits());
        var actions = new FlowPane(8, 8, save, approve, regenerate, retry, revert);
        var editor = new VBox(8, new Label("Email reçu"), incoming, route, routingBar, new Separator(), versionBar,
                new Label("Objet"), subject, new Label("Destinataires"), recipients, body, feedback, actions);
        editor.setPadding(new Insets(16)); VBox.setVgrow(body, Priority.ALWAYS);
        var editorScroll = new ScrollPane(editor); editorScroll.setFitToWidth(true); editorScroll.setFitToHeight(true);
        editorScroll.setMinWidth(0);editorScroll.setMinHeight(0);
        reviewEditor=editorScroll;
        configurationAgents.setPrefWidth(300); configurationAgents.setAccessibleText("Agent à consulter"); configurationAgents.valueProperty().addListener((o, a, b) -> showConfiguration());
        reload.setOnAction(event -> presenter.reloadConfiguration());
        var configNote = new Label("Paramètres actifs et versions conservées ; les formulaires permettent de préparer les modifications."); configNote.setWrapText(true); configNote.getStyleClass().add("muted");
        var configurationPanel = new VBox(10, configNote, new FlowPane(8, 8, configurationAgents, reload), config); configurationPanel.setPadding(new Insets(16)); VBox.setVgrow(config, Priority.ALWAYS);
        configurationPane.setSummary(configurationPanel);
        assistantTab = tab("Assistant MAS", editorScroll);
        pendingTab=tab("Validations en attente (0)",pendingPane);
        tabs.getTabs().setAll(tab("Messagerie", mailboxPane),pendingTab, assistantTab, tab("Fil et décisions", history), tab("Tentatives du message", outbox), tab("Configuration", configurationPane));
        tabs.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> {
            if(b==assistantTab){mountEditor(false);}
            if(b==pendingTab){if(presenter.state().view().filter(v->pendingIds.contains(v.message().id())).isPresent()){mountEditor(true);}mailboxPane.refresh();}
            if (b != null && b.getContent() == mailboxPane && !presenter.state().busy()) { mailboxPane.refresh(); }
        });
        var content = new BorderPane(tabs);
        notice.setWrapText(true); notice.setMaxWidth(Double.MAX_VALUE); notice.getStyleClass().add("notice");
        var root = new BorderPane(content); root.setTop(header); root.setBottom(notice);
        var scene = new Scene(root); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/fr/paybridge/mas/ui/review.css")).toExternalForm());
        var bounds=Screen.getPrimary().getVisualBounds();
        var placement=WindowPlacement.fit(bounds.getMinX(),bounds.getMinY(),bounds.getWidth(),bounds.getHeight());
        stage.setTitle("PayBridge — Assistant multi-agent"); stage.setScene(scene);stage.setResizable(true);
        stage.setMinWidth(placement.minWidth());stage.setMinHeight(placement.minHeight());
        stage.setWidth(placement.width());stage.setHeight(placement.height());stage.setX(placement.x());stage.setY(placement.y());
        stage.setOnCloseRequest(event -> { event.consume();closed=true; presenter.close(); mailboxPane.close(); stage.hide(); onClose.run(); });
        stage.show(); presenter.start(); mailboxPane.start();
    }
    private void mountEditor(boolean pending){
        if(reviewEditor==null||assistantTab==null){return;}
        if(pending){assistantTab.setContent(null);pendingPane.setCenter(reviewEditor);}
        else{pendingPane.placeholder();assistantTab.setContent(reviewEditor);}
    }
    private void updatePending(List<MailboxWorkspace.Entry> entries){
        pendingIds=entries.stream().filter(MailboxWorkspace.Entry::reviewRequired).map(MailboxWorkspace.Entry::messageId).collect(Collectors.toUnmodifiableSet());
        int count=pendingPane.update(entries);if(pendingTab!=null){pendingTab.setText("Validations en attente ("+count+")");}
        if(tabs.getSelectionModel().getSelectedItem()==pendingTab&&!presenter.state().busy()&&!presenter.state().dirty()
                &&presenter.state().view().filter(v->pendingIds.contains(v.message().id())).isEmpty()){mountEditor(false);}
    }
    public void flowEvent(LiveInboxFlow.Event event,boolean accelerated){
        if(closed){return;}
        String status=switch(event.status()){
            case SCHEDULED -> "Prochaine arrivée : "+event.nextArrival().map(t->java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(t)).orElse("—");
            case RECEIVED -> "Nouvel email reçu";
            case GENERATING -> "Le spécialiste prépare une réponse";
            case READY -> "Réponse disponible dans Validations en attente";
            case NEEDS_CHOICE -> "Un choix de spécialiste est nécessaire dans l'assistant";
            case FAILED -> event.failure().map(code->PresentationErrors.message(new fr.paybridge.mas.application.workspace.WorkspaceGenerationFailure(code))).orElse("Traitement interrompu : ouvrir le message pour vérifier et réessayer");
            case FINISHED -> "Toutes les arrivées sont terminées ; les réponses restent à valider";
            case STOPPED -> "Flux arrêté";
        };
        flowStatus.setText("Flux synthétique "+(accelerated?"ACCÉLÉRÉ (1–3 s)":"(1–3 min)")+" · "+event.received()+"/"+event.total()+" reçus · "+event.drafted()+" réponses préparées · "+event.failed()+" erreurs · "+event.choices()+" choix requis · "+status);
        mailboxPane.refresh();
        if(!presenter.state().busy()&&!presenter.state().dirty()&&presenter.state().view().filter(v->v.message().id().equals(event.messageId())).isPresent()){
            if(event.status()==LiveInboxFlow.Status.READY){presenter.select(event.messageId());}
        }
    }
    public void onModelRetry(Runnable retry){modelRetry.setOnAction(event->retry.run());}
    public void modelStartupEvent(fr.paybridge.mas.application.generation.ModelStartup.Event event){
        if(closed){return;}
        boolean failed=event.status()==fr.paybridge.mas.application.generation.ModelStartup.Status.FAILED;
        modelRetry.setVisible(failed);modelRetry.setManaged(failed);
        modelStatus.setText(switch(event.status()){
            case IDLE -> "";
            case LOADING -> "Démarrage d’Ollama et chargement du modèle local… Le flux d’emails attend sa disponibilité.";
            case READY -> "Modèle local prêt — démarrage du flux d’emails.";
            case FAILED -> "Flux en attente. "+PresentationErrors.message(new fr.paybridge.mas.application.workspace.WorkspaceGenerationFailure(event.failure().orElseThrow()));
            case STOPPED -> "Chargement arrêté.";
        });
    }
    private static Tab tab(String title, javafx.scene.Node content) { var tab = new Tab(title, content); tab.setClosable(false); return tab; }
    private static TextArea readOnly() { var area = new TextArea(); area.setEditable(false); area.setWrapText(true); return area; }
    private void editorChanged() { if (!rendering) { presenter.edit(new ReplyEditor(subject.getText(), body.getText(), recipients.getText())); } }
    private static void text(TextInputControl field, String value) { if (!field.getText().equals(value)) { field.setText(value); } }
    private void render(ReviewState state) {
        if (!Platform.isFxApplicationThread()) { throw new IllegalStateException("Affichage hors thread JavaFX"); }
        rendering = true;
        try {
            if(state.view().isEmpty()){
                text(incoming,"");route.setText("");versionState.setText("");versions.getItems().clear();agents.setValue(null);text(history,"");text(outbox,"");
            }
            state.view().ifPresent(view -> {
                if (rendered == null || rendered.view().isEmpty() || !rendered.view().get().message().id().equals(view.message().id())) { feedback.clear(); }
                text(incoming, view.message().subject() + "\nDe : " + view.message().sender().value() + "\n\n" + view.message().body());
                var decision = view.routing();
                route.setText(decision.effectiveAgentId().map(id -> "Spécialiste proposé : " + id).orElse("Choix requis : plusieurs spécialistes conviennent. Sélectionnez puis confirmez un agent."));
                if (rendered == null || rendered.view().isEmpty() || !rendered.view().get().message().id().equals(view.message().id()) || !rendered.view().get().routing().equals(decision)) {
                    agents.setValue(decision.effectiveAgentId().orElse(null));
                }
                var draft = view.draft();
                var numbers = draft.stream().flatMap(d -> d.versions().stream()).map(DraftVersion::number).toList();
                if (!versions.getItems().equals(numbers)) { versions.getItems().setAll(numbers); }
                versions.setValue(state.viewedVersion() == 0 ? null : state.viewedVersion());
                versionState.setText(draft.map(d -> (state.historical() ? "Historique · lecture seule" : d.state().name()) + "  ·  révision " + d.revision() + (state.dirty() ? "  ·  non enregistré" : "")).orElse("Aucun brouillon"));
                text(history, history(view, state.viewedVersion())); text(outbox, outbox(draft));
            });
            text(subject, state.editor().subject()); text(recipients, state.editor().recipients()); text(body, state.editor().body());
            subject.setEditable(state.canEdit()); recipients.setEditable(state.canEdit()); body.setEditable(state.canEdit());
            choose.setDisable(!state.canChoose()); agents.setDisable(!state.canChoose()); generate.setDisable(!state.canGenerate());
            save.setDisable(!state.canSave()); approve.setDisable(!state.canApprove()); regenerate.setDisable(!state.canRegenerate()); retry.setDisable(!state.canRetry());
            retry.setVisible(state.canRetry()); retry.setManaged(state.canRetry());
            revert.setDisable(state.busy() || (!state.dirty() && !state.historical())); versions.setDisable(state.busy() || state.dirty()); refresh.setDisable(state.busy() || state.dirty());
            feedback.setDisable(state.busy()); cancel.setDisable(state.operation() != ReviewState.Operation.GENERATING);
            cancel.setVisible(state.operation() == ReviewState.Operation.GENERATING); cancel.setManaged(cancel.isVisible());
            progress.setVisible(state.busy()); progress.setManaged(state.busy());
            reload.setDisable(state.busy() || state.dirty()); notice.setText(state.notice());
            state.configuration().ifPresent(current -> {
                if (configuration != current) {
                    configuration = current;
                    agents.getItems().setAll(current.registry().snapshot().activeAgents().stream().map(a -> a.id()).toList());
                    configurationAgents.getItems().setAll(current.registry().snapshot().agents().stream().map(a -> a.id()).toList());
                    if (configurationAgents.getValue() == null || !configurationAgents.getItems().contains(configurationAgents.getValue())) { configurationAgents.getSelectionModel().selectFirst(); }
                    showConfiguration();
                }
            });
            boolean draftChanged=rendered!=null&&!rendered.draft().equals(state.draft());
            rendered = state;
            configurationPane.update(state);
            if(draftChanged&&!state.busy()){mailboxPane.refresh();}
        } finally { rendering = false; }
    }
    private static String history(ReviewWorkspace.MessageView view, int version) {
        var result = new StringBuilder("FIL : " + view.message().conversationId() + "\n\n");
        for (var message : view.conversation().messages()) { result.append(message.receivedAt()).append(" — ").append(message.subject()).append("\n").append(message.body()).append("\n\n"); }
        result.append("ROUTAGE COURANT\n"); view.routing().initialOutcome().candidates().forEach(c -> result.append(c.agentId()).append(" : ").append(c.score()).append(" — ").append(String.join(" / ", c.reasons())).append('\n'));
        view.routing().corrections().forEach(c -> result.append("Choix humain : ").append(c.agentId()).append('\n'));
        view.draft().ifPresent(draft -> {
            result.append("\nVERSIONS ET DÉCISIONS\n"); draft.reviews().forEach(r -> result.append(r.at()).append(" — v").append(r.version()).append(" — ").append(r.type()).append('\n'));
            var receipt = draft.versions().get(Math.max(0, version - 1)).receipt();
            result.append("\nORIGINE DE LA VERSION AFFICHÉE\n").append(receipt.agent().id()).append(" v").append(receipt.agent().version()).append('\n');
            receipt.context().excerpts().forEach(e -> result.append(e.sourceId()).append(" v").append(e.sourceVersion()).append(" — ").append(e.provenance()).append('\n'));
            result.append("Budget entrée : ").append(receipt.context().usage().inputTokens()).append(receipt.context().usage().estimated() ? " (estimation)" : "");
        });
        return result.toString();
    }
    private static String outbox(Optional<Draft> draft) {
        if (draft.isEmpty() || draft.get().sendAttempts().isEmpty()) { return "Aucune tentative pour ce message. Aucun email réel envoyé."; }
        return "BOÎTE SIMULÉE — aucune livraison réelle\n\n" + draft.get().sendAttempts().stream().map(a -> "Version " + a.email().approval().version() + " · " + a.status() + "\nTentative : " + a.email().attemptId() + "\nCode : " + a.result().map(r -> r.code()).orElse("IN_PROGRESS")
                + (a.status() == fr.paybridge.mas.domain.delivery.DeliveryStatus.UNKNOWN ? "\nRésultat inconnu : aucune relance ni modification permise ; vérification requise." : "")).collect(Collectors.joining("\n\n"));
    }
    private void showConfiguration() {
        if (configuration == null) { return; }
        var result = new StringBuilder("PARAMÈTRES CHARGÉS AU DÉMARRAGE\nModèle : " + configuration.inference().model() + "\nMode : " + (configuration.simulated() ? "SIMULATION EXPLICITE" : "LLM LOCAL") + "\nCapacité : " + configuration.inference().contextCapacity() + " · sortie max : " + configuration.inference().outputLimit() + "\n\n");
        configuration.registry().snapshot().agents().stream().filter(a -> a.id().equals(configurationAgents.getValue())).findFirst().ifPresent(agent -> {
            result.append(agent.id()).append(" · v").append(agent.version()).append(agent.active() ? " · actif" : " · inactif").append("\nRôle : ").append(agent.role()).append("\nCatégories : ").append(agent.categories()).append("\nSources : ").append(agent.contextSources()).append("\nVersions conservées : ").append(configuration.registry().history().get(agent.id()).stream().map(a -> a.version()).toList()).append("\n\nINSTRUCTIONS\n").append(agent.systemPrompt());
        });
        result.append("\n\nCATALOGUE — métadonnées uniquement\n"); configuration.sources().forEach(source -> result.append(source.id()).append(" v").append(source.version()).append(" · ").append(source.visibility()).append(" · ").append(source.provenance()).append('\n'));
        config.setText(result.toString());
    }
}
