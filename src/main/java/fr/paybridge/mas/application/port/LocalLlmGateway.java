package fr.paybridge.mas.application.port;

import fr.paybridge.mas.application.generation.CancellationSignal;
import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.domain.generation.GeneratedReply;
import fr.paybridge.mas.domain.generation.GenerationRequest;

public interface LocalLlmGateway {
    GeneratedReply generate(GenerationRequest request, CancellationSignal cancellation) throws GenerationException;
}
