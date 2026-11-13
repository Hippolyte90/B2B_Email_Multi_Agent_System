package fr.paybridge.mas.adapters.outbound.config;

import fr.paybridge.mas.application.port.ContextSourceCatalog;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Lit les manifestes, sans ouvrir les documents qu'ils référencent. */
public final class PropertiesContextSourceCatalog implements ContextSourceCatalog {
    private static final Set<String> FIELDS = Set.of("id", "version", "reference", "provenance", "clientId", "projectId", "visibility", "agents", "recipients");
    private final Path configRoot;

    public PropertiesContextSourceCatalog(Path configRoot) { this.configRoot = configRoot.toAbsolutePath().normalize(); }

    @Override
    public List<ContextSource> sources() throws IOException {
        var root = configRoot.toRealPath();
        var directory = root.resolve("sources").toRealPath();
        if (!directory.startsWith(root)) { throw new IllegalArgumentException("Catalogue hors racine"); }
        var sources = new ArrayList<ContextSource>();
        try (var files = Files.list(directory)) {
            for (var path : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                var actual = path.toRealPath();
                if (!actual.startsWith(root)) { throw new IllegalArgumentException("Manifeste hors racine"); }
                sources.add(read(actual));
            }
        }
        if (sources.stream().map(ContextSource::id).distinct().count() != sources.size()
                || sources.stream().map(ContextSource::reference).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("Source dupliquée dans le catalogue");
        }
        return List.copyOf(sources);
    }

    private ContextSource read(Path path) throws IOException {
        var values = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) { throw new IllegalArgumentException("Propriété de source dupliquée"); }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { values.load(reader); }
        if (!values.stringPropertyNames().equals(FIELDS)) { throw new IllegalArgumentException("Champs de source absents ou inconnus"); }
        return new ContextSource(required(values, "id"), Long.parseLong(required(values, "version")), required(values, "reference"),
                required(values, "provenance"), new ClientScope(optional(values, "clientId"), optional(values, "projectId")),
                ContextSource.Visibility.valueOf(required(values, "visibility")), csv(values.getProperty("agents")),
                csv(values.getProperty("recipients")).stream().map(EmailAddress::new).collect(Collectors.toUnmodifiableSet()));
    }

    private static String required(Properties values, String field) {
        var value = values.getProperty(field).strip();
        if (value.isEmpty()) { throw new IllegalArgumentException("Champ de source obligatoire : " + field); }
        return value;
    }

    private static Optional<String> optional(Properties values, String field) {
        var value = values.getProperty(field).strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static Set<String> csv(String text) {
        if (text.isBlank()) { return Set.of(); }
        var list = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
        if (list.stream().anyMatch(String::isBlank) || list.stream().distinct().count() != list.size()) {
            throw new IllegalArgumentException("Liste de source invalide");
        }
        return Set.copyOf(list);
    }
}
