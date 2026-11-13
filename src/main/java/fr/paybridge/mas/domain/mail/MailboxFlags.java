package fr.paybridge.mas.domain.mail;

import java.util.Objects;

/** Classement local d'un message reçu ; indépendant du routage métier. */
public record MailboxFlags(String messageId, long revision, Location location, boolean read, boolean starred) {
    public enum Location { INBOX, ARCHIVE, SPAM, TRASH }
    public MailboxFlags {
        if (messageId == null || messageId.isBlank() || revision < 0) { throw new IllegalArgumentException("État de messagerie invalide"); }
        Objects.requireNonNull(location);
        if (revision == 0 && (location != Location.INBOX || read || starred)) { throw new IllegalArgumentException("État initial invalide"); }
    }
    public static MailboxFlags initial(String messageId) { return new MailboxFlags(messageId, 0, Location.INBOX, false, false); }
    public MailboxFlags change(Location location, boolean read, boolean starred) {
        if (this.location == location && this.read == read && this.starred == starred) { return this; }
        return new MailboxFlags(messageId, Math.addExact(revision, 1), location, read, starred);
    }
}
