package fr.paybridge.mas.application.context;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.context.ContextSource;
import java.io.IOException;
import java.util.Objects;

/** Appelé par ContextService après autorisation ; aucun repli vers un ancien texte archivé. */
public final class ArchivingSourceReader implements SourceReader {
    private final SourceReader delegate;
    private final SourceArchive archive;
    public ArchivingSourceReader(SourceReader delegate, SourceArchive archive) {
        this.delegate = Objects.requireNonNull(delegate); this.archive = Objects.requireNonNull(archive);
    }
    @Override public String read(ContextSource source) throws IOException {
        var text = delegate.read(source);
        archive.rememberText(source, text);
        return text;
    }
}
