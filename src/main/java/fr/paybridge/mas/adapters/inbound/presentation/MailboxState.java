package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.workspace.MailboxWorkspace.*;
import fr.paybridge.mas.domain.mail.MailboxFlags.Location;
import java.text.Normalizer;
import java.util.*;

public record MailboxState(List<Entry> entries, Folder folder, String query, Optional<String> selectedKey, boolean busy, String notice) {
    public MailboxState { entries = List.copyOf(entries); }
    public Optional<Entry> selected() { return visible().stream().filter(e -> selectedKey.filter(e.key()::equals).isPresent()).findFirst(); }
    public long count(Folder folder) { return entries.stream().filter(e -> matches(e, folder)).count(); }
    public List<Entry> visible() {
        var words = normalize(query).strip().split("\\s+");
        return entries.stream().filter(e -> matches(e, folder)).filter(e -> {
            var text = normalize(e.subject() + " " + e.body() + " " + e.sender() + " " + e.recipients().stream().map(a -> a.value()).toList());
            return Arrays.stream(words).allMatch(text::contains);
        }).toList();
    }
    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT); }
    private static boolean matches(Entry e, Folder folder) {
        return switch (folder) {
            case PENDING_VALIDATION -> e.reviewRequired();
            case DRAFTS -> e.kind() == Kind.DRAFT;
            case SENT -> e.kind() == Kind.SENT;
            case OUTBOX -> e.kind() == Kind.OUTBOX;
            case SIMULATED -> e.kind() == Kind.SIMULATED;
            default -> e.kind() == Kind.RECEIVED && switch (folder) {
                case INBOX -> e.flags().location() == Location.INBOX;
                case ARCHIVE -> e.flags().location() == Location.ARCHIVE;
                case SPAM -> e.flags().location() == Location.SPAM;
                case TRASH -> e.flags().location() == Location.TRASH;
                case STARRED -> e.flags().starred() && e.flags().location() != Location.SPAM && e.flags().location() != Location.TRASH;
                case ALL -> e.flags().location() != Location.SPAM && e.flags().location() != Location.TRASH;
                default -> false;
            };
        };
    }
}
