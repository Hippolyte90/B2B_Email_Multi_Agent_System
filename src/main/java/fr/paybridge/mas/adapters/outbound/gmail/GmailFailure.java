package fr.paybridge.mas.adapters.outbound.gmail;

/** Ne conserve jamais les réponses brutes du fournisseur ni les jetons. */
public final class GmailFailure extends fr.paybridge.mas.application.workspace.MailboxFailure {
    private final int status;
    public GmailFailure(int status) { super(status==401||status==403?Reason.ACCESS_DENIED:status==429?Reason.RATE_LIMIT:Reason.UNAVAILABLE); this.status = status; }
    public int status() { return status; }
}
