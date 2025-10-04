package com.example.githubaction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final RabbitTemplate rabbit;
    private final AppProps rprops;
    private final GithubProps gprops;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public WebhookController(RabbitTemplate rabbit, AppProps rprops, GithubProps gprops) {
        this.rabbit = rabbit;
        this.rprops = rprops;
        this.gprops = gprops;
    }

    // 헬스체크
    @GetMapping("/webhook")
    public String alive() { return "Webhook alive"; }

    // 수동 발행 테스트 (연결/큐 확인용)
    @GetMapping("/test-pub")
    public String testPublish() {
        String msg = "{\"ping\":\"pong\"}";
        rabbit.convertAndSend(rprops.getExchange(), rprops.getRoutingKey(), msg);
        log.info("[TEST] published to exchange='{}' rk='{}' body={}", rprops.getExchange(), rprops.getRoutingKey(), msg);
        return "sent";
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> onPush(@RequestBody(required = false) String payload,
                                         @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                         @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
                                         @RequestHeader(value = "User-Agent", required = false) String ua) {
        long t0 = System.currentTimeMillis();

        try {
            // 1) 기본 헤더/페이로드 로깅
            int len = payload == null ? 0 : payload.length();
            log.info(">>> /webhook received: event='{}' delivery='{}' ua='{}' payloadLen={}",
                    event, deliveryId, ua, len);

            if (!"push".equalsIgnoreCase(event)) {
                log.info("ignored event: {}", event);
                return ResponseEntity.ok("ignored event: " + event);
            }
            if (payload == null || payload.isBlank()) {
                log.warn("empty payload for push event");
                return ResponseEntity.badRequest().body("empty payload");
            }

            // 2) push payload 파싱
            JsonNode root = om.readTree(payload);
            String afterSha = opt(root, "after");
            String ref = opt(root, "ref");
            String targetPath = gprops.getTargetPath();

            // 커밋/파일 변경 여부 판단 로그
            boolean changed = false;
            int commitCount = root.path("commits").isArray() ? root.path("commits").size() : 0;
            for (JsonNode c : root.withArray("commits")) {
                boolean hit = contains(c.withArray("added"), targetPath)
                           || contains(c.withArray("modified"), targetPath)
                           || contains(c.withArray("removed"), targetPath);
                if (hit) { changed = true; break; }
            }
            log.info("push summary: ref='{}' after='{}' commits={} targetPath='{}' changed={}",
                    ref, afterSha, commitCount, targetPath, changed);

            if (!changed) {
                log.info("no change for targetPath='{}' -> skip publish", targetPath);
                return ResponseEntity.ok("no target file change");
            }

            // 3) raw.githubusercontent.com 에서 해당 커밋의 파일 가져오기
            String rawUrl = "https://raw.githubusercontent.com/%s/%s/%s/%s"
                    .formatted(gprops.getOwner(), gprops.getRepo(), afterSha, targetPath);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(rawUrl))
                    .header("User-Agent", "file-sync/1.0");
            if (hasText(gprops.getToken())) {
                rb.header("Authorization", "Bearer " + gprops.getToken());
            }

            log.info("fetching raw content: {}", rawUrl);
            HttpResponse<byte[]> resp = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            int sc = resp.statusCode();
            byte[] content = (sc == 200) ? resp.body() : new byte[0];
            boolean deleted = content.length == 0;

            log.info("raw fetched: status={} bytes={} deleted={}", sc, content.length, deleted);
            if (sc != 200) {
                log.warn("raw get non-200 ({}). Will publish deleted=true with empty body.", sc);
            }

            // 4) 메시지 구성 & 발행
            String contentB64 = Base64.getEncoder().encodeToString(content);
            String msg = om.createObjectNode()
                    .put("path", targetPath)
                    .put("sha", afterSha)
                    .put("deleted", deleted)
                    .put("contentBase64", contentB64)
                    .toString();

            rabbit.convertAndSend(rprops.getExchange(), rprops.getRoutingKey(), msg);
            log.info("published: exchange='{}' rk='{}' size={}B (b64={})",
                    rprops.getExchange(), rprops.getRoutingKey(), content.length, contentB64.length());

            long t1 = System.currentTimeMillis();
            log.info("<<< /webhook done in {} ms", (t1 - t0));
            return ResponseEntity.ok("published to " + rprops.getRoutingKey());

        } catch (Exception e) {
            log.error("webhook handling failed: {}", e.toString(), e);
            return ResponseEntity.internalServerError().body("error: " + e.getMessage());
        }
    }

    // -------- helpers --------
    private static boolean contains(JsonNode arr, String path) {
        for (JsonNode n : arr) {
            if (path.equals(n.asText())) return true;
        }
        return false;
    }

    private static String opt(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : "";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
