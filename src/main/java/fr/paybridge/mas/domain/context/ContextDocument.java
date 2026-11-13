package fr.paybridge.mas.domain.context;

import java.util.Objects;

public record ContextDocument(ContextSource source, String content) {
    public ContextDocument {
        Objects.requireNonNull(source);
        Objects.requireNonNull(content);
    }
}
