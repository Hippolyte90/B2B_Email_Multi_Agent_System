package fr.paybridge.mas;

import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.adapters.inbound.presentation.PresentationErrors;
import fr.paybridge.mas.domain.generation.GenerationFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveInboxDiagnosticsTest {
    @Test void truncatedResponseIsReportedWithoutDraftOrSend(){
        var f=new LiveInboxFlowTest();f.f.provider.set((r,c)->{throw new GenerationException(GenerationFailure.INCOMPLETE_RESPONSE);});
        var flow=f.flow("one");flow.start();f.arrive();f.complete();
        var failure=f.events.stream().filter(e->e.status()==LiveInboxFlow.Status.FAILED).findFirst().orElseThrow();
        assertEquals(GenerationFailure.INCOMPLETE_RESPONSE,failure.failure().orElseThrow());assertEquals("one",failure.messageId());
        assertEquals(1,failure.failed());assertTrue(f.f.drafts.findByMessage("one").isEmpty());assertTrue(f.backend.sender.entries().isEmpty());
    }
    @Test void modelUnavailableIsDistinguishedFromTruncatedResponse(){
        var f=new LiveInboxFlowTest();f.f.provider.set((r,c)->{throw new GenerationException(GenerationFailure.UNAVAILABLE);});
        var flow=f.flow("one");flow.start();f.arrive();f.complete();
        assertEquals(GenerationFailure.UNAVAILABLE,f.events.stream().filter(e->e.status()==LiveInboxFlow.Status.FAILED).findFirst().orElseThrow().failure().orElseThrow());
    }
    @Test void presentationExplainsTruncationWithoutClaimingEngineIsUnavailable(){
        var text=PresentationErrors.message(new WorkspaceGenerationFailure(GenerationFailure.INCOMPLETE_RESPONSE));
        assertTrue(text.contains("limite"));assertTrue(text.contains("Aucun texte coupé"));assertFalse(text.contains("indisponible"));
    }
}
