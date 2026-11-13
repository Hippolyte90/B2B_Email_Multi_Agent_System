package fr.paybridge.mas.application.context;

import fr.paybridge.mas.domain.context.ContextDocument;
import fr.paybridge.mas.domain.context.ContextExcerpt;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.context.MemoryEntry;

import java.util.List;

public interface ContextSelectionPolicy {
    List<ContextExcerpt> rank(EmailMessage message, List<MemoryEntry> selectedHistory, List<ContextDocument> authorizedDocuments);
}
