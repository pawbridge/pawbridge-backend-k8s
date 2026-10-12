package com.pawbridge.communityservice.visits;

import static com.pawbridge.communityservice.visits.VisitModels.*;
import com.pawbridge.communityservice.util.ResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="pawbridge.visits.enabled", havingValue="true")
public class VisitController {
    private static final Pattern KNOWN_BOT = Pattern.compile("(?i)(bot|crawler|spider|slurp|headlesschrome|facebookexternalhit)");
    private final VisitService service;
    private final VisitCookie cookies;
    private final Clock clock;
    private final Set<String> origins;

    public VisitController(VisitService service, VisitCookie cookies, Clock clock,
            @Value("${pawbridge.visits.allowed-origins:https://www.pawbridge.kr}") String origins) {
        this.service=service; this.cookies=cookies; this.clock=clock;
        this.origins=Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    @PostMapping("/api/v1/visits/identity")
    public ResponseEntity<?> identity(HttpServletRequest request) {
        checkOrigin(request);
        if (bot(request)) return noStore().build();
        ResponseCookie cookie=cookies.initialize(cookie(request),clock.instant());
        var response=noStore();
        if (cookie != null) response.header(HttpHeaders.SET_COOKIE,cookie.toString());
        return response.build();
    }

    @PostMapping("/api/v1/visits/today")
    public ResponseEntity<?> count(HttpServletRequest request) {
        checkOrigin(request);
        if (bot(request)) return noStore().build();
        return ok(service.count(cookie(request)));
    }

    @GetMapping("/api/v1/visits/today")
    public ResponseEntity<?> today() { return ok(service.today()); }

    @GetMapping("/api/v1/admin/visits/stats/period")
    public ResponseEntity<?> period(@RequestHeader(value="X-User-Role",required=false) String role,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (!"ROLE_ADMIN".equals(role))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"관리자 권한이 필요합니다.");
        return ok(service.period(startDate,endDate));
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResponseDTO.okWithData(body));
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
        var values=Arrays.stream(request.getCookies()).filter(c -> VisitCookie.NAME.equals(c.getName())).toList();
        return values.size()==1 ? values.get(0).getValue() : null;
    }
}
