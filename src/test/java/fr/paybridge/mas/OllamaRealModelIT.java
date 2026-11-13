package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.config.PropertiesInferenceConfiguration;
import fr.paybridge.mas.adapters.outbound.llm.OllamaLocalLlmGateway;
import fr.paybridge.mas.application.generation.CancellationSignal;
import fr.paybridge.mas.domain.context.ContextBundle;
import fr.paybridge.mas.domain.generation.GenerationRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exécuté uniquement avec le profil real-llm ; données exclusivement synthétiques. */
class OllamaRealModelIT {
    @Test
    void returnsNonEmptyLocalReplyWithNoRemoteFallback() throws Exception {
        var config = PropertiesInferenceConfiguration.load(Path.of("config/application/inference.properties"));
        var context = new ContextBundle("Réponds en français en une phrase. N'invente pas de faits.",
                "Prépare un email demandant la version du SDK avant une aide technique.", List.of(), List.of(), Optional.empty(), List.of(),
                new ContextBundle.Usage("test-estimate", true, 200, config.settings().outputLimit(), config.settings().contextCapacity()));
        var request = new GenerationRequest(UUID.randomUUID(), context, config.settings());
        var reply = new OllamaLocalLlmGateway(config.endpoint()).generate(request, new CancellationSignal());
        assertEquals(request.taskId(), reply.taskId());
        assertFalse(reply.text().isBlank());
        assertFalse(reply.simulated());
        assertEquals(config.settings().model(), reply.model());
    }
}
