package fr.paybridge.mas.application.configuration;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.context.ContextSource;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Le lecteur de génération reste appelé après les autorisations de ContextService. */
public final class ConfigurationSources implements ContextSourceCatalog, SourceReader {
    private final Supplier<EditableConfiguration> current;
    private final SourceReader files;
    public ConfigurationSources(Supplier<EditableConfiguration> current, SourceReader files) { this.current = current; this.files = files; }
    @Override public List<ContextSource> sources() { return current.get().sources(); }
    @Override public String read(ContextSource source) throws IOException {
        var snapshot = current.get();
        if (!snapshot.sources().contains(source)) { throw new IOException("Source modifiée pendant la lecture"); }
        var override = snapshot.textOverrides().get(source.id());
        return override == null ? files.read(source) : override;
    }
}
