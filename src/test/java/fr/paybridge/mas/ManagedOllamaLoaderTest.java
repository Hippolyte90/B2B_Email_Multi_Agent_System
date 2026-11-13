package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.llm.ManagedOllamaLoader;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.domain.generation.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ManagedOllamaLoaderTest {
    final GenerationSettings settings=new GenerationSettings("test",8192,1024,.2,42,Duration.ofSeconds(5));
    final CancellationSignal signal=new CancellationSignal();final AtomicInteger loads=new AtomicInteger();
    final class Runtime implements ManagedOllamaLoader.Runtime {
        boolean ready,alive=true;int starts,stops,pauses;long time;Runnable onPause=()->ready=true;GenerationFailure startFailure;
        public boolean ready(){return ready;}
        public void start()throws GenerationException{starts++;if(startFailure!=null)throw new GenerationException(startFailure);}
        public boolean alive(){return alive;}
        public void stop(){stops++;alive=false;}
        public void pause(){pauses++;time+=Duration.ofSeconds(10).toNanos();onPause.run();}
        public long nanoTime(){return time;}
    }
    final Runtime runtime=new Runtime();
    ManagedOllamaLoader loader(){return new ManagedOllamaLoader(runtime,(s,c)->{assertTrue(runtime.ready);loads.incrementAndGet();});}
    @Test void reusesExistingServerWithoutStartingOrStoppingIt()throws Exception{runtime.ready=true;loader().prepare(settings,signal);assertEquals(0,runtime.starts);assertEquals(0,runtime.stops);assertEquals(1,loads.get());}
    @Test void startsThenWaitsForServerBeforeLoadingModel()throws Exception{loader().prepare(settings,signal);assertEquals(1,runtime.starts);assertEquals(1,runtime.pauses);assertEquals(1,loads.get());assertEquals(0,runtime.stops);}
    @Test void missingExecutableDoesNotLoadModel(){runtime.startFailure=GenerationFailure.ENGINE_NOT_INSTALLED;assertEquals(runtime.startFailure,assertThrows(GenerationException.class,()->loader().prepare(settings,signal)).code());assertEquals(0,loads.get());}
    @Test void crashedServerDoesNotLoadModel(){runtime.alive=false;assertEquals(GenerationFailure.ENGINE_START_FAILED,assertThrows(GenerationException.class,()->loader().prepare(settings,signal)).code());assertEquals(0,loads.get());assertEquals(1,runtime.stops);}
    @Test void startupTimeoutStopsOnlyOwnedPendingProcess(){runtime.onPause=()->{};assertEquals(GenerationFailure.TIMEOUT,assertThrows(GenerationException.class,()->loader().prepare(settings,signal)).code());assertEquals(3,runtime.pauses);assertEquals(1,runtime.stops);assertEquals(0,loads.get());}
    @Test void cancelledBeforeStartDoesNotSpawn(){signal.cancel();assertEquals(GenerationFailure.CANCELLED,assertThrows(GenerationException.class,()->loader().prepare(settings,signal)).code());assertEquals(0,runtime.starts);}
    @Test void cancelledWhileStartingDoesNotPreload(){runtime.onPause=signal::cancel;assertEquals(GenerationFailure.CANCELLED,assertThrows(GenerationException.class,()->loader().prepare(settings,signal)).code());assertEquals(1,runtime.stops);assertEquals(0,loads.get());}
    @Test void modelFailureKeepsReadyServerAvailableForRetry(){var loader=new ManagedOllamaLoader(runtime,(s,c)->{throw new GenerationException(GenerationFailure.UNAVAILABLE);});assertEquals(GenerationFailure.UNAVAILABLE,assertThrows(GenerationException.class,()->loader.prepare(settings,signal)).code());assertTrue(runtime.ready);assertEquals(0,runtime.stops);}
    @Test void secondPreparationReusesServer()throws Exception{var loader=loader();loader.prepare(settings,signal);loader.prepare(settings,signal);assertEquals(1,runtime.starts);assertEquals(2,loads.get());}
}
