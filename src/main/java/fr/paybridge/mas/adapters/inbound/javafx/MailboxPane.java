package fr.paybridge.mas.adapters.inbound.javafx;

import fr.paybridge.mas.adapters.inbound.presentation.*;
import fr.paybridge.mas.application.workspace.MailboxWorkspace;
import fr.paybridge.mas.application.workspace.MailboxWorkspace.*;
import fr.paybridge.mas.domain.mail.MailboxFlags.Location;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.function.*;
import java.util.stream.Collectors;

/** Vue messagerie : les dossiers n'exécutent jamais le routeur ou le modèle. */
final class MailboxPane extends BorderPane {
    private final MailboxPresenter presenter;
    private final ListView<Folder> folders = new ListView<>();
    private final ListView<Entry> messages = new ListView<>();
    private final Label heading = new Label(), metadata = new Label(), status = new Label(), notice = new Label();
    private final TextArea body = new TextArea();
    private final Button export = new Button("Enregistrer ce brouillon dans Gmail");
    private final boolean connected;
    private final Consumer<java.util.List<Entry>> entriesChanged;
    private final Button reply = new Button("Ouvrir dans l'assistant MAS"), archive = new Button("Archiver"), spam = new Button("Spam"), trash = new Button("Corbeille"), restore = new Button("Remettre en réception"), read = new Button("Lu / non lu"), star = new Button("Favori"), refresh = new Button("Actualiser");
    private boolean rendering;
    private MailboxState state;
    MailboxPane(MailboxWorkspace workspace, Executor worker, BooleanSupplier blocked, Consumer<String> openAssistant) {
        this(workspace,worker,blocked,openAssistant,entries->{});
    }
    MailboxPane(MailboxWorkspace workspace, Executor worker, BooleanSupplier blocked, Consumer<String> openAssistant,Consumer<java.util.List<Entry>> entriesChanged) {
        this.entriesChanged=entriesChanged;
        connected=workspace.connected();
        folders.getStyleClass().add("mail-list");messages.getStyleClass().add("mail-list");
        presenter = new MailboxPresenter(workspace, worker, Platform::runLater, blocked, this::render);
        folders.getItems().setAll(Folder.values()); folders.setAccessibleText("Dossiers de messagerie"); folders.setPrefWidth(190); folders.setMinWidth(140);
        folders.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Folder folder, boolean empty) {
                super.updateItem(folder, empty);setWrapText(true); setText(empty || folder == null ? null : folder + "  (" + (state == null ? 0 : state.count(folder)) + ")");
            }
        });
        folders.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { if (!rendering && b != null) { presenter.folder(b); } });
        messages.setAccessibleText("Messages du dossier");
        messages.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) { setGraphic(null); setText(null); }
                else {
                    var title = new Label((entry.kind() == Kind.RECEIVED && !entry.flags().read() ? "● " : "") + (entry.flags().starred() ? "★ " : "") + entry.subject());
                    title.setWrapText(true);title.maxWidthProperty().bind(messages.widthProperty().subtract(42));
                    var sender = new Label(entry.sender()); sender.getStyleClass().add("muted");
                    var date = new Label(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(entry.at())); date.getStyleClass().add("muted");
                    setGraphic(new VBox(4, title, sender, date)); setText(null);
                }
            }
        });
        messages.getSelectionModel().selectedItemProperty().addListener((o,a,b) -> { if (!rendering && b != null) { presenter.select(b.key()); } });
        var search = new TextField(); search.setPromptText("Rechercher dans ce dossier…"); search.setAccessibleText("Recherche de messages");
        search.textProperty().addListener((o,a,b) -> { if (!rendering) { presenter.search(b); } });
        refresh.setOnAction(e -> presenter.refresh());
        search.setMinWidth(0);
        var listPane = new VBox(10, new HBox(8, search, refresh), messages); listPane.setPrefWidth(350); listPane.setMinWidth(220); listPane.setPadding(new Insets(12)); VBox.setVgrow(messages, Priority.ALWAYS); HBox.setHgrow(search, Priority.ALWAYS);
        heading.getStyleClass().add("section-title"); heading.setWrapText(true); metadata.setWrapText(true); metadata.getStyleClass().add("muted"); status.setWrapText(true);
        body.setEditable(false); body.setWrapText(true); body.setAccessibleText("Contenu du message sélectionné");
        archive.setOnAction(e -> presenter.move(Location.ARCHIVE)); spam.setOnAction(e -> presenter.move(Location.SPAM)); trash.setOnAction(e -> presenter.move(Location.TRASH)); restore.setOnAction(e -> presenter.move(Location.INBOX));
        read.setOnAction(e -> presenter.toggleRead()); star.setOnAction(e -> presenter.toggleStar());
        reply.setOnAction(e -> presenter.state().selected().ifPresent(entry -> openAssistant.accept(entry.messageId()))); reply.getStyleClass().add("primary");
        export.setOnAction(e->presenter.exportDraft());export.setVisible(connected);export.setManaged(connected);
        var detail = new VBox(12, heading, metadata, status, new FlowPane(8,8, archive, spam, trash, restore, read, star), body, new FlowPane(8,8,reply,export)); detail.setPadding(new Insets(18));detail.setMinWidth(220); VBox.setVgrow(body, Priority.ALWAYS);
        var detailScroll=new ScrollPane(detail);detailScroll.setFitToWidth(true);detailScroll.setFitToHeight(true);detailScroll.setMinWidth(220);detailScroll.setMinHeight(0);
        // Proportions de lancement : dossiers 13 %, liste 69 %, lecture 18 %.
        // Un seul conteneur évite les proportions cumulées de séparateurs imbriqués.
        var columns=new SplitPane(folders,listPane,detailScroll);
        columns.setDividerPositions(.13,.82);
        var proportions = new double[]{.13,.82};
        columns.widthProperty().addListener((observable,previous,current) -> columns.setDividerPositions(proportions));
        columns.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED,event -> {
            var positions=columns.getDividerPositions();
            System.arraycopy(positions,0,proportions,0,positions.length);
        });
        columns.setMinWidth(0);columns.setMinHeight(0);
        columns.setAccessibleText("Colonnes de messagerie redimensionnables");
        setCenter(columns); setBottom(notice); notice.setWrapText(true); notice.setPadding(new Insets(10));
        folders.getStyleClass().add("sidebar");
    }
    void start() { presenter.refresh(); }
    void refresh() { presenter.refresh(); }
    void close() { presenter.close(); }
    private void render(MailboxState state) {
        if (!Platform.isFxApplicationThread()) { throw new IllegalStateException("Affichage hors thread JavaFX"); }
        rendering = true;
        try {
            this.state = state; folders.refresh(); folders.getSelectionModel().select(state.folder());
            messages.getItems().setAll(state.visible()); state.selected().ifPresentOrElse(e -> messages.getSelectionModel().select(e), () -> messages.getSelectionModel().clearSelection());
            state.selected().ifPresentOrElse(e -> {
                heading.setText(e.subject()); metadata.setText("De : " + e.sender() + "\nÀ : " + e.recipients().stream().map(a -> a.value()).collect(Collectors.joining(", ")) + "\n" + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(e.at()));
                status.setText(switch (e.kind()) {
                    case RECEIVED -> e.flags().read() ? "Message lu" : "Message non lu";
                    case DRAFT -> "Brouillon local — à relire dans l'assistant";
                    case SENT -> "Envoi réellement confirmé";
                    case SIMULATED -> "Envoi simulé — aucune livraison réelle";
                    case OUTBOX -> switch (e.status()) {
                        case "PENDING" -> "Transmission en cours";
                        case "FAILED" -> "Échec d'envoi — reprise manuelle requise";
                        default -> "Résultat d'envoi inconnu — vérification requise";
                    };
                }); body.setText(e.body());
                boolean received = e.kind() == Kind.RECEIVED && e.canAssist();
                for (var b : new Button[]{archive,spam,trash,restore,read,star}) { b.setDisable(state.busy() || !received); }
                read.setText(e.flags().read() ? "Marquer non lu" : "Marquer lu"); star.setText(e.flags().starred() ? "Retirer le favori" : "Ajouter aux favoris");
                reply.setDisable(state.busy() || !e.canAssist() || e.flags().location() == Location.SPAM || e.flags().location() == Location.TRASH);
                export.setDisable(state.busy() || e.kind()!=Kind.DRAFT || !e.key().startsWith("draft:"));
                reply.setText(e.kind() == Kind.DRAFT ? "Reprendre le brouillon dans l'assistant" : "Ouvrir dans l'assistant MAS");
            }, () -> {
                heading.setText(state.visible().isEmpty() ? "Ce dossier est vide" : "Sélectionnez un message"); metadata.setText(""); status.setText(""); body.clear();
                for (var b : new Button[]{archive,spam,trash,restore,read,star,reply,export}) { b.setDisable(true); }
            });
            refresh.setDisable(state.busy()); folders.setDisable(state.busy()); messages.setDisable(state.busy()); notice.setText(state.notice());
            entriesChanged.accept(state.entries());
        } finally { rendering = false; }
    }
}
