package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.mail.MailboxFlags;

final class SqliteMailboxFlagsRepository implements MailboxFlagsRepository {
    private final SqliteStore store;
    SqliteMailboxFlagsRepository(SqliteStore store) { this.store = store; }
    public MailboxFlags find(String id) {
        return store.inTransaction(() -> {
            if (store.conversations().findMessage(id).isEmpty()) { throw new IllegalArgumentException("Message absent"); }
            return store.payload("SELECT payload FROM mailbox_flags WHERE message_id=?", id).map(p -> {
                var result = store.decode(p, MailboxFlags.class);
                var revision = store.payload("SELECT CAST(revision AS TEXT) FROM mailbox_flags WHERE message_id=?", id).orElseThrow();
                if (!result.messageId().equals(id) || result.revision() < 1 || !revision.equals(Long.toString(result.revision()))) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
                return result;
            }).orElseGet(() -> MailboxFlags.initial(id));
        });
    }
    public void replace(MailboxFlags expected, MailboxFlags updated) {
        store.inTransaction(() -> {
            var current = find(expected.messageId());
            if (!current.equals(expected) || !updated.messageId().equals(expected.messageId())) { throw new IllegalArgumentException("Classement obsolète"); }
            if (expected.equals(updated)) { return null; }
            if (updated.revision() != Math.addExact(expected.revision(), 1)) { throw new IllegalArgumentException("Révision invalide"); }
            if (expected.revision() == 0) {
                store.write("INSERT INTO mailbox_flags(message_id,revision,payload) VALUES(?,?,?)", updated.messageId(), updated.revision(), store.encode(updated));
            } else {
                store.write("UPDATE mailbox_flags SET revision=?,payload=? WHERE message_id=? AND revision=?", updated.revision(), store.encode(updated), updated.messageId(), expected.revision());
            }
            store.audit("MAILBOX_CLASSIFIED", updated.messageId(), updated.revision()); return null;
        });
    }
}
