package fr.paybridge.mas.application.configuration;

import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.port.*;
import fr.paybridge.mas.domain.agent.*;
import fr.paybridge.mas.domain.context.ContextSource;
import java.io.IOException;
import java.util.*;

/** Administration locale de confiance ; les changements n'élargissent pas les droits d'un email. */
public final class ConfigurationEditor {
    private final AgentRegistry registry;
    private final ConfigurationRepository repository;
    private final SourceReader files;
    private volatile EditableConfiguration current;
    public ConfigurationEditor(AgentRegistry registry, ConfigurationRepository repository, SourceReader files, EditableConfiguration initial) {
        this.registry = registry; this.repository = repository; this.files = files;
        current = repository.load().orElse(initial);
        if (!current.agents().equals(registry.snapshot().agents())) { throw new IllegalArgumentException("Configuration et registre incohérents"); }
    }
    public EditableConfiguration current() { return current; }
    public synchronized void reloadFiles(AgentConfigurationLoader loader, ContextSourceCatalog catalog) throws IOException {
        // Après la première édition, la base est l'autorité ; les fichiers restent les valeurs initiales.
        if (current.revision() > 0) { return; }
        var definitions = loader.load(); var sources = catalog.sources();
        if (new HashSet<>(definitions).equals(new HashSet<>(current.agents())) && new HashSet<>(sources).equals(new HashSet<>(current.sources()))) { return; }
        commit(new EditableConfiguration(current.revision() + 1, definitions, sources, Map.of(), current.inference()));
    }
    private void requireRevision(long expected) { if (current.revision() != expected) { throw new IllegalArgumentException("Formulaire obsolète ; rechargez-le"); } }
    public synchronized EditableConfiguration saveAgent(long expected, AgentDefinition proposed, boolean creating) {
        requireRevision(expected);
        var definitions = new ArrayList<>(current.agents());
        var old = definitions.stream().filter(a -> a.id().equals(proposed.id())).findFirst();
        if (creating == old.isPresent()) { throw new IllegalArgumentException("Identifiant déjà présent ou agent absent"); }
        if (old.isPresent()) {
            var previous = old.get();
            var sameVersion = agentVersion(proposed, previous.version());
            if (sameVersion.equals(previous)) { return current; }
            definitions.remove(previous); definitions.add(agentVersion(proposed, Math.addExact(previous.version(), 1)));
        } else { definitions.add(agentVersion(proposed, 1)); }
        return commit(new EditableConfiguration(current.revision() + 1, definitions, current.sources(), current.textOverrides(), current.inference()));
    }
    public synchronized String readSource(String id) throws IOException {
        var source = current.sources().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Source absente"));
        var override = current.textOverrides().get(id);
        return override == null ? files.read(source) : override;
    }
    public synchronized EditableConfiguration saveSource(long expected, ContextSource proposed, String text, boolean creating) throws IOException {
        requireRevision(expected); Objects.requireNonNull(text);
        if (text.isBlank()) { throw new IllegalArgumentException("Texte de source obligatoire"); }
        var sources = new ArrayList<>(current.sources());
        var old = sources.stream().filter(s -> s.id().equals(proposed.id())).findFirst();
        if (creating == old.isPresent()) { throw new IllegalArgumentException("Identifiant déjà présent ou source absente"); }
        long version = 1;
        if (old.isPresent()) {
            var previous = old.get();
            if (sourceVersion(proposed, previous.version()).equals(previous)) {
                try { if (text.equals(readSource(previous.id()))) { return current; } }
                catch (IOException unavailable) { /* L'utilisateur peut remplacer explicitement un texte devenu illisible. */ }
            }
            // Les références restent stables afin de préserver l'affectation des agents et les fichiers initiaux.
            if (!proposed.reference().equals(previous.reference())) { throw new IllegalArgumentException("La référence d'une source existante est immuable"); }
            version = Math.addExact(previous.version(), 1); sources.remove(previous);
        }
        sources.add(sourceVersion(proposed, version));
        var overrides = new HashMap<>(current.textOverrides()); overrides.put(proposed.id(), text);
        return commit(new EditableConfiguration(current.revision() + 1, advanceAgents(), sources, overrides, current.inference()));
    }
    public synchronized EditableConfiguration saveInference(long expected, LocalInferenceSettings inference) {
        requireRevision(expected); if (inference.equals(current.inference())) { return current; }
        return commit(new EditableConfiguration(current.revision() + 1, advanceAgents(), current.sources(), current.textOverrides(), inference));
    }
    private List<AgentDefinition> advanceAgents() { return current.agents().stream().map(a -> agentVersion(a, Math.addExact(a.version(), 1))).toList(); }
    private EditableConfiguration commit(EditableConfiguration next) {
        var expected = registry.archive(); var planned = AgentRegistry.restore(expected); planned.replaceAll(next.agents());
        repository.save(current.revision(), next, expected, planned.archive());
        registry.publishCommitted(expected, planned.archive()); current = next;
        return current;
    }
    private static AgentDefinition agentVersion(AgentDefinition a, long version) { return new AgentDefinition(a.id(), version, a.role(), a.categories(), a.systemPrompt(), a.contextSources(), a.active(), a.fallback()); }
    private static ContextSource sourceVersion(ContextSource s, long version) { return new ContextSource(s.id(), version, s.reference(), s.provenance(), s.scope(), s.visibility(), s.agentIds(), s.allowedRecipients()); }
}
