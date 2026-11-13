package fr.paybridge.mas.adapters.outbound.gmail;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Le seul réseau de ces tests est le rappel OAuth loopback ; Google et le navigateur sont remplacés. */
class GoogleDesktopOAuthHttpIT {
    final GoogleDesktopOAuth.Credentials credentials=new GoogleDesktopOAuth.Credentials("unit.apps.googleusercontent.com","SYNTHETIC_SECRET");
    final HttpClient http=HttpClient.newHttpClient();
    int callback(URI authorization,String query){
        try{var params=GoogleDesktopOAuth.query(authorization.getRawQuery());return http.send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri")+"?"+query)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void codeFlowUsesStatePkceLoopbackAndNeverPersistsTokens()throws Exception {
        var captured=new AtomicReference<Map<String,String>>();var auth=new AtomicReference<URI>();
        var oauth=GoogleDesktopOAuth.authorize(credentials,uri->{auth.set(uri);var q=GoogleDesktopOAuth.query(uri.getRawQuery());assertEquals(200,callback(uri,"state="+q.get("state")+"&code=synthetic-code"));},Duration.ofSeconds(3),fields->{captured.set(fields);return JsonParser.parseString("{\"access_token\":\"SYNTHETIC_ACCESS\",\"refresh_token\":\"SYNTHETIC_REFRESH\",\"expires_in\":3600}").getAsJsonObject();},Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        assertEquals("SYNTHETIC_ACCESS",oauth.token());assertFalse(oauth.toString().contains("SYNTHETIC"));var q=GoogleDesktopOAuth.query(auth.get().getRawQuery());assertEquals("accounts.google.com",auth.get().getHost());assertTrue(q.get("redirect_uri").startsWith("http://127.0.0.1:"));assertEquals("S256",q.get("code_challenge_method"));
        assertEquals(q.get("code_challenge"),Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(captured.get().get("code_verifier").getBytes(StandardCharsets.US_ASCII))));assertEquals("authorization_code",captured.get().get("grant_type"));
    }
    @Test void wrongStateNeverExchangesAuthorizationCode(){var exchanges=new AtomicInteger();assertThrows(GmailFailure.class,()->GoogleDesktopOAuth.authorize(credentials,uri->assertEquals(400,callback(uri,"state=wrong&code=synthetic")),Duration.ofMillis(100),fields->{exchanges.incrementAndGet();return new com.google.gson.JsonObject();},Clock.systemUTC()));assertEquals(0,exchanges.get());}
    @Test void userRefusalNeverExchangesTokens(){var exchanges=new AtomicInteger();assertThrows(GmailFailure.class,()->GoogleDesktopOAuth.authorize(credentials,uri->{var q=GoogleDesktopOAuth.query(uri.getRawQuery());assertEquals(400,callback(uri,"state="+q.get("state")+"&error=access_denied"));},Duration.ofSeconds(2),fields->{exchanges.incrementAndGet();return new com.google.gson.JsonObject();},Clock.systemUTC()));assertEquals(0,exchanges.get());}
    @Test void browserFailureDoesNotLeaveAnAuthorizationInProgress(){assertThrows(GmailFailure.class,()->GoogleDesktopOAuth.authorize(credentials,uri->{throw new IllegalStateException("SYNTHETIC");},Duration.ofMillis(100),fields->new com.google.gson.JsonObject(),Clock.systemUTC()));}
    @Test void expiredAccessTokenRefreshesInMemoryWithoutOpeningBrowserAgain()throws Exception {
        var now=new AtomicReference<>(Instant.EPOCH);var calls=new AtomicInteger();Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var oauth=GoogleDesktopOAuth.authorize(credentials,uri->{var q=GoogleDesktopOAuth.query(uri.getRawQuery());assertEquals(200,callback(uri,"state="+q.get("state")+"&code=synthetic"));},Duration.ofSeconds(2),fields->{int n=calls.incrementAndGet();if(n==2){assertEquals("refresh_token",fields.get("grant_type"));assertEquals("SYNTHETIC_REFRESH",fields.get("refresh_token"));}return JsonParser.parseString("{\"access_token\":\"ACCESS_"+n+"\",\"refresh_token\":\"SYNTHETIC_REFRESH\",\"expires_in\":120}").getAsJsonObject();},clock);
        assertEquals("ACCESS_1",oauth.token());now.set(Instant.EPOCH.plusSeconds(61));assertEquals("ACCESS_2",oauth.token());assertEquals(2,calls.get());
    }
}
