package fr.paybridge.mas.domain.context;

public record ContextExcerpt(String sourceId, long sourceVersion, String provenance, int paragraph,
                             String content, int relevance) {
    public ContextExcerpt {
        if (sourceId == null || sourceId.isBlank() || sourceVersion < 1 || provenance == null || provenance.isBlank()
                || paragraph < 0 || content == null || content.isBlank() || relevance < 1) {
            throw new IllegalArgumentException("Extrait de contexte invalide");
        }
    }
}
