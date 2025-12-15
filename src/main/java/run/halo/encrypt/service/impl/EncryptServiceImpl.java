package run.halo.encrypt.service.impl;

import static run.halo.app.extension.index.query.QueryFactory.and;
import static run.halo.app.extension.index.query.QueryFactory.equal;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.encrypt.extension.EncryptBlock;
import run.halo.encrypt.extension.UnlockRecord;
import run.halo.encrypt.service.EncryptService;

/**
 * 加密服务实现
 * 
 * @author Developer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EncryptServiceImpl implements EncryptService {

    private final ReactiveExtensionClient extensionClient;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Mono<UnlockResult> unlockWithPassword(String blockId, String password,
            String sessionId, String userId) {
        return findEncryptBlockById(blockId)
                .flatMap(block -> {
                    if (!"password".equals(block.getSpec().getEncryptType())) {
                        return Mono.just(UnlockResult.failure("此内容不支持密码解锁"));
                    }

                    String storedHash = block.getSpec().getPasswordHash();
                    if (!passwordEncoder.matches(password, storedHash)) {
                        return Mono.just(UnlockResult.failure("密码错误"));
                    }

                    // 密码正确，创建解锁记录
                    return createUnlockRecord(blockId, sessionId, userId, "password")
                            .thenReturn(UnlockResult.success(block.getSpec().getEncryptedContent()));
                })
                .defaultIfEmpty(UnlockResult.failure("加密区块不存在"));
    }

    @Override
    public Mono<Boolean> isUnlocked(String blockId, String sessionId, String userId) {
        return findUnlockRecord(blockId, sessionId, userId)
                .map(record -> {
                    // 检查是否过期
                    Instant expireTime = record.getSpec().getExpireTime();
                    if (expireTime != null && expireTime.isBefore(Instant.now())) {
                        return false;
                    }
                    return true;
                })
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<String> getDecryptedContent(String blockId, String sessionId, String userId) {
        return isUnlocked(blockId, sessionId, userId)
                .flatMap(unlocked -> {
                    if (!unlocked) {
                        return Mono.empty();
                    }
                    return findEncryptBlockById(blockId)
                            .map(block -> block.getSpec().getEncryptedContent());
                });
    }

    @Override
    public Flux<EncryptBlock> getEncryptBlocksByPost(String postName) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
                equal("spec.postName", postName)));
        return extensionClient.listAll(EncryptBlock.class, listOptions, Sort.unsorted());
    }

    @Override
    public Mono<EncryptBlock> saveEncryptBlock(EncryptBlock encryptBlock) {
        // 如果有明文密码，先进行哈希
        if (encryptBlock.getSpec() != null
                && StringUtils.hasText(encryptBlock.getSpec().getPasswordHash())
                && !encryptBlock.getSpec().getPasswordHash().startsWith("$2")) {
            String hashedPassword = passwordEncoder.encode(encryptBlock.getSpec().getPasswordHash());
            encryptBlock.getSpec().setPasswordHash(hashedPassword);
        }

        if (encryptBlock.getMetadata() == null ||
                !StringUtils.hasText(encryptBlock.getMetadata().getName())) {
            // 新建
            Metadata metadata = new Metadata();
            metadata.setName("encrypt-block-" + UUID.randomUUID().toString().substring(0, 8));
            encryptBlock.setMetadata(metadata);
            return extensionClient.create(encryptBlock);
        } else {
            // 更新
            return extensionClient.update(encryptBlock);
        }
    }

    @Override
    public Mono<Void> deleteEncryptBlock(String blockId) {
        return findEncryptBlockById(blockId)
                .flatMap(extensionClient::delete)
                .then();
    }

    private Mono<EncryptBlock> findEncryptBlockById(String blockId) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
                equal("spec.blockId", blockId)));
        return extensionClient.listAll(EncryptBlock.class, listOptions, Sort.unsorted())
                .next();
    }

    private Mono<UnlockRecord> findUnlockRecord(String blockId, String sessionId, String userId) {
        var listOptions = new ListOptions();

        if (StringUtils.hasText(userId)) {
            listOptions.setFieldSelector(FieldSelector.of(
                    and(equal("spec.blockId", blockId), equal("spec.userId", userId))));
        } else if (StringUtils.hasText(sessionId)) {
            listOptions.setFieldSelector(FieldSelector.of(
                    and(equal("spec.blockId", blockId), equal("spec.sessionId", sessionId))));
        } else {
            return Mono.empty();
        }

        return extensionClient.listAll(UnlockRecord.class, listOptions, Sort.unsorted())
                .next();
    }

    private Mono<UnlockRecord> createUnlockRecord(String blockId, String sessionId,
            String userId, String unlockType) {
        // 先检查是否已有解锁记录
        return findUnlockRecord(blockId, sessionId, userId)
                .switchIfEmpty(Mono.defer(() -> {
                    UnlockRecord record = new UnlockRecord();
                    Metadata metadata = new Metadata();
                    metadata.setName("unlock-" + UUID.randomUUID().toString().substring(0, 8));
                    record.setMetadata(metadata);

                    UnlockRecord.UnlockRecordSpec spec = new UnlockRecord.UnlockRecordSpec();
                    spec.setBlockId(blockId);
                    spec.setSessionId(sessionId);
                    spec.setUserId(userId);
                    spec.setUnlockTime(Instant.now());
                    spec.setUnlockType(unlockType);
                    // 密码解锁默认永久有效
                    spec.setExpireTime(null);
                    record.setSpec(spec);

                    return extensionClient.create(record);
                }));
    }
}
