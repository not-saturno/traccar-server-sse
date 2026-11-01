package org.traccar.publicaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.WebHelper;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates anonymous requests to public features, so they are only used by the project front (same origin or
 * allowed origins) or by apps holding an API key, and limits requests and streams per client IP.
 *
 * <p>This raises the bar but is not absolute: a non-browser client can forge Origin and Referer headers. Browsers
 * cannot, so other websites are blocked; forged scripts are still bound by the rate and stream limits.
 */
@Singleton
public class PublicAccessGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAccessGuard.class);

    static final long WINDOW_MS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private static final class Window {
        private long start;
        private int count;
    }

    private final Set<String> allowedOrigins;
    private final boolean allowAllOrigins;
    private final boolean requireOrigin;
    private final List<byte[]> apiKeys;
    private final int rateLimit;
    private final int maxStreams;

    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, Integer> streams = new HashMap<>();

    @Inject
    public PublicAccessGuard(Config config) {
        Set<String> origins = Stream.concat(split(config.getString(Keys.PUBLIC_ALLOWED_ORIGINS)),
                        split(config.getString(Keys.WEB_ORIGIN)))
                .collect(Collectors.toSet());
        allowAllOrigins = split(config.getString(Keys.PUBLIC_ALLOWED_ORIGINS)).anyMatch("*"::equals);
        origins.remove("*"); // "*" in web.origin is about CORS, not about this check
        allowedOrigins = origins.stream().map(PublicAccessGuard::normalizeOrigin).collect(Collectors.toSet());
        requireOrigin = config.getBoolean(Keys.PUBLIC_REQUIRE_ORIGIN);
        apiKeys = split(config.getString(Keys.PUBLIC_API_KEYS))
                .map(key -> key.getBytes(StandardCharsets.UTF_8))
                .toList();
        rateLimit = config.getInteger(Keys.PUBLIC_RATE_LIMIT);
        maxStreams = config.getInteger(Keys.PUBLIC_MAX_STREAMS);
    }

    /**
     * Throws 429 when the client exceeded the rate limit and 403 when the request does not come from an allowed
     * origin and has no valid API key.
     */
    public void check(HttpServletRequest request) {
        String client = WebHelper.retrieveRemoteAddress(request);
        long retryAfter = consume(client, System.currentTimeMillis());
        if (retryAfter > 0) {
            LOGGER.debug("Public rate limit exceeded by {}", client);
            throw error(Response.Status.TOO_MANY_REQUESTS, "Too many requests", retryAfter);
        }
        if (hasValidApiKey(request)) {
            return;
        }
        if (!isOriginAllowed(request)) {
            LOGGER.debug("Public request rejected from {} (origin {}, referer {})",
                    client, request.getHeader("Origin"), request.getHeader("Referer"));
            throw error(Response.Status.FORBIDDEN, "Origin not allowed", 0);
        }
    }

    /**
     * Registers a new public stream for the client; {@code false} when the client already has the maximum.
     */
    public synchronized boolean acquireStream(String client) {
        int current = streams.getOrDefault(client, 0);
        if (maxStreams > 0 && current >= maxStreams) {
            return false;
        }
        streams.put(client, current + 1);
        return true;
    }

    public synchronized void releaseStream(String client) {
        streams.computeIfPresent(client, (key, count) -> count > 1 ? count - 1 : null);
    }

    public static WebApplicationException tooManyStreams() {
        return error(Response.Status.TOO_MANY_REQUESTS, "Too many streams", WINDOW_MS / 1000);
    }

    boolean isOriginAllowed(HttpServletRequest request) {
        if (allowAllOrigins) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            origin = originOf(request.getHeader("Referer"));
        }
        if (origin == null) {
            return !requireOrigin;
        }
        if ("null".equals(origin)) {
            return false; // sandboxed or privacy-redirected context
        }
        String normalized = normalizeOrigin(origin);
        if (allowedOrigins.contains(normalized)) {
            return true;
        }
        // Same origin as the server. Proxies keep the Host header (Caddy by default, nginx with $host); the Vite
        // dev proxy rewrites Host and sends the original one in X-Forwarded-Host.
        String authority = authorityOf(normalized);
        return authority != null && (authority.equals(normalizeHost(request.getHeader("Host")))
                || authority.equals(normalizeHost(request.getHeader("X-Forwarded-Host"))));
    }

    boolean hasValidApiKey(HttpServletRequest request) {
        if (apiKeys.isEmpty()) {
            return false;
        }
        String key = request.getHeader("X-Api-Key");
        if (key == null) {
            key = request.getParameter("apiKey");
        }
        if (key == null || key.isEmpty()) {
            return false;
        }
        byte[] provided = key.getBytes(StandardCharsets.UTF_8);
        boolean valid = false;
        for (byte[] expected : apiKeys) {
            valid |= MessageDigest.isEqual(expected, provided); // constant time, no early exit
        }
        return valid;
    }

    /**
     * Counts a request; returns 0 when allowed or the seconds until the window resets when over the limit.
     */
    synchronized long consume(String client, long now) {
        if (rateLimit <= 0) {
            return 0;
        }
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.values().removeIf(window -> now - window.start >= WINDOW_MS);
        }
        Window window = windows.computeIfAbsent(client, key -> new Window());
        if (now - window.start >= WINDOW_MS) {
            window.start = now;
            window.count = 0;
        }
        window.count++;
        if (window.count > rateLimit) {
            return Math.max(1, (window.start + WINDOW_MS - now + 999) / 1000);
        }
        return 0;
    }

    private static WebApplicationException error(Response.Status status, String message, long retryAfter) {
        Response.ResponseBuilder builder = Response.status(status).type(MediaType.TEXT_PLAIN).entity(message);
        if (retryAfter > 0) {
            builder.header("Retry-After", retryAfter);
        }
        return new WebApplicationException(builder.build());
    }

    private static Stream<String> split(String value) {
        if (value == null) {
            return Stream.empty();
        }
        return Arrays.stream(value.split("[,\\s]+")).map(String::trim).filter(item -> !item.isEmpty());
    }

    static String originOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String normalizeOrigin(String origin) {
        String value = origin.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("https://") && value.endsWith(":443")) {
            value = value.substring(0, value.length() - 4);
        } else if (value.startsWith("http://") && value.endsWith(":80")) {
            value = value.substring(0, value.length() - 3);
        }
        return value;
    }

    private static String authorityOf(String normalizedOrigin) {
        int index = normalizedOrigin.indexOf("://");
        return index < 0 ? null : normalizedOrigin.substring(index + 3);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        String value = host.split(",")[0].trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(":443") || value.endsWith(":80")) {
            value = value.substring(0, value.lastIndexOf(':'));
        }
        return value;
    }

}
