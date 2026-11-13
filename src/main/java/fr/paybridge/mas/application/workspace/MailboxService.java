package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.delivery.*;
import fr.paybridge.mas.domain.mail.*;
import java.util.*;
import java.util.function.Supplier;

/** Projections distinctes : message reçu, brouillon courant et chaque tentative figée. */
public final class MailboxService implements MailboxWorkspace {
    private final Supplier<List<EmailMessage>> authorized;
    private final DraftRepository drafts;
    private final MailboxFlagsRepository flags;
    public MailboxService(Supplier<List<EmailMessage>> authorized, DraftRepository drafts, MailboxFlagsRepository flags) {
        this.authorized = Objects.requireNonNull(authorized); this.drafts = Objects.requireNonNull(drafts); this.flags = Objects.requireNonNull(flags);
    }
    @Override public List<Entry> entries() {
        var result = new ArrayList<Entry>();
        for (var message : authorized.get()) {
            var state = flags.find(message.id());
            result.add(new Entry("received:" + message.id(), message.id(), Kind.RECEIVED, message.subject(), message.body(), message.sender().value(),
                    List.copyOf(message.recipients()), message.receivedAt().toInstant(), state, state.read() ? "Lu" : "Non lu"));
            var draft = drafts.findByMessage(message.id()).filter(d -> d.scope().equals(message.scope()) && d.conversationId().equals(message.conversationId()));
            draft.ifPresent(d -> {
                var current = d.current(); var content = current.content();
                boolean transmitted = d.sendAttempts().stream().anyMatch(a -> a.email().approval().version() == current.number()
                        && (a.status() == DeliveryStatus.CONFIRMED || a.status() == DeliveryStatus.SIMULATED || a.status() == DeliveryStatus.PENDING || a.status() == DeliveryStatus.UNKNOWN));
                if (!transmitted) {
                    result.add(new Entry("draft:" + d.id(), message.id(), Kind.DRAFT, content.subject(), content.body(), "Brouillon local",
                            List.copyOf(content.recipients()), current.createdAt(), state, "v" + current.number() + " · " + d.state(),true,
                            d.state()==fr.paybridge.mas.domain.draft.Draft.State.GENERATED||d.state()==fr.paybridge.mas.domain.draft.Draft.State.EDITED));
                }
                for (var attempt : d.sendAttempts()) {
                    var kind = switch (attempt.status()) {
                        case CONFIRMED -> Kind.SENT;
                        case SIMULATED -> Kind.SIMULATED;
                        default -> Kind.OUTBOX;
                    };
                    var approved = attempt.email().approval().content();
                    result.add(new Entry("attempt:" + attempt.email().attemptId(), message.id(), kind, approved.subject(), approved.body(), attempt.email().sender().value(),
                            List.copyOf(approved.recipients()), attempt.completedAt().orElse(attempt.startedAt()), state, attempt.status().name()));
                }
            });
        }
        return result.stream().sorted(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::key)).toList();
    }
    @Override public void update(MailboxFlags expected, MailboxFlags.Location location, boolean read, boolean starred) {
        if (authorized.get().stream().noneMatch(m -> m.id().equals(expected.messageId()))) { throw new IllegalArgumentException("Message non autorisé"); }
        flags.replace(expected, expected.change(location, read, starred));
    }
}
