package fr.paybridge.mas.application.workspace;

import fr.paybridge.mas.domain.agent.*;
import fr.paybridge.mas.domain.context.ContextSource;
import fr.paybridge.mas.domain.conversation.Conversation;
import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.generation.GenerationSettings;
import fr.paybridge.mas.domain.mail.EmailMessage;
import fr.paybridge.mas.domain.routing.RoutingDecision;
import fr.paybridge.mas.application.configuration.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** API de présentation ; aucune technologie d'écran ni dépôt concret n'est exposé. */
public interface ReviewWorkspace {
    record MessageView(EmailMessage message, Conversation conversation, RoutingDecision routing, Optional<Draft> draft) { }
    record Work(CompletionStage<Draft> result, Runnable cancel) { }
    record Configuration(AgentRegistryArchive registry, List<ContextSource> sources, GenerationSettings inference, boolean simulated, Optional<EditableConfiguration> editable) {
        public Configuration { sources = List.copyOf(sources); }
        public Configuration(AgentRegistryArchive registry, List<ContextSource> sources, GenerationSettings inference, boolean simulated) { this(registry, sources, inference, simulated, Optional.empty()); }
    }
    List<EmailMessage> inbox();
    MessageView open(String messageId);
    MessageView choose(String messageId, String agentId);
    Work generate(String messageId);
    Work regenerate(Draft displayed, Optional<String> feedback);
    Draft save(Draft displayed, DraftContent content);
    Draft approveAndSend(Draft displayed);
    Draft retryFailed(Draft displayed);
    Configuration configuration() throws java.io.IOException;
    Configuration reloadAgents() throws java.io.IOException;
    default Configuration saveAgent(long revision, AgentDefinition definition, boolean creating) { throw new UnsupportedOperationException(); }
    default String sourceText(String id) throws java.io.IOException { throw new UnsupportedOperationException(); }
    default Configuration saveSource(long revision, ContextSource source, String text, boolean creating) throws java.io.IOException { throw new UnsupportedOperationException(); }
    default Configuration saveInference(long revision, LocalInferenceSettings inference) { throw new UnsupportedOperationException(); }
}
