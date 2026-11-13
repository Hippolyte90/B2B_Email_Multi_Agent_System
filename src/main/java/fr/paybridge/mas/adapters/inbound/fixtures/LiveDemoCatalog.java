package fr.paybridge.mas.adapters.inbound.fixtures;

import fr.paybridge.mas.application.usecase.ImportEmailCommand;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Corpus synthétique externe ; la catégorie du répertoire n'est jamais imposée au routeur. */
public final class LiveDemoCatalog {
    public record Seed(String category,ImportEmailCommand prototype) { }
    private LiveDemoCatalog(){}
    public static List<Seed> load(Path root)throws IOException{
        var result=new ArrayList<Seed>();var ids=new HashSet<String>();var reader=new PropertiesEmailReader();
        try(var files=Files.walk(root,2)){
            for(var file:files.filter(p->p.toString().endsWith(".properties")).sorted().toList()){
                if(file.getParent().equals(root)){throw new IllegalArgumentException("Répertoire de catégorie requis");}
                var command=reader.read(file);if(!ids.add(command.id())){throw new IllegalArgumentException("Identifiant de modèle dupliqué");}
                result.add(new Seed(file.getParent().getFileName().toString(),command));
            }
        }
        if(result.isEmpty()){throw new IllegalArgumentException("Corpus vide");}return List.copyOf(result);
    }
    public static List<Supplier<ImportEmailCommand>> campaign(List<Seed> seeds,UUID session,Clock clock,RandomGenerator random){
        var shuffled=new ArrayList<>(seeds);
        for(int i=shuffled.size()-1;i>0;i--){Collections.swap(shuffled,i,random.nextInt(i+1));}
        return shuffled.stream().<Supplier<ImportEmailCommand>>map(seed->{var source=seed.prototype();return ()->new ImportEmailCommand(
                "demo-live-"+session+"-"+source.id(),"demo-live-"+session+"-"+source.conversationId(),source.sender(),source.recipients(),source.subject(),source.body(),clock.instant().atOffset(ZoneOffset.UTC));}).toList();
    }
}
