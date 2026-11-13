package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.domain.generation.GenerationFailure;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationSignal {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean cancelled() { return cancelled.get(); }

    public void check() throws GenerationException {
        if (cancelled()) { throw new GenerationException(GenerationFailure.CANCELLED); }
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) { callbacks.forEach(Runnable::run); }
    }

    public AutoCloseable onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled()) { callback.run(); }
        return () -> callbacks.remove(callback);
    }
}
