package fr.paybridge.mas.application.context;

import fr.paybridge.mas.domain.context.ContextRequest;
import fr.paybridge.mas.domain.context.MemoryEntry;

import java.util.List;

public interface ConversationMemoryPolicy {
    /** Classe les entrées déjà autorisées, sans en ajouter ou modifier. */
    List<MemoryEntry> select(ContextRequest request, List<MemoryEntry> authorizedHistory);
}
