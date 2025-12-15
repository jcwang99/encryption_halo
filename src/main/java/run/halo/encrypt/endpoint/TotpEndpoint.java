package run.halo.encrypt.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static run.halo.app.extension.GroupVersion.parseAPIVersion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import run.halo.encrypt.util.TotpUtils;
import run.halo.encrypt.util.TotpUtils.ValidityPeriod;

import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;

/**
 * TOTP 动态密码 API 端点
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TotpEndpoint implements CustomEndpoint {

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONFIG_MAP_NAME = "plugin-encrypt-configMap";

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "TotpV1alpha1";
        return SpringdocRouteBuilder.route()
                .GET("totp/current", this::getCurrentCode,
                        builder -> builder.operationId("GetCurrentTotpCode")
                                .description("获取当前动态密码")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(TotpResponse.class)))
                .POST("totp/generate", this::generateAndSaveSecret,
                        builder -> builder.operationId("GenerateTotpSecret")
                                .description("生成新的TOTP密钥并保存到设置")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(GenerateResponse.class)))
                .build();
    }

    @Override
    public run.halo.app.extension.GroupVersion groupVersion() {
        return parseAPIVersion("encrypt.halo.run/v1alpha1");
    }

    /**
     * 获取当前 TOTP 密码
     */
    private Mono<ServerResponse> getCurrentCode(ServerRequest request) {
        return settingFetcher.get("totp")
                .flatMap(setting -> {
                    boolean enabled = setting.get("enableTotp").asBoolean(false);
                    if (!enabled) {
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new TotpResponse(false, null, null, null, null, "TOTP 未启用，请先在设置中启用"));
                    }

                    String secret = setting.get("totpSecret").asText("");
                    if (secret.isEmpty()) {
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new TotpResponse(false, null, null, null, null, "请点击「生成密钥」按钮"));
                    }

                    String periodStr = setting.get("validityPeriod").asText("DAY_1");
                    ValidityPeriod period;
                    try {
                        period = ValidityPeriod.valueOf(periodStr);
                    } catch (IllegalArgumentException e) {
                        period = ValidityPeriod.DAY_1;
                    }

                    String code = TotpUtils.getCurrentCode(secret, period);
                    String expiresAt = TotpUtils.getExpirationTime(period).toString();
                    String remaining = TotpUtils.getRemainingTimeDescription(period);
                    String periodDesc = TotpUtils.getCurrentPeriodDescription(period);

                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new TotpResponse(true, code, expiresAt, remaining, periodDesc, null));
                })
                .switchIfEmpty(ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new TotpResponse(false, null, null, null, null, "请先在插件设置中配置 TOTP")));
    }

    /**
     * 生成新密钥并保存到设置
     */
    private Mono<ServerResponse> generateAndSaveSecret(ServerRequest request) {
        String newSecret = TotpUtils.generateSecret();
        log.info("生成新的 TOTP 密钥");

        // 获取并更新 ConfigMap
        return client.get(ConfigMap.class, CONFIG_MAP_NAME)
                .flatMap(configMap -> {
                    try {
                        // 获取现有的 totp 配置
                        String totpJson = configMap.getData().getOrDefault("totp", "{}");
                        JsonNode totpNode = objectMapper.readTree(totpJson);

                        // 更新 secret 字段
                        ObjectNode updatedNode;
                        if (totpNode.isObject()) {
                            updatedNode = (ObjectNode) totpNode;
                        } else {
                            updatedNode = objectMapper.createObjectNode();
                        }
                        updatedNode.put("totpSecret", newSecret);

                        // 如果没有启用，自动启用
                        if (!updatedNode.has("enableTotp") || !updatedNode.get("enableTotp").asBoolean()) {
                            updatedNode.put("enableTotp", true);
                        }

                        // 如果没有设置有效期，使用默认值
                        if (!updatedNode.has("validityPeriod")) {
                            updatedNode.put("validityPeriod", "DAY_1");
                        }

                        // 保存回 ConfigMap
                        configMap.getData().put("totp", objectMapper.writeValueAsString(updatedNode));

                        return client.update(configMap);
                    } catch (Exception e) {
                        log.error("更新 TOTP 配置失败", e);
                        return Mono.error(e);
                    }
                })
                .flatMap(updated -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new GenerateResponse(true, "密钥已生成并保存", newSecret)))
                .onErrorResume(e -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new GenerateResponse(false, "生成失败: " + e.getMessage(), null)));
    }

    @Data
    public static class TotpResponse {
        private final boolean enabled;
        private final String code;
        private final String expiresAt;
        private final String remaining;
        private final String periodDescription;
        private final String error;
    }

    @Data
    public static class GenerateResponse {
        private final boolean success;
        private final String message;
        private final String secret;
    }
}
