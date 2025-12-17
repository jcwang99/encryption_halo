package run.halo.encrypt.endpoint;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.encrypt.util.TotpUtils;

/**
 * 区块级 TOTP 动态密码 API 端点
 * 使用 ConfigMap 存储，避免 Post annotation 缓存问题
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockTotpEndpoint implements run.halo.app.core.extension.endpoint.CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    private static final String CONFIGMAP_NAME = "encrypt-block-totp";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "BlockTotpV1alpha1";
        return SpringdocRouteBuilder.route()
                .POST("block-totp/generate", this::generateBlockTotp,
                        builder -> builder.operationId("generateBlockTotp")
                                .description("生成区块 TOTP")
                                .tag(tag)
                                .requestBody(requestBodyBuilder()
                                        .required(true)
                                        .implementation(BlockTotpRequest.class))
                                .response(responseBuilder()
                                        .implementation(Map.class)))
                .GET("block-totp/code/{blockId}", this::getBlockTotpCode,
                        builder -> builder.operationId("getBlockTotpCode")
                                .description("获取区块当前密码")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(Map.class)))
                .DELETE("block-totp/{blockId}", this::deleteBlockTotp,
                        builder -> builder.operationId("deleteBlockTotp")
                                .description("删除区块 TOTP")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(Map.class)))
                .GET("block-totp/list", this::listBlockTotps,
                        builder -> builder.operationId("listBlockTotps")
                                .description("列出所有区块 TOTP")
                                .tag(tag)
                                .response(responseBuilder()
                                        .implementation(Map.class)))
                .build();
    }

    @Override
    public run.halo.app.extension.GroupVersion groupVersion() {
        return new run.halo.app.extension.GroupVersion("api.encrypt.halo.run", "v1alpha1");
    }

    // ========== API 方法 ==========

    /**
     * 生成区块 TOTP
     */
    private Mono<ServerResponse> generateBlockTotp(ServerRequest request) {
        return request.bodyToMono(BlockTotpRequest.class)
                .flatMap(req -> {
                    String blockId = req.getBlockId();
                    if (blockId == null || blockId.isEmpty()) {
                        blockId = "totp-" + generateShortId();
                    }

                    int durationDays = req.getDurationDays();
                    if (durationDays <= 0) {
                        durationDays = 7;
                    }

                    final String finalBlockId = blockId;
                    final int finalDuration = durationDays;

                    // 生成密钥
                    String secret = TotpUtils.generateSecret();

                    // 创建配置
                    BlockTotpConfig config = new BlockTotpConfig();
                    config.setSecret(secret);
                    config.setDurationDays(finalDuration);
                    config.setCreatedAt(LocalDateTime.now().toString());
                    config.setLabel(req.getLabel() != null ? req.getLabel() : "区块密码");
                    config.setEnabled(true);

                    // 保存到 ConfigMap
                    return saveBlockTotpConfig(finalBlockId, config)
                            .flatMap(saved -> {
                                LocalDateTime createdAt = LocalDateTime.parse(config.getCreatedAt());
                                String currentCode = TotpUtils.getCodeByCreationTime(
                                        secret, createdAt, finalDuration);
                                String remaining = TotpUtils.getRemainingByCreation(
                                        createdAt, finalDuration);

                                Map<String, Object> result = new HashMap<>();
                                result.put("success", true);
                                result.put("blockId", finalBlockId);
                                result.put("currentCode", currentCode);
                                result.put("remainingTime", remaining);
                                result.put("durationDays", finalDuration);

                                log.info("区块 TOTP 生成成功: blockId={}", finalBlockId);
                                return ServerResponse.ok().bodyValue(result);
                            });
                })
                .onErrorResume(e -> {
                    log.error("生成区块 TOTP 失败", e);
                    return ServerResponse.ok().bodyValue(
                            Map.of("success", false, "error", e.getMessage()));
                });
    }

    /**
     * 获取区块当前密码
     */
    private Mono<ServerResponse> getBlockTotpCode(ServerRequest request) {
        String blockId = request.pathVariable("blockId");
        return loadBlockTotpConfig(blockId)
                .flatMap(config -> {
                    if (config == null || !config.isEnabled()) {
                        return ServerResponse.ok().bodyValue(
                                Map.of("error", "区块 TOTP 不存在或未启用"));
                    }

                    LocalDateTime createdAt = LocalDateTime.parse(config.getCreatedAt());
                    int days = config.getDurationDays();
                    String currentCode = TotpUtils.getCodeByCreationTime(
                            config.getSecret(), createdAt, days);
                    String remaining = TotpUtils.getRemainingByCreation(createdAt, days);

                    Map<String, Object> result = new HashMap<>();
                    result.put("blockId", blockId);
                    result.put("currentCode", currentCode);
                    result.put("remainingTime", remaining);
                    result.put("durationDays", days);
                    result.put("label", config.getLabel());

                    return ServerResponse.ok().bodyValue(result);
                })
                .switchIfEmpty(ServerResponse.ok().bodyValue(
                        Map.of("error", "区块 TOTP 不存在")));
    }

    /**
     * 删除区块 TOTP
     */
    private Mono<ServerResponse> deleteBlockTotp(ServerRequest request) {
        String blockId = request.pathVariable("blockId");
        return removeBlockTotpConfig(blockId)
                .flatMap(removed -> {
                    if (removed) {
                        log.info("区块 TOTP 删除成功: blockId={}", blockId);
                        return ServerResponse.ok().bodyValue(
                                Map.of("success", true, "message", "删除成功"));
                    } else {
                        return ServerResponse.ok().bodyValue(
                                Map.of("success", false, "error", "区块 TOTP 不存在"));
                    }
                });
    }

    /**
     * 列出所有区块 TOTP
     */
    private Mono<ServerResponse> listBlockTotps(ServerRequest request) {
        return loadAllBlockTotpConfigs()
                .flatMap(blocks -> {
                    List<Map<String, Object>> result = blocks.entrySet().stream()
                            .filter(entry -> entry.getValue().isEnabled())
                            .map(entry -> {
                                String blockId = entry.getKey();
                                BlockTotpConfig config = entry.getValue();

                                LocalDateTime createdAt = LocalDateTime.parse(config.getCreatedAt());
                                int days = config.getDurationDays();
                                String currentCode = TotpUtils.getCodeByCreationTime(
                                        config.getSecret(), createdAt, days);
                                String remaining = TotpUtils.getRemainingByCreation(createdAt, days);

                                Map<String, Object> info = new HashMap<>();
                                info.put("blockId", blockId);
                                info.put("currentCode", currentCode);
                                info.put("remainingTime", remaining);
                                info.put("durationDays", days);
                                info.put("label", config.getLabel());
                                info.put("createdAt", config.getCreatedAt());
                                return info;
                            })
                            .collect(Collectors.toList());

                    return ServerResponse.ok().bodyValue(result);
                });
    }

    // ========== ConfigMap 操作 ==========

    /**
     * 保存区块 TOTP 配置到 ConfigMap
     */
    private Mono<Boolean> saveBlockTotpConfig(String blockId, BlockTotpConfig config) {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                .flatMap(configMap -> {
                    Map<String, String> data = configMap.getData();
                    if (data == null) {
                        data = new HashMap<>();
                        configMap.setData(data);
                    }
                    try {
                        // 加载现有配置
                        Map<String, BlockTotpConfig> blocks = new HashMap<>();
                        String existingJson = data.get("blocks");
                        if (existingJson != null && !existingJson.isEmpty()) {
                            blocks = OBJECT_MAPPER.readValue(existingJson,
                                    new TypeReference<Map<String, BlockTotpConfig>>() {});
                        }
                        // 添加新配置
                        blocks.put(blockId, config);
                        // 保存
                        data.put("blocks", OBJECT_MAPPER.writeValueAsString(blocks));
                        return client.update(configMap).thenReturn(true);
                    } catch (Exception e) {
                        log.error("保存区块 TOTP 配置失败", e);
                        return Mono.just(false);
                    }
                })
                .switchIfEmpty(createConfigMapWithBlock(blockId, config));
    }

    /**
     * 创建 ConfigMap 并添加区块配置
     */
    private Mono<Boolean> createConfigMapWithBlock(String blockId, BlockTotpConfig config) {
        ConfigMap configMap = new ConfigMap();
        configMap.setMetadata(new run.halo.app.extension.Metadata());
        configMap.getMetadata().setName(CONFIGMAP_NAME);

        try {
            Map<String, BlockTotpConfig> blocks = new HashMap<>();
            blocks.put(blockId, config);

            Map<String, String> data = new HashMap<>();
            data.put("blocks", OBJECT_MAPPER.writeValueAsString(blocks));
            configMap.setData(data);

            return client.create(configMap).thenReturn(true);
        } catch (Exception e) {
            log.error("创建 ConfigMap 失败", e);
            return Mono.just(false);
        }
    }

    /**
     * 加载单个区块 TOTP 配置
     */
    private Mono<BlockTotpConfig> loadBlockTotpConfig(String blockId) {
        return loadAllBlockTotpConfigs()
                .map(blocks -> blocks.get(blockId))
                .defaultIfEmpty(null);
    }

    /**
     * 加载所有区块 TOTP 配置
     */
    private Mono<Map<String, BlockTotpConfig>> loadAllBlockTotpConfigs() {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                .map(configMap -> {
                    Map<String, String> data = configMap.getData();
                    if (data == null) {
                        return new HashMap<String, BlockTotpConfig>();
                    }
                    String json = data.get("blocks");
                    if (json == null || json.isEmpty()) {
                        return new HashMap<String, BlockTotpConfig>();
                    }
                    try {
                        return OBJECT_MAPPER.readValue(json,
                                new TypeReference<Map<String, BlockTotpConfig>>() {});
                    } catch (Exception e) {
                        log.error("解析区块 TOTP 配置失败", e);
                        return new HashMap<String, BlockTotpConfig>();
                    }
                })
                .defaultIfEmpty(new HashMap<>());
    }

    /**
     * 删除区块 TOTP 配置
     */
    private Mono<Boolean> removeBlockTotpConfig(String blockId) {
        return client.fetch(ConfigMap.class, CONFIGMAP_NAME)
                .flatMap(configMap -> {
                    Map<String, String> data = configMap.getData();
                    if (data == null) {
                        return Mono.just(false);
                    }
                    try {
                        String json = data.get("blocks");
                        if (json == null || json.isEmpty()) {
                            return Mono.just(false);
                        }
                        Map<String, BlockTotpConfig> blocks = OBJECT_MAPPER.readValue(json,
                                new TypeReference<Map<String, BlockTotpConfig>>() {});
                        if (blocks.remove(blockId) != null) {
                            data.put("blocks", OBJECT_MAPPER.writeValueAsString(blocks));
                            return client.update(configMap).thenReturn(true);
                        }
                        return Mono.just(false);
                    } catch (Exception e) {
                        log.error("删除区块 TOTP 配置失败", e);
                        return Mono.just(false);
                    }
                })
                .defaultIfEmpty(false);
    }

    /**
     * 生成短 ID
     */
    private String generateShortId() {
        return Long.toString(System.currentTimeMillis(), 36).substring(4);
    }

    // ========== 公开的验证方法（供 EncryptContentProcessor 使用） ==========

    /**
     * 验证区块 TOTP 密码
     */
    public Mono<Boolean> verifyBlockTotp(String blockId, String inputCode) {
        return loadBlockTotpConfig(blockId)
                .map(config -> {
                    if (config == null || !config.isEnabled()) {
                        return false;
                    }
                    LocalDateTime createdAt = LocalDateTime.parse(config.getCreatedAt());
                    return TotpUtils.verifyCodeByCreationTime(
                            config.getSecret(), inputCode, createdAt, config.getDurationDays());
                })
                .defaultIfEmpty(false);
    }

    // ========== 数据类 ==========

    @Data
    public static class BlockTotpRequest {
        private String blockId;
        private int durationDays;
        private String label;
    }

    @Data
    public static class BlockTotpConfig {
        private String secret;
        private int durationDays;
        private String createdAt;
        private String label;
        private boolean enabled;
    }
}
