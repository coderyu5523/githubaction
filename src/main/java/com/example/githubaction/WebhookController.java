package com.example.githubaction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

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

    @GetMapping("/webhook")
    public String alive() { return "Webhook alive"; }

    @PostMapping("/webhook")
    public ResponseEntity<String> onPush(@RequestBody String payload,
                                         @RequestHeader(value="X-GitHub-Event", required=false) String event) throws Exception {
        if (!"push".equalsIgnoreCase(event)) {
            return ResponseEntity.ok("ignored event: " + event);
        }

        JsonNode root = om.readTree(payload);
        String afterSha = optText(root, "after");
        String targetPath = gprops.getTargetPath();

        // 대상 파일(a.txt)이 바뀌었는지 확인
        boolean changed = false;
        for (JsonNode c : root.withArray("commits")) {
            if (containsPath(c.withArray("added"), targetPath)
             || containsPath(c.withArray("modified"), targetPath)
             || containsPath(c.withArray("removed"), targetPath)) {
                changed = true; break;
            }
        }
        if (!changed) return ResponseEntity.ok("no target file change");

        // raw URL로 해당 커밋 시점의 파일 내용 가져오기
        // public repo면 토큰 필요 없음
        String rawUrl = "https://raw.githubusercontent.com/%s/%s/%s/%s"
                .formatted(gprops.getOwner(), gprops.getRepo(), afterSha, targetPath);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(rawUrl))
                .header("User-Agent", "file-sync");
        if (gprops.getToken() != null && !gprops.getToken().isBlank()) {
            rb.header("Authorization", "Bearer " + gprops.getToken());
        }
        HttpResponse<byte[]> resp = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        byte[] content = resp.statusCode() == 200 ? resp.body() : new byte[0];
        boolean deleted = content.length == 0;

        String msg = om.createObjectNode()
                .put("path", targetPath)
                .put("sha", afterSha)
                .put("contentBase64", Base64.getEncoder().encodeToString(content))
                .put("deleted", deleted)
                .toString();

        rabbit.convertAndSend(rprops.getExchange(), rprops.getRoutingKey(), msg);
        return ResponseEntity.ok("published to " + rprops.getRoutingKey());
    }

    private static boolean containsPath(JsonNode arr, String path) {
        for (JsonNode n : arr) if (path.equals(n.asText())) return true;
        return false;
    }
    private static String optText(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).asText() : ""; }
}
