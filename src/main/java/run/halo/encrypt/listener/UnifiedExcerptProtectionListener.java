package run.halo.encrypt.listener;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 统一的摘要保护监听器
 * 在文章发布时检查所有加密场景并保护摘要
 * 
 * 支持的加密场景：
 * 1. 部分加密 - 内容中包含 [encrypt] 标签
 * 2. 分类加密 - 文章属于加密分类
 * 3. 全文加密 - 使用 annotation 或 <!--encrypt:full--> 注释
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnifiedExcerptProtectionListener {

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;
    private final PostContentService postContentService;

    private static final String ORIGINAL_EXCERPT_ANNOTATION = "encrypt.halo.run/original-excerpt";
    private static final String EXCERPT_PROTECTED_ANNOTATION = "encrypt.halo.run/excerpt-protected";

    // 全文加密 annotation
    private static final String ANNOTATION_PASSWORD = "encrypt.halo.run/password";

    // 匹配 [encrypt] 标签
    private static final Pattern ENCRYPT_TAG_PATTERN = Pattern.compile(
            "\\[encrypt[^\\]]*\\]",
            Pattern.CASE_INSENSITIVE);

    // 匹配 <!--encrypt:full--> 注释
    private static final Pattern ENCRYPT_FULL_PATTERN = Pattern.compile(
            "(?:<!--|&lt;!--)\\s*encrypt:full",
            Pattern.CASE_INSENSITIVE);

    @Async
    @EventListener(PostPublishedEvent.class)
    public void onPostPublished(PostPublishedEvent event) {
        String postName = event.getName();
        log.info("统一摘要保护 - 检测到文章发布事件: {}", postName);

        client.fetch(Post.class, postName)
                .flatMap(this::checkAndProtectExcerpt)
                .subscribe(
                        result -> log.info("摘要保护结果: {}, 文章: {}", result, postName),
                        error -> log.error("摘要保护失败: {}", postName, error));
    }

    private Mono<String> checkAndProtectExcerpt(Post post) {
        String postName = post.getMetadata().getName();

        // 检查是否已保护
        var annotations = post.getMetadata().getAnnotations();
        if (annotations != null && "true".equals(annotations.get(EXCERPT_PROTECTED_ANNOTATION))) {
            // 检查摘要是否仍然是保护状态
            var excerpt = post.getSpec().getExcerpt();
            if (excerpt != null && excerpt.getRaw() != null && excerpt.getRaw().startsWith("🔒")) {
                return Mono.just("已保护");
            }
        }

        // 1. 检查全文加密（annotation 方式）
        if (annotations != null && annotations.containsKey(ANNOTATION_PASSWORD)) {
            String password = annotations.get(ANNOTATION_PASSWORD);
            if (password != null && !password.isEmpty()) {
                String hint = annotations.getOrDefault("encrypt.halo.run/hint", "此内容需要密码才能查看");
                return protectExcerpt(post, hint, "全文加密(annotation)");
            }
        }

        // 2. 获取文章内容检查其他加密场景
        return postContentService.getReleaseContent(postName)
                .flatMap(contentWrapper -> {
                    String content = contentWrapper.getRaw();
                    if (content == null) {
                        content = "";
                    }

                    // 检查全文加密（注释方式）
                    if (ENCRYPT_FULL_PATTERN.matcher(content).find()) {
                        return protectExcerpt(post, "此内容需要密码才能查看", "全文加密(注释)");
                    }

                    // 检查部分加密（[encrypt] 标签）
                    if (ENCRYPT_TAG_PATTERN.matcher(content).find()) {
                        return protectExcerpt(post, "部分内容已加密", "部分加密");
                    }

                    // 检查分类加密
                    return checkCategoryEncryption(post);
                })
                .onErrorResume(e -> {
                    log.warn("获取文章内容失败: {}, 错误: {}", postName, e.getMessage());
                    // 即使获取内容失败，仍然检查分类加密
                    return checkCategoryEncryption(post);
                });
    }

    private Mono<String> checkCategoryEncryption(Post post) {
        List<String> categoryNames = post.getSpec().getCategories();

        if (categoryNames == null || categoryNames.isEmpty()) {
            return Mono.just("无需保护");
        }

        // 获取分类 slugs
        return getCategorySlugs(categoryNames)
                .flatMap(categorySlugs -> checkCategoryEncryptionConfig(post, categorySlugs));
    }

    private Mono<Set<String>> getCategorySlugs(List<String> categoryNames) {
        return Flux.fromIterable(categoryNames)
                .flatMap(name -> client.fetch(Category.class, name)
                        .map(cat -> cat.getSpec().getSlug())
                        .onErrorResume(e -> Mono.empty()))
                .collect(HashSet::new, Set::add);
    }

    private Mono<String> checkCategoryEncryptionConfig(Post post, Set<String> categorySlugs) {
        return settingFetcher.get("categoryEncrypt")
                .flatMap(setting -> {
                    JsonNode categorySettings = setting.get("categoryEncryptSettings");
                    if (categorySettings == null || categorySettings.isNull()) {
                        return Mono.just("无分类加密配置");
                    }

                    JsonNode categoryList = categorySettings.get("categoryList");
                    if (categoryList == null || !categoryList.isArray()) {
                        return Mono.just("分类列表为空");
                    }

                    // 查找匹配的加密分类
                    for (JsonNode wrapper : categoryList) {
                        JsonNode item = wrapper.has("item") ? wrapper.get("item") : wrapper;
                        String configSlug = getTextValue(item, "categoryName");
                        boolean enabled = getBooleanValue(item, "enabled", true);
                        String hint = getTextValue(item, "hint");

                        if (enabled && configSlug != null && categorySlugs.contains(configSlug)) {
                            if (hint == null || hint.isEmpty()) {
                                hint = "此分类内容需要密码查看";
                            }
                            return protectExcerpt(post, hint, "分类加密");
                        }
                    }

                    return Mono.just("非加密分类");
                })
                .defaultIfEmpty("无需保护");
    }

    private Mono<String> protectExcerpt(Post post, String hint, String encryptType) {
        var spec = post.getSpec();
        var excerpt = spec.getExcerpt();

        if (excerpt == null) {
            return Mono.just("无摘要");
        }

        String currentExcerpt = excerpt.getRaw();
        String protectedExcerpt = "🔒 " + hint;

        // 如果摘要已经被保护，跳过
        if (currentExcerpt != null && currentExcerpt.startsWith("🔒")) {
            return Mono.just("摘要已保护");
        }

        // 备份和标记
        var annotations = post.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new java.util.HashMap<>();
            post.getMetadata().setAnnotations(annotations);
        }

        // 备份原始摘要
        if (!annotations.containsKey(ORIGINAL_EXCERPT_ANNOTATION) && currentExcerpt != null) {
            annotations.put(ORIGINAL_EXCERPT_ANNOTATION, currentExcerpt);
        }
        annotations.put(EXCERPT_PROTECTED_ANNOTATION, "true");

        // 设置保护后的摘要
        excerpt.setRaw(protectedExcerpt);
        excerpt.setAutoGenerate(false);

        // 同时更新 status 中的摘要
        if (post.getStatus() != null) {
            post.getStatus().setExcerpt(protectedExcerpt);
        }

        // 持久化更改
        return client.update(post)
                .thenReturn(encryptType + " - 摘要已保护: " + post.getMetadata().getName());
    }

    private String getTextValue(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return (fieldNode == null || fieldNode.isNull()) ? null : fieldNode.asText();
    }

    private boolean getBooleanValue(JsonNode node, String field, boolean defaultValue) {
        JsonNode fieldNode = node.get(field);
        return (fieldNode == null || fieldNode.isNull()) ? defaultValue : fieldNode.asBoolean(defaultValue);
    }
}
