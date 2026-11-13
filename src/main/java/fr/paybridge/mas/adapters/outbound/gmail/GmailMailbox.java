package fr.paybridge.mas.adapters.outbound.gmail;

import fr.paybridge.mas.application.workspace.*;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.mail.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Consumer;

/** Réconciliation manuelle Gmail ; les emails importés conservent un périmètre résolu par annuaire approuvé. */
public final class GmailMailbox implements MailboxWorkspace {
    private final GmailClient client;
    private final EmailAddress account;
    private final String namespace;
    private final ImportEmailUseCase importer;
    private final ConversationRepository conversations;
    private final DraftRepository drafts;
    private final MailboxFlagsRepository flags;
    private final MailboxWorkspace local;
    private final Consumer<EmailMessage> authorize;
    private List<GmailClient.Message> remote=List.of();
    public GmailMailbox(GmailClient client,EmailAddress account,ImportEmailUseCase importer,ConversationRepository conversations,DraftRepository drafts,
                        MailboxFlagsRepository flags,MailboxWorkspace local,Consumer<EmailMessage> authorize) {
        this.client=client;this.account=account;this.importer=importer;this.conversations=conversations;this.drafts=drafts;this.flags=flags;this.local=local;this.authorize=authorize;
        namespace=namespace(account);
    }
    public static String namespace(EmailAddress account){
        try{return "gmail-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(account.value().getBytes(StandardCharsets.UTF_8)))+"-";}
        catch(Exception failure){throw new IllegalStateException("Empreinte de compte indisponible");}
    }
    public String localId(String remoteId){return namespace+GmailClient.safe(remoteId);}
    public GmailClient.Message source(String localId){
        if(!localId.startsWith(namespace)){throw new IllegalArgumentException("Autre compte Gmail");}
        return client.message(GmailClient.safe(localId.substring(namespace.length())));
    }
    private static boolean incoming(GmailClient.Message message){return !message.labels().contains("DRAFT")&&!message.labels().contains("SENT");}
    private static MailboxFlags.Location location(Set<String> labels){return labels.contains("TRASH")?MailboxFlags.Location.TRASH:labels.contains("SPAM")?MailboxFlags.Location.SPAM:labels.contains("INBOX")?MailboxFlags.Location.INBOX:MailboxFlags.Location.ARCHIVE;}
    @Override public synchronized void synchronize(){
        var loaded=new LinkedHashMap<String,GmailClient.Message>();
        for(var message:client.messages(200)){loaded.put(message.id(),message);}
        for(var draft:client.drafts()){loaded.put(draft.id(),draft);}
        for(var message:loaded.values()){
            if(!incoming(message)){continue;}
            String id=localId(message.id());
            importer.execute(new ImportEmailCommand(id,namespace+"thread-"+message.threadId(),message.sender(),message.recipients().isEmpty()?List.of(account):message.recipients(),message.subject(),message.body(),message.at().atOffset(ZoneOffset.UTC)));
            var imported=conversations.findMessage(id).orElseThrow();authorize.accept(imported);
            var before=flags.find(id);flags.replace(before,before.change(location(message.labels()),!message.labels().contains("UNREAD"),message.labels().contains("STARRED")));
        }
        remote=List.copyOf(loaded.values());
    }
    @Override public synchronized List<Entry> entries(){
        var result=new ArrayList<Entry>();var localEntries=local.entries();
        var localDraftIds=localEntries.stream().filter(e->e.kind()==Kind.DRAFT).map(e->e.key().substring("draft:".length())).collect(java.util.stream.Collectors.toSet());
        for(var message:remote){
            var id=localId(message.id());boolean received=incoming(message);
            var state=received?flags.find(id):new MailboxFlags(id,1,location(message.labels()),!message.labels().contains("UNREAD"),message.labels().contains("STARRED"));
            var kind=message.labels().contains("DRAFT")?Kind.DRAFT:message.labels().contains("SENT")?Kind.SENT:Kind.RECEIVED;
            if(kind==Kind.DRAFT&&localDraftIds.contains(message.localDraftId())){continue;}
            if(state.location()==MailboxFlags.Location.TRASH||state.location()==MailboxFlags.Location.SPAM){kind=Kind.RECEIVED;}
            result.add(new Entry(received?"received:"+id:"gmail:"+message.id(),id,kind,message.subject(),message.body(),message.sender().value(),message.recipients(),message.at(),state,"Gmail",received));
        }
        // Les reçus affichés viennent de la fenêtre distante, pas d'une ancienne importation disparue.
        result.addAll(localEntries.stream().filter(e->e.kind()!=Kind.RECEIVED&&e.kind()!=Kind.SENT).toList());
        return result.stream().sorted(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::key)).toList();
    }
    @Override public synchronized void update(MailboxFlags expected,MailboxFlags.Location location,boolean read,boolean starred){
        var source=remote.stream().filter(m->incoming(m)&&localId(m.id()).equals(expected.messageId())).findFirst().orElseThrow(()->new IllegalArgumentException("Message reçu absent de la fenêtre Gmail"));
        if(!flags.find(expected.messageId()).equals(expected)){throw new IllegalArgumentException("Classement obsolète");}
        client.flags(source.id(),location==MailboxFlags.Location.INBOX,location==MailboxFlags.Location.SPAM,location==MailboxFlags.Location.TRASH,read,starred);
        var confirmed=client.message(source.id());
        var current=flags.find(expected.messageId());flags.replace(current,current.change(location(confirmed.labels()),!confirmed.labels().contains("UNREAD"),confirmed.labels().contains("STARRED")));
        remote=remote.stream().map(m->m.id().equals(confirmed.id())?confirmed:m).toList();
    }
    @Override public boolean connected(){return true;}
    @Override public synchronized void exportDraft(String key){
        if(!key.startsWith("draft:")){throw new IllegalArgumentException("Brouillon local requis");}
        var draft=drafts.find(UUID.fromString(key.substring(6))).orElseThrow();
        if(local.entries().stream().noneMatch(e->e.kind()==Kind.DRAFT&&e.key().equals(key))){throw new IllegalArgumentException("Brouillon non disponible");}
        var incoming=source(draft.messageId());
        var existing=client.drafts().stream().filter(d->draft.id().toString().equals(d.localDraftId())).toList();
        if(existing.size()>1){throw new IllegalArgumentException("Plusieurs brouillons distants ; vérification manuelle requise");}
        if(!existing.isEmpty()){
            var before=remote.stream().filter(d->d.remoteDraftId().equals(existing.getFirst().remoteDraftId())).findFirst();
            if(before.isEmpty()||!before.get().equals(existing.getFirst())){throw new IllegalArgumentException("Brouillon Gmail modifié ; actualisez et vérifiez-le avant de remplacer");}
        }
        client.saveDraft(existing.isEmpty()?"":existing.getFirst().remoteDraftId(),incoming.threadId(),account,draft.current().content(),incoming.rfcId(),draft.id().toString());
        synchronize();
    }
}
