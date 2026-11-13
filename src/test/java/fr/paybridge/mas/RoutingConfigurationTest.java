package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.config.PropertiesRoutingPolicyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RoutingConfigurationTest {
    @TempDir Path root;
    private static final String POLICY = "id=test-rules\nversion=1\nthreshold=3\nambiguityMargin=1\nhistoryLimit=6\n";
    private static final String RULE = "id=technical-body\ncategory=technical\nfield=BODY\nterms=échec,sdk\nweight=3\n";

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(root.resolve("routing/rules"));
        Files.writeString(root.resolve("routing/policy.properties"), POLICY);
        Files.writeString(root.resolve("routing/rules/test.properties"), RULE);
    }

    @Test
    void loadsVersionThresholdsAndUtf8Rule() throws IOException {
        var policy = new PropertiesRoutingPolicyLoader(root).load();
        assertEquals(3, policy.threshold());
        assertEquals(1, policy.ambiguityMargin());
        assertEquals("échec", policy.rules().getFirst().terms().getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"threshold=0", "threshold=bad", "version=0", "ambiguityMargin=-1", "historyLimit=-1"})
    void rejectsInvalidPolicy(String replacement) throws IOException {
        var key = replacement.substring(0, replacement.indexOf('='));
        Files.writeString(root.resolve("routing/policy.properties"), POLICY.replaceAll("(?m)^" + key + "=.*$", replacement));
        assertThrows(IllegalArgumentException.class, () -> new PropertiesRoutingPolicyLoader(root).load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"weight=0", "weight=-1", "field=UNKNOWN", "terms=", "terms=sdk,,other", "terms=sdk,sdk", "category="})
    void rejectsMalformedRule(String replacement) throws IOException {
        var key = replacement.substring(0, replacement.indexOf('='));
        Files.writeString(root.resolve("routing/rules/test.properties"), RULE.replaceAll("(?m)^" + key + "=.*$", replacement));
        assertThrows(IllegalArgumentException.class, () -> new PropertiesRoutingPolicyLoader(root).load());
    }

    @Test
    void rejectsDuplicateRuleIds() throws IOException {
        Files.writeString(root.resolve("routing/rules/duplicate.properties"), RULE);
        assertThrows(IllegalArgumentException.class, () -> new PropertiesRoutingPolicyLoader(root).load());
    }

    @Test
    void rejectsUnknownMissingOrDuplicateProperties() throws IOException {
        for (var invalid : java.util.List.of(RULE + "unknown=1\n", RULE.replace("field=BODY\n", ""), RULE + "weight=9\n")) {
            Files.writeString(root.resolve("routing/rules/test.properties"), invalid);
            assertThrows(IllegalArgumentException.class, () -> new PropertiesRoutingPolicyLoader(root).load());
        }
    }
}
