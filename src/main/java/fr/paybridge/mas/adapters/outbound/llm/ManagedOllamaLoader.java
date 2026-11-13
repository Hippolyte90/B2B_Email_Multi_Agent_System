package fr.paybridge.mas.adapters.outbound.llm;

import fr.paybridge.mas.application.port.LocalModelLoader;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.domain.generation.*;
import java.nio.file.Path;
import java.time.Duration;

/** Démarre le service installé si nécessaire, puis précharge le modèle sans email. */
public final class ManagedOllamaLoader implements LocalModelLoader {
    public interface Runtime {
        boolean ready();
        void start() throws GenerationException;
        boolean alive();
        void stop();
        void pause() throws InterruptedException;
        long nanoTime();
    }
    private final Runtime runtime;
    private final LocalModelLoader model;
    public ManagedOllamaLoader(Path projectRoot,LoopbackEndpoint endpoint,LocalModelLoader model){this(new OllamaProcessRuntime(projectRoot,endpoint),model);}
    public ManagedOllamaLoader(Runtime runtime,LocalModelLoader model){this.runtime=java.util.Objects.requireNonNull(runtime);this.model=java.util.Objects.requireNonNull(model);}
    @Override public synchronized void prepare(GenerationSettings settings,CancellationSignal cancellation)throws GenerationException{
        cancellation.check();
        if(!runtime.ready()){
            cancellation.check();runtime.start();
            long deadline=runtime.nanoTime()+Duration.ofSeconds(30).toNanos();
            boolean ready=false;
            try{
                while(!(ready=runtime.ready())){
                    cancellation.check();
                    if(!runtime.alive()){throw new GenerationException(GenerationFailure.ENGINE_START_FAILED);}
                    if(runtime.nanoTime()>=deadline){throw new GenerationException(GenerationFailure.TIMEOUT);}
                    runtime.pause();
                }
                cancellation.check();
            }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new GenerationException(GenerationFailure.CANCELLED);}
            finally{if(!ready||cancellation.cancelled()){runtime.stop();}}
        }
        cancellation.check();model.prepare(settings,cancellation);
    }
}
