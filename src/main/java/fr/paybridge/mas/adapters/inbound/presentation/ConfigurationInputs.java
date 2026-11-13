package fr.paybridge.mas.adapters.inbound.presentation;

import fr.paybridge.mas.application.configuration.LocalInferenceSettings;
import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.domain.generation.GenerationSettings;
import fr.paybridge.mas.domain.mail.*;
import java.time.Duration;
import java.util.*;

/** Conversion de formulaires, déterministe et indépendante de JavaFX. */
public final class ConfigurationInputs {
    private ConfigurationInputs() { }
    private static Set<String> csv(String value, boolean emptyAllowed) {
        if (value.isBlank() && emptyAllowed) { return Set.of(); }
        var values = Arrays.stream(value.split(",", -1)).map(String::strip).toList();
        if (values.stream().anyMatch(String::isEmpty) || new HashSet<>(values).size() != values.size()) { throw new IllegalArgumentException("Liste vide ou contenant des doublons"); }
        return Set.copyOf(values);
    }
    public record AgentInput(String id, String role, String categories, String sources, String prompt, boolean active, boolean fallback) {
        public AgentDefinition value() { return new AgentDefinition(id.strip(), 1, role.strip(), csv(categories, false), prompt, csv(sources, true), active, fallback); }
        public static AgentInput of(AgentDefinition a) { return new AgentInput(a.id(), a.role(), join(a.categories()), join(a.contextSources()), a.systemPrompt(), a.active(), a.fallback()); }
    }
    public record SourceInput(String id, String reference, String provenance, String client, String project, ContextSource.Visibility visibility,
                              String agents, String recipients, String text) {
        public ContextSource value() {
            var addresses = csv(recipients, true).stream().map(EmailAddress::new).toList();
            if (new HashSet<>(addresses).size() != addresses.size()) { throw new IllegalArgumentException("Destinataires dupliqués"); }
            if (text == null || text.isBlank()) { throw new IllegalArgumentException("Texte de source obligatoire"); }
            return new ContextSource(id.strip(), 1, reference.strip(), provenance.strip(), new ClientScope(optional(client), optional(project)),
                    Objects.requireNonNull(visibility), csv(agents, false), Set.copyOf(addresses));
        }
        public static SourceInput of(ContextSource s, String text) { return new SourceInput(s.id(), s.reference(), s.provenance(), s.scope().clientId().orElse(""), s.scope().projectId().orElse(""), s.visibility(), join(s.agentIds()), s.allowedRecipients().stream().map(EmailAddress::value).sorted().collect(java.util.stream.Collectors.joining(", ")), text); }
    }
    public record InferenceInput(String endpoint, String model, String capacity, String output, String temperature, String seed, String timeoutSeconds, String overhead) {
        public LocalInferenceSettings value() {
            return new LocalInferenceSettings(endpoint.strip(), new GenerationSettings(model.strip(), Integer.parseInt(capacity.strip()), Integer.parseInt(output.strip()), Double.parseDouble(temperature.strip()), Long.parseLong(seed.strip()), Duration.ofSeconds(Long.parseLong(timeoutSeconds.strip()))), Integer.parseInt(overhead.strip()));
        }
        public static InferenceInput of(LocalInferenceSettings s) { var g = s.generation(); return new InferenceInput(s.endpoint(), g.model(), "" + g.contextCapacity(), "" + g.outputLimit(), "" + g.temperature(), "" + g.seed(), "" + g.timeout().toSeconds(), "" + s.framingOverhead()); }
    }
    private static Optional<String> optional(String value) { return Optional.of(value.strip()).filter(s -> !s.isEmpty()); }
    private static String join(Set<String> values) { return values.stream().sorted().collect(java.util.stream.Collectors.joining(", ")); }
}
