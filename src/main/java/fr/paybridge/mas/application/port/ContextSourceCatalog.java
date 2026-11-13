package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.context.ContextSource;

import java.io.IOException;
import java.util.List;

/** Lit les métadonnées approuvées uniquement, pas les textes documentaires. */
public interface ContextSourceCatalog {
    List<ContextSource> sources() throws IOException;
}
