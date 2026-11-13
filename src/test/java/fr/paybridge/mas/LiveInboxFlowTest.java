package fr.paybridge.mas;

import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.generation.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveInboxFlowTest {
    final ReviewWorkspaceServiceTest backend=new ReviewWorkspaceServiceTest();
    final DraftTestFixture f=backend.f;
    final Deque<Runnable> worker=new ArrayDeque<>(),model=new ArrayDeque<>();
    final List<LiveInboxFlow.Event> events=new ArrayList<>();
    final class Timer implements LiveInboxFlow.Timer {
        final class Task {final Duration delay;final Runnable action;boolean cancelled;Task(Duration delay,Runnable action){this.delay=delay;this.action=action;}}
        final Deque<Task> tasks=new ArrayDeque<>();
        public Runnable schedule(Duration delay,Runnable action){var task=new Task(delay,action);tasks.addLast(task);return ()->task.cancelled=true;}
        void fire(){var task=tasks.removeFirst();if(!task.cancelled){task.action.run();}}
    }
    final Timer timer=new Timer();
    final List<String> imported=new ArrayList<>();
    LiveInboxFlowTest(){f.executor.set(model::addLast);}
    ImportEmailCommand command(String id){return new ImportEmailCommand(id,id+"-thread",f.alice,List.of(new EmailAddress("support@paybridge.example")),"SDK","Question technique",f.clock.instant().atOffset(ZoneOffset.UTC));}
    void receive(ImportEmailCommand command){new ImportEmailUseCase(f.conversations,sender->f.scope).execute(command);backend.workspace.authorizeImportedMessage(f.conversations.findMessage(command.id()).orElseThrow());imported.add(command.id());}
    LiveInboxFlow flow(String...ids){return new LiveInboxFlow(Arrays.stream(ids).<Supplier<ImportEmailCommand>>map(id->()->command(id)).toList(),this::receive,backend.workspace,timer,()->Duration.ofSeconds(60),worker::addLast,f.clock,events::add);}
    void drainWorker(){while(!worker.isEmpty()){worker.removeFirst().run();}}
    void arrive(){timer.fire();drainWorker();}
    void complete(){model.removeFirst().run();drainWorker();}
    @Test void firstArrivalIsDelayedAndNeverImportedOnStart(){var flow=flow("one");flow.start();assertTrue(imported.isEmpty());assertEquals(Duration.ofSeconds(60),timer.tasks.getFirst().delay);assertEquals(f.clock.instant().plusSeconds(60),events.getFirst().nextArrival().orElseThrow());}
    @Test void timerCallbackOnlyEnqueuesWork(){var flow=flow("one");flow.start();timer.fire();assertTrue(imported.isEmpty());drainWorker();assertEquals(List.of("one"),imported);}
    @Test void receivedMailAutomaticallyCreatesUnapprovedDraftWithoutSending(){var flow=flow("one");flow.start();arrive();assertTrue(f.drafts.findByMessage("one").isEmpty());complete();var draft=f.drafts.findByMessage("one").orElseThrow();assertTrue(draft.approval().isEmpty());assertTrue(backend.sender.entries().isEmpty());assertTrue(events.stream().anyMatch(e->e.status()==LiveInboxFlow.Status.READY));}
    @Test void laterEmailsArriveWhileGenerationIsRunningButDraftingStaysSequential(){var flow=flow("one","two");flow.start();arrive();arrive();assertEquals(2,imported.size());assertEquals(1,model.size());complete();assertEquals(1,model.size());complete();assertEquals(2,f.requests.size());assertEquals(2,events.getLast().drafted());assertEquals(LiveInboxFlow.Status.FINISHED,events.getLast().status());}
    @Test void ambiguousRoutingRequiresHumanChoiceRatherThanGuessing(){backend.ambiguous.set(true);var flow=flow("one");flow.start();arrive();assertTrue(model.isEmpty());assertEquals(1,events.getLast().choices());assertTrue(f.drafts.findByMessage("one").isEmpty());}
    @Test void failedGenerationDoesNotBlockTheFollowingEmail(){f.provider.set((request,cancellation)->{throw new GenerationException(GenerationFailure.UNAVAILABLE);});var flow=flow("one","two");flow.start();arrive();arrive();complete();f.provider.set((request,cancellation)->new GeneratedReply(request.taskId(),"Réponse suivante",request.settings().model(),true,Duration.ZERO,OptionalLong.empty(),OptionalLong.empty()));complete();assertTrue(f.drafts.findByMessage("two").isPresent());assertEquals(1,events.getLast().failed());assertEquals(1,events.getLast().drafted());}
    @Test void importFailureDoesNotStopScheduling(){var commands=List.<Supplier<ImportEmailCommand>>of(()->{throw new IllegalArgumentException("SECRET");},()->command("two"));var flow=new LiveInboxFlow(commands,this::receive,backend.workspace,timer,()->Duration.ofSeconds(60),worker::addLast,f.clock,events::add);flow.start();arrive();arrive();complete();assertEquals(List.of("two"),imported);assertEquals(1,events.getLast().failed());assertFalse(events.toString().contains("SECRET"));}
    @Test void closingBeforeTimerOrQueuedCallbackPreventsFurtherImports(){var flow=flow("one");flow.start();timer.fire();flow.close();drainWorker();assertTrue(imported.isEmpty());}
    @Test void closingCancelsInferenceAndIgnoresLateCompletion(){var flow=flow("one","two");flow.start();arrive();flow.close();int count=events.size();complete();assertEquals(count,events.size());assertTrue(f.drafts.findByMessage("one").isEmpty());assertTrue(timer.tasks.getFirst().cancelled);}
    @Test void startingTwiceDoesNotCreateTwoTimers(){var flow=flow("one");flow.start();flow.start();assertEquals(1,timer.tasks.size());}
    @Test void existingDraftIsKeptWithoutRegenerationOrApproval(){
        f.executor.set(Runnable::run);var original=f.create();f.executor.set(model::addLast);
        var flow=new LiveInboxFlow(List.of(()->command("message")),ignored->{},backend.workspace,timer,()->Duration.ofSeconds(60),worker::addLast,f.clock,events::add);
        flow.start();arrive();assertTrue(model.isEmpty());assertEquals(original,f.drafts.findByMessage("message").orElseThrow());assertTrue(original.approval().isEmpty());
    }
    @Test void configuredRandomDelaysAlwaysStayWithinOneToThreeMinutes(){var delays=LiveInboxFlow.randomDelays(new Random(41),false);var values=new HashSet<Long>();for(int i=0;i<2000;i++){long n=delays.get().toSeconds();assertTrue(n>=60&&n<=180);values.add(n);}assertTrue(values.size()>100);}
    @Test void acceleratedModeIsExplicitlySeparate(){var delays=LiveInboxFlow.randomDelays(new Random(41),true);for(int i=0;i<50;i++){assertTrue(Set.of(1L,2L,3L).contains(delays.get().toSeconds()));}}
    @Test void manualAndAutomaticGenerationShareTheSameRunningWork(){var first=backend.workspace.generate("message");var second=backend.workspace.generate("message");assertSame(first,second);assertEquals(1,model.size());complete();assertEquals(1,f.requests.size());assertTrue(backend.sender.entries().isEmpty());}
}
