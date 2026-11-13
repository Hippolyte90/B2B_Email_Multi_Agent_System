package fr.paybridge.mas.adapters.outbound.llm;

import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.domain.generation.GenerationFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OllamaProcessRuntimeTest {
    @TempDir Path temp;
    @Test void findsInstalledExecutableInPathWithoutShell()throws Exception{var file=Files.createFile(temp.resolve("ollama.exe"));assertEquals(file,OllamaProcessRuntime.findExecutable(Map.of("Path",temp.toString()),true));}
    @Test void supportsDefaultWindowsInstallation()throws Exception{var dir=Files.createDirectories(temp.resolve("Programs/Ollama"));var file=Files.createFile(dir.resolve("ollama.exe"));assertEquals(file,OllamaProcessRuntime.findExecutable(Map.of("LOCALAPPDATA",temp.toString()),true));}
    @Test void missingInstallationIsExplicit(){assertEquals(GenerationFailure.ENGINE_NOT_INSTALLED,assertThrows(GenerationException.class,()->OllamaProcessRuntime.findExecutable(Map.of(),true)).code());}
    @Test void environmentUsesConfiguredLoopbackAndExistingProjectModels(){var endpoint=new LoopbackEndpoint(URI.create("http://localhost:12435/"));var env=OllamaProcessRuntime.environment(endpoint,temp);assertEquals("127.0.0.1:12435",env.get("OLLAMA_HOST"));assertEquals(temp.toString(),env.get("OLLAMA_MODELS"));assertEquals("1",env.get("OLLAMA_NO_CLOUD"));assertEquals("0",env.get("OLLAMA_DEBUG"));}
}
