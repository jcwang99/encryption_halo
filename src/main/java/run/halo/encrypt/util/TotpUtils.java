package run.halo.encrypt.util;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base32;

/**
 * TOTP 动态密码工具类
 * 支持可配置的有效期：30秒/1小时/1天/1周/1月
 * 
 * @author Developer
 */
@Slf4j
public class TotpUtils {

    private static final Base32 BASE32 = new Base32();
    private static final int CODE_DIGITS = 6;
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    /**
     * 密码有效期枚举
     */
    @Getter
    public enum ValidityPeriod {
        SECONDS_30(30, "30秒", "每30秒更换"),
        HOUR_1(3600, "1小时", "每整点更换"),
        DAY_1(86400, "1天", "每日0点更换"),
        WEEK_1(604800, "1周", "每周一0点更换"),
        MONTH_1(2592000, "1月", "每月1日0点更换");

        private final int seconds;
        private final String label;
        private final String description;

        ValidityPeriod(int seconds, String label, String description) {
            this.seconds = seconds;
            this.label = label;
            this.description = description;
        }
    }

    /**
     * 生成随机密钥（Base32 编码，20字节）
     */
    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return BASE32.encodeToString(bytes).replaceAll("=", "");
    }

    /**
     * 获取当前有效的 TOTP 密码
     */
    public static String getCurrentCode(String secret, ValidityPeriod period) {
        long counter = getCounter(period, Instant.now());
        return generateCode(secret, counter);
    }

    /**
     * 验证 TOTP 密码（允许容错窗口）
     */
    public static boolean verifyCode(String secret, String inputCode, ValidityPeriod period) {
        if (secret == null || inputCode == null || inputCode.length() != CODE_DIGITS) {
            return false;
        }

        // 只验证数字
        if (!inputCode.matches("\\d{6}")) {
            return false;
        }

        long currentCounter = getCounter(period, Instant.now());

        // 对于短周期（30秒），允许前后1个窗口的容错
        // 对于长周期（1天+），只验证当前窗口
        int tolerance = period.getSeconds() <= 3600 ? 1 : 0;

        for (int i = -tolerance; i <= tolerance; i++) {
            String expectedCode = generateCode(secret, currentCounter + i);
            if (expectedCode.equals(inputCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取过期时间
     */
    public static Instant getExpirationTime(ValidityPeriod period) {
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();

        switch (period) {
            case SECONDS_30:
                // 下一个30秒边界
                long epochSecond = now.getEpochSecond();
                long next30 = ((epochSecond / 30) + 1) * 30;
                return Instant.ofEpochSecond(next30);

            case HOUR_1:
                // 下一个整点
                LocalDateTime nextHour = LocalDateTime.now(zone)
                        .truncatedTo(ChronoUnit.HOURS)
                        .plusHours(1);
                return nextHour.atZone(zone).toInstant();

            case DAY_1:
                // 今天23:59:59
                LocalDateTime endOfDay = LocalDate.now(zone)
                        .atTime(23, 59, 59);
                return endOfDay.atZone(zone).toInstant();

            case WEEK_1:
                // 本周日23:59:59
                LocalDateTime endOfWeek = LocalDate.now(zone)
                        .with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
                        .atTime(23, 59, 59);
                return endOfWeek.atZone(zone).toInstant();

            case MONTH_1:
                // 本月最后一天23:59:59
                LocalDateTime endOfMonth = LocalDate.now(zone)
                        .with(TemporalAdjusters.lastDayOfMonth())
                        .atTime(23, 59, 59);
                return endOfMonth.atZone(zone).toInstant();

            default:
                return now.plusSeconds(period.getSeconds());
        }
    }

    /**
     * 获取剩余时间描述
     */
    public static String getRemainingTimeDescription(ValidityPeriod period) {
        Instant expiration = getExpirationTime(period);
        Instant now = Instant.now();
        long remainingSeconds = expiration.getEpochSecond() - now.getEpochSecond();

        if (remainingSeconds <= 0) {
            return "即将更换";
        }

        if (remainingSeconds < 60) {
            return remainingSeconds + "秒";
        }

        if (remainingSeconds < 3600) {
            long minutes = remainingSeconds / 60;
            long seconds = remainingSeconds % 60;
            return minutes + "分" + seconds + "秒";
        }

        if (remainingSeconds < 86400) {
            long hours = remainingSeconds / 3600;
            long minutes = (remainingSeconds % 3600) / 60;
            return hours + "小时" + minutes + "分";
        }

        long days = remainingSeconds / 86400;
        long hours = (remainingSeconds % 86400) / 3600;
        return days + "天" + hours + "小时";
    }

    /**
     * 获取当前周期的描述
     */
    public static String getCurrentPeriodDescription(ValidityPeriod period) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        switch (period) {
            case SECONDS_30:
            case HOUR_1:
                return "当前密码";
            case DAY_1:
                return "今日密码 (" + today + ")";
            case WEEK_1:
                return "本周密码 (第" + today.get(java.time.temporal.WeekFields.ISO.weekOfYear()) + "周)";
            case MONTH_1:
                return today.getYear() + "年" + today.getMonthValue() + "月密码";
            default:
                return "当前密码";
        }
    }

    /**
     * 计算时间计数器
     */
    private static long getCounter(ValidityPeriod period, Instant time) {
        ZoneId zone = ZoneId.systemDefault();

        switch (period) {
            case SECONDS_30:
                return time.getEpochSecond() / 30;

            case HOUR_1:
                return time.getEpochSecond() / 3600;

            case DAY_1:
                // 基于本地日期
                return LocalDate.ofInstant(time, zone).toEpochDay();

            case WEEK_1:
                // 基于ISO周数
                LocalDate date = LocalDate.ofInstant(time, zone);
                return date.getYear() * 100L + date.get(java.time.temporal.WeekFields.ISO.weekOfYear());

            case MONTH_1:
                // 基于年月
                LocalDate monthDate = LocalDate.ofInstant(time, zone);
                return monthDate.getYear() * 100L + monthDate.getMonthValue();

            default:
                return time.getEpochSecond() / period.getSeconds();
        }
    }

    /**
     * 生成 TOTP 码
     */
    private static String generateCode(String secret, long counter) {
        try {
            byte[] key = BASE32.decode(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data);

            // 动态截取
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("TOTP 生成失败", e);
            return "000000";
        }
    }
}
