package org.traccar.api;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CorsResponseFilterTest {

    private static Object allowOrigin(String configured, String origin) throws IOException {
        Config config = new Config();
        if (configured != null) {
            config.setString(Keys.WEB_ORIGIN, configured);
        }
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getHeaderString("origin")).thenReturn(origin);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getHeaders()).thenReturn(headers);
        new CorsResponseFilter(config).filter(request, response);
        return headers.getFirst("access-control-allow-origin");
    }

    @Test
    public void testListedOriginsAllowed() throws IOException {
        String configured = "http://localhost:8000, https://ceamazon.com.br/";
        assertEquals("http://localhost:8000", allowOrigin(configured, "http://localhost:8000"));
        assertEquals("https://ceamazon.com.br", allowOrigin(configured, "https://ceamazon.com.br"));
    }

    @Test
    public void testPartialMatchRejected() throws IOException {
        String configured = "http://localhost:8000,https://ceamazon.com.br";
        assertNull(allowOrigin(configured, "https://ceamazon.com"));
        assertNull(allowOrigin(configured, "http://localhost:80"));
        assertNull(allowOrigin(configured, "http://localhost"));
        assertNull(allowOrigin(configured, "https://evil.com.br"));
    }

    @Test
    public void testWildcardAndMissing() throws IOException {
        assertEquals("https://any.site", allowOrigin("*", "https://any.site"));
        assertNull(allowOrigin(null, "https://any.site"));
        assertEquals("*", allowOrigin(null, null));
    }

}
