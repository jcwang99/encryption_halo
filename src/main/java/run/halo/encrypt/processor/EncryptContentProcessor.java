package run.halo.encrypt.processor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * 文章内容处理器（后端验证版 + 安全功能）
 * 解析 [encrypt]...[/encrypt] 标记，替换为安全占位符
 * 支持密码错误锁定和解锁日志
 * 
 * 注意：此处理器应该最后运行，处理所有 [encrypt] 标签
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE) // 最后运行，处理所有 [encrypt] 标签
public class EncryptContentProcessor implements ReactivePostContentHandler {

    // 内存存储加密内容（生产环境应使用数据库）
    private static final Map<String, EncryptedBlock> ENCRYPTED_BLOCKS = new ConcurrentHashMap<>();

    // 失败尝试计数器（IP/Session -> blockId -> 失败次数和时间）
    private static final Map<String, FailedAttemptInfo> FAILED_ATTEMPTS = new ConcurrentHashMap<>();

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    // 安全配置（默认值）
    private static int maxFailAttempts = 5;
    private static int lockDurationMinutes = 15;
    private static boolean enableUnlockLog = true;

    private final ReactiveSettingFetcher settingFetcher;

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

        // 加载安全配置
        return loadSecuritySettings()
                .then(Mono.fromCallable(() -> {
                    String processedContent = processEncryptBlocks(content);
                    context.setContent(processedContent);
                    return context;
                }));
    }

    /**
     * 加载安全配置
     */
    private Mono<Void> loadSecuritySettings() {
        return settingFetcher.get("security")
                .doOnNext(setting -> {
                    if (setting != null) {
                        maxFailAttempts = setting.get("maxFailAttempts").asInt(5);
                        lockDurationMinutes = setting.get("lockDuration").asInt(15);
                        enableUnlockLog = setting.get("enableUnlockLog").asBoolean(true);
                    }
                })
                .then();
    }

    private String processEncryptBlocks(String content) {
        Matcher matcher = ENCRYPT_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String attributes = matcher.group(1);
            String encryptedContent = matcher.group(2).trim();

            // 解析属性
            String type = extractAttribute(attributes, "type", "password");
            String hint = extractAttribute(attributes, "hint", "");
            String hintType = extractAttribute(attributes, "hint-type", "text");
            String password = extractAttribute(attributes, "password", "");

            // 生成确定性的 blockId（基于内容哈希，刷新后保持一致）
            String blockId = generateDeterministicBlockId(encryptedContent, password);

            // 存储加密内容到内存（密码用 BCrypt 哈希）
            EncryptedBlock block = new EncryptedBlock(
                    blockId,
                    type,
                    PASSWORD_ENCODER.encode(password),
                    encryptedContent,
                    hint);
            ENCRYPTED_BLOCKS.put(blockId, block);

            // 生成占位符 HTML（不包含加密内容！）
            String placeholder = generatePlaceholder(blockId, type, hint, hintType);
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

    /**
     * 生成确定性的 blockId（基于内容和密码的哈希）
     * 相同的加密区块每次访问都会生成相同的 ID，确保锁定状态持久化
     */
    private String generateDeterministicBlockId(String content, String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String combined = content + "|" + password;
            byte[] hash = md.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 取前 12 个字符作为 blockId
            StringBuilder sb = new StringBuilder("block-");
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // 降级到简单哈希
            return "block-" + Integer.toHexString((content + password).hashCode());
        }
    }

    private String generatePlaceholder(String blockId, String type, String hint, String hintType) {
        String typeLabel = "password".equals(type) ? "密码保护" : "付费内容";
        String hintHtml = generateHintHtml(hint, hintType);

        // 注意：这里不输出 data-content，内容只能通过 API 获取
        return String.format(
                """
                        <div class="encrypt-block" data-block-id="%s" data-type="%s">
                            <div class="encrypt-lock-icon">
                                <svg viewBox="0 0 24 24" width="48" height="48">
                                    <path fill="currentColor" d="M12 17a2 2 0 0 0 2-2a2 2 0 0 0-2-2a2 2 0 0 0-2 2a2 2 0 0 0 2 2m6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 5-5a5 5 0 0 1 5 5v2h1m-6-5a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3z"/>
                                </svg>
                            </div>
                            <div class="encrypt-info">
                                <span class="encrypt-type-label">%s</span>
                                <p class="encrypt-desc">此内容已加密，请输入密码解锁</p>
                                %s
                            </div>
                            <div class="encrypt-unlock-form">
                                <input type="password" class="encrypt-password-input" placeholder="请输入密码" />
                                <button class="encrypt-unlock-btn" type="button">解锁</button>
                            </div>
                            <p class="encrypt-error-msg" style="display: none;"></p>
                        </div>
                        """,
                escapeHtml(blockId), escapeHtml(type), typeLabel, hintHtml);
    }

    /**
     * 根据提示类型生成 HTML
     * 支持: text（默认，转义）、html（原样输出）、image（图片标签）
     */
    private String generateHintHtml(String hint, String hintType) {
        if (hint == null || hint.isEmpty()) {
            return "";
        }

        return switch (hintType.toLowerCase()) {
            case "html" ->
                // HTML 类型：直接输出（允许链接等）
                "<div class=\"encrypt-hint encrypt-hint-html\">" + hint + "</div>";
            case "image" ->
                // 图片类型：生成 img 标签
                "<div class=\"encrypt-hint encrypt-hint-image\"><img src=\"" + escapeHtml(hint)
                        + "\" alt=\"提示图片\" /></div>";
            default ->
                // 文本类型：转义 HTML
                "<p class=\"encrypt-hint\">" + escapeHtml(hint) + "</p>";
        };
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

    /**
     * 验证密码并获取内容（供 API 调用）
     * 
     * @param blockId  区块ID
     * @param password 用户输入的密码
     * @param clientIp 客户端IP（用于锁定）
     */
    public static VerifyResult verifyAndGetContent(String blockId, String password, String clientIp) {
        // 检查是否被锁定
        String lockKey = clientIp + ":" + blockId;
        FailedAttemptInfo attemptInfo = FAILED_ATTEMPTS.get(lockKey);

        if (attemptInfo != null && attemptInfo.isLocked()) {
            long remainingMinutes = attemptInfo.getRemainingLockMinutes();
            String message = String.format("密码错误次数过多，请在 %d 分钟后重试", remainingMinutes);
            log.warn("解锁被锁定 - blockId: {}, IP: {}, 剩余锁定时间: {} 分钟", blockId, clientIp, remainingMinutes);
            return new VerifyResult(false, message, null, true, (int) remainingMinutes);
        }

        // 检查区块是否存在
        EncryptedBlock block = ENCRYPTED_BLOCKS.get(blockId);
        if (block == null) {
            return new VerifyResult(false, "加密区块不存在", null, false, 0);
        }

        // 验证密码
        if (!PASSWORD_ENCODER.matches(password, block.passwordHash)) {
            // 记录失败尝试
            recordFailedAttempt(lockKey);

            int remainingAttempts = getRemainingAttempts(lockKey);
            String message;
            if (remainingAttempts > 0) {
                message = String.format("密码错误，还剩 %d 次尝试机会", remainingAttempts);
            } else {
                message = String.format("密码错误次数过多，已锁定 %d 分钟", lockDurationMinutes);
            }

            if (enableUnlockLog) {
                log.info("解锁失败 - blockId: {}, IP: {}, 剩余尝试次数: {}", blockId, clientIp, remainingAttempts);
            }

            return new VerifyResult(false, message, null, remainingAttempts <= 0,
                    remainingAttempts <= 0 ? lockDurationMinutes : 0);
        }

        // 密码正确，清除失败记录
        FAILED_ATTEMPTS.remove(lockKey);

        if (enableUnlockLog) {
            log.info("解锁成功 - blockId: {}, IP: {}", blockId, clientIp);
        }

        return new VerifyResult(true, "解锁成功", block.content, false, 0);
    }

    /**
     * 兼容老接口（不带 IP 参数）
     */
    public static VerifyResult verifyAndGetContent(String blockId, String password) {
        return verifyAndGetContent(blockId, password, "unknown");
    }

    /**
     * 记录失败尝试
     */
    private static void recordFailedAttempt(String lockKey) {
        FailedAttemptInfo attemptInfo = FAILED_ATTEMPTS.computeIfAbsent(
                lockKey, k -> new FailedAttemptInfo());
        attemptInfo.increment();

        // 如果达到最大次数，设置锁定时间
        if (attemptInfo.failCount >= maxFailAttempts) {
            attemptInfo.lockUntil = Instant.now().plusSeconds(lockDurationMinutes * 60L);
        }
    }

    /**
     * 获取剩余尝试次数
     */
    private static int getRemainingAttempts(String lockKey) {
        FailedAttemptInfo attemptInfo = FAILED_ATTEMPTS.get(lockKey);
        if (attemptInfo == null) {
            return maxFailAttempts;
        }
        return Math.max(0, maxFailAttempts - attemptInfo.failCount);
    }

    /**
     * 检查区块是否存在
     */
    public static boolean blockExists(String blockId) {
        return ENCRYPTED_BLOCKS.containsKey(blockId);
    }

    /**
     * 通过 blockId 直接获取内容（用于会话记忆，已验证过的请求）
     * 注意：此方法不验证密码，调用方需确保用户已验证
     */
    public static String getContentByBlockId(String blockId) {
        EncryptedBlock block = ENCRYPTED_BLOCKS.get(blockId);
        return block != null ? block.content : null;
    }

    /**
     * 获取当前配置
     */
    public static SecurityConfig getSecurityConfig() {
        return new SecurityConfig(maxFailAttempts, lockDurationMinutes, enableUnlockLog);
    }

    // 内部数据类
    public record EncryptedBlock(
            String blockId,
            String type,
            String passwordHash,
            String content,
            String hint) {
    }

    public record VerifyResult(
            boolean success,
            String message,
            String content,
            boolean locked,
            int lockRemainingMinutes) {
    }

    public record SecurityConfig(
            int maxFailAttempts,
            int lockDurationMinutes,
            boolean enableUnlockLog) {
    }

    // 失败尝试信息
    private static class FailedAttemptInfo {
        int failCount = 0;
        Instant lockUntil = null;
        Instant lastAttempt = Instant.now();

        void increment() {
            // 如果距离上次尝试超过锁定时间，重置计数
            if (lockUntil != null && Instant.now().isAfter(lockUntil)) {
                failCount = 0;
                lockUntil = null;
            }
            failCount++;
            lastAttempt = Instant.now();
        }

        boolean isLocked() {
            if (lockUntil == null) {
                return false;
            }
            if (Instant.now().isAfter(lockUntil)) {
                // 锁定已过期，重置
                failCount = 0;
                lockUntil = null;
                return false;
            }
            return true;
        }

        long getRemainingLockMinutes() {
            if (lockUntil == null) {
                return 0;
            }
            long seconds = lockUntil.getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(1, (seconds + 59) / 60); // 向上取整
        }
    }
}
