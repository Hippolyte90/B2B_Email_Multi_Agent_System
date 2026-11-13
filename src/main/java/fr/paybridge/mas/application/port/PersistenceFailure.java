package fr.paybridge.mas.application.port;

/** Erreur publique sans SQL, chemin, corps de message ni cause technique confidentielle. */
public final class PersistenceFailure extends RuntimeException {
    public enum Code { OPEN_FAILED, UNSUPPORTED_SCHEMA, READ_FAILED, WRITE_FAILED, CORRUPT_DATA, CLOSED, ROLLBACK_ONLY, VERSION_CONFLICT, ALREADY_OPEN }
    private final Code code;

    public PersistenceFailure(Code code) {
        super("Stockage local indisponible : " + code);
        this.code = code;
    }

    public Code code() { return code; }
}
