package fr.paybridge.mas.application.context;

import fr.paybridge.mas.application.port.ContextSourceCatalog;
import fr.paybridge.mas.application.port.SourceReader;
import fr.paybridge.mas.application.port.TokenCounter;
import fr.paybridge.mas.domain.context.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static fr.paybridge.mas.domain.context.ContextExclusion.Reason.*;

/** Autorisations applicatives avant toute lecture documentaire, puis pertinence et budget. */
public final class ContextService {
    private final ContextSourceCatalog catalog;
    private final SourceReader reader;
    private final TokenCounter counter;
    private final ContextSelectionPolicy selection;
    private final ConversationMemoryPolicy memory;

    public ContextService(ContextSourceCatalog catalog, SourceReader reader, TokenCounter counter,
                          ContextSelectionPolicy selection, ConversationMemoryPolicy memory) {
        this.catalog = Objects.requireNonNull(catalog);
        this.reader = Objects.requireNonNull(reader);
        this.counter = Objects.requireNonNull(counter);
        this.selection = Objects.requireNonNull(selection);
        this.memory = Objects.requireNonNull(memory);
    }

    public ContextBundle prepare(ContextRequest request) throws IOException {
        var instructions = ContextPromptFormatter.instructions(request);
        var history = new ArrayList<MemoryEntry>();
        var excerpts = new ArrayList<ContextExcerpt>();
        var exclusions = new ArrayList<ContextExclusion>();
        Optional<String> feedback = Optional.empty();
        if (!fits(request, instructions, history, excerpts, feedback)) {
            throw new IllegalArgumentException("Les instructions et le message courant dépassent le budget incompressible");
        }
        if (request.userFeedback().isPresent()) {
            if (fits(request, instructions, history, excerpts, request.userFeedback())) {
                feedback = request.userFeedback();
            } else {
                exclusions.add(new ContextExclusion("user-feedback", BUDGET));
            }
        }

        var authorizedHistory = new ArrayList<MemoryEntry>();
        for (var entry : request.history()) {
            if (!entry.scope().equals(request.message().scope()) || !entry.conversationId().equals(request.message().conversationId())) {
                exclusions.add(new ContextExclusion(entry.id(), WRONG_SCOPE));
            } else if (!entry.shareableWith().containsAll(request.replyRecipients())) {
                exclusions.add(new ContextExclusion(entry.id(), RECIPIENT_NOT_ALLOWED));
            } else if ((entry.kind() != MemoryEntry.Kind.RECEIVED && entry.kind() != MemoryEntry.Kind.SENT_CONFIRMED)
                    || entry.id().equals(request.message().id()) || entry.occurredAt().isAfter(request.message().receivedAt().toInstant())) {
                exclusions.add(new ContextExclusion(entry.id(), HISTORY_NOT_ELIGIBLE));
            } else {
                authorizedHistory.add(entry);
            }
        }
        var chosenHistory = memory.select(request, List.copyOf(authorizedHistory));
        if (!authorizedHistory.containsAll(chosenHistory)) {
            throw new IllegalArgumentException("La politique mémoire a ajouté une entrée non autorisée");
        }
        authorizedHistory.stream().filter(e -> !chosenHistory.contains(e))
                .forEach(e -> exclusions.add(new ContextExclusion(e.id(), HISTORY_LIMIT)));
        var seenMemory = new HashSet<String>();
        for (var entry : chosenHistory.stream().sorted(Comparator.comparing(MemoryEntry::occurredAt)
                .thenComparing(MemoryEntry::id).reversed()).toList()) {
            if (!seenMemory.add(canonical(entry.content()))) {
                exclusions.add(new ContextExclusion(entry.id(), DUPLICATE));
                continue;
            }
            var candidate = new ArrayList<>(history);
            candidate.add(entry);
            candidate.sort(Comparator.comparing(MemoryEntry::occurredAt).thenComparing(MemoryEntry::id));
            if (fits(request, instructions, candidate, excerpts, feedback)) {
                history = candidate;
            } else {
                exclusions.add(new ContextExclusion(entry.id(), BUDGET));
            }
        }

        var sources = List.copyOf(catalog.sources());
        if (sources.stream().map(ContextSource::id).distinct().count() != sources.size()
                || sources.stream().map(ContextSource::reference).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("Identifiant ou référence de source dupliqué dans le catalogue");
        }
        request.agent().contextSources().stream().sorted()
                .filter(ref -> sources.stream().noneMatch(s -> s.reference().equals(ref)))
                .forEach(ref -> exclusions.add(new ContextExclusion(ref, UNKNOWN_SOURCE)));
        var documents = new ArrayList<ContextDocument>();
        for (var source : sources.stream().sorted(Comparator.comparing(ContextSource::id)).toList()) {
            var denied = denial(source, request);
            if (denied.isPresent()) {
                exclusions.add(new ContextExclusion(source.id(), denied.get()));
                continue;
            }
            try {
                documents.add(new ContextDocument(source, reader.read(source)));
            } catch (IOException | IllegalArgumentException error) {
                exclusions.add(new ContextExclusion(source.id(), UNREADABLE_SOURCE));
            }
        }
        var ranked = selection.rank(request.message(), List.copyOf(history), List.copyOf(documents));
        for (var excerpt : ranked) {
            boolean authorized = documents.stream().anyMatch(d -> d.source().id().equals(excerpt.sourceId())
                    && d.source().version() == excerpt.sourceVersion() && d.source().provenance().equals(excerpt.provenance())
                    && d.content().contains(excerpt.content()));
            if (!authorized) {
                throw new IllegalArgumentException("La sélection a produit un extrait non autorisé");
            }
        }
        documents.stream().filter(d -> ranked.stream().noneMatch(e -> e.sourceId().equals(d.source().id())))
                .forEach(d -> exclusions.add(new ContextExclusion(d.source().id(), NOT_RELEVANT)));
        var seenExcerpts = new HashSet<String>();
        for (var excerpt : ranked) {
            var reference = excerpt.sourceId() + "#" + excerpt.paragraph();
            var fingerprint = canonical(excerpt.content());
            if (seenExcerpts.contains(fingerprint)) {
                exclusions.add(new ContextExclusion(reference, DUPLICATE));
                continue;
            }
            var candidate = new ArrayList<>(excerpts);
            candidate.add(excerpt);
            if (fits(request, instructions, history, candidate, feedback)) {
                excerpts = candidate;
                seenExcerpts.add(fingerprint);
            } else {
                exclusions.add(new ContextExclusion(reference, BUDGET));
            }
        }
        var data = ContextPromptFormatter.userData(request, history, excerpts, feedback);
        var usage = new ContextBundle.Usage(counter.id(), counter.estimated(), inputTokens(request, instructions, data),
                request.budget().outputReserve(), request.budget().capacity());
        return new ContextBundle(instructions, data, history, excerpts, feedback, exclusions, usage);
    }

    private Optional<ContextExclusion.Reason> denial(ContextSource source, ContextRequest request) {
        if (!request.agent().contextSources().contains(source.reference()) || !source.agentIds().contains(request.agent().id())) {
            return Optional.of(NOT_ASSIGNED_TO_AGENT);
        }
        if (source.visibility() == ContextSource.Visibility.INTERNAL_ONLY) {
            return Optional.of(INTERNAL_ONLY);
        }
        if (source.visibility() == ContextSource.Visibility.CLIENT_SHAREABLE) {
            if (request.message().scope().clientId().isEmpty()
                    || !source.scope().clientId().equals(request.message().scope().clientId())
                    || (source.scope().projectId().isPresent() && !source.scope().projectId().equals(request.message().scope().projectId()))) {
                return Optional.of(WRONG_SCOPE);
            }
            if (!source.allowedRecipients().containsAll(request.replyRecipients())) {
                return Optional.of(RECIPIENT_NOT_ALLOWED);
            }
        }
        return Optional.empty();
    }

    private boolean fits(ContextRequest request, String instructions, List<MemoryEntry> history,
                         List<ContextExcerpt> excerpts, Optional<String> feedback) {
        var input = inputTokens(request, instructions, ContextPromptFormatter.userData(request, history, excerpts, feedback));
        return input <= (long) request.budget().capacity() - request.budget().outputReserve();
    }

    private long inputTokens(ContextRequest request, String instructions, String data) {
        long system = counter.count(instructions);
        long user = counter.count(data);
        if (system < 0 || user < 0) { throw new IllegalArgumentException("Compteur de tokens invalide"); }
        return Math.addExact(Math.addExact(system, user), request.budget().framingOverhead());
    }

    private static String canonical(String text) { return text.strip().replaceAll("\\s+", " "); }
}
