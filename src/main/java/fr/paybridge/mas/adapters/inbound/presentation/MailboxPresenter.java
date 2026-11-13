package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.workspace.MailboxWorkspace;
import fr.paybridge.mas.application.workspace.MailboxWorkspace.*;
import fr.paybridge.mas.domain.mail.MailboxFlags.Location;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Chaque E/S passe sur worker ; une réponse tardive ne remplace pas la sélection courante. */
public final class MailboxPresenter {
    private final MailboxWorkspace workspace;
    private final Executor worker, ui;
    private final Consumer<MailboxState> render;
    private final BooleanSupplier blocked;
    private MailboxState state = new MailboxState(List.of(), Folder.INBOX, "", Optional.empty(), false, "Messagerie locale — Gmail non connecté.");
    private long epoch;
    private boolean closed;
    private boolean refreshAgain;
    public MailboxPresenter(MailboxWorkspace workspace, Executor worker, Executor ui, BooleanSupplier blocked, Consumer<MailboxState> render) {
        this.workspace = workspace; this.worker = worker; this.ui = ui; this.blocked = blocked; this.render = render;
    }
    public MailboxState state() { return state; }
    private void publish() { if (!closed) { render.accept(state); } }
    private boolean canNavigate() {
        if (blocked.getAsBoolean()) { state = new MailboxState(state.entries(), state.folder(), state.query(), state.selectedKey(), state.busy(), "Enregistrez ou annulez le travail en cours dans l'assistant."); publish(); return false; }
        return !closed && !state.busy();
    }
    public void folder(Folder folder) {
        if (!canNavigate()) { return; }
        state = new MailboxState(state.entries(), Objects.requireNonNull(folder), state.query(), Optional.empty(), false, "Dossier : " + folder); publish();
    }
    public void search(String query) {
        if (closed) { return; }
        state = new MailboxState(state.entries(), state.folder(), Objects.requireNonNull(query), state.selectedKey(), state.busy(), state.notice()); publish();
    }
    public void select(String key) {
        if (!canNavigate()) { return; }
        var entry = state.visible().stream().filter(e -> e.key().equals(key)).findFirst();
        if (entry.isEmpty()) { return; }
        state = new MailboxState(state.entries(), state.folder(), state.query(), Optional.of(key), false, entry.get().status()); publish();
        if (entry.get().kind() == Kind.RECEIVED && !entry.get().flags().read()) { update(entry.get().flags().location(), true, entry.get().flags().starred()); }
    }
    public void move(Location location) { state.selected().filter(e -> e.kind() == Kind.RECEIVED).ifPresent(e -> update(location, e.flags().read(), e.flags().starred())); }
    public void toggleRead() { state.selected().filter(e -> e.kind() == Kind.RECEIVED).ifPresent(e -> update(e.flags().location(), !e.flags().read(), e.flags().starred())); }
    public void toggleStar() { state.selected().filter(e -> e.kind() == Kind.RECEIVED).ifPresent(e -> update(e.flags().location(), e.flags().read(), !e.flags().starred())); }
    private void update(Location location, boolean read, boolean starred) {
        if (!canNavigate()) { return; }
        var selected = state.selected().orElseThrow(); load(() -> workspace.update(selected.flags(), location, read, starred), "Classement enregistré localement.");
    }
    public void refresh() {
        if(closed){return;}if(state.busy()){refreshAgain=true;return;}
        load(workspace::synchronize, workspace.connected()?"Gmail actualisé (200 messages récents maximum, plus les brouillons).":"Messagerie actualisée — Gmail non connecté.");
    }
    private void refreshQueued(){if(refreshAgain&&!closed){refreshAgain=false;refresh();}}
    public void exportDraft() {
        if(!canNavigate()||!workspace.connected()){return;}
        state.selected().filter(e->e.kind()==Kind.DRAFT&&e.key().startsWith("draft:")).ifPresent(e->load(()->workspace.exportDraft(e.key()),"Brouillon enregistré dans Gmail ; aucun envoi."));
    }
    private void load(Runnable mutation, String notice) {
        var token = ++epoch;
        state = new MailboxState(state.entries(), state.folder(), state.query(), state.selectedKey(), true, "Chargement…"); publish();
        worker.execute(() -> {
            try {
                mutation.run(); var entries = workspace.entries();
                ui.execute(() -> { if (!closed && token == epoch) {
                    state = new MailboxState(entries, state.folder(), state.query(), state.selectedKey(), false, notice); publish();refreshQueued();
                } });
            } catch (Exception failure) {
                ui.execute(() -> { if (!closed && token == epoch) {
                    state = new MailboxState(state.entries(), state.folder(), state.query(), state.selectedKey(), false, PresentationErrors.message(failure) + " Actualisez la messagerie avant de réessayer."); publish();refreshQueued();
                } });
            }
        });
    }
    public void close() { closed = true; epoch++; }
}
