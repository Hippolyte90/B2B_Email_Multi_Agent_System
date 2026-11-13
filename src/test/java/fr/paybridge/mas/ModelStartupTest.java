package fr.paybridge.mas;

import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.application.port.LocalModelLoader;
import fr.paybridge.mas.domain.generation.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelStartupTest {
    final GenerationSettings settings=new GenerationSettings("test",8192,1024,.2,42,Duration.ofSeconds(5));
    final Queue<Runnable> work=new ArrayDeque<>();
    final List<ModelStartup.Event> events=new ArrayList<>();
    final AtomicInteger starts=new AtomicInteger();
    ModelStartup subject(LocalModelLoader loader){return new ModelStartup(loader,settings,work::add,starts::incrementAndGet,events::add);}
    void run(){work.remove().run();}
    ModelStartup.Status status(){return events.getLast().status();}
    @Test void noFlowStartsBeforeLoadingHasSucceeded(){
        var calls=new AtomicInteger();var startup=subject((s,c)->{assertEquals(settings,s);calls.incrementAndGet();assertEquals(0,starts.get());});
        startup.start();assertEquals(ModelStartup.Status.LOADING,status());assertEquals(0,starts.get());assertEquals(0,calls.get());
        run();assertEquals(ModelStartup.Status.READY,status());assertEquals(1,starts.get());startup.start();assertTrue(work.isEmpty());
    }
    @Test void duplicateClicksDoNotCreateConcurrentLoads(){var startup=subject((s,c)->{});startup.start();startup.start();assertEquals(1,work.size());run();assertEquals(1,starts.get());}
    @Test void failureLeavesFlowUnstartedUntilExplicitRetry(){
        var calls=new AtomicInteger();var startup=subject((s,c)->{if(calls.incrementAndGet()==1)throw new GenerationException(GenerationFailure.UNAVAILABLE);});
        startup.start();run();assertEquals(ModelStartup.Status.FAILED,status());assertEquals(GenerationFailure.UNAVAILABLE,events.getLast().failure().orElseThrow());
        assertEquals(0,starts.get());assertTrue(work.isEmpty());startup.start();run();assertEquals(1,starts.get());
    }
    @Test void closeBeforeWorkerRunsPreventsLateStart(){var startup=subject((s,c)->c.check());startup.start();startup.close();run();assertEquals(ModelStartup.Status.STOPPED,status());assertEquals(0,starts.get());startup.start();assertTrue(work.isEmpty());}
    @Test void closeDuringLoadingCancelsRequestAndIgnoresLateSuccess(){
        var startup=new AtomicReference<ModelStartup>();var cancelled=new AtomicBoolean();
        startup.set(subject((s,c)->{try(var ignored=c.onCancel(()->cancelled.set(true))){startup.get().close();}catch(Exception e){throw new IllegalStateException(e);}}));
        startup.get().start();run();assertTrue(cancelled.get());assertEquals(0,starts.get());assertEquals(ModelStartup.Status.STOPPED,status());
    }
    @Test void loaderRuntimeFailureIsSanitized(){var startup=subject((s,c)->{throw new IllegalStateException("SECRET");});startup.start();run();assertEquals(GenerationFailure.INTERNAL_ERROR,events.getLast().failure().orElseThrow());assertEquals(0,starts.get());}
    @Test void executorRejectionLeavesRetryableFailure(){var startup=new ModelStartup((s,c)->{},settings,r->{throw new RejectedExecutionException();},starts::incrementAndGet,events::add);startup.start();assertEquals(ModelStartup.Status.FAILED,status());assertEquals(0,starts.get());}
    @Test void closeIsIdempotent(){var startup=subject((s,c)->{});startup.close();startup.close();assertEquals(1,events.size());assertEquals(0,starts.get());}
    @Test void firstArrivalTimerIsScheduledOnlyAfterPreparation(){
        var flowFixture=new LiveInboxFlowTest();var flow=flowFixture.flow("one");
        var startup=new ModelStartup((s,c)->{},settings,work::add,flow::start,events::add);
        startup.start();assertTrue(flowFixture.timer.tasks.isEmpty());assertTrue(flowFixture.imported.isEmpty());
        run();assertEquals(1,flowFixture.timer.tasks.size());assertEquals(Duration.ofMinutes(1),flowFixture.timer.tasks.getFirst().delay);
        assertTrue(flowFixture.imported.isEmpty());flowFixture.arrive();assertEquals(List.of("one"),flowFixture.imported);
        flowFixture.complete();assertTrue(flowFixture.backend.sender.entries().isEmpty());flow.close();startup.close();
    }
    @Test void failedPreparationNeverSchedulesOrImportsEmails(){
        var flowFixture=new LiveInboxFlowTest();var flow=flowFixture.flow("one");
        var startup=new ModelStartup((s,c)->{throw new GenerationException(GenerationFailure.TIMEOUT);},settings,work::add,flow::start,events::add);
        startup.start();run();assertTrue(flowFixture.timer.tasks.isEmpty());assertTrue(flowFixture.imported.isEmpty());
        assertTrue(flowFixture.model.isEmpty());assertTrue(flowFixture.backend.sender.entries().isEmpty());startup.close();flow.close();
    }

}
