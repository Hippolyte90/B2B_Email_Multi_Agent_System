package fr.paybridge.mas.domain.draft;

import fr.paybridge.mas.domain.generation.GeneratedReply;
import fr.paybridge.mas.domain.generation.GenerationReceipt;

import java.time.Instant;
import java.util.Objects;

public record DraftVersion(int number, DraftContent content, Origin origin, Instant createdAt,
                            GeneratedReply generation, GenerationReceipt receipt) {
    public enum Origin { GENERATED, EDITED }

    public DraftVersion {
        if (number < 1) { throw new IllegalArgumentException("Version positive requise"); }
        Objects.requireNonNull(content);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(receipt);
        if (origin == Origin.GENERATED && (!content.body().equals(generation.text())
                || !java.util.Set.copyOf(content.recipients()).equals(receipt.recipients()))) {
            throw new IllegalArgumentException("La version générée doit correspondre à la proposition et à ses destinataires");
        }
    }
}
