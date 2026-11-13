package fr.paybridge.mas.adapters.outbound.config;

import fr.paybridge.mas.domain.routing.RoutingPolicy;
import fr.paybridge.mas.domain.routing.RoutingRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

/** Configuration de stratégie chargée au lancement ; aucune catégorie métier codée ici. */
public final class PropertiesRoutingPolicyLoader {
    private final Path configRoot;

    public PropertiesRoutingPolicyLoader(Path configRoot) {
        this.configRoot = configRoot.toAbsolutePath().normalize();
    }

    public RoutingPolicy load() throws IOException {
        var root = configRoot.toRealPath();
        var policy = read(inside(root, root.resolve("routing/policy.properties")),
                Set.of("id", "version", "threshold", "ambiguityMargin", "historyLimit"));
        var rules = new ArrayList<RoutingRule>();
        try (var files = Files.list(inside(root, root.resolve("routing/rules")))) {
            for (var file : files.filter(p -> p.toString().endsWith(".properties")).sorted().toList()) {
                var rule = read(inside(root, file), Set.of("id", "category", "field", "terms", "weight"));
                var terms = Arrays.stream(required(rule, "terms").split(",", -1)).map(String::strip).toList();
                if (terms.stream().distinct().count() != terms.size()) {
                    throw new IllegalArgumentException("Terme de règle dupliqué");
                }
                rules.add(new RoutingRule(required(rule, "id"), required(rule, "category"),
                        RoutingRule.Field.valueOf(required(rule, "field")), terms, Integer.parseInt(required(rule, "weight"))));
            }
        }
        return new RoutingPolicy(required(policy, "id"), Long.parseLong(required(policy, "version")),
                Integer.parseInt(required(policy, "threshold")), Integer.parseInt(required(policy, "ambiguityMargin")),
                Integer.parseInt(required(policy, "historyLimit")), rules);
    }

    private static Path inside(Path root, Path file) throws IOException {
        var actual = file.toRealPath();
        if (!actual.startsWith(root)) {
            throw new IllegalArgumentException("Configuration de routage hors de la racine approuvée");
        }
        return actual;
    }

    private static Properties read(Path path, Set<String> fields) throws IOException {
        var values = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("Propriété de routage dupliquée");
                }
                return super.put(key, value);
            }
        };
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        if (!values.stringPropertyNames().equals(fields)) {
            throw new IllegalArgumentException("Champs de routage absents ou inconnus : " + path.getFileName());
        }
        return values;
    }

    private static String required(Properties values, String field) {
        var value = values.getProperty(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ de routage obligatoire : " + field);
        }
        return value.strip();
    }
}
