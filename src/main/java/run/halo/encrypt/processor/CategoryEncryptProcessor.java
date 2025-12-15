package run.halo.encrypt.processor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Category;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * 分类加密处理器
 * 从插件设置中读取分类加密配置，自动为配置的分类下的文章应用加密
 * 
 * 注意：此处理器在 ArticleEncryptProcessor 之后运行，但在 EncryptContentProcessor 之前
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CategoryEncryptProcessor implements ReactivePostContentHandler {

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient client;

    @Override
    public Mono<PostContentContext> handle(PostContentContext context) {
        // 获取文章的分类列表（这是分类的 metadata.name，如 category-xxx）
        List<String> categoryNames = context.getPost().getSpec().getCategories();

        if (categoryNames == null || categoryNames.isEmpty()) {
            return Mono.just(context);
        }

        // 先查询这些分类的 slug
        return getCategorySlugs(categoryNames)
                .flatMap(categorySlugs -> {
                    log.info("分类加密处理 - 文章: {}, 分类slugs: {}",
                            context.getPost().getMetadata().getName(), categorySlugs);

                    // 从设置中读取分类加密配置
                    return settingFetcher.get("categoryEncrypt")
                            .flatMap(setting -> processWithSettings(context, categorySlugs, setting))
                            .defaultIfEmpty(context);
                });
    }

    /**
     * 获取分类的 slug 列表
     */
    private Mono<Set<String>> getCategorySlugs(List<String> categoryNames) {
        return Flux.fromIterable(categoryNames)
                .flatMap(name -> client.get(Category.class, name)
                        .map(cat -> cat.getSpec().getSlug())
                        .onErrorResume(e -> Mono.empty()))
                .collect(Collectors.toSet());
    }

    /**
     * 使用设置处理内容
     */
    private Mono<PostContentContext> processWithSettings(PostContentContext context,
            Set<String> categorySlugs,
            JsonNode setting) {
        JsonNode categorySettings = setting.get("categoryEncryptSettings");
        if (categorySettings == null || categorySettings.isNull()) {
            log.debug("分类加密处理 - 未找到 categoryEncryptSettings");
            return Mono.just(context);
        }

        JsonNode categoryList = categorySettings.get("categoryList");
        if (categoryList == null || !categoryList.isArray() || categoryList.isEmpty()) {
            log.debug("分类加密处理 - categoryList 为空");
            return Mono.just(context);
        }

        log.info("分类加密处理 - 找到 {} 条分类配置", categoryList.size());

        // 查找匹配的分类配置
        for (JsonNode wrapper : categoryList) {
            // FormKit repeater 会把每个项包装在 "item" 中
            JsonNode item = wrapper.has("item") ? wrapper.get("item") : wrapper;

            String configCategorySlug = getTextValue(item, "categoryName");
            boolean enabled = getBooleanValue(item, "enabled", true);

            log.debug("检查分类配置: slug={}, enabled={}", configCategorySlug, enabled);

            if (!enabled || configCategorySlug == null || configCategorySlug.isEmpty()) {
                continue;
            }

            // 检查文章的分类 slug 是否匹配配置的 slug
            if (categorySlugs.contains(configCategorySlug)) {
                String password = getTextValue(item, "password");
                if (password == null || password.isEmpty()) {
                    log.warn("分类 {} 配置了加密但没有设置密码", configCategorySlug);
                    continue;
                }

                // 检查文章是否已经有自己的加密设置（文章级优先）
                var annotations = context.getPost().getMetadata().getAnnotations();
                if (annotations != null && annotations.containsKey("encrypt.halo.run/password")) {
                    log.debug("文章已有自己的加密设置，跳过分类加密");
                    return Mono.just(context);
                }

                // 检查内容是否已经包含 [encrypt] 或全文加密标记
                String content = context.getContent();
                if (content != null && (content.contains("[encrypt") || content.contains("encrypt:full"))) {
                    log.debug("文章内容已包含加密区块，跳过分类加密");
                    return Mono.just(context);
                }

                // 应用分类加密
                String hint = getTextValue(item, "hint");
                if (hint == null || hint.isEmpty()) {
                    hint = "此分类内容需要密码查看";
                }

                String wrappedContent = String.format(
                        "[encrypt type=\"password\" password=\"%s\" hint=\"%s\"]%s[/encrypt]",
                        escapeAttr(password),
                        escapeAttr(hint),
                        content);

                context.setContent(wrappedContent);
                log.info("分类加密已应用到文章: {}, 分类slug: {}",
                        context.getPost().getMetadata().getName(),
                        configCategorySlug);

                return Mono.just(context);
            }
        }

        log.debug("未找到匹配的分类加密配置");
        return Mono.just(context);
    }

    private String getTextValue(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }
        return fieldNode.asText();
    }

    private boolean getBooleanValue(JsonNode node, String field, boolean defaultValue) {
        JsonNode fieldNode = node.get(field);
        if (fieldNode == null || fieldNode.isNull()) {
            return defaultValue;
        }
        return fieldNode.asBoolean(defaultValue);
    }

    private String escapeAttr(String value) {
        if (value == null)
            return "";
        return value.replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
