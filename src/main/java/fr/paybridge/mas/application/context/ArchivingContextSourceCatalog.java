package fr.paybridge.mas.application.context;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.context.ContextSource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class ArchivingContextSourceCatalog implements ContextSourceCatalog {
    private final ContextSourceCatalog delegate;
    private final SourceArchive archive;
    public ArchivingContextSourceCatalog(ContextSourceCatalog delegate, SourceArchive archive) {
        this.delegate = Objects.requireNonNull(delegate); this.archive = Objects.requireNonNull(archive);
    }
    @Override public List<ContextSource> sources() throws IOException {
        var sources = List.copyOf(delegate.sources());
        archive.rememberMetadata(sources);
        return sources;
    }
}
