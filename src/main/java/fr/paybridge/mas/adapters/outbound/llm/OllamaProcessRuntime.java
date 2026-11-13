package fr.paybridge.mas.adapters.outbound.llm;

import com.google.gson.JsonParser;
import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.domain.generation.GenerationFailure;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** Processus sans shell ; seules ses variables d’environnement sont modifiées. */
final class OllamaProcessRuntime implements ManagedOllamaLoader.Runtime {
    private final Path root;
    private final LoopbackEndpoint endpoint;
    private final HttpClient client;
    private Process owned;
    OllamaProcessRuntime(Path root,LoopbackEndpoint endpoint){
        this.root=root.toAbsolutePath().normalize();this.endpoint=endpoint;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException error){}}).build();
    }
    @Override public boolean ready(){
        try{
            var request=HttpRequest.newBuilder(endpoint.base().resolve("api/version")).timeout(Duration.ofSeconds(1)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>4096){return false;}
            var version=JsonParser.parseString(response.body()).getAsJsonObject().get("version");
            return version!=null&&version.isJsonPrimitive()&&version.getAsJsonPrimitive().isString()&&!version.getAsString().isBlank();
        }catch(InterruptedException failure){Thread.currentThread().interrupt();return false;}
        catch(Exception failure){return false;}
    }
    @Override public void start()throws GenerationException{
        if(alive()){return;}
        var executable=findExecutable(System.getenv(),System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        try{
            var models=root.resolve("data/runtime/ollama-models");Files.createDirectories(models);
            var builder=new ProcessBuilder(executable.toString(),"serve").directory(root.toFile());
            builder.environment().putAll(environment(endpoint,models));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            owned=builder.start();
        }catch(IOException|SecurityException failure){throw new GenerationException(GenerationFailure.ENGINE_START_FAILED);}
    }
    static Map<String,String> environment(LoopbackEndpoint endpoint,Path models){
        return Map.of("OLLAMA_HOST",endpoint.base().getRawAuthority(),"OLLAMA_MODELS",models.toAbsolutePath().normalize().toString(),"OLLAMA_NO_CLOUD","1","OLLAMA_DEBUG","0");
    }
    static Path findExecutable(Map<String,String> environment,boolean windows)throws GenerationException{
        var name=windows?"ollama.exe":"ollama";var candidates=new ArrayList<Path>();
        var path=environment.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("PATH")).map(Map.Entry::getValue).findFirst().orElse("");
        for(var entry:path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))){if(!entry.isBlank()){candidates.add(Path.of(entry).resolve(name));}}
        var local=environment.get("LOCALAPPDATA");if(windows&&local!=null){candidates.add(Path.of(local,"Programs","Ollama",name));}
        for(var candidate:candidates){if(Files.isRegularFile(candidate)&&(windows||Files.isExecutable(candidate))){return candidate.toAbsolutePath().normalize();}}
        throw new GenerationException(GenerationFailure.ENGINE_NOT_INSTALLED);
    }
    @Override public boolean alive(){return owned!=null&&owned.isAlive();}
    @Override public void stop(){if(alive()){owned.destroy();}}
    @Override public void pause()throws InterruptedException{Thread.sleep(200);}
    @Override public long nanoTime(){return System.nanoTime();}
}
