package fr.paybridge.mas;

import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.mail.ClientScope;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.routing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoutingStrategyTest {
    private final AgentDefinition fallback = agent("Fallback", "general", true);
    private final AgentDefinition technical = agent("Technical", "technical", false);
    private final AgentDefinition onsite = agent("Onsite", "onsite", false);

    private static AgentDefinition agent(String id, String category, boolean fallback) {
        return new AgentDefinition(id, 1, "Rôle", Set.of(category), "Prompt", Set.of(), true, fallback);
    }

    private EmailMessage message(String id, String subject, String body) {
        return new EmailMessage(id, "thread", new EmailAddress("alice@client.example"),
                List.of(new EmailAddress("support@paybridge.example")), subject, body,
                OffsetDateTime.parse("2026-11-12T10:00:00+01:00"),
                new ClientScope(Optional.of("client-a"), Optional.of("project-x")));
    }

    private RoutingRule rule(String id, String category, RoutingRule.Field field, String term, int weight) {
        return new RoutingRule(id, category, field, List.of(term), weight);
    }

    private RuleBasedRoutingStrategy strategy(int threshold, int margin, int historyLimit, RoutingRule... rules) {
        return new RuleBasedRoutingStrategy(new RoutingPolicy("rules", 1, threshold, margin, historyLimit, List.of(rules)));
    }

    private RoutingRequest request(EmailMessage message, List<EmailMessage> history) {
        return new RoutingRequest(message, history, List.of(fallback, technical, onsite), 1);
    }

    @Test
    void classifiesSimpleMatchAndExplainsWithoutCopyingEmail() {
        var rule = rule("technical-body", "technical", RoutingRule.Field.BODY, "sdk", 3);
        var outcome = strategy(3, 1, 6, rule).route(request(message("m", "Bonjour", "Mon SDK ne répond pas, secret-123"), List.of()));
        assertEquals(RoutingOutcome.Status.SELECTED, outcome.status());
        assertEquals(Optional.of("Technical"), outcome.selectedAgentId());
        assertEquals(3, outcome.candidates().getFirst().score());
        assertTrue(outcome.candidates().getFirst().reasons().getFirst().contains("technical-body"));
        assertFalse(outcome.toString().contains("secret-123"));
    }

    @Test
    void scoreIsPerRuleNotPerKeywordOccurrence() {
        var outcome = strategy(3, 0, 6, rule("r", "technical", RoutingRule.Field.BODY, "sdk", 3))
                .route(request(message("m", "Bonjour", "sdk sdk sdk"), List.of()));
        assertEquals(3, outcome.candidates().getFirst().score());
    }

    @Test
    void accentsCaseAndWhitespaceAreNormalizedWithWordBoundaries() {
        var router = strategy(3, 0, 6, rule("r", "technical", RoutingRule.Field.BODY, "échec sdk", 3));
        assertEquals(RoutingOutcome.Status.SELECTED, router.route(request(message("m", "Bonjour", "ÉCHEC   SDK !"), List.of())).status());
        var words = strategy(3, 0, 6, rule("r", "technical", RoutingRule.Field.BODY, "sdk", 3));
        assertEquals(RoutingOutcome.Status.FALLBACK, words.route(request(message("m", "Bonjour", "xsdkplus"), List.of())).status());
    }

    @Test
    void equalScoresAreAmbiguousAndRequireHumanChoice() {
        var router = strategy(3, 0, 6,
                rule("r1", "technical", RoutingRule.Field.BODY, "panne", 3),
                rule("r2", "onsite", RoutingRule.Field.BODY, "ingénieur", 3));
        var result = router.route(request(message("m", "Urgent", "Panne, demande d'ingénieur"), List.of()));
        assertEquals(RoutingOutcome.Status.AMBIGUOUS, result.status());
        assertTrue(result.selectedAgentId().isEmpty());
        assertEquals(List.of("Onsite", "Technical"), result.candidates().stream().map(RoutingCandidate::agentId).toList());
    }

    @Test
    void ambiguityMarginIncludesExactBoundary() {
        var rules = List.of(rule("a", "technical", RoutingRule.Field.BODY, "panne", 4),
                rule("b", "onsite", RoutingRule.Field.BODY, "ingénieur", 3));
        var input = request(message("m", "Urgent", "Panne et ingénieur"), List.of());
        assertEquals(RoutingOutcome.Status.AMBIGUOUS, strategy(3, 1, 6, rules.toArray(RoutingRule[]::new)).route(input).status());
        assertEquals(Optional.of("Technical"), strategy(3, 0, 6, rules.toArray(RoutingRule[]::new)).route(input).selectedAgentId());
    }

    @Test
    void exactThresholdQualifiesButJustBelowFallsBack() {
        var rule = rule("r", "technical", RoutingRule.Field.BODY, "sdk", 3);
        var input = request(message("m", "Bonjour", "SDK"), List.of());
        assertEquals(RoutingOutcome.Status.SELECTED, strategy(3, 0, 0, rule).route(input).status());
        var below = strategy(4, 0, 0, rule).route(input);
        assertEquals(RoutingOutcome.Status.FALLBACK, below.status());
        assertEquals(Optional.of("Fallback"), below.selectedAgentId());
        assertEquals(3, below.candidates().getFirst().score());
    }

    @Test
    void noMatchingRuleFallsBackToClarification() {
        var outcome = strategy(3, 0, 0).route(request(message("m", "Bonjour", "Aidez-nous"), List.of()));
        assertEquals(RoutingOutcome.Status.FALLBACK, outcome.status());
        assertEquals(Optional.of("Fallback"), outcome.selectedAgentId());
    }

    @Test
    void onlyQualifiedCandidatesParticipateInAmbiguity() {
        var router = strategy(3, 5, 0,
                rule("a", "technical", RoutingRule.Field.BODY, "sdk", 3),
                rule("b", "onsite", RoutingRule.Field.BODY, "site", 2));
        assertEquals(RoutingOutcome.Status.SELECTED, router.route(request(message("m", "Bonjour", "sdk site"), List.of())).status());
    }

    @Test
    void sameSenderRoutesDifferentlyDependingOnContent() {
        var router = strategy(3, 0, 0,
                rule("a", "technical", RoutingRule.Field.BODY, "sdk", 3),
                rule("b", "onsite", RoutingRule.Field.BODY, "site", 3));
        assertEquals(Optional.of("Technical"), router.route(request(message("m1", "Bonjour", "SDK"), List.of())).selectedAgentId());
        assertEquals(Optional.of("Onsite"), router.route(request(message("m2", "Bonjour", "Sur site"), List.of())).selectedAgentId());
    }

    @ParameterizedTest
    @EnumSource(RoutingRule.Field.class)
    void supportsEachConfiguredSignal(RoutingRule.Field field) {
        var term = switch (field) {
            case SUBJECT -> "sujet";
            case BODY -> "corps";
            case THREAD -> "ancien";
            case SENDER -> "alice@client.example";
            case CLIENT -> "client-a";
            case PROJECT -> "project-x";
        };
        var current = message("m2", "Sujet", "Corps");
        var router = strategy(3, 0, 1, rule("r", "technical", field, term, 3));
        assertEquals(Optional.of("Technical"), router.route(request(current, List.of(message("m1", "Ancien", "Ancien")))).selectedAgentId());
    }

    @Test
    void historyLimitExcludesOlderMessagesAndCanDisableHistory() {
        var router = strategy(3, 0, 1, rule("r", "technical", RoutingRule.Field.THREAD, "sdk", 3));
        var old = message("m1", "SDK", "SDK");
        var recent = message("m2", "Bonjour", "Autre demande");
        var input = request(message("m3", "Suite", "Suite"), List.of(old, recent));
        assertEquals(RoutingOutcome.Status.FALLBACK, router.route(input).status());
        assertEquals(RoutingOutcome.Status.SELECTED, strategy(3, 0, 2,
                rule("r", "technical", RoutingRule.Field.THREAD, "sdk", 3)).route(input).status());
        assertEquals(RoutingOutcome.Status.FALLBACK, strategy(3, 0, 0,
                rule("r", "technical", RoutingRule.Field.THREAD, "sdk", 3)).route(input).status());
    }

    @Test
    void historyFromDifferentClientOrThreadIsRejected() {
        var foreign = new EmailMessage("old", "foreign", new EmailAddress("bob@other.example"),
                List.of(new EmailAddress("support@paybridge.example")), "SDK", "SDK",
                OffsetDateTime.parse("2026-11-12T09:00:00+01:00"), ClientScope.client("other"));
        assertThrows(IllegalArgumentException.class, () -> request(message("m", "Suite", "Suite"), List.of(foreign)));
    }

    @Test
    void emptyOrInactiveEligibleRegistryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingRequest(message("m", "Sujet", "Corps"), List.of(), List.of(), 1));
        var inactive = new AgentDefinition("Fallback", 1, "Rôle", Set.of("general"), "Prompt", Set.of(), false, true);
        assertThrows(IllegalArgumentException.class, () -> new RoutingRequest(message("m", "Sujet", "Corps"), List.of(), List.of(inactive), 1));
    }

    @Test
    void reasonsAndOrderingAreDeterministicRegardlessOfInputOrder() {
        var router = strategy(3, 0, 0,
                rule("z", "technical", RoutingRule.Field.BODY, "sdk", 3),
                rule("a", "technical", RoutingRule.Field.SUBJECT, "sdk", 3));
        var message = message("m", "SDK", "SDK");
        var first = router.route(request(message, List.of()));
        var second = router.route(new RoutingRequest(message, List.of(), List.of(onsite, technical, fallback), 1));
        assertEquals(first, second);
        assertTrue(first.candidates().getFirst().reasons().getFirst().contains("règle=a;"));
    }
}
