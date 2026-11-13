package fr.paybridge.mas.application.port;

import java.util.function.Supplier;

/** Frontière atomique locale ; ne jamais englober un appel au modèle ou au transport. */
public interface TransactionRunner {
    <T> T inTransaction(Supplier<T> operation);

    static TransactionRunner direct() {
        return new TransactionRunner() {
            @Override public <T> T inTransaction(Supplier<T> operation) { return operation.get(); }
        };
    }
}
