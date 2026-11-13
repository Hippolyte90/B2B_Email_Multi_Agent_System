package fr.paybridge.mas.adapters.outbound.config;

import fr.paybridge.mas.application.port.AgentConfigurationLoader;
import fr.paybridge.mas.domain.agent.AgentDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Charge uniquement la configuration approuvée ; aucune donnée issue d'un email. */
public final class PropertiesAgentConfigurationLoader implements AgentConfigurationLoader {
    private static final Set<String> FIELDS = Set.of("id", "version", "role", "categories", "prompt", "sources", "active", "fallback");
    private final Path root;

    public PropertiesAgentConfigurationLoader(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public List<AgentDefinition> load() throws IOException {
        var realRoot = root.toRealPath();
        var directory = resolveInside(realRoot, "agents", false);
        var definitions = new ArrayList<AgentDefinition>();
        try (var files = Files.list(directory)) {
            for (var file : files.filter(p -> p.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                var safeFile = resolveInside(realRoot, realRoot.relativize(file).toString(), true);
                definitions.add(read(realRoot, safeFile));
            }
        }
        return List.copyOf(definitions);
    }

    private AgentDefinition read(Path realRoot, Path file) throws IOException {
        var properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("Propriété de configuration dupliquée : " + key);
                }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!properties.stringPropertyNames().equals(FIELDS)) {
            throw new IllegalArgumentException("Champs de configuration d'agent absents ou inconnus : " + file.getFileName());
        }
        var prompt = Files.readString(resolveInside(realRoot, required(properties, "prompt"), true), StandardCharsets.UTF_8);
        var sources = csv(properties.getProperty("sources"), true);
        for (var source : sources) {
            resolveInside(realRoot, source, true);
        }
        return new AgentDefinition(required(properties, "id"), Long.parseLong(required(properties, "version")),
                required(properties, "role"), csv(required(properties, "categories"), false), prompt, sources,
                bool(properties, "active"), bool(properties, "fallback"));
    }

    private Path resolveInside(Path realRoot, String relative, boolean file) throws IOException {
        var path = Path.of(relative);
        if (path.isAbsolute() || !realRoot.resolve(path).normalize().startsWith(realRoot)) {
            throw new IllegalArgumentException("Une référence de configuration doit rester sous sa racine");
        }
        var resolved = realRoot.resolve(path).toRealPath();
        if (!resolved.startsWith(realRoot) || (file ? !Files.isRegularFile(resolved) : !Files.isDirectory(resolved))) {
            throw new IllegalArgumentException("Référence de configuration hors racine ou de type invalide");
        }
        if (!Files.isReadable(resolved)) {
            throw new IllegalArgumentException("Référence de configuration illisible");
        }
        return resolved;
    }

    private static String required(Properties properties, String field) {
        var value = properties.getProperty(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ d'agent obligatoire : " + field);
        }
        return value.strip();
    }

    private static boolean bool(Properties properties, String field) {
        return switch (required(properties, field)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Booléen invalide : " + field);
        };
    }

    private static Set<String> csv(String text, boolean allowEmpty) {
        if (text.isBlank() && allowEmpty) {
            return Set.of();
        }
        var values = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
        if (values.stream().anyMatch(String::isBlank) || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("Liste vide, incomplète ou contenant un doublon");
        }
        return values.stream().collect(Collectors.toUnmodifiableSet());
    }
}
