package fr.paybridge.mas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.paybridge.mas.adapters.outbound.llm.*;
import fr.paybridge.mas.application.generation.*;
import fr.paybridge.mas.domain.context.ContextBundle;
import fr.paybridge.mas.domain.generation.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OllamaLocalHttpIT {
    private HttpServer server;
    private ExecutorService workers;
    private final AtomicReference<String> metadata = new AtomicReference<>("""
            {"details":{"format":"gguf"},"model_info":{"general.architecture":"test","test.context_length":8192}}
            """);
    private final AtomicReference<String> response = new AtomicReference<>("""
            {"model":"test-model","done":true,"done_reason":"stop","message":{"role":"assistant","content":"Proposition synthétique"},"prompt_eval_count":20,"eval_count":5}
            """);
    private final AtomicReference<JsonObject> chatRequest = new AtomicReference<>();
    private final AtomicReference<JsonObject> showRequest = new AtomicReference<>();
    private final AtomicInteger chatCalls = new AtomicInteger();
    private volatile int showStatus = 200;
    private volatile int chatStatus = 200;
    private volatile CountDownLatch blockChat;
    private final CountDownLatch chatEntered = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/api/show", e -> {
            showRequest.set(JsonParser.parseString(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            send(e, showStatus, metadata.get());
        });
        server.createContext("/api/chat", e -> {
            chatCalls.incrementAndGet();
            chatRequest.set(JsonParser.parseString(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            chatEntered.countDown();
            try {
                if (blockChat != null) { blockChat.await(3, TimeUnit.SECONDS); }
                send(e, chatStatus, response.get());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* Le client peut annuler sa connexion. */ }
            finally { e.close(); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (blockChat != null) { blockChat.countDown(); }
        if (server != null) { server.stop(0); }
        if (workers != null) { workers.close(); }
    }

    private static void send(HttpExchange exchange, int status, String text) throws IOException {
        if (status >= 300 && status < 400) { exchange.getResponseHeaders().add("Location", "https://example.com/"); }
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private OllamaLocalLlmGateway gateway() {
        return new OllamaLocalLlmGateway(new LoopbackEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
    }

    private GenerationRequest request(Duration timeout) {
        var context = new ContextBundle("Instructions de test", "Données synthétiques UTF-8 : échec", List.of(), List.of(), Optional.empty(), List.of(),
                new ContextBundle.Usage("test", true, 100, 64, 4096));
        return new GenerationRequest(UUID.randomUUID(), context, new GenerationSettings("test-model", 4096, 64, 0.2, 42, timeout));
    }

    @Test
    void serializesRolesParametersAndReadsCorrelatedReply() throws Exception {
        var request = request(Duration.ofSeconds(5));
        var reply = gateway().generate(request, new CancellationSignal());
        assertEquals(request.taskId(), reply.taskId());
        assertFalse(reply.simulated());
        assertEquals("Proposition synthétique", reply.text());
        assertEquals(20, reply.inputTokens().orElseThrow());
        assertEquals(request.context().systemInstructions(), chatRequest.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", chatRequest.get().getAsJsonArray("messages").get(1).getAsJsonObject().get("role").getAsString());
        assertFalse(chatRequest.get().get("stream").getAsBoolean());
        assertEquals(4096, chatRequest.get().getAsJsonObject("options").get("num_ctx").getAsInt());
        assertEquals("30m",chatRequest.get().get("keep_alive").getAsString());
        assertEquals(1.15,chatRequest.get().getAsJsonObject("options").get("repeat_penalty").getAsDouble());
        assertEquals(256,chatRequest.get().getAsJsonObject("options").get("repeat_last_n").getAsInt());
        assertEquals(64, chatRequest.get().getAsJsonObject("options").get("num_predict").getAsInt());
        assertEquals(1, showRequest.get().size());
        assertFalse(showRequest.get().toString().contains("Données"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not json", "{\"done\":true,\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"\"}}",
            "{\"done\":false,\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"Incomplet\"}}",
            "{\"done\":true,\"done_reason\":\"length\",\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"Tronqué\"}}"})
    void rejectsMalformedEmptyOrIncompleteResponse(String value) {
        response.set(value);
        var error = assertThrows(GenerationException.class, () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal()));
        assertEquals(value.contains("length") ? GenerationFailure.INCOMPLETE_RESPONSE : GenerationFailure.INVALID_RESPONSE, error.code());
    }

    @Test
    void refusesRemoteModelBeforeSendingContext() {
        metadata.set("{\"remote_host\":\"https://remote.invalid\",\"details\":{\"format\":\"gguf\"}}");
        var error = assertThrows(GenerationException.class, () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal()));
        assertEquals(GenerationFailure.MODEL_NOT_LOCAL, error.code());
        assertEquals(0, chatCalls.get());
    }

    @Test
    void refusesContextCapacityLargerThanModelBeforeSendingContext() {
        metadata.set(metadata.get().replace("8192", "2048"));
        var error = assertThrows(GenerationException.class, () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal()));
        assertEquals(GenerationFailure.CONTEXT_LIMIT, error.code());
        assertEquals(0, chatCalls.get());
    }

    @Test
    void refusesRedirectsInPreflightAndChatWithoutFollowingThem() {
        showStatus = 302;
        assertEquals(GenerationFailure.MODEL_NOT_LOCAL, assertThrows(GenerationException.class,
                () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal())).code());
        assertEquals(0, chatCalls.get());
        showStatus = 200;
        chatStatus = 307;
        assertEquals(GenerationFailure.MODEL_NOT_LOCAL, assertThrows(GenerationException.class,
                () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal())).code());
    }

    @Test
    void engineErrorDoesNotLeakResponseBodyAndIsNotRetried() {
        chatStatus = 500;
        response.set("SECRET_ERROR_BODY");
        var error = assertThrows(GenerationException.class, () -> gateway().generate(request(Duration.ofSeconds(5)), new CancellationSignal()));
        assertEquals(GenerationFailure.UNAVAILABLE, error.code());
        assertFalse(error.getMessage().contains("SECRET"));
        assertEquals(1, chatCalls.get());
    }

    @Test
    void timesOutWithNoDraft() {
        blockChat = new CountDownLatch(1);
        var error = assertThrows(GenerationException.class, () -> gateway().generate(request(Duration.ofMillis(250)), new CancellationSignal()));
        assertEquals(GenerationFailure.TIMEOUT, error.code());
    }

    @Test
    void cancellationAbortsPendingHttpCall() throws Exception {
        blockChat = new CountDownLatch(1);
        var signal = new CancellationSignal();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> {
                try { gateway().generate(request(Duration.ofSeconds(5)), signal); return null; }
                catch (GenerationException error) { return error.code(); }
            });
            assertTrue(chatEntered.await(3, TimeUnit.SECONDS));
            signal.cancel();
            assertEquals(GenerationFailure.CANCELLED, result.get(2, TimeUnit.SECONDS));
        }
    }
    @Test void preloadsWithoutPromptOrEmailAndKeepsModelResident()throws Exception{
        response.set("{\"model\":\"test-model\",\"done\":true,\"done_reason\":\"load\"}");
        gateway().prepare(request(Duration.ofSeconds(5)).settings(),new CancellationSignal());
        var body=chatRequest.get();assertTrue(body.getAsJsonArray("messages").isEmpty());assertFalse(body.has("prompt"));
        assertEquals("30m",body.get("keep_alive").getAsString());assertEquals(4096,body.getAsJsonObject("options").get("num_ctx").getAsInt());
        assertEquals(1,showRequest.get().size());assertEquals(1,chatCalls.get());assertFalse(body.toString().contains("Données"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","not json","{\"model\":\"other\",\"done\":true}","{\"model\":\"test-model\",\"done\":false}","{\"model\":\"test-model\",\"done\":true,\"done_reason\":\"unload\"}"})
    void rejectsInvalidLoadAcknowledgement(String value){response.set(value);assertEquals(GenerationFailure.INVALID_RESPONSE,assertThrows(GenerationException.class,()->gateway().prepare(request(Duration.ofSeconds(5)).settings(),new CancellationSignal())).code());}
    @Test void refusesRemoteModelBeforeLoading(){metadata.set("{\"remote_model\":\"test\"}");assertEquals(GenerationFailure.MODEL_NOT_LOCAL,assertThrows(GenerationException.class,()->gateway().prepare(request(Duration.ofSeconds(5)).settings(),new CancellationSignal())).code());assertEquals(0,chatCalls.get());}
    @Test void loadingTimeoutDoesNotRetry(){blockChat=new CountDownLatch(1);assertEquals(GenerationFailure.TIMEOUT,assertThrows(GenerationException.class,()->gateway().prepare(request(Duration.ofMillis(300)).settings(),new CancellationSignal())).code());assertTrue(chatCalls.get()<=1);}
    @Test void cancellationAbortsModelLoading()throws Exception{
        blockChat=new CountDownLatch(1);var signal=new CancellationSignal();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var result=executor.submit(()->{try{gateway().prepare(request(Duration.ofSeconds(5)).settings(),signal);return null;}catch(GenerationException failure){return failure.code();}});
            assertTrue(chatEntered.await(3,TimeUnit.SECONDS));signal.cancel();assertEquals(GenerationFailure.CANCELLED,result.get(2,TimeUnit.SECONDS));
        }
    }

}
