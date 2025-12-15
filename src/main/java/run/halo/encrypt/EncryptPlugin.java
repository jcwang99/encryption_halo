package run.halo.encrypt;

import static run.halo.app.extension.index.IndexAttributeFactory.simpleAttribute;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpec;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.encrypt.extension.CategoryEncrypt;
import run.halo.encrypt.extension.EncryptBlock;
import run.halo.encrypt.extension.UnlockRecord;

/**
 * 文章加密插件主类
 * 
 * @author Developer
 */
@Component
@Slf4j
public class EncryptPlugin extends BasePlugin {

    @Autowired
    private SchemeManager schemeManager;

    public EncryptPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("文章加密插件启动中...");
        registerSchemes();
        log.info("文章加密插件启动完成");
    }

    @Override
    public void stop() {
        log.info("文章加密插件停止中...");
        unregisterSchemes();
        log.info("文章加密插件已停止");
    }

    private void registerSchemes() {
        // 注册加密区块 Extension
        schemeManager.register(EncryptBlock.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                    .setName("spec.postName")
                    .setIndexFunc(simpleAttribute(EncryptBlock.class,
                            block -> block.getSpec().getPostName())));
            indexSpecs.add(new IndexSpec()
                    .setName("spec.blockId")
                    .setIndexFunc(simpleAttribute(EncryptBlock.class,
                            block -> block.getSpec().getBlockId())));
            indexSpecs.add(new IndexSpec()
                    .setName("spec.encryptType")
                    .setIndexFunc(simpleAttribute(EncryptBlock.class,
                            block -> block.getSpec().getEncryptType())));
        });

        // 注册解锁记录 Extension
        schemeManager.register(UnlockRecord.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                    .setName("spec.blockId")
                    .setIndexFunc(simpleAttribute(UnlockRecord.class,
                            record -> record.getSpec().getBlockId())));
            indexSpecs.add(new IndexSpec()
                    .setName("spec.sessionId")
                    .setIndexFunc(simpleAttribute(UnlockRecord.class,
                            record -> record.getSpec().getSessionId())));
            indexSpecs.add(new IndexSpec()
                    .setName("spec.userId")
                    .setIndexFunc(simpleAttribute(UnlockRecord.class,
                            record -> record.getSpec().getUserId())));
        });

        // 注册分类加密配置 Extension
        schemeManager.register(CategoryEncrypt.class, indexSpecs -> {
            indexSpecs.add(new IndexSpec()
                    .setName("spec.categoryName")
                    .setIndexFunc(simpleAttribute(CategoryEncrypt.class,
                            cat -> cat.getSpec().getCategoryName())));
            indexSpecs.add(new IndexSpec()
                    .setName("spec.enabled")
                    .setIndexFunc(simpleAttribute(CategoryEncrypt.class,
                            cat -> String.valueOf(cat.getSpec().getEnabled()))));
        });
    }

    private void unregisterSchemes() {
        Scheme encryptBlockScheme = schemeManager.get(EncryptBlock.class);
        schemeManager.unregister(encryptBlockScheme);

        Scheme unlockRecordScheme = schemeManager.get(UnlockRecord.class);
        schemeManager.unregister(unlockRecordScheme);

        Scheme categoryEncryptScheme = schemeManager.get(CategoryEncrypt.class);
        schemeManager.unregister(categoryEncryptScheme);
    }
}
