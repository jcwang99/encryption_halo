package run.halo.encrypt.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpCookie;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.encrypt.processor.EncryptContentProcessor;

/**
 * 加密功能 API 端点（支持安全功能 + 会话记忆）
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptEndpoint implements CustomEndpoint {

        // Cookie 前缀
        private static final String UNLOCK_COOKIE_PREFIX = "encrypt_unlocked_";
        // Cookie 有效期（默认 24 小时）
        private static final Duration COOKIE_MAX_AGE = Duration.ofHours(24);

        @Override
        public RouterFunction<ServerResponse> endpoint() {
                final var tag = "api.encrypt.halo.run/v1alpha1/Encrypt";

                return SpringdocRouteBuilder.route()
                                // 密码解锁
                                .POST("/unlock", this::unlockWithPassword,
                                                builder -> builder.operationId("UnlockWithPassword")
                                                                .tag(tag)
                                                                .description("使用密码解锁加密内容")
                                                                .requestBody(requestBodyBuilder()
                                                                                .implementation(UnlockRequest.class))
                                                                .response(responseBuilder()
                                                                                .implementation(UnlockResponse.class)))
                                // 检查解锁状态
                                .GET("/check-unlock/{blockId}", this::checkUnlockStatus,
                                                builder -> builder.operationId("CheckUnlockStatus")
                                                                .tag(tag)
                                                                .description("检查区块是否已解锁（基于会话）")
                                                                .parameter(parameterBuilder().name("blockId")
                                                                                .required(true))
                                                                .response(responseBuilder()
                                                                                .implementation(CheckUnlockResponse.class)))
                                // 获取已解锁内容
                                .GET("/get-content/{blockId}", this::getUnlockedContent,
                                                builder -> builder.operationId("GetUnlockedContent")
                                                                .tag(tag)
                                                                .description("获取已解锁的内容（需要有效会话）")
                                                                .parameter(parameterBuilder().name("blockId")
                                                                                .required(true))
                                                                .response(responseBuilder()
                                                                                .implementation(UnlockResponse.class)))
                                // 获取安全配置
                                .GET("/security-config", this::getSecurityConfig,
                                                builder -> builder.operationId("GetSecurityConfig")
                                                                .tag(tag)
                                                                .description("获取安全配置")
                                                                .response(responseBuilder().implementation(
                                                                                SecurityConfigResponse.class)))
                                .build();
        }

        /**
         * 密码解锁（成功后设置会话 Cookie）
         */
        private Mono<ServerResponse> unlockWithPassword(ServerRequest request) {
                String clientIp = getClientIp(request);

                return request.bodyToMono(UnlockRequest.class)
                                .flatMap(req -> {
                                        if (req.blockId() == null || req.blockId().isEmpty()) {
                                                return ServerResponse.ok()
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .bodyValue(new UnlockResponse(false, "缺少 blockId", null,
                                                                                false, 0));
                                        }

                                        if (req.password() == null || req.password().isEmpty()) {
                                                return ServerResponse.ok()
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .bodyValue(new UnlockResponse(false, "请输入密码", null,
                                                                                false, 0));
                                        }

                                        // 调用内容处理器验证密码
                                        var result = EncryptContentProcessor.verifyAndGetContent(
                                                        req.blockId(), req.password(), clientIp);

                                        if (result.success()) {
                                                // 解锁成功，设置会话 Cookie
                                                ResponseCookie cookie = createUnlockCookie(req.blockId());
                                                return ServerResponse.ok()
                                                                .cookie(cookie)
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .bodyValue(new UnlockResponse(
                                                                                result.success(),
                                                                                result.message(),
                                                                                result.content(),
                                                                                result.locked(),
                                                                                result.lockRemainingMinutes()));
                                        } else {
                                                return ServerResponse.ok()
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .bodyValue(new UnlockResponse(
                                                                                result.success(),
                                                                                result.message(),
                                                                                result.content(),
                                                                                result.locked(),
                                                                                result.lockRemainingMinutes()));
                                        }
                                })
                                .onErrorResume(e -> {
                                        log.error("解锁失败: {}", e.getMessage(), e);
                                        return ServerResponse.ok()
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .bodyValue(new UnlockResponse(false, "解锁失败: " + e.getMessage(),
                                                                        null, false, 0));
                                });
        }

        /**
         * 检查区块是否已解锁
         */
        private Mono<ServerResponse> checkUnlockStatus(ServerRequest request) {
                String blockId = request.pathVariable("blockId");

                boolean isUnlocked = hasUnlockCookie(request, blockId);
                boolean blockExists = EncryptContentProcessor.blockExists(blockId);

                return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new CheckUnlockResponse(isUnlocked, blockExists));
        }

        /**
         * 获取已解锁的内容（需要有效会话）
         */
        private Mono<ServerResponse> getUnlockedContent(ServerRequest request) {
                String blockId = request.pathVariable("blockId");

                // 检查是否有解锁 Cookie
                if (!hasUnlockCookie(request, blockId)) {
                        return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(new UnlockResponse(false, "未解锁或会话已过期", null, false, 0));
                }

                // 获取内容
                String content = EncryptContentProcessor.getContentByBlockId(blockId);
                if (content == null) {
                        return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(new UnlockResponse(false, "加密区块不存在", null, false, 0));
                }

                return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new UnlockResponse(true, "获取成功", content, false, 0));
        }

        /**
         * 获取安全配置
         */
        private Mono<ServerResponse> getSecurityConfig(ServerRequest request) {
                var config = EncryptContentProcessor.getSecurityConfig();
                return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new SecurityConfigResponse(
                                                config.maxFailAttempts(),
                                                config.lockDurationMinutes()));
        }

        /**
         * 创建解锁 Cookie
         */
        private ResponseCookie createUnlockCookie(String blockId) {
                return ResponseCookie.from(UNLOCK_COOKIE_PREFIX + blockId, "1")
                                .maxAge(COOKIE_MAX_AGE)
                                .path("/")
                                .httpOnly(false) // 允许 JS 访问以便前端检查
                                .secure(false) // 开发环境不强制 HTTPS
                                .sameSite("Lax")
                                .build();
        }

        /**
         * 检查是否有解锁 Cookie
         */
        private boolean hasUnlockCookie(ServerRequest request, String blockId) {
                String cookieName = UNLOCK_COOKIE_PREFIX + blockId;
                List<HttpCookie> cookies = request.cookies().get(cookieName);
                return cookies != null && !cookies.isEmpty();
        }

        /**
         * 获取客户端 IP
         */
        private String getClientIp(ServerRequest request) {
                String[] headers = {
                                "X-Forwarded-For",
                                "X-Real-IP",
                                "Proxy-Client-IP",
                                "WL-Proxy-Client-IP",
                                "HTTP_X_FORWARDED_FOR"
                };

                for (String header : headers) {
                        String ip = request.headers().firstHeader(header);
                        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                                if (ip.contains(",")) {
                                        ip = ip.split(",")[0].trim();
                                }
                                return ip;
                        }
                }

                var remoteAddress = request.remoteAddress();
                if (remoteAddress.isPresent()) {
                        return remoteAddress.get().getAddress().getHostAddress();
                }

                return "unknown";
        }

        @Override
        public GroupVersion groupVersion() {
                return GroupVersion.parseAPIVersion("api.encrypt.halo.run/v1alpha1");
        }

        // Request/Response DTOs
        public record UnlockRequest(String blockId, String password) {
        }

        public record UnlockResponse(
                        boolean success,
                        String message,
                        String content,
                        boolean locked,
                        int lockRemainingMinutes) {
        }

        public record CheckUnlockResponse(
                        boolean unlocked,
                        boolean blockExists) {
        }

        public record SecurityConfigResponse(
                        int maxFailAttempts,
                        int lockDurationMinutes) {
        }
}
