package run.halo.encrypt.processor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * 全文加密处理器
 * 支持两种方式：
 * 1. 文章元数据中的 encrypt.halo.run/password 注解
 * 2. 内容中的 <!--encrypt:full ... --> 注释标记（可以在任意位置）
 * 
 * 注意：此处理器应该最先运行，为 EncryptContentProcessor 生成 [encrypt] 标签
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE) // 最先运行
public class ArticleEncryptProcessor implements ReactivePostContentHandler {

    // 元数据注解键
    private static final String ANNOTATION_PASSWORD = "encrypt.halo.run/password";
    private static final String ANNOTATION_HINT = "encrypt.halo.run/hint";
    private static final String ANNOTATION_TYPE = "encrypt.halo.run/type";

    // HTML 注释格式，支持转义和未转义两种形式：
    // 1. 未转义: <!--encrypt:full password="xxx" -->
    // 2. 已转义: &lt;!--encrypt:full password="xxx" --&gt;
    // 注意：使用 [\s\S] 代替 . 以匹配跨行内容
    private static final Pattern ENCRYPT_COMMENT_PATTERN = Pattern.compile(
            "(?:<!--|&lt;!--)\\s*encrypt:full([\\s\\S]+?)(?:-->|--&gt;)",
            Pattern.CASE_INSENSITIVE);

    // 属性解析 - 支持跨行
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(\\w+)\\s*=\\s*\"([^\"]*?)\"",
            Pattern.DOTALL);

    @Override
    public Mono<PostContentContext> handle(PostContentContext context) {
        String content = context.getContent();

        if (content == null || content.isEmpty()) {
            return Mono.just(context);
        }

        // 优先检查 HTML 注释格式（编辑器插入的）
        if (content.contains("encrypt:full")) {
            return processCommentFormat(context);
        }

        // 然后检查文章的 annotations
        Map<String, String> annotations = context.getPost().getMetadata().getAnnotations();

        if (annotations == null || !annotations.containsKey(ANNOTATION_PASSWORD)) {
            return Mono.just(context);
        }

        String password = annotations.get(ANNOTATION_PASSWORD);
        if (password == null || password.isEmpty()) {
            return Mono.just(context);
        }

        // 获取可选的提示和类型
        String hint = annotations.getOrDefault(ANNOTATION_HINT, "此内容需要密码才能查看");
        String type = annotations.getOrDefault(ANNOTATION_TYPE, "password");

        // 将整篇文章内容包装在 [encrypt] 标签中
        String wrappedContent = wrapContent(content, password, hint, "text", type);
        context.setContent(wrappedContent);

        log.info("全文加密已应用到文章（注解方式）: {}", context.getPost().getMetadata().getName());

        return Mono.just(context);
    }

    /**
     * 处理 HTML 注释格式的全文加密
     */
    private Mono<PostContentContext> processCommentFormat(PostContentContext context) {
        String content = context.getContent();
        Matcher matcher = ENCRYPT_COMMENT_PATTERN.matcher(content);

        if (!matcher.find()) {
            log.warn("未能匹配全文加密注释，内容: {}",
                    content.substring(0, Math.min(200, content.length())));
            return Mono.just(context);
        }

        String attributesBlock = matcher.group(1);

        // 解析属性
        String password = extractAttribute(attributesBlock, "password", "");
        String hint = extractAttribute(attributesBlock, "hint", "");
        String hintType = extractAttribute(attributesBlock, "hintType", "text");
        String type = "password"; // 目前只支持密码模式

        if (password.isEmpty()) {
            log.warn("全文加密注释缺少密码");
            return Mono.just(context);
        }

        // 移除注释标记，获取干净的内容
        String cleanContent = matcher.replaceAll("").trim();

        // 包装整个内容
        String wrappedContent = wrapContent(cleanContent, password, hint, hintType, type);
        context.setContent(wrappedContent);

        log.info("全文加密已应用到文章（注释方式）: {}",
                context.getPost().getMetadata().getName());

        return Mono.just(context);
    }

    /**
     * 提取属性值
     */
    private String extractAttribute(String block, String name, String defaultValue) {
        Matcher attrMatcher = ATTR_PATTERN.matcher(block);
        while (attrMatcher.find()) {
            if (name.equalsIgnoreCase(attrMatcher.group(1))) {
                return unescapeAttr(attrMatcher.group(2));
            }
        }
        return defaultValue;
    }

    /**
     * 包装内容为加密区块
     */
    private String wrapContent(String content, String password, String hint,
            String hintType, String type) {
        StringBuilder tag = new StringBuilder();
        tag.append("[encrypt type=\"").append(escapeAttr(type)).append("\"");
        tag.append(" password=\"").append(escapeAttr(password)).append("\"");

        if (hint != null && !hint.isEmpty()) {
            tag.append(" hint=\"").append(escapeAttr(hint)).append("\"");
            tag.append(" hint-type=\"").append(escapeAttr(hintType)).append("\"");
        }

        tag.append("]");
        tag.append(content);
        tag.append("[/encrypt]");

        return tag.toString();
    }

    /**
     * 转义属性值中的特殊字符
     */
    private String escapeAttr(String value) {
        if (value == null)
            return "";
        return value.replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * 反转义属性值
     */
    private String unescapeAttr(String value) {
        if (value == null)
            return "";
        return value.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
