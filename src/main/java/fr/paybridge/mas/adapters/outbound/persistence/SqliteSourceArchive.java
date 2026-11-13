package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.SourceArchive;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.domain.context.ContextSource;
import java.util.*;

final class SqliteSourceArchive implements SourceArchive {
    private final SqliteStore store;
    SqliteSourceArchive(SqliteStore store) { this.store = store; }

    @Override public void rememberMetadata(Collection<ContextSource> sources) {
        store.inTransaction(() -> {
            for (var source : sources) {
                var existing = metadata(source.id(), source.version());
                if (existing.isPresent()) {
                    if (!existing.get().equals(source)) { throw new PersistenceFailure(PersistenceFailure.Code.VERSION_CONFLICT); }
                } else {
                    store.write("INSERT INTO source_metadata(id,version,payload) VALUES(?,?,?)", source.id(), source.version(), store.encode(source));
                    store.audit("SOURCE_METADATA_SAVED", source.id(), source.version());
                }
            }
            return null;
        });
    }

    @Override public void rememberText(ContextSource source, String text) {
        Objects.requireNonNull(text);
        store.inTransaction(() -> {
            rememberMetadata(List.of(source));
            var existing = text(source.id(), source.version());
            if (existing.isPresent()) {
                if (!existing.get().equals(text)) { throw new PersistenceFailure(PersistenceFailure.Code.VERSION_CONFLICT); }
            } else {
                store.write("INSERT INTO source_text(id,version,body) VALUES(?,?,?)", source.id(), source.version(), text);
                store.audit("SOURCE_TEXT_SAVED", source.id(), source.version());
            }
            return null;
        });
    }

    @Override public Optional<ContextSource> metadata(String id, long version) {
        return store.inTransaction(() -> store.payload("SELECT payload FROM source_metadata WHERE id=? AND version=?", id, version)
                .map(payload -> store.decode(payload, ContextSource.class)));
    }

    @Override public Optional<String> text(String id, long version) {
        return store.inTransaction(() -> store.payload("SELECT body FROM source_text WHERE id=? AND version=?", id, version));
    }
}
