package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.MailboxFlagsRepository;
import fr.paybridge.mas.domain.mail.MailboxFlags;
import java.util.*;

public final class InMemoryMailboxFlagsRepository implements MailboxFlagsRepository {
    private final Map<String, MailboxFlags> entries = new HashMap<>();
    public synchronized MailboxFlags find(String id) { return entries.getOrDefault(id, MailboxFlags.initial(id)); }
    public synchronized void replace(MailboxFlags expected, MailboxFlags updated) {
        if (!find(expected.messageId()).equals(expected) || !updated.messageId().equals(expected.messageId())) { throw new IllegalArgumentException("Classement obsolète"); }
        if (expected.equals(updated)) { return; }
        if (updated.revision() != Math.addExact(expected.revision(), 1)) { throw new IllegalArgumentException("Révision invalide"); }
        entries.put(updated.messageId(), updated);
    }
}
