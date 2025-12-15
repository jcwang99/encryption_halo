/**
 * 文章加密插件 - 解锁交互脚本
 * 简化版：前端验证密码并解密内容
 * @author Developer
 */

(function () {
    'use strict';

    /**
     * 初始化所有加密区块
     */
    function initEncryptBlocks() {
        const blocks = document.querySelectorAll('.encrypt-block');

        blocks.forEach(block => {
            const blockId = block.dataset.blockId;
            const type = block.dataset.type;

            if (!blockId) return;

            // 检查 localStorage 中是否已解锁
            if (isUnlockedLocally(blockId)) {
                const content = block.dataset.content;
                if (content) {
                    revealContent(block, decodeContent(content));
                }
            } else {
                setupUnlockForm(block, blockId, type);
            }
        });
    }

    /**
     * 检查本地是否已解锁
     */
    function isUnlockedLocally(blockId) {
        try {
            const unlocked = localStorage.getItem('encrypt_unlocked_' + blockId);
            return unlocked === 'true';
        } catch (e) {
            return false;
        }
    }

    /**
     * 标记为已解锁
     */
    function markAsUnlocked(blockId) {
        try {
            localStorage.setItem('encrypt_unlocked_' + blockId, 'true');
        } catch (e) {
            // ignore
        }
    }

    /**
     * 解码 Base64 内容
     */
    function decodeContent(encodedContent) {
        try {
            return decodeURIComponent(escape(atob(encodedContent)));
        } catch (e) {
            console.error('解码内容失败:', e);
            return '';
        }
    }

    /**
     * 简单哈希（与后端一致）
     */
    function simpleHash(input) {
        if (!input) return '';
        let hash = 0;
        for (let i = 0; i < input.length; i++) {
            hash = 31 * hash + input.charCodeAt(i);
            hash = hash | 0; // Convert to 32bit integer
        }
        return (hash >>> 0).toString(16); // Unsigned
    }

    /**
     * 设置解锁表单事件
     */
    function setupUnlockForm(block, blockId, type) {
        const passwordInput = block.querySelector('.encrypt-password-input');
        const unlockBtn = block.querySelector('.encrypt-unlock-btn');
        const errorMsg = block.querySelector('.encrypt-error-msg');

        if (!unlockBtn) return;

        // 密码类型解锁
        if (type === 'password') {
            unlockBtn.addEventListener('click', () => {
                handlePasswordUnlock(block, blockId, passwordInput, errorMsg);
            });

            // 回车键提交
            if (passwordInput) {
                passwordInput.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        handlePasswordUnlock(block, blockId, passwordInput, errorMsg);
                    }
                });
            }
        }

        // 付费类型解锁（待实现）
        if (type === 'paid') {
            unlockBtn.textContent = '付费解锁';
            unlockBtn.addEventListener('click', () => {
                alert('付费解锁功能开发中，敬请期待！');
            });
        }
    }

    /**
     * 处理密码解锁
     */
    function handlePasswordUnlock(block, blockId, passwordInput, errorMsg) {
        const password = passwordInput ? passwordInput.value.trim() : '';

        if (!password) {
            showError(errorMsg, '请输入密码');
            passwordInput && passwordInput.focus();
            return;
        }

        // 获取存储的哈希值
        const storedHash = block.dataset.hash;
        const inputHash = simpleHash(password);

        hideError(errorMsg);

        if (inputHash === storedHash) {
            // 密码正确
            markAsUnlocked(blockId);

            const content = block.dataset.content;
            block.classList.add('unlocking');

            setTimeout(() => {
                revealContent(block, decodeContent(content));
            }, 500);
        } else {
            // 密码错误
            showError(errorMsg, '密码错误，请重试');
            passwordInput && (passwordInput.value = '');
            passwordInput && passwordInput.focus();
        }
    }

    /**
     * 显示解密内容
     */
    function revealContent(block, content) {
        // 创建内容容器
        const contentDiv = document.createElement('div');
        contentDiv.className = 'encrypt-content-revealed';
        contentDiv.innerHTML = content;

        // 替换加密区块
        block.classList.add('unlocked');

        setTimeout(() => {
            block.parentNode.replaceChild(contentDiv, block);
        }, 300);
    }

    /**
     * 显示错误消息
     */
    function showError(errorEl, message) {
        if (!errorEl) return;
        errorEl.textContent = message;
        errorEl.style.display = 'block';
    }

    /**
     * 隐藏错误消息
     */
    function hideError(errorEl) {
        if (!errorEl) return;
        errorEl.style.display = 'none';
    }

    // DOM 加载完成后初始化
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initEncryptBlocks);
    } else {
        initEncryptBlocks();
    }

    // 处理动态加载的内容（如 SPA）
    const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
            mutation.addedNodes.forEach((node) => {
                if (node.nodeType === 1) {
                    const blocks = node.querySelectorAll
                        ? node.querySelectorAll('.encrypt-block')
                        : [];
                    if (blocks.length > 0 || (node.classList && node.classList.contains('encrypt-block'))) {
                        initEncryptBlocks();
                    }
                }
            });
        });
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true
    });

})();
