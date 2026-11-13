package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.workspace.ReviewWorkspace;
import fr.paybridge.mas.domain.draft.Draft;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Appelé sur le thread de présentation ; toutes les E/S sont déléguées à worker. */
public final class ReviewPresenter {
    private final ReviewWorkspace workspace;
    private final Executor worker, ui;
    private final Consumer<ReviewState> render;
    private ReviewState state = new ReviewState(List.of(), Optional.empty(), ReplyEditor.empty(), 0, ReviewState.Operation.IDLE, "Choisissez un message.", Optional.empty());
    private long epoch;
    private Cancellation active;
    private boolean closed;
    private final class Cancellation {
        private boolean requested;
        private Runnable command;
        synchronized void install(Runnable action) { command = action; if (requested) { worker.execute(action); } }
        synchronized void request() { if (!requested) { requested = true; if (command != null) { worker.execute(command); } } }
        synchronized boolean requested() { return requested; }
    }
    public ReviewPresenter(ReviewWorkspace workspace, Executor worker, Executor ui, Consumer<ReviewState> render) {
        this.workspace = workspace; this.worker = worker; this.ui = ui; this.render = render;
    }
    public ReviewState state() { return state; }
    private void publish() { if (!closed) { render.accept(state); } }
    private void notice(String text) { state = new ReviewState(state.inbox(), state.view(), state.editor(), state.viewedVersion(), state.operation(), text, state.configuration()); publish(); }
    private long begin(ReviewState.Operation operation) {
        var token = ++epoch;
        state = new ReviewState(state.inbox(), state.view(), state.editor(), state.viewedVersion(), operation, operation == ReviewState.Operation.GENERATING ? "Génération en cours…" : "Chargement…", state.configuration()); publish(); return token;
    }
    private void display(ReviewWorkspace.MessageView view, String notice) {
        var editor = view.draft().map(d -> ReplyEditor.of(d.current().content())).orElseGet(ReplyEditor::empty);
        state = new ReviewState(state.inbox(), Optional.of(view), editor, view.draft().map(d -> d.current().number()).orElse(0), ReviewState.Operation.IDLE, notice, state.configuration()); active = null; publish();
    }
    private void failed(long token, Throwable failure, Optional<ReviewWorkspace.MessageView> refreshed) {
        if (closed || token != epoch) { return; }
        if (refreshed.isPresent()) { display(refreshed.get(), PresentationErrors.message(failure)); }
        else { state = new ReviewState(state.inbox(), state.view(), state.editor(), state.viewedVersion(), ReviewState.Operation.IDLE, PresentationErrors.message(failure), state.configuration()); active = null; publish(); }
    }
    public void start() {
        long token = begin(ReviewState.Operation.LOADING);
        worker.execute(() -> {
            try {
                var inbox = workspace.inbox(); var configuration = workspace.configuration();
                ui.execute(() -> { if (!closed && token == epoch) {
                    state = new ReviewState(inbox, Optional.empty(), ReplyEditor.empty(), 0, ReviewState.Operation.IDLE, "Choisissez un message.", Optional.of(configuration)); publish();
                } });
            } catch (Exception failure) { ui.execute(() -> failed(token, failure, Optional.empty())); }
        });
    }
    public void select(String id) {
        if (state.dirty()) { notice("Enregistrez les modifications ou revenez au texte enregistré avant de changer de message."); return; }
        if (state.busy() && state.operation() != ReviewState.Operation.GENERATING && state.operation() != ReviewState.Operation.LOADING) { return; }
        if (active != null) { active.request(); active = null; }
        if(state.view().filter(v->!v.message().id().equals(id)).isPresent()){
            state=new ReviewState(state.inbox(),Optional.empty(),ReplyEditor.empty(),0,ReviewState.Operation.IDLE,"",state.configuration());
        }
        var token = begin(ReviewState.Operation.LOADING);
        worker.execute(() -> { try { var view = workspace.open(id); ui.execute(() -> { if (!closed && token == epoch) { display(view, "Message chargé. Aucune transmission au chargement."); } }); }
            catch (Exception failure) { ui.execute(() -> failed(token, failure, Optional.empty())); } });
    }
    public void edit(ReplyEditor editor) {
        if (!state.canEdit()) { return; }
        state = new ReviewState(state.inbox(), state.view(), editor, state.viewedVersion(), state.operation(), editor.validationMessage(), state.configuration()); publish();
    }
    public void discardEdits() { if (!state.busy() && state.view().isPresent()) { display(state.view().get(), "Texte enregistré affiché."); } }
    public void showVersion(int number) {
        if (state.busy() || state.dirty() || state.draft().isEmpty()) { return; }
        var draft = state.draft().get();
        if (number < 1 || number > draft.versions().size()) { return; }
        state = new ReviewState(state.inbox(), state.view(), ReplyEditor.of(draft.versions().get(number - 1).content()), number, ReviewState.Operation.IDLE,
                number == draft.current().number() ? "Version courante." : "Ancienne version en lecture seule : aucune approbation possible.", state.configuration()); publish();
    }
    private void action(Supplier<ReviewWorkspace.MessageView> action) {
        if (state.busy() || state.view().isEmpty()) { return; }
        var id = state.view().get().message().id(); var token = begin(ReviewState.Operation.WRITING);
        worker.execute(() -> {
            try { var result = action.get(); ui.execute(() -> { if (!closed && token == epoch) { display(result, "État enregistré."); } }); }
            catch (Exception failure) { refreshAfterFailure(token, id, failure); }
        });
    }
    private void refreshAfterFailure(long token, String id, Throwable failure) {
        Optional<ReviewWorkspace.MessageView> current;
        try { current = Optional.of(workspace.open(id)); } catch (Exception ignored) { current = Optional.empty(); }
        var refreshed = current; ui.execute(() -> failed(token, failure, refreshed));
    }
    public void choose(String agent) {
        if (!state.canChoose() || agent == null) { return; } var id = state.view().orElseThrow().message().id(); action(() -> workspace.choose(id, agent));
    }
    public void save() {
        if (!state.canSave()) { notice("Sujet, texte et destinataires valides sont requis pour enregistrer."); return; }
        var expected = state.draft().orElseThrow(); var pendingEditor = state.editor(); var content = pendingEditor.content(); var token = begin(ReviewState.Operation.WRITING);
        worker.execute(() -> {
            try { workspace.save(expected, content); var view = workspace.open(expected.messageId()); ui.execute(() -> { if (!closed && token == epoch) { display(view, "Nouvelle version enregistrée ; approbation requise."); } }); }
            catch (Exception failure) { ui.execute(() -> { if (!closed && token == epoch) {
                state = new ReviewState(state.inbox(), state.view(), pendingEditor, state.viewedVersion(), ReviewState.Operation.IDLE,
                        PresentationErrors.message(failure) + " Votre texte non enregistré est conservé ; copiez-le avant d'actualiser.", state.configuration()); publish();
            } }); }
        });
    }
    public void approve() {
        if (!state.canApprove()) { return; } var expected = state.draft().orElseThrow();
        action(() -> { workspace.approveAndSend(expected); return workspace.open(expected.messageId()); });
    }
    public void retry() {
        if (!state.canRetry()) { return; } var expected = state.draft().orElseThrow();
        action(() -> { workspace.retryFailed(expected); return workspace.open(expected.messageId()); });
    }
    public void generate() {
        if (!state.canGenerate()) { return; } var id = state.view().orElseThrow().message().id(); generation(id, () -> workspace.generate(id));
    }
    public void regenerate(String feedback) {
        if (!state.canRegenerate()) { return; } var expected = state.draft().orElseThrow();
        generation(expected.messageId(), () -> workspace.regenerate(expected, Optional.ofNullable(feedback).filter(s -> !s.isBlank())));
    }
    private void generation(String id, Supplier<ReviewWorkspace.Work> start) {
        long token = begin(ReviewState.Operation.GENERATING); var cancellation = new Cancellation(); active = cancellation;
        worker.execute(() -> {
            try {
                if (cancellation.requested()) { ui.execute(() -> failed(token, new fr.paybridge.mas.application.workspace.WorkspaceGenerationFailure(fr.paybridge.mas.domain.generation.GenerationFailure.CANCELLED), Optional.empty())); return; }
                var work = start.get(); cancellation.install(work.cancel());
                work.result().whenComplete((draft, failure) -> {
                    // Le rappel peut arriver depuis l'exécuteur d'inférence ; les lectures restent sur worker.
                    worker.execute(() -> {
                        if (failure != null) { refreshAfterFailure(token, id, failure); }
                        else { try { var view = workspace.open(id); ui.execute(() -> { if (!closed && token == epoch) { display(view, "Proposition non approuvée : relisez avant de décider."); } }); }
                            catch (Exception error) { ui.execute(() -> failed(token, error, Optional.empty())); } }
                    });
                });
            } catch (Exception failure) { refreshAfterFailure(token, id, failure); }
        });
    }
    public void cancel() { if (active != null) { active.request(); notice("Annulation demandée…"); } }
    public void reloadConfiguration() {
        configure(() -> workspace.reloadAgents(), "Configuration rechargée ; les anciens reçus sont conservés.");
    }
    @FunctionalInterface private interface ConfigurationAction { ReviewWorkspace.Configuration run() throws Exception; }
    private void configure(ConfigurationAction action, String message) {
        if (state.busy() || state.dirty()) { return; } var oldView = state.view(); var token = begin(ReviewState.Operation.CONFIGURING);
        worker.execute(() -> { try {
            var configuration = action.run();
            var view = oldView.map(v -> workspace.open(v.message().id()));
            ui.execute(() -> { if (!closed && token == epoch) {
                state = new ReviewState(state.inbox(), state.view(), state.editor(), state.viewedVersion(), ReviewState.Operation.IDLE, "Configuration rechargée.", Optional.of(configuration));
                if (view.isPresent()) { display(view.get(), message); } else { notice(message); }
            } });
        } catch (Exception failure) { ui.execute(() -> failed(token, failure, Optional.empty())); } });
    }
    public void saveAgent(long revision, ConfigurationInputs.AgentInput input, boolean creating) {
        try { var value = input.value(); configure(() -> workspace.saveAgent(revision, value, creating), "Agent enregistré et appliqué. Les reçus précédents restent inchangés."); }
        catch (IllegalArgumentException failure) { notice("Agent invalide : identifiant, rôle, catégories, références et prompt sont requis. Aucune modification enregistrée."); }
    }
    public void saveSource(long revision, ConfigurationInputs.SourceInput input, boolean creating) {
        try { var value = input.value(); configure(() -> workspace.saveSource(revision, value, input.text(), creating), "Source enregistrée et appliquée. Les anciennes versions sont conservées."); }
        catch (IllegalArgumentException failure) { notice("Source invalide : vérifiez portée client/projet, visibilité, agents, destinataires et texte. Aucune modification enregistrée."); }
    }
    public void saveInference(long revision, ConfigurationInputs.InferenceInput input) {
        try { var value = input.value(); configure(() -> workspace.saveInference(revision, value), "Paramètres enregistrés pour le prochain lancement. Le moteur de cette session conserve ses paramètres chargés."); }
        catch (IllegalArgumentException failure) { notice("Paramètres invalides : endpoint HTTP loopback, nombres et budget cohérents requis. Aucune modification enregistrée."); }
    }
    public void loadSourceText(String id, java.util.function.Consumer<String> success, java.util.function.Consumer<String> failure) {
        var configuration = state.configuration();
        worker.execute(() -> {
            try { var text = workspace.sourceText(id); ui.execute(() -> { if (!closed && state.configuration().equals(configuration)) { success.accept(text); } }); }
            catch (Exception error) { ui.execute(() -> { if (!closed && state.configuration().equals(configuration)) { failure.accept(PresentationErrors.message(error)); } }); }
        });
    }
    public void close() { closed = true; epoch++; if (active != null) { active.request(); } }
}
