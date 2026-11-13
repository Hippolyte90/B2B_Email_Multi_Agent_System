package fr.paybridge.mas;

import fr.paybridge.mas.adapters.outbound.persistence.SqliteStore;
import fr.paybridge.mas.application.port.PersistenceFailure;
import fr.paybridge.mas.domain.mail.MailboxFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class MailboxStorageSqliteIT {
    @TempDir Path directory;
    Path file(){return directory.resolve("mail.sqlite");}
    void sql(String command)throws Exception {try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement()){s.execute(command);}}
    @Test void unreadFlagsDefaultAndChangesSurviveRestart() {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);var initial=store.mailbox().find("message");assertEquals(0,initial.revision());store.mailbox().replace(initial,initial.change(MailboxFlags.Location.SPAM,true,true));}
        try(var store=new SqliteStore(file())){var flags=store.mailbox().find("message");assertEquals(1,flags.revision());assertEquals(MailboxFlags.Location.SPAM,flags.location());assertTrue(flags.read());assertTrue(flags.starred());assertEquals(1,store.summary().messages());}
    }
    @Test void twoConnectionsRejectStaleClassification() {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);}
        try(var a=new SqliteStore(file());var b=new SqliteStore(file())){
            var first=a.mailbox().find("message");var old=b.mailbox().find("message");a.mailbox().replace(first,first.change(MailboxFlags.Location.ARCHIVE,true,false));
            assertThrows(IllegalArgumentException.class,()->b.mailbox().replace(old,old.change(MailboxFlags.Location.TRASH,false,false)));assertEquals(MailboxFlags.Location.ARCHIVE,b.mailbox().find("message").location());
        }
    }
    @Test void rollbackIncludesClassificationAndAudit() {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);var before=store.summary();var flags=store.mailbox().find("message");
            assertThrows(IllegalStateException.class,()->store.inTransaction(()->{store.mailbox().replace(flags,flags.change(MailboxFlags.Location.TRASH,true,true));throw new IllegalStateException("failure");}));
            assertEquals(flags,store.mailbox().find("message"));assertEquals(before.auditEvents(),store.summary().auditEvents());
        }
    }
    @Test void absentMessagesCannotAcquireFlags() {try(var store=new SqliteStore(file())){assertThrows(IllegalArgumentException.class,()->store.mailbox().find("missing"));}}
    @Test void migrationFromFourPreservesDraftAndConfiguration()throws Exception {
        java.util.UUID draft;
        try(var store=new SqliteStore(file())){var f=new DurableTestFixture(store);draft=f.create().id();}
        sql("DROP TABLE retired_demo_content");sql("DROP TABLE mailbox_flags");sql("PRAGMA user_version=4");
        try(var store=new SqliteStore(file())){assertEquals(6,store.summary().schema());assertTrue(store.drafts().find(draft).isPresent());assertEquals(0,store.mailbox().find("message").revision());assertEquals(1,store.summary().messages());}
    }
    @Test void migrationFailureLeavesSchemaFourAndExistingData()throws Exception {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);}
        sql("PRAGMA user_version=4");assertThrows(PersistenceFailure.class,()->new SqliteStore(file()));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+file());var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){assertEquals(4,r.getInt(1));}
    }
    @Test void corruptPayloadOrRevisionIsRefusedWithoutLeakingText()throws Exception {
        try(var store=new SqliteStore(file())){new DurableTestFixture(store);var flags=store.mailbox().find("message");store.mailbox().replace(flags,flags.change(MailboxFlags.Location.ARCHIVE,false,false));}
        sql("UPDATE mailbox_flags SET revision=9");
        try(var store=new SqliteStore(file())){assertEquals(PersistenceFailure.Code.CORRUPT_DATA,assertThrows(PersistenceFailure.class,()->store.mailbox().find("message")).code());}
        sql("UPDATE mailbox_flags SET payload='PRIVATE_SYNTHETIC'");
        try(var store=new SqliteStore(file())){assertFalse(assertThrows(PersistenceFailure.class,()->store.mailbox().find("message")).toString().contains("PRIVATE_SYNTHETIC"));}
    }
}
