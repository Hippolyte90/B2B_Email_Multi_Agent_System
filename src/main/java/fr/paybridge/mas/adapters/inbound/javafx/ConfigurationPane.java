package fr.paybridge.mas.adapters.inbound.javafx;

import fr.paybridge.mas.adapters.inbound.presentation.*;
import fr.paybridge.mas.application.configuration.EditableConfiguration;
import fr.paybridge.mas.application.workspace.ReviewWorkspace;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextSource;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;

/** Formulaires locaux ; la conversion est pure, toute lecture/écriture passe par le présentateur. */
public final class ConfigurationPane extends VBox {
    private final ReviewPresenter presenter;
    private final ComboBox<String> agentChoice = new ComboBox<>(), sourceChoice = new ComboBox<>();
    private final TextField aid = field("Identifiant de l'agent"), role = field("Rôle"), categories = field("Catégories séparées par des virgules"), refs = field("Références des sources séparées par des virgules");
    private final TextArea prompt = area("Instructions de l'agent");
    private final CheckBox active = new CheckBox("Actif"), fallback = new CheckBox("Agent de repli");
    private final TextField sid = field("Identifiant de la source"), reference = field("Référence relative de la source"), provenance = field("Provenance"), client = field("Client, vide pour une source publique"), project = field("Projet facultatif"), sourceAgents = field("Identifiants des agents autorisés"), recipients = field("Destinataires autorisés");
    private final ComboBox<ContextSource.Visibility> visibility = new ComboBox<>();
    private final TextArea document = area("Texte de la source");
    private final TextField endpoint = field("Endpoint HTTP local"), model = field("Modèle local"), capacity = field("Capacité du contexte"), output = field("Limite de sortie"), temperature = field("Température"), seed = field("Graine"), timeout = field("Délai en secondes"), overhead = field("Marge de protocole");
    private final Label note = new Label("Versions automatiques ; les changements sont conservés dans cette base locale."), sourceStatus = new Label(), runtime = new Label();
    private final Button saveAgent = new Button("Enregistrer l'agent"), saveSource = new Button("Enregistrer la source"), saveInference = new Button("Enregistrer les paramètres");
    private final Tab agentTab, sourceTab, inferenceTab;
    private final TabPane tabs;
    private EditableConfiguration current;
    private ConfigurationInputs.AgentInput agentBaseline;
    private ConfigurationInputs.SourceInput sourceBaseline;
    private ConfigurationInputs.InferenceInput inferenceBaseline;
    private boolean creatingAgent, creatingSource, suppress, loadingSource, saving;
    private long agentRevision, sourceRevision, inferenceRevision, sourceEpoch;
    private ReviewWorkspace.Configuration seen;

    public ConfigurationPane(ReviewPresenter presenter, javafx.scene.Node readOnly) {
        this.presenter = presenter;
        setSpacing(10); setPadding(new Insets(14)); note.setWrapText(true); runtime.setWrapText(true);
        var newAgent = new Button("Nouvel agent"); newAgent.setOnAction(e -> { if (clean()) { fillAgent(null); } });
        agentChoice.setAccessibleText("Agent à modifier"); agentChoice.setPrefWidth(260);
        agentChoice.valueProperty().addListener((o, old, value) -> {
            if (suppress || current == null) { return; }
            if (!clean()) { suppress = true; agentChoice.setValue(old); suppress = false; return; }
            fillAgent(current.agents().stream().filter(a -> a.id().equals(value)).findFirst().orElse(null));
        });
        var agentGrid = grid(); add(agentGrid, 0, "Identifiant", aid); add(agentGrid, 1, "Rôle", role); add(agentGrid, 2, "Catégories", categories); add(agentGrid, 3, "Sources (références)", refs);
        var undoAgent = new Button("Annuler la saisie"); undoAgent.setOnAction(e -> fillAgent(current.agents().stream().filter(a -> a.id().equals(agentChoice.getValue())).findFirst().orElse(null)));
        saveAgent.setOnAction(e -> { saving = true; presenter.saveAgent(agentRevision, agentInput(), creatingAgent); });
        var agentBox = new VBox(10, new HBox(8, agentChoice, newAgent), agentGrid, new HBox(16, active, fallback), new Label("Prompt — une modification crée la version suivante"), prompt, new HBox(8, saveAgent, undoAgent));
        var newSource = new Button("Nouvelle source"); newSource.setOnAction(e -> { if (clean()) { fillSource(null); } });
        sourceChoice.setAccessibleText("Source à modifier"); sourceChoice.setPrefWidth(260);
        sourceChoice.valueProperty().addListener((o, old, value) -> {
            if (suppress || current == null) { return; }
            if (!clean()) { suppress = true; sourceChoice.setValue(old); suppress = false; return; }
            fillSource(current.sources().stream().filter(s -> s.id().equals(value)).findFirst().orElse(null));
        });
        visibility.getItems().setAll(ContextSource.Visibility.values()); visibility.setAccessibleText("Visibilité de la source");
        var sourceGrid = grid(); add(sourceGrid, 0, "Identifiant", sid); add(sourceGrid, 1, "Référence", reference); add(sourceGrid, 2, "Provenance", provenance); add(sourceGrid, 3, "Visibilité", visibility); add(sourceGrid, 4, "Client", client); add(sourceGrid, 5, "Projet", project); add(sourceGrid, 6, "Agents autorisés", sourceAgents); add(sourceGrid, 7, "Destinataires", recipients);
        var undoSource = new Button("Annuler la saisie"); undoSource.setOnAction(e -> fillSource(current.sources().stream().filter(s -> s.id().equals(sourceChoice.getValue())).findFirst().orElse(null)));
        saveSource.setOnAction(e -> { saving = true; presenter.saveSource(sourceRevision, sourceInput(), creatingSource); });
        var sourceBox = new VBox(10, new HBox(8, sourceChoice, newSource), sourceGrid, sourceStatus, document, new HBox(8, saveSource, undoSource));
        var inferenceGrid = grid(); add(inferenceGrid, 0, "Endpoint local", endpoint); add(inferenceGrid, 1, "Modèle", model); add(inferenceGrid, 2, "Capacité contexte", capacity); add(inferenceGrid, 3, "Sortie maximale", output); add(inferenceGrid, 4, "Température", temperature); add(inferenceGrid, 5, "Graine", seed); add(inferenceGrid, 6, "Délai (secondes)", timeout); add(inferenceGrid, 7, "Marge protocole", overhead);
        var info = new Label("Ces paramètres seront chargés au prochain lancement. Aucun modèle n'est téléchargé et aucun endpoint distant n'est accepté."); info.setWrapText(true);
        saveInference.setOnAction(e -> { saving = true; presenter.saveInference(inferenceRevision, inferenceInput()); });
        var undoInference = new Button("Annuler la saisie"); undoInference.setOnAction(e -> fillInference());
        agentTab = tab("Agents", agentBox); sourceTab = tab("Sources", sourceBox); inferenceTab = tab("Moteur local", new VBox(12, info, runtime, inferenceGrid, new HBox(8, saveInference, undoInference)));
        tabs = new TabPane(agentTab, sourceTab, inferenceTab, new Tab("Vue active", readOnly)); tabs.getTabs().forEach(t -> t.setClosable(false));
        getChildren().addAll(note, tabs); VBox.setVgrow(tabs, Priority.ALWAYS);
        for (var field : List.of(aid, role, categories, refs, sid, reference, provenance, client, project, sourceAgents, recipients, endpoint, model, capacity, output, temperature, seed, timeout, overhead)) { field.textProperty().addListener((o, a, b) -> changed()); }
        prompt.textProperty().addListener((o, a, b) -> changed()); document.textProperty().addListener((o, a, b) -> changed()); active.selectedProperty().addListener((o, a, b) -> changed()); fallback.selectedProperty().addListener((o, a, b) -> changed()); visibility.valueProperty().addListener((o, a, b) -> changed());
    }
    private static TextField field(String label) { var f = new TextField(); f.setAccessibleText(label); f.setPromptText(label); f.setMaxWidth(Double.MAX_VALUE); return f; }
    private static TextArea area(String label) { var a = new TextArea(); a.setAccessibleText(label); a.setWrapText(true); a.setPrefRowCount(7); return a; }
    private static GridPane grid() { var g = new GridPane(); g.setHgap(12); g.setVgap(8); var label = new ColumnConstraints(155); var value = new ColumnConstraints(); value.setHgrow(Priority.ALWAYS); g.getColumnConstraints().addAll(label, value); return g; }
    private static void add(GridPane g, int row, String label, javafx.scene.Node field) { g.add(new Label(label), 0, row); g.add(field, 1, row); }
    private static Tab tab(String label, javafx.scene.Node content) { var scroll = new ScrollPane(content); scroll.setFitToWidth(true); ((Region) content).setPadding(new Insets(12)); return new Tab(label, scroll); }
    private ConfigurationInputs.AgentInput agentInput() { return new ConfigurationInputs.AgentInput(aid.getText(), role.getText(), categories.getText(), refs.getText(), prompt.getText(), active.isSelected(), fallback.isSelected()); }
    private ConfigurationInputs.SourceInput sourceInput() { return new ConfigurationInputs.SourceInput(sid.getText(), reference.getText(), provenance.getText(), client.getText(), project.getText(), visibility.getValue(), sourceAgents.getText(), recipients.getText(), document.getText()); }
    private ConfigurationInputs.InferenceInput inferenceInput() { return new ConfigurationInputs.InferenceInput(endpoint.getText(), model.getText(), capacity.getText(), output.getText(), temperature.getText(), seed.getText(), timeout.getText(), overhead.getText()); }
    private boolean agentDirty() { return agentBaseline != null && !agentInput().equals(agentBaseline); }
    private boolean sourceDirty() { return !loadingSource && sourceBaseline != null && !sourceInput().equals(sourceBaseline); }
    private boolean inferenceDirty() { return inferenceBaseline != null && !inferenceInput().equals(inferenceBaseline); }
    private boolean clean() { if (agentDirty() || sourceDirty() || inferenceDirty()) { note.setText("Enregistrez ou annulez la saisie avant de changer de formulaire."); return false; } return true; }
    private void changed() {
        if (suppress) { return; }
        boolean a = agentDirty(), s = sourceDirty(), i = inferenceDirty();
        agentTab.setDisable(s || i); sourceTab.setDisable(a || i); inferenceTab.setDisable(a || s);
        tabs.getTabs().getLast().setDisable(a || s || i);
        saveAgent.setDisable(!a); saveSource.setDisable(!s || loadingSource); saveInference.setDisable(!i);
        if (a || s || i) { note.setText("Modifications non enregistrées. Les anciennes valeurs restent actives."); }
    }
    private void fillAgent(AgentDefinition definition) {
        suppress = true; creatingAgent = definition == null; agentRevision = current.revision();
        var value = definition == null ? new ConfigurationInputs.AgentInput("", "", "", "", "", true, false) : ConfigurationInputs.AgentInput.of(definition);
        aid.setText(value.id()); aid.setEditable(creatingAgent); role.setText(value.role()); categories.setText(value.categories()); refs.setText(value.sources()); prompt.setText(value.prompt()); active.setSelected(value.active()); fallback.setSelected(value.fallback()); agentBaseline = value;
        suppress = false; changed();
    }
    private void fillSource(ContextSource source) {
        suppress = true; var token = ++sourceEpoch; creatingSource = source == null; sourceRevision = current.revision();
        var value = source == null ? new ConfigurationInputs.SourceInput("", "context/gui/new-source.md", "", "", "", ContextSource.Visibility.PUBLIC_APPROVED, "", "", "") : ConfigurationInputs.SourceInput.of(source, "");
        sid.setText(value.id()); sid.setEditable(creatingSource); reference.setText(value.reference()); reference.setEditable(creatingSource); provenance.setText(value.provenance()); client.setText(value.client()); project.setText(value.project()); visibility.setValue(value.visibility()); sourceAgents.setText(value.agents()); recipients.setText(value.recipients()); document.setText(""); sourceBaseline = value;
        loadingSource = source != null; document.setEditable(!loadingSource); sourceStatus.setText(loadingSource ? "Chargement du texte sélectionné…" : "Nouvelle source : texte obligatoire. Référence unique terminée par .md ou .txt."); suppress = false; changed();
        if (source != null) { presenter.loadSourceText(source.id(), text -> {
            if (token != sourceEpoch) { return; } suppress = true; document.setText(text); loadingSource = false; document.setEditable(true); sourceBaseline = sourceInput(); sourceStatus.setText("Version " + source.version() + " ; une modification créera la version suivante."); suppress = false; changed();
        }, error -> { if (token == sourceEpoch) { loadingSource = false; document.setEditable(true); sourceStatus.setText(error + " Vous pouvez saisir un texte de remplacement."); changed(); } }); }
    }
    private void fillInference() {
        suppress = true; inferenceRevision = current.revision(); var v = ConfigurationInputs.InferenceInput.of(current.inference());
        endpoint.setText(v.endpoint()); model.setText(v.model()); capacity.setText(v.capacity()); output.setText(v.output()); temperature.setText(v.temperature()); seed.setText(v.seed()); timeout.setText(v.timeoutSeconds()); overhead.setText(v.overhead()); inferenceBaseline = v; suppress = false; changed();
    }
    public void update(ReviewState state) {
        setDisable(state.busy() || state.dirty() || state.configuration().flatMap(ReviewWorkspace.Configuration::editable).isEmpty());
        state.configuration().ifPresent(configuration -> {
            if (configuration != seen && configuration.editable().isPresent()) {
                if (!saving && (agentDirty() || sourceDirty() || inferenceDirty())) { note.setText("Configuration actualisée ailleurs. Votre saisie est conservée ; une ancienne révision sera refusée."); return; }
                seen = configuration; current = configuration.editable().get();
                suppress = true;
                var selectedAgent = saving && creatingAgent ? aid.getText().strip() : agentChoice.getValue();
                var selectedSource = saving && creatingSource ? sid.getText().strip() : sourceChoice.getValue();
                agentChoice.getItems().setAll(current.agents().stream().map(AgentDefinition::id).toList()); sourceChoice.getItems().setAll(current.sources().stream().map(ContextSource::id).toList());
                agentChoice.setValue(agentChoice.getItems().contains(selectedAgent) ? selectedAgent : agentChoice.getItems().getFirst());
                sourceChoice.setValue(sourceChoice.getItems().contains(selectedSource) ? selectedSource : sourceChoice.getItems().stream().findFirst().orElse(null)); suppress = false;
                fillAgent(current.agents().stream().filter(a -> a.id().equals(agentChoice.getValue())).findFirst().orElseThrow());
                fillSource(current.sources().stream().filter(s -> s.id().equals(sourceChoice.getValue())).findFirst().orElse(null)); fillInference();
                runtime.setText("En service : " + configuration.inference().model() + " · capacité " + configuration.inference().contextCapacity() + " · sortie " + configuration.inference().outputLimit());
                note.setText("Configuration locale révision " + current.revision() + ". Versions automatiques ; paramètres moteur au prochain lancement.");
            }
        });
        if (!state.busy()) { saving = false; }
    }
    public void setSummary(javafx.scene.Node summary) { tabs.getTabs().getLast().setContent(summary); }
}
