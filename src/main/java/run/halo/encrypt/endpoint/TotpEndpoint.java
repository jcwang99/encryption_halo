package run.halo.encrypt.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static run.halo.app.extension.GroupVersion.parseAPIVersion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.encrypt.model.TotpPassword;
import run.halo.encrypt.util.TotpUtils;

/**
 * TOTP 动态密码 API 端点（支持多密码）
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpEndpoint implements CustomEndpoint {

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String CONFIG_MAP_NAME = "plugin-encrypt-configMap";
    private static final String TOTP_PASSWORDS_KEY = "totpPasswords";

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "TotpV1alpha1";
        return SpringdocRouteBuilder.route()
                // 获取所有密码列表（含当前密码）
                .GET("totp/list", this::listPasswords,
                        builder -> builder.operationId("ListTotpPasswords")
                                .description("获取所有动态密码列表")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(ListResponse.class)))
                // 创建新密码
                .POST("totp/create", this::createPassword,
                        builder -> builder.operationId("CreateTotpPassword")
                                .description("创建新的动态密码")
                                .tag(tag)
                                .requestBody(requestBodyBuilder()
                                        .implementation(CreateRequest.class))
                                .response(responseBuilder()
                                        .implementation(PasswordInfo.class)))
                // 删除密码
                .DELETE("totp/{id}", this::deletePassword,
                        builder -> builder.operationId("DeleteTotpPassword")
                                .description("删除指定动态密码")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(DeleteResponse.class)))
                // 兼容旧 API：获取当前密码（返回第一个启用的）
                .GET("totp/current", this::getCurrentCode,
                        builder -> builder.operationId("GetCurrentTotpCode")
                                .description("获取当前动态密码（兼容旧版）")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(TotpResponse.class)))
                .build();
    }

    @Override
    public run.halo.app.extension.GroupVersion groupVersion() {
        return parseAPIVersion("encrypt.halo.run/v1alpha1");
    }

    /**
     * 获取所有密码列表
     */
    private Mono<ServerResponse> listPasswords(ServerRequest request) {
        return loadPasswords()
                .flatMap(passwords -> {
                    List<PasswordInfo> infos = passwords.stream()
                            .filter(TotpPassword::isEnabled)
                            .map(this::toPasswordInfo)
                            .toList();
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new ListResponse(true, infos, null));
                })
                .onErrorResume(e -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new ListResponse(false, List.of(), e.getMessage())));
    }

    /**
     * 创建新密码
     */
    private Mono<ServerResponse> createPassword(ServerRequest request) {
        return request.bodyToMono(CreateRequest.class)
                .flatMap(req -> {
                    String name = req.getName() != null ? req.getName() : "动态密码";
                    int days = req.getDurationDays() > 0 ? req.getDurationDays() : 7;

                    String secret = TotpUtils.generateSecret();
                    TotpPassword newPassword = TotpPassword.create(name, secret, days);

                    log.info("创建新 TOTP 密码: name={}, duration={}天", name, days);

                    return loadPasswords()
                            .flatMap(passwords -> {
                                passwords.add(newPassword);
                                return savePasswords(passwords);
                            })
                            .thenReturn(newPassword);
                })
                .flatMap(password -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(toPasswordInfo(password)))
                .onErrorResume(e -> {
                    log.error("创建 TOTP 密码失败", e);
                    return ServerResponse.badRequest()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new ErrorResponse("创建失败: " + e.getMessage()));
                });
    }

    /**
     * 删除密码
     */
    private Mono<ServerResponse> deletePassword(ServerRequest request) {
        String id = request.pathVariable("id");

        return loadPasswords()
                .flatMap(passwords -> {
                    boolean removed = passwords.removeIf(p -> p.getId().equals(id));
                    if (!removed) {
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new DeleteResponse(false, "密码不存在"));
                    }
                    return savePasswords(passwords)
                            .then(ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(new DeleteResponse(true, "删除成功")));
                })
                .onErrorResume(e -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new DeleteResponse(false, "删除失败: " + e.getMessage())));
    }

    /**
     * 获取当前密码（兼容旧版，返回第一个启用的密码）
     */
    private Mono<ServerResponse> getCurrentCode(ServerRequest request) {
        return loadPasswords()
                .flatMap(passwords -> {
                    Optional<TotpPassword> first = passwords.stream()
                            .filter(TotpPassword::isEnabled)
                            .findFirst();

                    if (first.isEmpty()) {
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new TotpResponse(false, null, null, null, null, "无可用密码，请先创建"));
                    }

                    TotpPassword password = first.get();
                    String code = TotpUtils.getCodeByCreationTime(
                            password.getSecret(),
                            password.getCreatedAt(),
                            password.getDurationDays());
                    String remaining = TotpUtils.getRemainingByCreation(
                            password.getCreatedAt(),
                            password.getDurationDays());
                    String expiresAt = TotpUtils.getExpirationTimeByCreation(
                            password.getCreatedAt(),
                            password.getDurationDays()).toString();

                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new TotpResponse(true, code, expiresAt, remaining,
                                    password.getName() + " (" + password.getDurationDays() + "天)", null));
                })
                .switchIfEmpty(ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new TotpResponse(false, null, null, null, null, "请先创建动态密码")));
    }

    /**
     * 加载密码列表
     */
    private Mono<List<TotpPassword>> loadPasswords() {
        return client.get(ConfigMap.class, CONFIG_MAP_NAME)
                .map(configMap -> {
                    try {
                        String json = configMap.getData().getOrDefault(TOTP_PASSWORDS_KEY, "[]");
                        return objectMapper.readValue(json, new TypeReference<List<TotpPassword>>() {
                        });
                    } catch (Exception e) {
                        log.warn("解析 TOTP 密码列表失败", e);
                        return new ArrayList<TotpPassword>();
                    }
                })
                .defaultIfEmpty(new ArrayList<>());
    }

    /**
     * 保存密码列表
     */
    private Mono<Void> savePasswords(List<TotpPassword> passwords) {
        return client.get(ConfigMap.class, CONFIG_MAP_NAME)
                .flatMap(configMap -> {
                    try {
                        String json = objectMapper.writeValueAsString(passwords);
                        configMap.getData().put(TOTP_PASSWORDS_KEY, json);
                        return client.update(configMap);
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                })
                .then();
    }

    /**
     * 转换为响应 DTO
     */
    private PasswordInfo toPasswordInfo(TotpPassword password) {
        String code = TotpUtils.getCodeByCreationTime(
                password.getSecret(),
                password.getCreatedAt(),
                password.getDurationDays());
        String remaining = TotpUtils.getRemainingByCreation(
                password.getCreatedAt(),
                password.getDurationDays());
        LocalDateTime expiresAt = TotpUtils.getExpirationTimeByCreation(
                password.getCreatedAt(),
                password.getDurationDays());

        return new PasswordInfo(
                password.getId(),
                password.getName(),
                code,
                password.getDurationDays(),
                remaining,
                expiresAt.toString(),
                password.getCreatedAt().toString());
    }

    // ========== DTO 类 ==========

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;
        private int durationDays;
    }

    @Data
    @AllArgsConstructor
    public static class PasswordInfo {
        private String id;
        private String name;
        private String code;
        private int durationDays;
        private String remaining;
        private String expiresAt;
        private String createdAt;
    }

    @Data
    @AllArgsConstructor
    public static class ListResponse {
        private boolean success;
        private List<PasswordInfo> passwords;
        private String error;
    }

    @Data
    @AllArgsConstructor
    public static class DeleteResponse {
        private boolean success;
        private String message;
    }

    @Data
    @AllArgsConstructor
    public static class ErrorResponse {
        private String error;
    }

    @Data
    @AllArgsConstructor
    public static class TotpResponse {
        private boolean enabled;
        private String code;
        private String expiresAt;
        private String remaining;
        private String periodDescription;
        private String error;
    }
}
