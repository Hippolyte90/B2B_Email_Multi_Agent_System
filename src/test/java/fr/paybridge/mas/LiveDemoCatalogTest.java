package fr.paybridge.mas;

import fr.paybridge.mas.adapters.inbound.fixtures.LiveDemoCatalog;
import fr.paybridge.mas.adapters.outbound.config.*;
import fr.paybridge.mas.adapters.outbound.persistence.*;
import fr.paybridge.mas.application.agent.AgentRegistry;
import fr.paybridge.mas.application.routing.RuleBasedRoutingStrategy;
import fr.paybridge.mas.application.usecase.*;
import fr.paybridge.mas.domain.mail.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class LiveDemoCatalogTest {
    @Test void corpusContainsFourDistinctEmailsPerBusinessCategory()throws Exception{
        var seeds=LiveDemoCatalog.load(Path.of("data/demo/live"));assertEquals(20,seeds.size());
        var counts=seeds.stream().collect(Collectors.groupingBy(LiveDemoCatalog.Seed::category,Collectors.counting()));assertEquals(Map.of("commercial",4L,"integration",4L,"sur-site",4L,"administration",4L,"general",4L),counts);
        assertEquals(20,seeds.stream().map(s->s.prototype().body()).distinct().count());
    }
    @Test void campaignUsesArrivalTimeAndUniqueSessionIdentitiesWithoutChangingContent()throws Exception{
        var seeds=LiveDemoCatalog.load(Path.of("data/demo/live"));var first=LiveDemoCatalog.campaign(seeds,new UUID(0,1),Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"),ZoneOffset.UTC),new Random(41));
        var second=LiveDemoCatalog.campaign(seeds,new UUID(0,2),Clock.fixed(Instant.parse("2026-11-13T13:00:00Z"),ZoneOffset.UTC),new Random(41));
        for(int i=0;i<20;i++){assertNotEquals(first.get(i).get().id(),second.get(i).get().id());assertEquals(first.get(i).get().body(),second.get(i).get().body());assertEquals(Instant.parse("2026-11-13T12:00:00Z"),first.get(i).get().receivedAt().toInstant());}
        assertNotEquals(seeds.stream().map(s->s.prototype().subject()).toList(),first.stream().map(s->s.get().subject()).toList());
    }
    @Test void allTwentyMessagesRouteToTheirExpectedSpecialistWithoutForcingCategory()throws Exception{
        var config=Path.of("config");var registry=new AgentRegistry(new PropertiesAgentConfigurationLoader(config).load());var conversations=new InMemoryConversationRepository();var decisions=new InMemoryRoutingDecisionRepository();
        var importer=new ImportEmailUseCase(conversations,sender->ClientScope.unknown());var routing=new RouteEmailUseCase(registry,conversations,new RuleBasedRoutingStrategy(new PropertiesRoutingPolicyLoader(config).load()),decisions,Clock.systemUTC(),UUID::randomUUID);
        var expected=Map.of("commercial","CommercialAgent","integration","IntegrationAgent","sur-site","OnsiteSupportAgent","administration","AccountAdminAgent","general","GenericAgent");var allowed=registry.snapshot().activeAgents().stream().map(a->a.id()).collect(Collectors.toSet());
        for(var seed:LiveDemoCatalog.load(Path.of("data/demo/live"))){importer.execute(seed.prototype());assertEquals(Optional.of(expected.get(seed.category())),routing.execute(seed.prototype().id(),ClientScope.unknown(),allowed).effectiveAgentId(),seed.prototype().subject());}
    }
}
