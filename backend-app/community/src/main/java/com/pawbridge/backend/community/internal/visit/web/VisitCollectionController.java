package com.pawbridge.backend.community.internal.visit.web;

import com.pawbridge.backend.community.internal.visit.application.VisitCollection;
import com.pawbridge.backend.community.internal.visit.application.VisitIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class VisitCollectionController {
    private static final Pattern KNOWN_BOT = Pattern.compile("(?i)(bot|crawler|spider|slurp|headlesschrome|facebookexternalhit)");
    private final VisitCollection service;
    private final VisitIdentity cookies;
    private final Clock clock;
    private final Set<String> origins;

    public VisitCollectionController(VisitCollection service, VisitIdentity cookies, Clock clock,
            String origins) {
        this.service=service; this.cookies=cookies; this.clock=clock;
        this.origins=Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    @PostMapping("/api/v1/visits/identity")
    public ResponseEntity<?> identity(HttpServletRequest request) {
        checkOrigin(request);
        if (bot(request)) return noStore().build();
        var cookie=cookies.initialize(cookie(request),clock.instant());
        var response=noStore();
        if (cookie != null) response.header(HttpHeaders.SET_COOKIE,ResponseCookie.from(VisitIdentity.NAME,cookie.value())
                .secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(cookie.maxAgeSeconds()).build().toString());
        return response.build();
    }

    @PostMapping("/api/v1/visits/today")
    public ResponseEntity<?> count(HttpServletRequest request) {
        checkOrigin(request);
        if (bot(request)) return noStore().build();
        return ok(service.count(cookie(request)));
    }

    @GetMapping("/api/v1/admin/visits/stats/period")
    public ResponseEntity<?> period(@RequestParam("startDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDate") @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ok(service.period(startDate,endDate));
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response<>(200,"요청이 성공적으로 처리되었습니다.",body));
    }
    private ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).cacheControl(CacheControl.noStore());
    }
    private void checkOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if (origin == null || !origins.contains(origin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"허용된 출처의 요청만 가능합니다.");
    }
    private boolean bot(HttpServletRequest request) {
        String ua=request.getHeader("User-Agent");
        return ua != null && KNOWN_BOT.matcher(ua).find();
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies()==null) return null;
        // Reject ambiguous duplicate cookies, rather than choosing a different identity per request.
        var values=Arrays.stream(request.getCookies()).filter(c -> VisitIdentity.NAME.equals(c.getName())).toList();
        return values.size()==1 ? values.get(0).getValue() : null;
    }
    public record Response<T>(int code, String message, T data) {}
}
