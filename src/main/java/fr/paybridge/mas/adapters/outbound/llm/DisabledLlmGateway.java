package fr.paybridge.mas.adapters.outbound.llm;

import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.domain.generation.*;

/** Mode réception seule : aucun texte de remplacement et aucune fausse génération réussie. */
public final class DisabledLlmGateway implements LocalLlmGateway {
    @Override public GeneratedReply generate(GenerationRequest request,CancellationSignal cancellation)throws GenerationException {
        cancellation.check();throw new GenerationException(GenerationFailure.GENERATION_DISABLED);
    }
}
