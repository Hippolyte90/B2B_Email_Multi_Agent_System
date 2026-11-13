package fr.paybridge.mas.application.configuration;

import fr.paybridge.mas.domain.agent.AgentDefinition;
import fr.paybridge.mas.domain.context.ContextSource;
import java.util.*;

/** Instantané actif : seuls les textes explicitement édités sont copiés depuis les sources de fichiers. */
public record EditableConfiguration(long revision, List<AgentDefinition> agents, List<ContextSource> sources,
                                    Map<String, String> textOverrides, LocalInferenceSettings inference) {
    public EditableConfiguration {
        agents = agents.stream().sorted(Comparator.comparing(AgentDefinition::id)).toList();
        sources = sources.stream().sorted(Comparator.comparing(ContextSource::id)).toList();
        textOverrides = Map.copyOf(textOverrides); Objects.requireNonNull(inference);
        if (revision < 0 || agents.stream().noneMatch(a -> a.active() && a.fallback())
                || agents.stream().map(AgentDefinition::id).distinct().count() != agents.size()
                || sources.stream().map(ContextSource::id).distinct().count() != sources.size()
                || sources.stream().map(ContextSource::reference).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("Configuration dupliquée ou sans repli actif");
        }
        var references = sources.stream().map(ContextSource::reference).collect(java.util.stream.Collectors.toSet());
        var sourceIds = sources.stream().map(ContextSource::id).collect(java.util.stream.Collectors.toSet());
        var agentIds = agents.stream().map(AgentDefinition::id).collect(java.util.stream.Collectors.toSet());
        if (!sourceIds.containsAll(textOverrides.keySet()) || textOverrides.values().stream().anyMatch(String::isBlank)
                || agents.stream().anyMatch(a -> !references.containsAll(a.contextSources()))
                || sources.stream().anyMatch(s -> !agentIds.containsAll(s.agentIds()))) {
            throw new IllegalArgumentException("Référence de source ou d'agent absente, ou texte vide");
        }
        for (var source : sources) {
            if (!source.id().matches("[A-Za-z][A-Za-z0-9_-]*") || !source.reference().matches("[A-Za-z0-9_./-]+\\.(md|txt)")
                    || source.reference().startsWith("/") || Arrays.asList(source.reference().split("/")).contains("..")) {
                throw new IllegalArgumentException("Identifiant ou référence relative de source invalide");
            }
        }
    }
}
