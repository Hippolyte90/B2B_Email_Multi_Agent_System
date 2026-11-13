package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.context.ContextSource;
import java.util.Collection;
import java.util.Optional;

/** Archive interne : ne remplace jamais l'autorisation d'accès aux sources actuelles. */
public interface SourceArchive {
    void rememberMetadata(Collection<ContextSource> sources);
    void rememberText(ContextSource source, String text);
    Optional<ContextSource> metadata(String id, long version);
    Optional<String> text(String id, long version);
}
