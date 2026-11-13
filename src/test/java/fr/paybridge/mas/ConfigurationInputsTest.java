package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.presentation.ConfigurationInputs.*;
import fr.paybridge.mas.domain.context.ContextSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationInputsTest {
    @Test void agentFormParsesSetsAndPreservesPromptExactly() {
        var input=new AgentInput("Tech","Rôle","sdk, technical","context/public.md","Prompt\nexact",true,false);
        var value=input.value(); assertEquals(2,value.categories().size()); assertEquals("Prompt\nexact",value.systemPrompt()); assertEquals(input,AgentInput.of(value));
    }
    @Test void duplicateAndBlankCategoriesAreRefused() {
        assertThrows(IllegalArgumentException.class,()->new AgentInput("Tech","Rôle","x,x","","Prompt",true,false).value());
        assertThrows(IllegalArgumentException.class,()->new AgentInput("Tech","Rôle","","","Prompt",true,false).value());
    }
    @Test void blankRoleOrPromptAreRefused() {
        assertThrows(IllegalArgumentException.class,()->new AgentInput("Tech","","x","","Prompt",true,false).value());
        assertThrows(IllegalArgumentException.class,()->new AgentInput("Tech","Rôle","x",""," ",true,false).value());
    }
    @Test void publicSourceCannotCarryClientScope() {
        assertThrows(IllegalArgumentException.class,()->new SourceInput("s","context/s.md","Provenance","client-a","",ContextSource.Visibility.PUBLIC_APPROVED,"Tech","","Texte").value());
    }
    @Test void clientSourceRequiresRecipientsAndProjectRequiresClient() {
        assertThrows(IllegalArgumentException.class,()->new SourceInput("s","context/s.md","P","client-a","",ContextSource.Visibility.CLIENT_SHAREABLE,"Tech","","Texte").value());
        assertThrows(IllegalArgumentException.class,()->new SourceInput("s","context/s.md","P","","project-a",ContextSource.Visibility.INTERNAL_ONLY,"Tech","","Texte").value());
    }
    @Test void clientSourceAndRecipientsAreParsedExactly() {
        var input=new SourceInput("s","context/s.md","P","client-a","project-a",ContextSource.Visibility.CLIENT_SHAREABLE,"Tech","alice@client-a.example","Texte");
        var value=input.value(); assertEquals("client-a",value.scope().clientId().orElseThrow()); assertEquals(input,SourceInput.of(value,"Texte"));
    }
    @Test void sourceTextAndAgentListAreRequired() {
        assertThrows(IllegalArgumentException.class,()->new SourceInput("s","context/s.md","P","","",ContextSource.Visibility.PUBLIC_APPROVED,"Tech","","").value());
        assertThrows(IllegalArgumentException.class,()->new SourceInput("s","context/s.md","P","","",ContextSource.Visibility.PUBLIC_APPROVED,"","","Texte").value());
    }
    @Test void inferenceRoundTripsAndRejectsBadNumbersOrRemoteEndpoints() {
        var input=new InferenceInput("http://127.0.0.1:11435/","local-model","8192","1024","0.2","42","120","128");
        assertEquals(input,InferenceInput.of(input.value()));
        assertThrows(IllegalArgumentException.class,()->new InferenceInput("http://example.com:11435/","m","8192","1024","0.2","42","120","128").value());
        assertThrows(IllegalArgumentException.class,()->new InferenceInput(input.endpoint(),"m","NaN","1024","0.2","42","120","128").value());
    }
}
