package run.halo.encrypt.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TOTP 动态密码实体
 * 支持多密码管理和自定义起始时间
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TotpPassword {

    /**
     * 唯一标识
     */
    private String id;

    /**
     * 密码名称（如"VIP周密码"）
     */
    private String name;

    /**
     * TOTP 密钥（Base32 编码）
     */
    private String secret;

    /**
     * 创建时间（作为周期起始时间）
     */
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 有效天数（1/7/30/90/365）
     */
    private int durationDays;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 创建新密码
     */
    public static TotpPassword create(String name, String secret, int durationDays) {
        return TotpPassword.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .secret(secret)
                .createdAt(LocalDateTime.now())
                .durationDays(durationDays)
                .enabled(true)
                .build();
    }

    /**
     * 计算当前密码所处的周期编号
     * 从创建时间开始计算
     */
    @JsonIgnore
    public long getCurrentPeriod() {
        if (createdAt == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        long daysPassed = java.time.Duration.between(createdAt, now).toDays();
        return daysPassed / durationDays;
    }

    /**
     * 获取当前周期的起始时间
     */
    @JsonIgnore
    public LocalDateTime getPeriodStartTime() {
        if (createdAt == null) {
            return LocalDateTime.now();
        }
        long period = getCurrentPeriod();
        return createdAt.plusDays(period * durationDays);
    }

    /**
     * 获取当前周期的结束时间
     */
    @JsonIgnore
    public LocalDateTime getPeriodEndTime() {
        return getPeriodStartTime().plusDays(durationDays);
    }

    /**
     * 获取剩余时间描述
     */
    @JsonIgnore
    public String getRemainingDescription() {
        LocalDateTime endTime = getPeriodEndTime();
        LocalDateTime now = LocalDateTime.now();

        if (now.isAfter(endTime)) {
            return "已过期";
        }

        java.time.Duration remaining = java.time.Duration.between(now, endTime);
        long days = remaining.toDays();
        long hours = remaining.toHours() % 24;
        long minutes = remaining.toMinutes() % 60;

        if (days > 0) {
            return days + "天" + hours + "小时";
        } else if (hours > 0) {
            return hours + "小时" + minutes + "分钟";
        } else {
            return minutes + "分钟";
        }
    }
}
