package run.halo.encrypt.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 加密区块数据模型
 * 存储文章中的加密内容块信息
 * 
 * @author Developer
 */
@Data
@ToString(callSuper = true)
@GVK(kind = EncryptBlock.KIND, group = "encrypt.halo.run", version = "v1alpha1", singular = "encryptblock", plural = "encryptblocks")
@EqualsAndHashCode(callSuper = true)
public class EncryptBlock extends AbstractExtension {

    public static final String KIND = "EncryptBlock";

    @Schema(requiredMode = REQUIRED)
    private EncryptBlockSpec spec;

    @Data
    public static class EncryptBlockSpec {

        /**
         * 关联的文章名称 (metadata.name)
         */
        @Schema(requiredMode = REQUIRED)
        private String postName;

        /**
         * 加密区块唯一ID
         */
        @Schema(requiredMode = REQUIRED)
        private String blockId;

        /**
         * 加密类型: password / paid
         */
        @Schema(requiredMode = REQUIRED)
        private String encryptType;

        /**
         * 密码哈希值 (BCrypt)，仅 password 类型使用
         */
        private String passwordHash;

        /**
         * 付费价格，仅 paid 类型使用
         */
        private BigDecimal price;

        /**
         * 外部商品ID，仅 paid 类型使用
         */
        private String productId;

        /**
         * 加密后的原始内容
         */
        @Schema(requiredMode = REQUIRED)
        private String encryptedContent;

        /**
         * 提示文字（显示在解锁界面）
         */
        private String hint;
    }
}
