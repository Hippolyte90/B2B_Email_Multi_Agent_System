package fr.paybridge.mas.application.context;

import fr.paybridge.mas.domain.context.ContextDocument;
import fr.paybridge.mas.domain.context.ContextExcerpt;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.context.MemoryEntry;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Extraits par paragraphes, classés par nombre de mots significatifs communs. */
public final class LexicalContextSelectionPolicy implements ContextSelectionPolicy {
    private static final Set<String> STOP_WORDS = Set.of("bonjour", "merci", "nous", "vous", "pour", "dans", "avec",
            "notre", "votre", "nos", "vos", "des", "les", "une", "sur", "aux", "est", "sont", "que", "qui", "quel", "quels");

    @Override
    public List<ContextExcerpt> rank(EmailMessage message, List<MemoryEntry> history, List<ContextDocument> documents) {
        var query = words(message.subject() + " " + message.body() + " "
                + history.stream().map(MemoryEntry::content).collect(Collectors.joining(" ")));
        var result = new ArrayList<ContextExcerpt>();
        for (var document : documents) {
            var paragraphs = document.content().split("\\R\\s*\\R");
            for (int i = 0; i < paragraphs.length; i++) {
                var text = paragraphs[i].strip();
                var terms = words(text);
                int relevance = (int) query.stream().filter(terms::contains).count();
                if (!text.isBlank() && relevance > 0) {
                    var source = document.source();
                    result.add(new ContextExcerpt(source.id(), source.version(), source.provenance(), i, text, relevance));
                }
            }
        }
        result.sort(Comparator.comparingInt(ContextExcerpt::relevance).reversed()
                .thenComparing(ContextExcerpt::sourceId).thenComparingInt(ContextExcerpt::paragraph));
        return List.copyOf(result);
    }

    private static Set<String> words(String text) {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(s -> s.length() > 2 && !STOP_WORDS.contains(s)).collect(Collectors.toSet());
    }
}
