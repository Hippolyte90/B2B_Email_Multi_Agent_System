package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.application.usecase.RecoverInterruptedWorkUseCase;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;

/** Cycle de vie exclusif du parcours durable ; les dépôts seuls ne déclenchent aucune récupération. */
public final class SqliteSession implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final SqliteStore store;
    private final RecoverInterruptedWorkUseCase.Summary recovered;
    public SqliteSession(Path file, Clock clock) {
        FileChannel opened = null; FileLock acquired = null; SqliteStore database = null;
        try {
            var absolute = file.toAbsolutePath().normalize(); Files.createDirectories(absolute.getParent());
            var canonical = Files.exists(absolute) ? absolute.toRealPath() : absolute.getParent().toRealPath().resolve(absolute.getFileName());
            opened = FileChannel.open(canonical.resolveSibling(canonical.getFileName() + ".session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { acquired = opened.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new PersistenceFailure(PersistenceFailure.Code.ALREADY_OPEN); }
            if (acquired == null) { throw new PersistenceFailure(PersistenceFailure.Code.ALREADY_OPEN); }
            database = new SqliteStore(canonical);
            recovered = new RecoverInterruptedWorkUseCase(database.drafts(), database.conversations(), database.journal(), database, clock).execute();
            channel = opened; lock = acquired; store = database;
        } catch (Exception failure) {
            if (database != null) { try { database.close(); } catch (RuntimeException ignored) { } }
            if (acquired != null) { try { acquired.release(); } catch (Exception ignored) { } }
            if (opened != null) { try { opened.close(); } catch (Exception ignored) { } }
            if (failure instanceof PersistenceFailure known) { throw known; }
            throw new PersistenceFailure(PersistenceFailure.Code.OPEN_FAILED);
        }
    }
    public SqliteStore store() { return store; }
    public RecoverInterruptedWorkUseCase.Summary recovered() { return recovered; }
    @Override public void close() {
        try { store.close(); }
        finally { try { lock.release(); channel.close(); } catch (Exception ignored) { } }
    }
}
