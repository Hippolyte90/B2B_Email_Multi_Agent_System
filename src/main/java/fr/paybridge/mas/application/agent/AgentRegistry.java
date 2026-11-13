package fr.paybridge.mas.application.agent;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.agent.AgentRegistrySnapshot;
import fr.paybridge.mas.domain.agent.AgentRegistryArchive;
import fr.paybridge.mas.application.port.AgentRegistryRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registre à publication atomique, avec conservation durable optionnelle des versions. */
public final class AgentRegistry {
    private record State(AgentRegistrySnapshot snapshot, Map<String, List<AgentDefinition>> history) { }
    private volatile State state;
    private AgentRegistryRepository repository;

    public AgentRegistry(Collection<AgentDefinition> initial) {
        var definitions = validate(initial);
        var history = new HashMap<String, List<AgentDefinition>>();
        definitions.forEach(d -> history.put(d.id(), List.of(d)));
        state = new State(new AgentRegistrySnapshot(1, definitions), Map.copyOf(history));
    }

    public AgentRegistry(Collection<AgentDefinition> initial, AgentRegistryRepository repository) {
        this(initial);
        this.repository = Objects.requireNonNull(repository);
        var saved = repository.load();
        if (saved.isEmpty()) { repository.save(0, archive()); }
        else {
            var restored = restore(saved.get());
            state = restored.state;
            replaceAll(initial);
        }
    }

    public static AgentRegistry restore(AgentRegistryArchive archive) {
        Objects.requireNonNull(archive);
        var registry = new AgentRegistry(archive.snapshot().agents());
        registry.state = new State(new AgentRegistrySnapshot(archive.snapshot().revision(), validate(archive.snapshot().agents())), archive.history());
        return registry;
    }

    public AgentRegistryArchive archive() { var current = state; return new AgentRegistryArchive(current.snapshot(), current.history()); }

    /** Publication après commit de ConfigurationRepository ; aucune E/S sous ce verrou. */
    public synchronized void publishCommitted(AgentRegistryArchive expected, AgentRegistryArchive next) {
        if (!archive().equals(expected)) { throw new IllegalArgumentException("Registre modifié entre-temps"); }
        var planned = restore(expected);
        planned.replaceAll(next.snapshot().agents());
        if (!planned.archive().equals(next)) { throw new IllegalArgumentException("Évolution de registre incohérente"); }
        state = new State(next.snapshot(), next.history());
    }

    public AgentRegistrySnapshot snapshot() {
        return state.snapshot();
    }

    public List<AgentDefinition> history(String id) {
        return state.history().getOrDefault(id, List.of());
    }

    public synchronized AgentRegistrySnapshot replaceAll(Collection<AgentDefinition> replacements) {
        var definitions = validate(replacements);
        var previous = state;
        var byId = new HashMap<String, AgentDefinition>();
        definitions.forEach(d -> byId.put(d.id(), d));
        if (!byId.keySet().containsAll(previous.history().keySet())) {
            throw new IllegalArgumentException("Désactivez un agent au lieu de supprimer sa définition");
        }
        var history = new HashMap<>(previous.history());
        for (var definition : definitions) {
            var versions = history.get(definition.id());
            if (versions == null) {
                if (definition.version() != 1) {
                    throw new IllegalArgumentException("Un nouvel agent commence à la version 1");
                }
                history.put(definition.id(), List.of(definition));
            } else {
                var latest = versions.getLast();
                if (definition.equals(latest)) {
                    continue;
                }
                if (latest.version() == Long.MAX_VALUE || definition.version() != latest.version() + 1) {
                    throw new IllegalArgumentException("Toute modification exige la version suivante de l'agent");
                }
                var updated = new ArrayList<>(versions);
                updated.add(definition);
                history.put(definition.id(), List.copyOf(updated));
            }
        }
        if (definitions.equals(previous.snapshot().agents())) {
            return previous.snapshot();
        }
        var next = new AgentRegistrySnapshot(Math.addExact(previous.snapshot().revision(), 1), definitions);
        if (repository != null) { repository.save(previous.snapshot().revision(), new AgentRegistryArchive(next, history)); }
        state = new State(next, Map.copyOf(history));
        return next;
    }

    private static List<AgentDefinition> validate(Collection<AgentDefinition> input) {
        Objects.requireNonNull(input, "Définitions obligatoires");
        var definitions = List.copyOf(input);
        if (definitions.stream().map(AgentDefinition::id).distinct().count() != definitions.size()) {
            throw new IllegalArgumentException("Identifiant d'agent dupliqué");
        }
        if (definitions.stream().noneMatch(d -> d.active() && d.fallback())) {
            throw new IllegalArgumentException("Au moins un agent générique de repli doit rester actif");
        }
        return definitions.stream().sorted(Comparator.comparing(AgentDefinition::id)).toList();
    }
}
