package run.halo.encrypt.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.encrypt.extension.EncryptBlock;
import run.halo.encrypt.extension.UnlockRecord;

/**
 * 加密服务接口
 * 
 * @author Developer
 */
public interface EncryptService {

    /**
     * 验证密码并解锁
     * 
     * @param blockId   加密区块ID
     * @param password  用户输入的密码
     * @param sessionId 会话ID
     * @param userId    用户ID（可选）
     * @return 解锁结果，成功返回解密内容
     */
    Mono<UnlockResult> unlockWithPassword(String blockId, String password, String sessionId, String userId);

    /**
     * 检查是否已解锁
     * 
     * @param blockId   加密区块ID
     * @param sessionId 会话ID
     * @param userId    用户ID（可选）
     * @return 是否已解锁
     */
    Mono<Boolean> isUnlocked(String blockId, String sessionId, String userId);

    /**
     * 获取解密内容（需已解锁）
     * 
     * @param blockId   加密区块ID
     * @param sessionId 会话ID
     * @param userId    用户ID（可选）
     * @return 解密后的内容
     */
    Mono<String> getDecryptedContent(String blockId, String sessionId, String userId);

    /**
     * 根据文章名称获取所有加密区块
     * 
     * @param postName 文章名称
     * @return 加密区块列表
     */
    Flux<EncryptBlock> getEncryptBlocksByPost(String postName);

    /**
     * 保存加密区块
     * 
     * @param encryptBlock 加密区块
     * @return 保存后的加密区块
     */
    Mono<EncryptBlock> saveEncryptBlock(EncryptBlock encryptBlock);

    /**
     * 删除加密区块
     * 
     * @param blockId 区块ID
     * @return 完成信号
     */
    Mono<Void> deleteEncryptBlock(String blockId);

    /**
     * 解锁结果
     */
    record UnlockResult(boolean success, String message, String content) {
        public static UnlockResult success(String content) {
            return new UnlockResult(true, "解锁成功", content);
        }

        public static UnlockResult failure(String message) {
            return new UnlockResult(false, message, null);
        }
    }
}
