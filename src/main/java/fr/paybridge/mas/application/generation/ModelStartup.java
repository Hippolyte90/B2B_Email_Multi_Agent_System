package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.application.port.LocalModelLoader;
import fr.paybridge.mas.domain.generation.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Barrière de démarrage : aucun flux autorisé avant confirmation du chargement local. */
public final class ModelStartup implements AutoCloseable {
    public enum Status { IDLE, LOADING, READY, FAILED, STOPPED }
    public record Event(Status status, Optional<GenerationFailure> failure) { }
    private final LocalModelLoader loader;
    private final GenerationSettings settings;
    private final Executor worker;
    private final Runnable ready;
    private final Consumer<Event> events;
    private Status status=Status.IDLE;
    private CancellationSignal active;
    public ModelStartup(LocalModelLoader loader,GenerationSettings settings,Executor worker,Runnable ready,Consumer<Event> events){
        this.loader=Objects.requireNonNull(loader);this.settings=Objects.requireNonNull(settings);this.worker=Objects.requireNonNull(worker);
        this.ready=Objects.requireNonNull(ready);this.events=Objects.requireNonNull(events);
    }
    public synchronized void start(){
        if(status!=Status.IDLE&&status!=Status.FAILED){return;}
        active=new CancellationSignal();var signal=active;publish(Status.LOADING,Optional.empty());
        try{worker.execute(()->prepare(signal));}catch(RuntimeException failure){finish(signal,GenerationFailure.INTERNAL_ERROR);}
    }
    private void prepare(CancellationSignal signal){
        GenerationFailure failure=null;
        try{loader.prepare(settings,signal);signal.check();}
        catch(GenerationException error){failure=error.code();}
        catch(RuntimeException error){failure=GenerationFailure.INTERNAL_ERROR;}
        finish(signal,failure);
    }
    private synchronized void finish(CancellationSignal signal,GenerationFailure failure){
        if(status!=Status.LOADING||active!=signal){return;}
        active=null;
        if(failure!=null){publish(Status.FAILED,Optional.of(failure));return;}
        publish(Status.READY,Optional.empty());ready.run();
    }
    private void publish(Status value,Optional<GenerationFailure> failure){status=value;events.accept(new Event(value,failure));}
    @Override public synchronized void close(){
        if(status==Status.STOPPED){return;}if(active!=null){active.cancel();active=null;}publish(Status.STOPPED,Optional.empty());
    }
}
