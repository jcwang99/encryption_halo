package run.halo.encrypt.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 解锁记录数据模型
 * 存储用户/访客的解锁状态
 * 
 * @author Developer
 */
@Data
@ToString(callSuper = true)
@GVK(kind = UnlockRecord.KIND, group = "encrypt.halo.run", version = "v1alpha1", singular = "unlockrecord", plural = "unlockrecords")
@EqualsAndHashCode(callSuper = true)
public class UnlockRecord extends AbstractExtension {

    public static final String KIND = "UnlockRecord";

    @Schema(requiredMode = REQUIRED)
    private UnlockRecordSpec spec;

    @Data
    public static class UnlockRecordSpec {

        /**
         * 加密区块ID
         */
        @Schema(requiredMode = REQUIRED)
        private String blockId;

        /**
         * 用户ID（登录用户）
         */
        private String userId;

        /**
         * 会话ID（匿名访客）
         */
        private String sessionId;

        /**
         * 解锁时间
         */
        @Schema(requiredMode = REQUIRED)
        private Instant unlockTime;

        /**
         * 过期时间，null 表示永久有效
         */
        private Instant expireTime;

        /**
         * 解锁方式: password / paid
         */
        @Schema(requiredMode = REQUIRED)
        private String unlockType;
    }
}
