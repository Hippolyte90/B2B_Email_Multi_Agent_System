package fr.paybridge.mas.adapters.outbound.gmail;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/** Origine Google fixée ; aucun suivi de redirection ni nouvelle tentative implicite. */
public final class GmailHttpTransport implements GmailTransport {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Supplier<String> token;
    public GmailHttpTransport(Supplier<String> token) { this.token = token; }
    public JsonObject request(String method, String path, JsonObject body) {
        if (!path.startsWith("/") || path.contains(":") || path.contains("..") || path.contains("#")) { throw new IllegalArgumentException("Chemin Gmail invalide"); }
        try {
            var request = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com/gmail/v1/users/me" + path)).timeout(Duration.ofSeconds(45)).header("Authorization", "Bearer " + token.get()).header("Accept", "application/json");
            if (body != null) { request.header("Content-Type", "application/json; charset=UTF-8"); }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) { throw new GmailFailure(response.statusCode()); }
            return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (GmailFailure known) { throw known; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new GmailFailure(0); }
        catch (Exception failure) { throw new GmailFailure(0); }
    }
}
