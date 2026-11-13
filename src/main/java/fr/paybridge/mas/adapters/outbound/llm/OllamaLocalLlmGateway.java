package fr.paybridge.mas.adapters.outbound.llm;

import com.google.gson.*;
import fr.paybridge.mas.application.generation.CancellationSignal;
import fr.paybridge.mas.application.generation.GenerationException;
import fr.paybridge.mas.application.port.LocalLlmGateway;
import fr.paybridge.mas.domain.generation.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.*;

/** Vérification du modèle local puis génération Ollama, sans repli distant ni relance automatique. */
public final class OllamaLocalLlmGateway implements LocalLlmGateway, fr.paybridge.mas.application.port.LocalModelLoader {
    private final LoopbackEndpoint endpoint;
    private final HttpClient client;
    private final Gson json = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public OllamaLocalLlmGateway(LoopbackEndpoint endpoint) {
        this.endpoint = endpoint;
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    @Override
    public GeneratedReply generate(GenerationRequest request, CancellationSignal cancellation) throws GenerationException {
        cancellation.check();
        var settings = request.settings();
        var model = settings.model();
        long started = System.nanoTime();
        long deadline = started + settings.timeout().toNanos();
        try {
            preflight(settings, deadline, cancellation);
            var body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            body.addProperty("keep_alive", "30m");
            var messages = new JsonArray();
            messages.add(json.toJsonTree(java.util.Map.of("role", "system", "content", request.context().systemInstructions())));
            messages.add(json.toJsonTree(java.util.Map.of("role", "user", "content", request.context().userData())));
            body.add("messages", messages);
            body.add("options", json.toJsonTree(java.util.Map.of("num_ctx", settings.contextCapacity(), "num_predict", settings.outputLimit(),
                    "temperature", settings.temperature(), "seed", settings.seed(), "repeat_penalty", 1.15, "repeat_last_n", 256)));
            var response = post("api/chat", json.toJson(body), deadline, cancellation);
            cancellation.check();
            if (!response.has("done") || !response.get("done").isJsonPrimitive() || !response.getAsJsonPrimitive("done").isBoolean()
                    || !response.get("done").getAsBoolean() || !string(response, "model").equals(model)) {
                throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
            }
            if (response.has("done_reason") && !string(response, "done_reason").equals("stop")) {
                throw new GenerationException(GenerationFailure.INCOMPLETE_RESPONSE);
            }
            var message = response.getAsJsonObject("message");
            if (!string(message, "role").equals("assistant")) { throw new GenerationException(GenerationFailure.INVALID_RESPONSE); }
            var input = count(response, "prompt_eval_count");
            var output = count(response, "eval_count");
            if ((input.isPresent() && input.getAsLong() > (long) settings.contextCapacity() - settings.outputLimit())
                    || (output.isPresent() && output.getAsLong() > settings.outputLimit())) {
                throw new GenerationException(GenerationFailure.CONTEXT_LIMIT);
            }
            return new GeneratedReply(request.taskId(), string(message, "content"), model, false,
                    Duration.ofNanos(System.nanoTime() - started), input, output);
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException | ArithmeticException error) {
            throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
        }
    }

    /** Précharge sans email ni prompt ; la fin de cet appel autorise le démarrage du flux. */
    @Override public void prepare(GenerationSettings settings, CancellationSignal cancellation) throws GenerationException {
        cancellation.check();
        long deadline=System.nanoTime()+settings.timeout().toNanos();
        try {
            preflight(settings,deadline,cancellation);
            var body=new JsonObject();body.addProperty("model",settings.model());body.addProperty("stream",false);
            body.addProperty("keep_alive","30m");body.add("messages",new JsonArray());
            body.add("options",json.toJsonTree(java.util.Map.of("num_ctx",settings.contextCapacity())));
            var response=post("api/chat",json.toJson(body),deadline,cancellation);
            cancellation.check();
            if(!response.has("done")||!response.get("done").isJsonPrimitive()||!response.getAsJsonPrimitive("done").isBoolean()
                    ||!response.get("done").getAsBoolean()||!string(response,"model").equals(settings.model())
                    ||response.has("done_reason")&&!string(response,"done_reason").equals("load")) {
                throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
            }
        } catch(JsonParseException|IllegalArgumentException|IllegalStateException|NullPointerException|ArithmeticException failure){
            throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
        }
    }
    private void preflight(GenerationSettings settings,long deadline,CancellationSignal cancellation) throws GenerationException {
        var model=settings.model();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*") || model.contains("://") || model.toLowerCase(java.util.Locale.ROOT).contains("cloud")) {
            throw new GenerationException(GenerationFailure.MODEL_NOT_LOCAL);
        }
            // Aucune donnée métier dans cet appel. La présence d'un endpoint local seule ne suffit pas.
            var show = post("api/show", json.toJson(java.util.Map.of("model", model)), deadline, cancellation);
            if (show.has("remote_host") || show.has("remote_model")
                    || !string(show.getAsJsonObject("details"), "format").equals("gguf")) {
                throw new GenerationException(GenerationFailure.MODEL_NOT_LOCAL);
            }
            var info = show.getAsJsonObject("model_info");
            var architecture = string(info, "general.architecture");
            if (!info.has(architecture + ".context_length") || info.get(architecture + ".context_length").getAsLong() < settings.contextCapacity()) {
                throw new GenerationException(GenerationFailure.CONTEXT_LIMIT);
            }
    }

    private JsonObject post(String path, String body, long deadline, CancellationSignal cancellation) throws GenerationException {
        cancellation.check();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new GenerationException(GenerationFailure.TIMEOUT); }
        var request = HttpRequest.newBuilder(endpoint.base().resolve(path)).timeout(Duration.ofNanos(remaining))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try (var registration = cancellation.onCancel(() -> pending.cancel(true))) {
            var response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new GenerationException(GenerationFailure.MODEL_NOT_LOCAL);
            }
            if (response.statusCode() != 200) {
                throw new GenerationException(response.statusCode() == 400 ? GenerationFailure.CONTEXT_LIMIT : GenerationFailure.UNAVAILABLE);
            }
            if (response.body().length() > 1_000_000) { throw new GenerationException(GenerationFailure.INVALID_RESPONSE); }
            var parsed = json.fromJson(response.body(), JsonObject.class);
            if (parsed == null || parsed.has("error")) { throw new GenerationException(GenerationFailure.INVALID_RESPONSE); }
            return parsed;
        } catch (TimeoutException error) {
            pending.cancel(true);
            throw new GenerationException(GenerationFailure.TIMEOUT);
        } catch (CancellationException error) {
            throw new GenerationException(GenerationFailure.CANCELLED);
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new GenerationException(GenerationFailure.CANCELLED);
        } catch (ExecutionException error) {
            if (cancellation.cancelled()) { throw new GenerationException(GenerationFailure.CANCELLED); }
            throw new GenerationException(error.getCause() instanceof HttpTimeoutException ? GenerationFailure.TIMEOUT : GenerationFailure.UNAVAILABLE);
        } catch (GenerationException error) {
            throw error;
        } catch (Exception error) {
            throw new GenerationException(GenerationFailure.INVALID_RESPONSE);
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Champ texte absent ou invalide");
        }
        var text = object.get(key).getAsString();
        if (text.isBlank()) { throw new IllegalArgumentException("Champ texte vide"); }
        return text;
    }

    private static OptionalLong count(JsonObject object, String key) {
        if (!object.has(key)) { return OptionalLong.empty(); }
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Compteur invalide");
        }
        return OptionalLong.of(object.get(key).getAsBigDecimal().longValueExact());
    }
}
