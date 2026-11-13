package fr.paybridge.mas.adapters.outbound.context;

import fr.paybridge.mas.application.port.SourceReader;
import fr.paybridge.mas.domain.context.ContextSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class LocalTextSourceReader implements SourceReader {
    private final Path configRoot;

    public LocalTextSourceReader(Path configRoot) { this.configRoot = configRoot.toAbsolutePath().normalize(); }

    @Override
    public String read(ContextSource source) throws IOException {
        var root = configRoot.toRealPath();
        var relative = Path.of(source.reference());
        if (relative.isAbsolute() || !root.resolve(relative).normalize().startsWith(root)) {
            throw new IllegalArgumentException("Source hors racine approuvée");
        }
        var actual = root.resolve(relative).toRealPath();
        var name = actual.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!actual.startsWith(root) || !Files.isRegularFile(actual) || !Files.isReadable(actual)
                || (!name.endsWith(".md") && !name.endsWith(".txt"))) {
            throw new IllegalArgumentException("Source hors racine, illisible ou format non pris en charge");
        }
        return Files.readString(actual, StandardCharsets.UTF_8);
    }
}
