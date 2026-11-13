package fr.paybridge.mas.application.port;

import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.domain.generation.GenerationSettings;

/** Préparation locale sans donnée métier ni brouillon. */
@FunctionalInterface
public interface LocalModelLoader {
    void prepare(GenerationSettings settings, CancellationSignal cancellation) throws GenerationException;
}
