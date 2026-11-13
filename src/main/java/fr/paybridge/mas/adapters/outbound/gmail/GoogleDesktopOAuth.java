package fr.paybridge.mas.adapters.outbound.gmail;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** OAuth bureau : PKCE, state, navigateur système et rappel loopback éphémère. Jetons en mémoire seulement. */
public final class GoogleDesktopOAuth {
    public record Credentials(String clientId, String clientSecret) {
        public Credentials {
            if (clientId == null || !clientId.endsWith(".apps.googleusercontent.com") || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) { throw new IllegalArgumentException("Client OAuth bureau Google invalide"); }
        }
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Credentials credentials;
    private final java.util.function.Function<Map<String,String>,JsonObject> tokenEndpoint;
    private final Clock clock;
    private String accessToken, refreshToken;
    private Instant expires = Instant.EPOCH;
    private GoogleDesktopOAuth(Credentials credentials,java.util.function.Function<Map<String,String>,JsonObject> tokenEndpoint,Clock clock) { this.credentials = credentials;this.tokenEndpoint=tokenEndpoint;this.clock=clock; }
    public static Credentials read(Path file) throws java.io.IOException {
        try {
            var installed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("installed");
            return new Credentials(installed.get("client_id").getAsString(), installed.get("client_secret").getAsString());
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Fichier OAuth Google de type bureau requis"); }
    }
    public static GoogleDesktopOAuth connect(Path credentialsFile) throws Exception {
        return authorize(read(credentialsFile), uri -> {
            try { if (!Desktop.isDesktopSupported()) { throw new GmailFailure(0); } Desktop.getDesktop().browse(uri); }
            catch (Exception failure) { throw new GmailFailure(0); }
        }, Duration.ofMinutes(3));
    }
    static GoogleDesktopOAuth authorize(Credentials credentials, Consumer<URI> browser, Duration timeout) throws Exception {
        return authorize(credentials,browser,timeout,null,Clock.systemUTC());
    }
    static GoogleDesktopOAuth authorize(Credentials credentials,Consumer<URI> browser,Duration timeout,java.util.function.Function<Map<String,String>,JsonObject> tokenEndpoint,Clock clock) throws Exception {
        var oauth = new GoogleDesktopOAuth(credentials,tokenEndpoint,clock);
        String state = random(), verifier = random();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var code = new CompletableFuture<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/callback";
        server.createContext("/oauth2/callback", exchange -> {
            int status = 400;
            try {
                var values = query(exchange.getRequestURI().getRawQuery());
                if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/oauth2/callback") && state.equals(values.get("state"))) {
                    if (values.containsKey("error")) { code.completeExceptionally(new GmailFailure(403)); }
                    else if (values.getOrDefault("code", "").length() > 0 && !code.isDone()) { code.complete(values.get("code")); status = 200; }
                }
                byte[] body = (status == 200 ? "Autorisation recue. Vous pouvez revenir dans PayBridge." : "Autorisation refusee ou rappel invalide.").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            browser.accept(URI.create("https://accounts.google.com/o/oauth2/v2/auth?" + form(Map.of(
                    "client_id", credentials.clientId(), "redirect_uri", redirect, "response_type", "code", "scope", "https://www.googleapis.com/auth/gmail.modify",
                    "state", state, "code_challenge", challenge, "code_challenge_method", "S256", "access_type", "offline", "prompt", "consent"))));
            var authorizationCode = code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            oauth.exchange(Map.of("client_id", credentials.clientId(), "client_secret", credentials.clientSecret(), "grant_type", "authorization_code", "code", authorizationCode, "redirect_uri", redirect, "code_verifier", verifier));
            return oauth;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new GmailFailure(0); }
        catch (Exception failure) { throw new GmailFailure(0); }
        finally { server.stop(0); }
    }
    public synchronized String token() {
        if (accessToken == null || !clock.instant().isBefore(expires)) {
            if (refreshToken == null) { throw new GmailFailure(401); }
            exchange(Map.of("client_id", credentials.clientId(), "client_secret", credentials.clientSecret(), "grant_type", "refresh_token", "refresh_token", refreshToken));
        }
        return accessToken;
    }
    private void exchange(Map<String,String> fields) {
        try {
            JsonObject json;
            if(tokenEndpoint!=null){json=tokenEndpoint.apply(Map.copyOf(fields));}
            else {
            var request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) { throw new GmailFailure(response.statusCode()); }
            json = JsonParser.parseString(response.body()).getAsJsonObject();
            }
            accessToken = json.get("access_token").getAsString();
            if (accessToken.isBlank()) { throw new GmailFailure(401); }
            if (json.has("refresh_token")) { refreshToken = json.get("refresh_token").getAsString(); }
            expires = clock.instant().plusSeconds(Math.max(1, json.get("expires_in").getAsLong() - 60));
        } catch (GmailFailure known) { throw known; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new GmailFailure(0); }
        catch (Exception failure) { throw new GmailFailure(0); }
    }
    static Map<String,String> query(String raw) {
        var values = new HashMap<String,String>();
        if (raw != null) { for (var field : raw.split("&")) { var pair = field.split("=",2); if (pair.length == 2) { var key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8); if(values.putIfAbsent(key,URLDecoder.decode(pair[1],StandardCharsets.UTF_8))!=null){return Map.of();} } } }
        return values;
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String form(Map<String,String> fields) { return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&")); }
    @Override public String toString() { return "GoogleDesktopOAuth[tokens redacted]"; }
}
