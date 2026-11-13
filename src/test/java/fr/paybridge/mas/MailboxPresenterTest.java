package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.presentation.*;
import fr.paybridge.mas.adapters.outbound.persistence.InMemoryMailboxFlagsRepository;
import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.workspace.MailboxWorkspace.*;
import fr.paybridge.mas.domain.mail.MailboxFlags;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MailboxPresenterTest {
    final DraftTestFixture f = new DraftTestFixture();
    final InMemoryMailboxFlagsRepository flags = new InMemoryMailboxFlagsRepository();
    final MailboxService service = new MailboxService(() -> List.of(f.conversations.findMessage("message").orElseThrow()),f.drafts,flags);
    final Deque<Runnable> pending = new ArrayDeque<>();
    final Executor worker = pending::add;
    final AtomicBoolean blocked = new AtomicBoolean();
    final AtomicBoolean fail = new AtomicBoolean();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicBoolean connected = new AtomicBoolean();
    final AtomicInteger synchronizations = new AtomicInteger(), exports = new AtomicInteger();
    final MailboxWorkspace backend = new MailboxWorkspace() {
        public List<Entry> entries() { reads.incrementAndGet();return service.entries(); }
        public boolean connected(){return connected.get();}
        public void synchronize(){synchronizations.incrementAndGet();}
        public void exportDraft(String key){assertTrue(key.startsWith("draft:"));exports.incrementAndGet();}
        public void update(MailboxFlags expected,MailboxFlags.Location location,boolean read,boolean starred) {
            if(fail.get()){throw new IllegalArgumentException("SECRET_SYNTHETIQUE");}service.update(expected,location,read,starred);
        }
    };
    final List<MailboxState> rendered = new ArrayList<>();
    final MailboxPresenter presenter = new MailboxPresenter(backend,worker,Runnable::run,blocked::get,rendered::add);
    void drain(){while(!pending.isEmpty()){pending.removeFirst().run();}}
    void start(){presenter.refresh();drain();}
    void select(){presenter.select("received:message");drain();}
    @Test void loadAndReadWritesRunOutsideUiCall() {
        presenter.refresh();assertEquals(0,reads.get());drain();presenter.select("received:message");assertFalse(flags.find("message").read());drain();assertTrue(flags.find("message").read());assertTrue(f.requests.isEmpty());
    }
    @Test void selectingAnEntryKeepsItSelectedAfterReadUpdate() {start();select();assertEquals("message",presenter.state().selected().orElseThrow().messageId());assertFalse(presenter.state().busy());}
    @Test void movingUpdatesFolderCountsAndRemovesOldSelection() {
        start();select();presenter.move(MailboxFlags.Location.SPAM);drain();assertTrue(presenter.state().selected().isEmpty());assertEquals(1,presenter.state().count(Folder.SPAM));assertEquals(0,presenter.state().count(Folder.INBOX));
        presenter.folder(Folder.SPAM);select();assertEquals(1,presenter.state().visible().size());
    }
    @Test void readAndStarAreIndependentAndPersistentInProjection() {
        start();select();presenter.toggleStar();drain();presenter.toggleRead();drain();assertTrue(flags.find("message").starred());assertFalse(flags.find("message").read());assertEquals(1,presenter.state().count(Folder.STARRED));
    }
    @Test void dirtyAssistantPreventsNavigationAndMutations() {
        start();select();blocked.set(true);var before=flags.find("message");presenter.folder(Folder.SPAM);presenter.move(MailboxFlags.Location.SPAM);drain();assertEquals(Folder.INBOX,presenter.state().folder());assertEquals(before,flags.find("message"));
    }
    @Test void failureRetainsSelectionAndHidesRawError() {
        start();select();fail.set(true);presenter.move(MailboxFlags.Location.TRASH);drain();assertTrue(presenter.state().selected().isPresent());assertEquals(MailboxFlags.Location.INBOX,flags.find("message").location());assertFalse(presenter.state().notice().contains("SECRET"));assertFalse(presenter.state().busy());
    }
    @Test void searchIsImmediateAndDoesNotReadStorageAgain() {
        start();int before=reads.get();presenter.search("absent");assertTrue(presenter.state().visible().isEmpty());presenter.search("SDK");assertEquals(1,presenter.state().visible().size());assertEquals(before,reads.get());
    }
    @Test void closingIgnoresLateResults() {presenter.refresh();int before=rendered.size();presenter.close();drain();assertEquals(before,rendered.size());}
    @Test void remoteRefreshRunsOnWorkerAndShowsConnectedMode(){connected.set(true);presenter.refresh();assertEquals(0,synchronizations.get());drain();assertEquals(1,synchronizations.get());assertTrue(presenter.state().notice().contains("Gmail actualisé"));}
    @Test void draftExportRequiresConnectedModeAndRunsOnWorker(){
        var draft=f.create();start();presenter.folder(Folder.DRAFTS);presenter.select("draft:"+draft.id());presenter.exportDraft();drain();assertEquals(0,exports.get());
        connected.set(true);presenter.exportDraft();assertEquals(0,exports.get());drain();assertEquals(1,exports.get());assertTrue(presenter.state().notice().contains("aucun envoi"));
    }
    @Test void dirtyAssistantBlocksRemoteDraftExport(){var draft=f.create();connected.set(true);start();presenter.folder(Folder.DRAFTS);presenter.select("draft:"+draft.id());blocked.set(true);presenter.exportDraft();drain();assertEquals(0,exports.get());}
}
