package run.halo.encrypt.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 分类加密配置
 * 为文章分类设置统一的加密密码
 * 
 * @author Developer
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "encrypt.halo.run", version = "v1alpha1", kind = "CategoryEncrypt", plural = "categoryencrypts", singular = "categoryencrypt")
public class CategoryEncrypt extends AbstractExtension {

    @Schema(description = "分类加密规格")
    private CategoryEncryptSpec spec;

    @Data
    public static class CategoryEncryptSpec {

        @Schema(description = "分类名称（slug）", required = true)
        private String categoryName;

        @Schema(description = "加密密码", required = true)
        private String password;

        @Schema(description = "提示文字")
        private String hint;

        @Schema(description = "是否启用", defaultValue = "true")
        private Boolean enabled = true;

        @Schema(description = "加密类型", defaultValue = "password")
        private String encryptType = "password";
    }
}
