package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.llm.DisabledLlmGateway;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.domain.generation.GenerationFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoDefaultReplyTest {
    @Test void disabledProviderFailsWithoutReturningTemplateText(){var f=new DraftTestFixture();f.provider.set(new DisabledLlmGateway());var result=f.generate();assertEquals(GenerationFailure.GENERATION_DISABLED,result.failure().orElseThrow());assertTrue(result.reply().isEmpty());assertTrue(f.drafts.findByMessage("message").isEmpty());}
    @Test void failedAutomaticGenerationLeavesNoDraftToValidate(){var backend=new ReviewWorkspaceServiceTest();backend.f.provider.set(new DisabledLlmGateway());var work=backend.workspace.generate("message");assertThrows(java.util.concurrent.CompletionException.class,()->work.result().toCompletableFuture().join());assertTrue(backend.workspace.open("message").draft().isEmpty());assertTrue(backend.sender.entries().isEmpty());}
    @Test void changingMessageClearsPreviousAnswerWhileNewViewLoads(){var f=new ReviewPresenterTest();f.start("message");assertFalse(f.presenter.state().editor().body().isBlank());f.presenter.select("second");assertEquals("",f.presenter.state().editor().body());assertTrue(f.presenter.state().view().isEmpty());assertFalse(f.presenter.state().canApprove());f.queue.drain();assertEquals("",f.presenter.state().editor().body());}
}
