package org.traccar.publicaccess;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PublicAccessGuardTest {

    private static HttpServletRequest request(String host, String origin, String referer) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn(host);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("Referer")).thenReturn(referer);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        return request;
    }

    private static int status(PublicAccessGuard guard, HttpServletRequest request) {
        try {
            guard.check(request);
            return 200;
        } catch (WebApplicationException e) {
            return e.getResponse().getStatus();
        }
    }

    @Test
    public void testSameOriginAllowed() {
        PublicAccessGuard guard = new PublicAccessGuard(new Config());
        assertEquals(200, status(guard, request("onibus.ufpa.br", "https://onibus.ufpa.br", null)));
        assertEquals(200, status(guard, request("onibus.ufpa.br", null, "https://onibus.ufpa.br/settings")));
        assertEquals(200, status(guard, request("localhost:8082", "http://localhost:8082", null)));
        assertEquals(200, status(guard, request("onibus.ufpa.br:443", "https://ONIBUS.ufpa.br/", null)));
    }

    @Test
    public void testForeignOriginAndMissingOriginRejected() {
        PublicAccessGuard guard = new PublicAccessGuard(new Config());
        assertEquals(403, status(guard, request("onibus.ufpa.br", "https://outro-app.com", null)));
        assertEquals(403, status(guard, request("onibus.ufpa.br", null, "https://outro-app.com/mapa")));
        assertEquals(403, status(guard, request("onibus.ufpa.br", null, null))); // curl, scripts, other apps
        assertEquals(403, status(guard, request("onibus.ufpa.br", "null", null)));
    }

    @Test
    public void testRequireOriginDisabled() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_REQUIRE_ORIGIN, "false");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        assertEquals(200, status(guard, request("onibus.ufpa.br", null, null)));
        assertEquals(403, status(guard, request("onibus.ufpa.br", "https://outro-app.com", null)));
    }

    @Test
    public void testAllowedOrigins() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_ALLOWED_ORIGINS, "https://app.ufpa.br, http://localhost:5175/");
        config.setString(Keys.WEB_ORIGIN, "https://painel.ufpa.br");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        assertEquals(200, status(guard, request("api.ufpa.br", "https://app.ufpa.br", null)));
        assertEquals(200, status(guard, request("api.ufpa.br", "http://localhost:5175", null)));
        assertEquals(200, status(guard, request("api.ufpa.br", "https://painel.ufpa.br", null)));
        assertEquals(403, status(guard, request("api.ufpa.br", "https://app.ufpa.br.evil.com", null)));
    }

    @Test
    public void testWildcardDisablesOriginCheck() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_ALLOWED_ORIGINS, "*");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        assertEquals(200, status(guard, request("onibus.ufpa.br", "https://outro-app.com", null)));

        Config webOriginOnly = new Config();
        webOriginOnly.setString(Keys.WEB_ORIGIN, "*"); // CORS wildcard does not open public features
        assertEquals(403, status(new PublicAccessGuard(webOriginOnly), request("x", "https://outro-app.com", null)));
    }

    @Test
    public void testDevProxyForwardedHost() {
        PublicAccessGuard guard = new PublicAccessGuard(new Config());
        HttpServletRequest request = request("localhost:8082", "http://localhost:5175", null);
        when(request.getHeader("X-Forwarded-Host")).thenReturn("localhost:5175");
        assertEquals(200, status(guard, request));
    }

    @Test
    public void testApiKey() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_API_KEYS, "chave-parceiro-1,chave-parceiro-2");
        PublicAccessGuard guard = new PublicAccessGuard(config);

        HttpServletRequest header = request("onibus.ufpa.br", null, null);
        when(header.getHeader("X-Api-Key")).thenReturn("chave-parceiro-2");
        assertEquals(200, status(guard, header));

        HttpServletRequest query = request("onibus.ufpa.br", "https://outro-app.com", null);
        when(query.getParameter("apiKey")).thenReturn("chave-parceiro-1");
        assertEquals(200, status(guard, query));

        HttpServletRequest wrong = request("onibus.ufpa.br", null, null);
        when(wrong.getHeader("X-Api-Key")).thenReturn("chave-errada");
        assertEquals(403, status(guard, wrong));
    }

    @Test
    public void testRateLimit() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_RATE_LIMIT, "3");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        HttpServletRequest request = request("onibus.ufpa.br", "https://onibus.ufpa.br", null);
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> guard.check(request));
        }
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> guard.check(request));
        assertEquals(429, error.getResponse().getStatus());
        assertTrue(Long.parseLong(error.getResponse().getHeaderString("Retry-After")) > 0);

        // window resets after a minute; other clients are counted separately
        long now = System.currentTimeMillis();
        assertEquals(0, guard.consume("10.0.0.2", now));
        assertEquals(0, guard.consume("10.0.0.1", now + PublicAccessGuard.WINDOW_MS + 1));
    }

    @Test
    public void testRateLimitUsesProxyClientAddress() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_RATE_LIMIT, "1");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        HttpServletRequest first = request("onibus.ufpa.br", "https://onibus.ufpa.br", null);
        when(first.getHeader("X-Real-IP")).thenReturn("200.1.1.1");
        HttpServletRequest second = request("onibus.ufpa.br", "https://onibus.ufpa.br", null);
        when(second.getHeader("X-Real-IP")).thenReturn("200.1.1.2");
        assertEquals(200, status(guard, first));
        assertEquals(200, status(guard, second)); // same proxy address, different clients
        assertEquals(429, status(guard, first));
    }

    @Test
    public void testStreamLimit() {
        Config config = new Config();
        config.setString(Keys.PUBLIC_MAX_STREAMS, "2");
        PublicAccessGuard guard = new PublicAccessGuard(config);
        assertTrue(guard.acquireStream("10.0.0.1"));
        assertTrue(guard.acquireStream("10.0.0.1"));
        assertFalse(guard.acquireStream("10.0.0.1"));
        assertTrue(guard.acquireStream("10.0.0.2"));
        guard.releaseStream("10.0.0.1");
        assertTrue(guard.acquireStream("10.0.0.1"));
    }

}
