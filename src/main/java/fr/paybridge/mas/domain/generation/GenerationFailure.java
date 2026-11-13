package fr.paybridge.mas.domain.generation;

/** Codes seuls : pas de corps d'email, réponse HTTP ou secret dans les erreurs. */
public enum GenerationFailure {
    UNAVAILABLE, TIMEOUT, CANCELLED, INVALID_RESPONSE, INCOMPLETE_RESPONSE, MODEL_NOT_LOCAL, CONTEXT_LIMIT,
    STALE_CONTEXT, CONTEXT_ERROR, INTERNAL_ERROR, STORAGE_ERROR, GENERATION_DISABLED, ENGINE_NOT_INSTALLED, ENGINE_START_FAILED
}
