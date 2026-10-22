package com.pawbridge.backend.boot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Bounded public upload admission for {@code POST /api/v1/animals/lost-candidates}, moved from the API Gateway
 * {@code LostSearchLimits} filter. Counters apply to this application instance, not to individual clients.
 *
 * <p>Differences from the Gateway filter: the body is not buffered here (the servlet container parses the multipart
 * parts itself), so the size cap is the declared Content-Length and requests without one are refused with 411;
 * permits are taken only after the body has been received so slow uploads cannot hold them;
 * the 50 second limit is not enforced by interrupting the request, only by the 3s+45s Python client timeouts and by
 * mapping a timeout that escapes the controller to 504.
 */
public final class LostSearchLimitsFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/v1/animals/lost-candidates";
    private static final Set<String> STRIPPED_HEADERS = Set.of("authorization", "x-user-id", "x-internal-api-key", "transfer-encoding");
    private final int maxBytes;
    private final int requestsPerMinute;
    private final Semaphore active;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();

    public LostSearchLimitsFilter(int maxBytes, int maxConcurrent, int requestsPerMinute, int timeoutSeconds) {
        if (maxBytes <= 0 || maxConcurrent <= 0 || requestsPerMinute <= 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Lost search limits must be positive");
        }
        this.maxBytes = maxBytes;
        this.active = new Semaphore(maxConcurrent);
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Decide on the decoded path (what Tomcat mapping, Security and MVC use), never the raw URI:
        // /lost-%63andidates must not slip past the limits.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !"POST".equals(request.getMethod()) || !(PATH.equals(path) || (PATH + "/").equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > maxBytes) { reject(response, 413); return; }
        MediaType type;
        try { type = request.getContentType() == null ? null : MediaType.parseMediaType(request.getContentType()); }
        catch (IllegalArgumentException ex) { reject(response, 415); return; }
        if (type == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type)) { reject(response, 415); return; }
        if (length < 0) { reject(response, 411); return; }
        // Read the whole multipart body before taking a permit: a slow or stalled upload then occupies only its own
        // request thread, so two slow clients cannot exhaust the concurrency permits for everyone. A malformed body is
        // left for the handler to report exactly as before (the container rethrows the same failure there).
        try { request.getParts(); } catch (IOException | ServletException | IllegalStateException ignored) { }
        if (!active.tryAcquire()) { reject(response, 503); return; }
        try {
            if (!admit()) { reject(response, 429); return; }
            if (length == 0) { reject(response, 400); return; }
            try { chain.doFilter(new StrippedRequest(request), response); }
            catch (ServletException | RuntimeException ex) {
                if (response.isCommitted() || !timedOut(ex)) throw ex;
                reject(response, 504);
            }
        } finally { active.release(); }
    }

    private static boolean timedOut(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private synchronized boolean admit() {
        long now = System.nanoTime();
        long minute = Duration.ofMinutes(1).toNanos();
        while (!starts.isEmpty() && now - starts.peekFirst() >= minute) starts.removeFirst();
        if (starts.size() >= requestsPerMinute) return false;
        starts.addLast(now);
        return true;
    }

    private static void reject(HttpServletResponse response, int status) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json");
        if (status == 429) response.setHeader("Retry-After", "60");
        if (status == 503) response.setHeader("Retry-After", "3");
        String message = switch (status) {
            case 413 -> "사진 크기 제한을 초과했습니다";
            case 429 -> "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요";
            case 504 -> "검색 시간이 초과됐습니다";
            case 415, 411 -> "사진 업로드 형식을 확인해 주세요";
            case 400 -> "사진을 선택해 주세요";
            default -> "잠시 후 다시 시도해 주세요";
        };
        byte[] body = ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** Client-supplied identity and internal-key headers must never reach the downstream handler. */
    private static final class StrippedRequest extends HttpServletRequestWrapper {
        StrippedRequest(HttpServletRequest request) { super(request); }

        private static boolean hidden(String name) { return name != null && STRIPPED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT)); }

        @Override public String getHeader(String name) { return hidden(name) ? null : super.getHeader(name); }

        @Override public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames()).stream().filter(name -> !hidden(name)).toList();
            return Collections.enumeration(names);
        }
    }
}
