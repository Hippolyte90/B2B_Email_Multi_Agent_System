package fr.paybridge.mas.adapters.outbound.gmail;

import fr.paybridge.mas.application.port.EmailSender;
import fr.paybridge.mas.domain.delivery.*;
import java.util.*;
import java.util.function.Function;

/** Une seule requête d'envoi. Le contenu MIME est reconstruit depuis l'approbation exacte. */
public final class GmailEmailSender implements EmailSender {
    private final GmailClient client;
    private final Function<String,GmailClient.Message> source;
    public GmailEmailSender(GmailClient client,Function<String,GmailClient.Message> source) { this.client=client;this.source=source; }
    public TransportMode mode(){return TransportMode.REAL;}
    public DeliveryResult send(ApprovedEmail email) {
        GmailClient.Message incoming; List<GmailClient.Message> drafts;
        try {incoming=source.apply(email.messageId());drafts=client.drafts();}
        catch(Exception failure){return new DeliveryResult(DeliveryStatus.FAILED,Optional.empty(),"GMAIL_PREPARATION_FAILED");}
        if(incoming.labels().contains("SPAM")||incoming.labels().contains("TRASH")){return new DeliveryResult(DeliveryStatus.FAILED,Optional.empty(),"GMAIL_MESSAGE_QUARANTINED");}
        var owned=drafts.stream().filter(d->email.draftId().toString().equals(d.localDraftId())).toList();
        if(owned.size()>1){return new DeliveryResult(DeliveryStatus.FAILED,Optional.empty(),"GMAIL_DRAFT_CONFLICT");}
        try {
            var id=owned.isEmpty()?client.send(incoming.threadId(),email.sender(),email.approval().content(),incoming.rfcId())
                    :client.sendDraft(owned.getFirst().remoteDraftId(),incoming.threadId(),email.sender(),email.approval().content(),incoming.rfcId());
            return new DeliveryResult(DeliveryStatus.CONFIRMED,Optional.of(id),"GMAIL_ACCEPTED");
        }catch(GmailFailure failure){
            // 4xx de rejet certain ; timeout/5xx/réponse malformée après POST restent incertains.
            boolean rejected=Set.of(400,401,403,404,413,429).contains(failure.status());
            return new DeliveryResult(rejected?DeliveryStatus.FAILED:DeliveryStatus.UNKNOWN,Optional.empty(),rejected?"GMAIL_REJECTED":"GMAIL_UNCERTAIN");
        }catch(Exception failure){return new DeliveryResult(DeliveryStatus.UNKNOWN,Optional.empty(),"GMAIL_UNCERTAIN");}
    }
}
