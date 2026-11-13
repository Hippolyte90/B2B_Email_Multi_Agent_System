package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import fr.paybridge.mas.domain.generation.GenerationFailure;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;
import java.util.random.RandomGenerator;

/** Arrivées temporisées et file de rédaction ; ce composant n'a aucune capacité d'envoi. */
public final class LiveInboxFlow implements AutoCloseable {
    @FunctionalInterface public interface Timer { Runnable schedule(Duration delay,Runnable action); }
    public enum Status { SCHEDULED, RECEIVED, GENERATING, READY, NEEDS_CHOICE, FAILED, FINISHED, STOPPED }
    public record Event(Status status,String messageId,int received,int total,int drafted,int failed,int choices,Optional<Instant> nextArrival,Optional<GenerationFailure> failure) { }
    private final List<Supplier<ImportEmailCommand>> arrivals;
    private final Consumer<ImportEmailCommand> receive;
    private final ReviewWorkspace workspace;
    private final Timer timer;
    private final Supplier<Duration> delays;
    private final Executor worker;
    private final Consumer<Event> events;
    private final Clock clock;
    private final Deque<String> queue=new ArrayDeque<>();
    private int received,drafted,failed,choices;
    private boolean finished;
    private boolean started,closed;
    private Runnable cancelTimer=()->{};
    private ReviewWorkspace.Work active;
    private String activeId;
    private Optional<Instant> next=Optional.empty();
    public LiveInboxFlow(List<Supplier<ImportEmailCommand>> arrivals,Consumer<ImportEmailCommand> receive,ReviewWorkspace workspace,
                         Timer timer,Supplier<Duration> delays,Executor worker,Clock clock,Consumer<Event> events) {
        this.arrivals=List.copyOf(arrivals);this.receive=Objects.requireNonNull(receive);this.workspace=Objects.requireNonNull(workspace);
        this.timer=Objects.requireNonNull(timer);this.delays=Objects.requireNonNull(delays);this.worker=Objects.requireNonNull(worker);this.clock=Objects.requireNonNull(clock);this.events=Objects.requireNonNull(events);
    }
    public static Supplier<Duration> randomDelays(RandomGenerator random,boolean accelerated){
        return ()->Duration.ofSeconds(accelerated?random.nextLong(1,4):random.nextLong(60,181));
    }
    public synchronized void start(){if(started||closed){return;}started=true;schedule();}
    private void emit(Status status,String id){emit(status,id,Optional.empty());}
    private void emitFailure(String id,Throwable error){
        while(error instanceof java.util.concurrent.CompletionException && error.getCause()!=null){error=error.getCause();}
        var code=error instanceof WorkspaceGenerationFailure failure?failure.code():GenerationFailure.INTERNAL_ERROR;
        emit(Status.FAILED,id,Optional.of(code));
    }
    private void emit(Status status,String id,Optional<GenerationFailure> failure){
        if(status==Status.FINISHED){if(finished){return;}finished=true;}
        if(status==Status.READY){drafted++;}if(status==Status.FAILED){failed++;}if(status==Status.NEEDS_CHOICE){choices++;}
        events.accept(new Event(status,id,received,arrivals.size(),drafted,failed,choices,next,failure));
    }
    private void schedule(){
        if(closed){return;}
        if(received>=arrivals.size()){next=Optional.empty();if(active==null&&queue.isEmpty()){emit(Status.FINISHED,"");}return;}
        var delay=Objects.requireNonNull(delays.get());if(delay.isNegative()||delay.isZero()){throw new IllegalArgumentException("Délai positif requis");}
        next=Optional.of(clock.instant().plus(delay));
        cancelTimer=timer.schedule(delay,()->worker.execute(this::arrive));emit(Status.SCHEDULED,"");
    }
    private synchronized void arrive(){
        if(closed){return;}
        var supplier=arrivals.get(received++);String id="";
        try{var command=supplier.get();id=command.id();receive.accept(command);queue.addLast(id);emit(Status.RECEIVED,id);}
        catch(Exception failure){emitFailure(id,failure);}
        schedule();drain();
    }
    private void drain(){
        if(closed||active!=null){return;}
        while(!queue.isEmpty()){
            var id=queue.removeFirst();
            try{
                var view=workspace.open(id);
                if(view.draft().isPresent()){emit(Status.READY,id);continue;}
                if(view.routing().effectiveAgentId().isEmpty()){emit(Status.NEEDS_CHOICE,id);continue;}
                active=workspace.generate(id);activeId=id;var work=active;emit(Status.GENERATING,id);
                work.result().whenComplete((draft,failure)->worker.execute(()->complete(work,id,failure)));return;
            }catch(Exception failure){active=null;activeId=null;emitFailure(id,failure);}
        }
        if(received>=arrivals.size()){emit(Status.FINISHED,"");}
    }
    private synchronized void complete(ReviewWorkspace.Work work,String id,Throwable failure){
        if(closed||active!=work){return;}
        active=null;activeId=null;if(failure==null){emit(Status.READY,id);}else{emitFailure(id,failure);}drain();
    }
    @Override public synchronized void close(){
        if(closed){return;}closed=true;cancelTimer.run();queue.clear();next=Optional.empty();
        if(active!=null){active.cancel().run();active=null;}emit(Status.STOPPED,activeId==null?"":activeId);
    }
}
