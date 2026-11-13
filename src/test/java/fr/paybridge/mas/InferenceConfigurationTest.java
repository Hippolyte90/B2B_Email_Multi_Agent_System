package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.config.PropertiesInferenceConfiguration;
import fr.paybridge.mas.adapters.outbound.llm.LoopbackEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InferenceConfigurationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com:443", "http://192.168.0.10:11434", "http://0.0.0.0:11434", "http://127.0.0.1:11434/api/chat", "http://user@127.0.0.1:11434", "http://127.0.0.1:11434/?x=1", "http://127.0.0.1", "http://localhost.evil:11434"})
    void refusesNonLoopbackOrAmbiguousEndpoints(String uri) {
        assertThrows(IllegalArgumentException.class, () -> new LoopbackEndpoint(URI.create(uri)));
    }

    @Test
    void pinsLocalhostToNumericLoopbackAndSupportsIpv6Literal() {
        assertEquals("127.0.0.1", new LoopbackEndpoint(URI.create("http://localhost:11435")).base().getHost());
        assertEquals("[::1]", new LoopbackEndpoint(URI.create("http://[::1]:11435")).base().getHost());
    }

    @Test
    void loadsLocalConfigurationAndRefusesDuplicateOrMissingKeys() throws Exception {
        var original = Files.readString(Path.of("config/application/inference.properties"));
        var path = temporary.resolve("inference.properties");
        Files.writeString(path, original);
        var configuration = PropertiesInferenceConfiguration.load(path);
        assertEquals("qwen2.5:0.5b", configuration.settings().model());
        assertEquals(8192, configuration.settings().contextCapacity());
        Files.writeString(path, original + "\nmodel=other\n");
        assertThrows(IllegalArgumentException.class, () -> PropertiesInferenceConfiguration.load(path));
        Files.writeString(path, original.replace("seed=42\n", ""));
        assertThrows(IllegalArgumentException.class, () -> PropertiesInferenceConfiguration.load(path));
    }
}
