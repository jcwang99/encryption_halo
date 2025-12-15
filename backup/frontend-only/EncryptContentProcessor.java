package run.halo.encrypt.processor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * 文章内容处理器
 * 解析 [encrypt]...[/encrypt] 标记，替换为加密占位符
 * 注意：此处理器只负责替换显示，不存储数据
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptContentProcessor implements ReactivePostContentHandler {

    // 匹配 [encrypt type="password" password="xxx"]内容[/encrypt]
    private static final Pattern ENCRYPT_PATTERN = Pattern.compile(
            "\\[encrypt\\s+([^\\]]+)\\](.*?)\\[/encrypt\\]",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // 匹配属性
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(\\w+)\\s*=\\s*[\"']([^\"']*)[\"']");

    @Override
    public Mono<PostContentContext> handle(PostContentContext context) {
        String content = context.getContent();

        if (content == null || !content.contains("[encrypt")) {
            return Mono.just(context);
        }

        String processedContent = processEncryptBlocks(content);
        context.setContent(processedContent);
        return Mono.just(context);
    }

    private String processEncryptBlocks(String content) {
        Matcher matcher = ENCRYPT_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String attributes = matcher.group(1);
            String encryptedContent = matcher.group(2);

            // 解析属性
            String type = extractAttribute(attributes, "type", "password");
            String hint = extractAttribute(attributes, "hint", "");
            String blockId = extractAttribute(attributes, "id",
                    "block-" + UUID.randomUUID().toString().substring(0, 8));
            String password = extractAttribute(attributes, "password", "");

            // 生成占位符 HTML（不存储到数据库，密码直接编码在页面中用于验证）
            String placeholder = generatePlaceholder(blockId, type, hint, password, encryptedContent);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String extractAttribute(String attributes, String name, String defaultValue) {
        Matcher attrMatcher = ATTR_PATTERN.matcher(attributes);
        while (attrMatcher.find()) {
            if (name.equalsIgnoreCase(attrMatcher.group(1))) {
                return attrMatcher.group(2);
            }
        }
        return defaultValue;
    }

    private String generatePlaceholder(String blockId, String type, String hint,
            String password, String encryptedContent) {
        String typeLabel = "password".equals(type) ? "密码保护" : "付费内容";
        String hintHtml = hint.isEmpty() ? "" : "<p class=\"encrypt-hint\">" + escapeHtml(hint) + "</p>";

        // 将加密内容 Base64 编码存储在 data 属性中（前端解锁后显示）
        String encodedContent = java.util.Base64.getEncoder()
                .encodeToString(encryptedContent.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 将密码进行简单哈希（前端验证用，不是真正的安全措施，仅作为演示）
        String passwordHash = simpleHash(password);

        return String.format(
                """
                        <div class="encrypt-block" data-block-id="%s" data-type="%s" data-hash="%s" data-content="%s">
                            <div class="encrypt-lock-icon">
                                <svg viewBox="0 0 24 24" width="48" height="48">
                                    <path fill="currentColor" d="M12 17a2 2 0 0 0 2-2a2 2 0 0 0-2-2a2 2 0 0 0-2 2a2 2 0 0 0 2 2m6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 5-5a5 5 0 0 1 5 5v2h1m-6-5a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3z"/>
                                </svg>
                            </div>
                            <div class="encrypt-info">
                                <span class="encrypt-type-label">%s</span>
                                <p class="encrypt-desc">此内容已加密，请解锁后查看</p>
                                %s
                            </div>
                            <div class="encrypt-unlock-form">
                                <input type="password" class="encrypt-password-input" placeholder="请输入密码" />
                                <button class="encrypt-unlock-btn" type="button">解锁</button>
                            </div>
                            <p class="encrypt-error-msg" style="display: none;"></p>
                        </div>
                        """,
                escapeHtml(blockId), escapeHtml(type), passwordHash, encodedContent, typeLabel, hintHtml);
    }

    /**
     * 简单哈希（仅用于前端验证，不是安全哈希）
     */
    private String simpleHash(String input) {
        if (input == null || input.isEmpty())
            return "";
        int hash = 0;
        for (char c : input.toCharArray()) {
            hash = 31 * hash + c;
        }
        return Integer.toHexString(hash);
    }

    private String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
