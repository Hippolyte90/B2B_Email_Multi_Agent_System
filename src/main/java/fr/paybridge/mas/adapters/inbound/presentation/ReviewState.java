package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.workspace.ReviewWorkspace;
import fr.paybridge.mas.domain.delivery.DeliveryStatus;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.mail.EmailMessage;
import java.util.*;

/** État de présentation indépendant de JavaFX ; chaque décision utilise le brouillon affiché. */
public record ReviewState(List<EmailMessage> inbox, Optional<ReviewWorkspace.MessageView> view, ReplyEditor editor,
                          int viewedVersion, Operation operation, String notice, Optional<ReviewWorkspace.Configuration> configuration) {
    public enum Operation { IDLE, LOADING, GENERATING, WRITING, CONFIGURING }
    public ReviewState { inbox = List.copyOf(inbox); }
    public Optional<Draft> draft() { return view.flatMap(ReviewWorkspace.MessageView::draft); }
    public boolean busy() { return operation != Operation.IDLE; }
    public boolean historical() { return draft().filter(d -> viewedVersion != d.current().number()).isPresent(); }
    public boolean dirty() { return draft().filter(d -> !historical() && !editor.equals(ReplyEditor.of(d.current().content()))).isPresent(); }
    public boolean unresolvedSend() { return draft().stream().flatMap(d -> d.sendAttempts().stream()).anyMatch(a -> a.status() == DeliveryStatus.PENDING || a.status() == DeliveryStatus.UNKNOWN); }
    public boolean canEdit() { return !busy() && !historical() && !unresolvedSend() && draft().filter(d -> d.state() != Draft.State.REJECTED && d.pendingRegeneration().isEmpty()).isPresent(); }
    public boolean canSave() { return canEdit() && dirty() && editor.valid(); }
    public boolean canGenerate() { return !busy() && view.filter(v -> v.draft().isEmpty() && v.routing().effectiveAgentId().isPresent()).isPresent(); }
    public boolean canChoose() { return !busy() && !dirty() && !historical() && view.isPresent(); }
    public boolean canApprove() { return canEdit() && !dirty() && draft().filter(d -> d.lastAttempt(d.current().number()).isEmpty()).isPresent(); }
    public boolean canRegenerate() {
        return !busy() && !dirty() && !historical() && !unresolvedSend()
                && view.filter(v -> v.routing().effectiveAgentId().isPresent()).isPresent()
                && draft().filter(d -> d.state() != Draft.State.APPROVED && d.pendingRegeneration().isEmpty()).isPresent();
    }
    public boolean canRetry() { return !busy() && !dirty() && !historical() && !unresolvedSend() && draft().filter(d -> d.approval().isPresent()
            && d.lastAttempt(d.current().number()).filter(a -> a.status() == DeliveryStatus.FAILED).isPresent()).isPresent(); }
}
