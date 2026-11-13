package fr.paybridge.mas.adapters.outbound.gmail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GoogleDesktopOAuthTest {
    @TempDir Path directory;
    @Test void readsDesktopCredentialsWithoutHonoringExternalEndpoints()throws Exception {
        var file=directory.resolve("oauth.json");Files.writeString(file,"{\"installed\":{\"client_id\":\"unit.apps.googleusercontent.com\",\"client_secret\":\"SYNTHETIC_SECRET\",\"token_uri\":\"https://attacker.invalid/\"}}");
        var credentials=GoogleDesktopOAuth.read(file);assertEquals("unit.apps.googleusercontent.com",credentials.clientId());assertFalse(credentials.toString().contains("SYNTHETIC_SECRET"));
    }
    @Test void webOrEmptyCredentialsAreRejectedBeforeBrowser()throws Exception {
        var file=directory.resolve("oauth.json");Files.writeString(file,"{\"web\":{\"client_secret\":\"SYNTHETIC_SECRET\"}}");assertFalse(assertThrows(IllegalArgumentException.class,()->GoogleDesktopOAuth.read(file)).toString().contains("SYNTHETIC_SECRET"));
        assertThrows(IllegalArgumentException.class,()->new GoogleDesktopOAuth.Credentials("",""));
    }
    @Test void queryDecodesStateButRejectsDuplicateKeys(){assertEquals("a b",GoogleDesktopOAuth.query("state=a+b&code=c%2Bd").get("state"));assertTrue(GoogleDesktopOAuth.query("state=good&state=bad").isEmpty());}
    @Test void invalidTransportPathsAreRejectedBeforeAccessingTokens(){var reads=new AtomicInteger();var transport=new GmailHttpTransport(()->{reads.incrementAndGet();return "SYNTHETIC_TOKEN";});assertThrows(IllegalArgumentException.class,()->transport.request("GET","https://attacker.invalid",null));assertThrows(IllegalArgumentException.class,()->transport.request("GET","/../profile",null));assertEquals(0,reads.get());}
    @Test void externalMailboxErrorsRemainSanitized(){var failure=new GmailFailure(403);assertEquals(fr.paybridge.mas.application.workspace.MailboxFailure.Reason.ACCESS_DENIED,failure.reason());assertFalse(failure.toString().contains("token"));}
}
