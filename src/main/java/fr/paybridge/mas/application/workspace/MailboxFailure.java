package fr.paybridge.mas.application.workspace;

/** Erreur de frontière de messagerie, sans payload fournisseur ni secret. */
public class MailboxFailure extends RuntimeException {
    public enum Reason { ACCESS_DENIED, RATE_LIMIT, UNAVAILABLE }
    private final Reason reason;
    protected MailboxFailure(Reason reason){super("Messagerie : "+reason);this.reason=reason;}
    public Reason reason(){return reason;}
}
