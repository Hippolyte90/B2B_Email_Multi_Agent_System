package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.routing.RoutingDecision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Persistance des conversations, décisions, configurations et générations dans une transaction locale. */
public final class SqliteStore implements TransactionRunner, AutoCloseable {
    private static final int SCHEMA = 6;
    private static final int APPLICATION_ID = 1346459981;
    private final Connection connection;
    private final StorageJson json = new StorageJson();
    private final ConversationRepository conversations = new Conversations();
    private final RoutingDecisionRepository decisions = new Decisions();
    private final AgentRegistryRepository agents = new SqliteAgentRegistryRepository(this);
    private final SourceArchive sources = new SqliteSourceArchive(this);
    private final CompletedGenerationRepository generations = new SqliteCompletedGenerationRepository(this);
    private final DraftRepository drafts = new SqliteDraftRepository(this);
    private final GenerationJournal journal = new SqliteGenerationJournal(this);
    private final SqliteSimulatedEmailSender sender = new SqliteSimulatedEmailSender(this);
    private final ConfigurationRepository configuration = new SqliteConfigurationRepository(this);
    private final MailboxFlagsRepository mailbox = new SqliteMailboxFlagsRepository(this);
    private int depth;
    private boolean rollbackOnly;
    private boolean closed;

    public SqliteStore(Path file) {
        Connection opened = null;
        try {
            var absolute = file.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
            connection = opened;
            execute("PRAGMA busy_timeout=5000");
            execute("PRAGMA foreign_keys=ON");
            initialize();
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
        } catch (Exception failure) {
            if (opened != null) { try { opened.close(); } catch (SQLException ignored) { } }
            if (failure instanceof PersistenceFailure known) { throw known; }
            throw new PersistenceFailure(PersistenceFailure.Code.OPEN_FAILED);
        }
    }

    private void initialize() {
        inTransaction(() -> {
            int version = scalar("PRAGMA user_version");
            int application = scalar("PRAGMA application_id");
            if (version == SCHEMA && application == APPLICATION_ID) { return null; }
            if (application == APPLICATION_ID && (version >= 1 && version <= 5)) {
                if (version == 1) { migrateToTwo(); }
                if (version <= 2) { migrateToThree(); }
                if (version <= 3) { migrateToFour(); }
                if(version<=4){migrateToFive();}migrateToSix(); return null;
            }
            if (version != 0 || application != 0
                    || scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") != 0) {
                throw new PersistenceFailure(PersistenceFailure.Code.UNSUPPORTED_SCHEMA);
            }
            execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision>0), payload TEXT NOT NULL)");
            execute("CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id))");
            execute("CREATE TABLE routing_decisions (id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id), payload TEXT NOT NULL)");
            execute("CREATE TABLE audit (sequence INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, entity_id TEXT NOT NULL, revision INTEGER NOT NULL, occurred_at TEXT NOT NULL)");
            execute("PRAGMA application_id=" + APPLICATION_ID);
            migrateToTwo();
            migrateToThree();
            migrateToFour();
            migrateToFive();
            migrateToSix();
            return null;
        });
    }

    private void migrateToTwo() {
        execute("CREATE TABLE registry_archive (singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL, payload TEXT NOT NULL)");
        execute("CREATE TABLE source_metadata (id TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id,version))");
        execute("CREATE TABLE source_text (id TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(id,version), FOREIGN KEY(id,version) REFERENCES source_metadata(id,version))");
        execute("CREATE TABLE completed_generations (sequence INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT UNIQUE NOT NULL, message_id TEXT NOT NULL REFERENCES messages(id), payload TEXT NOT NULL)");
        execute("CREATE INDEX generation_message ON completed_generations(message_id,sequence)");
        execute("PRAGMA user_version=2");
    }

    private void migrateToThree() {
        execute("CREATE TABLE drafts (id TEXT PRIMARY KEY, message_id TEXT UNIQUE NOT NULL REFERENCES messages(id), revision INTEGER NOT NULL CHECK(revision>0), unfinished INTEGER NOT NULL, payload TEXT NOT NULL)");
        execute("CREATE TABLE generation_work (task_id TEXT PRIMARY KEY, message_id TEXT NOT NULL REFERENCES messages(id), state TEXT NOT NULL, payload TEXT NOT NULL)");
        execute("CREATE TABLE simulated_outbox (attempt_id TEXT PRIMARY KEY, draft_id TEXT NOT NULL REFERENCES drafts(id), message_id TEXT NOT NULL REFERENCES messages(id), payload TEXT NOT NULL)");
        execute("PRAGMA user_version=3");
    }
    private void migrateToFour() {
        execute("CREATE TABLE configuration_snapshots (revision INTEGER PRIMARY KEY CHECK(revision>0), payload TEXT NOT NULL)");
        execute("PRAGMA user_version=4");
    }
    private void migrateToFive() {
        execute("CREATE TABLE mailbox_flags (message_id TEXT PRIMARY KEY REFERENCES messages(id), revision INTEGER NOT NULL CHECK(revision>0), payload TEXT NOT NULL)");
        execute("PRAGMA user_version=5");
    }
    private void migrateToSix(){
        execute("CREATE TABLE retired_demo_content (kind TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(kind,id))");
        new SqliteLegacyReplies(this).remove();execute("PRAGMA user_version=6");
    }

    public ConversationRepository conversations() { return conversations; }
    public RoutingDecisionRepository decisions() { return decisions; }
    public AgentRegistryRepository agents() { return agents; }
    public SourceArchive sources() { return sources; }
    public CompletedGenerationRepository generations() { return generations; }
    public DraftRepository drafts() { return drafts; }
    public GenerationJournal journal() { return journal; }
    public SqliteSimulatedEmailSender simulatedSender() { return sender; }
    public ConfigurationRepository configuration() { return configuration; }
    /** Inventaire pour l'assemblage local de confiance ; les vues passent ensuite par leur périmètre autorisé. */
    public List<EmailMessage> messagesWithPrefix(String prefix) {
        if(prefix==null||prefix.isBlank()){throw new IllegalArgumentException("Préfixe requis");}
        return inTransaction(()->payloads("SELECT id FROM messages WHERE substr(id,1,?)=? ORDER BY id",prefix.length(),prefix)
                .stream().map(id->conversations.findMessage(id).orElseThrow(()->new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA))).toList());
    }
    public MailboxFlagsRepository mailbox() { return mailbox; }
    String encode(Object value) { return json.encode(value); }
    <T> T decode(String payload, Class<T> type) { return json.decode(payload, type); }
    fr.paybridge.mas.domain.draft.Draft decodeDraft(String payload) { return json.draft(payload); }
    List<String> payloads(String sql, Object... parameters) {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            try (var rows = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (rows.next()) { values.add(rows.getString(1)); }
                return List.copyOf(values);
            }
        } catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.READ_FAILED); }
    }

    @Override public synchronized <T> T inTransaction(Supplier<T> operation) {
        Objects.requireNonNull(operation);
        if (closed) { throw new PersistenceFailure(PersistenceFailure.Code.CLOSED); }
        boolean outer = depth == 0;
        if (outer) { execute("BEGIN IMMEDIATE"); rollbackOnly = false; }
        depth++;
        try {
            T result = operation.get();
            if (outer) {
                if (rollbackOnly) { throw new PersistenceFailure(PersistenceFailure.Code.ROLLBACK_ONLY); }
                execute("COMMIT");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            rollbackOnly = true;
            if (outer) {
                try { execute("ROLLBACK"); } catch (RuntimeException rollbackFailure) { closed = true; try { connection.close(); } catch (SQLException ignored) { } }
            }
            throw failure;
        } finally { depth--; }
    }

    public record Summary(int schema, int conversations, int messages, int decisions, int auditEvents) { }
    public Summary summary() {
        return inTransaction(() -> new Summary(scalar("PRAGMA user_version"), scalar("SELECT count(*) FROM conversations"),
                scalar("SELECT count(*) FROM messages"), scalar("SELECT count(*) FROM routing_decisions"), scalar("SELECT count(*) FROM audit")));
    }

    private void execute(String sql) {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
        catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
    }

    private int scalar(String sql) {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { return result.getInt(1); }
        catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.READ_FAILED); }
    }

    Optional<String> payload(String sql, Object... parameters) {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) { statement.setObject(index + 1, parameters[index]); }
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty(); }
        } catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.READ_FAILED); }
    }

    void write(String sql, Object... parameters) {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) { statement.setObject(index + 1, parameters[index]); }
            if (statement.executeUpdate() != 1) { throw new IllegalArgumentException("Écriture obsolète"); }
        } catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
    }

    void audit(String kind, String id, long revision) {
        write("INSERT INTO audit(kind,entity_id,revision,occurred_at) VALUES(?,?,?,?)", kind, id, revision, Instant.now().toString());
    }

    private final class Conversations implements ConversationRepository {
        @Override public Optional<Conversation> findById(String id) {
            return inTransaction(() -> payload("SELECT payload FROM conversations WHERE id=?", id).map(value -> {
                var result = json.conversation(value);
                if (!result.id().equals(id)) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
                return result;
            }));
        }

        @Override public Optional<EmailMessage> findMessage(String id) {
            return inTransaction(() -> payload("SELECT conversation_id FROM messages WHERE id=?", id).map(conversationId ->
                    findById(conversationId).orElseThrow(() -> new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA))
                            .messages().stream().filter(message -> message.id().equals(id)).findFirst()
                            .orElseThrow(() -> new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA))));
        }

        @Override public void save(Conversation conversation) {
            Objects.requireNonNull(conversation);
            inTransaction(() -> {
                var current = findById(conversation.id());
                if (current.isPresent()) {
                    var before = current.get();
                    if (before.snapshot().equals(conversation.snapshot())) { return null; }
                    // Un état ancien ne peut supprimer ni modifier un message, événement ou envoi confirmé.
                    if (!before.scope().equals(conversation.scope()) || !conversation.messages().containsAll(before.messages())
                            || !conversation.history().containsAll(before.history()) || !conversation.confirmedSent().containsAll(before.confirmedSent())
                            || conversation.history().size() <= before.history().size()) {
                        throw new IllegalArgumentException("Fil modifié entre-temps ; rechargez-le");
                    }
                    write("UPDATE conversations SET revision=?,payload=? WHERE id=? AND revision=?", conversation.history().size(),
                            json.encode(conversation.snapshot()), conversation.id(), before.history().size());
                } else {
                    write("INSERT INTO conversations(id,revision,payload) VALUES(?,?,?)", conversation.id(), conversation.history().size(), json.encode(conversation.snapshot()));
                }
                for (var message : conversation.messages()) {
                    if (current.isEmpty() || current.get().messages().stream().noneMatch(existing -> existing.id().equals(message.id()))) {
                        write("INSERT INTO messages(id,conversation_id) VALUES(?,?)", message.id(), conversation.id());
                    }
                }
                audit("CONVERSATION_SAVED", conversation.id(), conversation.history().size());
                return null;
            });
        }
    }

    private final class Decisions implements RoutingDecisionRepository {
        @Override public void add(RoutingDecision decision) {
            inTransaction(() -> {
                validateMessage(decision);
                if (find(decision.id()).isPresent()) { throw new IllegalArgumentException("Identifiant de décision déjà présent"); }
                write("INSERT INTO routing_decisions(id,message_id,payload) VALUES(?,?,?)", decision.id().toString(), decision.messageId(), json.encode(decision));
                audit("ROUTING_CREATED", decision.id().toString(), 1);
                return null;
            });
        }

        @Override public Optional<RoutingDecision> find(UUID id) {
            return inTransaction(() -> payload("SELECT payload FROM routing_decisions WHERE id=?", id.toString()).map(value -> {
                var decision = json.decode(value, RoutingDecision.class);
                if (!decision.id().equals(id)) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
                try { validateMessage(decision); }
                catch (IllegalArgumentException corruption) { throw new PersistenceFailure(PersistenceFailure.Code.CORRUPT_DATA); }
                return decision;
            }));
        }

        private void validateMessage(RoutingDecision decision) {
            var message = conversations.findMessage(decision.messageId()).orElseThrow(() -> new IllegalArgumentException("Message de décision absent"));
            if (!message.scope().equals(decision.scope()) || !message.conversationId().equals(decision.conversationId())) {
                throw new IllegalArgumentException("Décision d'un autre fil ou périmètre");
            }
        }

        @Override public void replace(RoutingDecision expected, RoutingDecision updated) {
            inTransaction(() -> {
                var current = find(expected.id()).orElseThrow(() -> new IllegalArgumentException("Décision absente"));
                if (!current.equals(expected) || !expected.id().equals(updated.id())
                        || !expected.messageId().equals(updated.messageId()) || !expected.conversationId().equals(updated.conversationId())
                        || !expected.scope().equals(updated.scope()) || expected.registryRevision() != updated.registryRevision()
                        || !expected.decidedAt().equals(updated.decidedAt()) || !expected.initialOutcome().equals(updated.initialOutcome())
                        || updated.corrections().size() != expected.corrections().size() + 1
                        || !updated.corrections().subList(0, expected.corrections().size()).equals(expected.corrections())) {
                    throw new IllegalArgumentException("Correction obsolète ou identité modifiée");
                }
                write("UPDATE routing_decisions SET payload=? WHERE id=?", json.encode(updated), updated.id().toString());
                audit("ROUTING_CORRECTED", updated.id().toString(), updated.corrections().size() + 1L);
                return null;
            });
        }
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        if (depth != 0) { throw new IllegalStateException("Transaction encore active"); }
        closed = true;
        try { connection.close(); }
        catch (SQLException failure) { throw new PersistenceFailure(PersistenceFailure.Code.WRITE_FAILED); }
    }
}
