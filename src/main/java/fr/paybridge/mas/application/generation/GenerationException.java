package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.domain.generation.GenerationFailure;

public final class GenerationException extends Exception {
    private final GenerationFailure code;
    public GenerationException(GenerationFailure code) { super(code.name()); this.code = code; }
    public GenerationFailure code() { return code; }
}
