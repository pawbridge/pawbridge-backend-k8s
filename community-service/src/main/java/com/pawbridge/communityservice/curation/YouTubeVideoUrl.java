package com.pawbridge.communityservice.curation;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Parse only. Never fetch a URL supplied by an administrator. */
public final class YouTubeVideoUrl {
    private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private YouTubeVideoUrl() {}

    public static String id(String input) {
        try {
            if (input == null || input.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(input.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath();
            String candidate = null;
            if ("youtu.be".equals(host)) {
                candidate = path.substring(1);
            } else if (HOSTS.contains(host)) {
                if ("/watch".equals(path) && uri.getRawQuery() != null) {
                    var ids = Arrays.stream(uri.getRawQuery().split("&"))
                            .map(part -> part.split("=", 2))
                            .filter(pair -> pair.length == 2 && pair[0].equals("v"))
                            .map(pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)).toList();
                    if (ids.size() == 1) candidate = ids.get(0);
                } else if (path.startsWith("/shorts/") || path.startsWith("/embed/")) {
                    candidate = path.substring(path.lastIndexOf('/') + 1);
                    if (path.split("/").length != 3) throw new IllegalArgumentException();
                }
            }
            if (candidate == null || !candidate.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException();
            return candidate;
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 HTTPS YouTube 영상 주소를 입력해 주세요.");
        }
    }
}
