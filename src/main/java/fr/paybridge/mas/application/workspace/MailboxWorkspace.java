package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.domain.mail.*;
import java.time.Instant;
import java.util.*;

/** Consultation sans routage ni génération ; les sorties conservent leur contenu exact. */
public interface MailboxWorkspace {
    enum Folder {
        INBOX("Réception"), DRAFTS("Brouillons"), SENT("Envoyés"), OUTBOX("À envoyer / échecs"),
        STARRED("Favoris"), ARCHIVE("Archives"), SPAM("Spam"), TRASH("Corbeille"), ALL("Tous les reçus"), SIMULATED("Envois simulés"), PENDING_VALIDATION("Validations en attente");
        private final String label;
        Folder(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    enum Kind { RECEIVED, DRAFT, SENT, OUTBOX, SIMULATED }
    record Entry(String key, String messageId, Kind kind, String subject, String body, String sender,
                 List<EmailAddress> recipients, Instant at, MailboxFlags flags, String status, boolean canAssist,boolean reviewRequired) {
        public Entry { recipients = List.copyOf(recipients); }
        public Entry(String key,String messageId,Kind kind,String subject,String body,String sender,List<EmailAddress> recipients,Instant at,MailboxFlags flags,String status,boolean canAssist){this(key,messageId,kind,subject,body,sender,recipients,at,flags,status,canAssist,false);}
        public Entry(String key,String messageId,Kind kind,String subject,String body,String sender,List<EmailAddress> recipients,Instant at,MailboxFlags flags,String status) {
            this(key,messageId,kind,subject,body,sender,recipients,at,flags,status,true,false);
        }
    }
    List<Entry> entries();
    void update(MailboxFlags expected, MailboxFlags.Location location, boolean read, boolean starred);
    default void synchronize() { }
    default boolean connected() { return false; }
    default void exportDraft(String key) { throw new UnsupportedOperationException(); }
}
