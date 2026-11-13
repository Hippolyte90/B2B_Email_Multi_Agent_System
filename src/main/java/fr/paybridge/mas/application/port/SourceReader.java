package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.context.ContextSource;

import java.io.IOException;

public interface SourceReader {
    String read(ContextSource source) throws IOException;
}
