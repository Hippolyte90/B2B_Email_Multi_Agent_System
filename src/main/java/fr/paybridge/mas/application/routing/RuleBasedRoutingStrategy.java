package fr.paybridge.mas.application.routing;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.routing.RoutingCandidate;
import fr.paybridge.mas.domain.routing.RoutingOutcome;
import fr.paybridge.mas.domain.routing.RoutingPolicy;
import fr.paybridge.mas.domain.routing.RoutingRequest;
import fr.paybridge.mas.domain.routing.RoutingRule;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Score de correspondance déterministe, pas une probabilité de bonne réponse. */
public final class RuleBasedRoutingStrategy implements RoutingStrategy {
    private final RoutingPolicy policy;

    public RuleBasedRoutingStrategy(RoutingPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public RoutingOutcome route(RoutingRequest request) {
        var ranked = new ArrayList<RoutingCandidate>();
        var history = request.previousMessages().stream()
                .sorted(Comparator.comparing((EmailMessage m) -> m.receivedAt().toInstant()).thenComparing(EmailMessage::id))
                .toList();
        var recent = history.subList(Math.max(0, history.size() - policy.historyLimit()), history.size());
        for (var agent : request.eligibleAgents()) {
            if (agent.fallback()) {
                continue;
            }
            int score = 0;
            var reasons = new ArrayList<String>();
            for (var rule : policy.rules().stream().sorted(Comparator.comparing(RoutingRule::id)).toList()) {
                if (agent.categories().contains(rule.category()) && matches(rule, request.message(), recent)) {
                    score += rule.weight();
                    // Aucun extrait d'email ni adresse dans les motifs.
                    reasons.add("règle=" + rule.id() + "; champ=" + rule.field() + "; catégorie=" + rule.category() + "; poids=" + rule.weight());
                }
            }
            if (score > 0) {
                ranked.add(new RoutingCandidate(agent.id(), score, reasons));
            }
        }
        ranked.sort(Comparator.comparingInt(RoutingCandidate::score).reversed().thenComparing(RoutingCandidate::agentId));
        var qualified = ranked.stream().filter(c -> c.score() >= policy.threshold()).toList();
        if (qualified.isEmpty()) {
            var fallback = request.eligibleAgents().stream().filter(AgentDefinition::fallback)
                    .min(Comparator.comparing(AgentDefinition::id)).orElseThrow();
            ranked.add(new RoutingCandidate(fallback.id(), 0, List.of("Aucun spécialiste n'atteint le seuil ; clarification nécessaire")));
            return outcome(RoutingOutcome.Status.FALLBACK, ranked, Optional.of(fallback.id()));
        }
        if (qualified.size() > 1 && qualified.getFirst().score() - qualified.get(1).score() <= policy.ambiguityMargin()) {
            return outcome(RoutingOutcome.Status.AMBIGUOUS, ranked, Optional.empty());
        }
        return outcome(RoutingOutcome.Status.SELECTED, ranked, Optional.of(qualified.getFirst().agentId()));
    }

    private RoutingOutcome outcome(RoutingOutcome.Status status, List<RoutingCandidate> candidates, Optional<String> selected) {
        return new RoutingOutcome(policy.id(), policy.version(), status, candidates, selected);
    }

    private boolean matches(RoutingRule rule, EmailMessage message, List<EmailMessage> history) {
        if (rule.field() == RoutingRule.Field.THREAD) {
            // Ne pas faire correspondre une expression à cheval sur deux messages.
            return history.stream().anyMatch(m -> containsTerm(m.subject(), rule) || containsTerm(m.body(), rule));
        }
        var text = switch (rule.field()) {
            case SUBJECT -> message.subject();
            case BODY -> message.body();
            case SENDER -> message.sender().value();
            case CLIENT -> message.scope().clientId().orElse("");
            case PROJECT -> message.scope().projectId().orElse("");
            case THREAD -> throw new IllegalStateException("Historique traité séparément");
        };
        return containsTerm(text, rule);
    }

    private static boolean containsTerm(String text, RoutingRule rule) {
        var normalized = normalize(text);
        return rule.terms().stream().map(RuleBasedRoutingStrategy::normalize).anyMatch(term ->
                Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])")
                        .matcher(normalized).find());
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
