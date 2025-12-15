/**
 * 文章加密插件 - 解锁交互脚本（后端验证版）
 * 支持 TOTP 动态密码、万能密钥、区块密码
 * @author Developer
 */

(function () {
    'use strict';

    // API 基础路径
    const API_BASE = '/apis/encrypt.halo.run/v1alpha1';

    /**
     * 初始化所有加密区块
     */
    function initEncryptBlocks() {
        const blocks = document.querySelectorAll('.encrypt-block');

        blocks.forEach(block => {
            const blockId = block.dataset.blockId;
            const type = block.dataset.type;

            if (!blockId) return;

            // 检查是否已解锁（通过 Cookie 或 sessionStorage）
            if (isUnlocked(blockId)) {
                // 尝试自动获取内容
                autoReveal(block, blockId);
            } else {
                setupUnlockForm(block, blockId, type);
            }
        });
    }

    /**
     * 检查是否已解锁
     */
    function isUnlocked(blockId) {
        // 检查 sessionStorage
        try {
            if (sessionStorage.getItem('unlock_' + blockId) === 'true') {
                return true;
            }
        } catch (e) { }

        // 检查 Cookie
        return document.cookie.includes('encrypt_unlocked_' + blockId + '=');
    }

    /**
     * 自动显示已解锁内容
     */
    async function autoReveal(block, blockId) {
        try {
            const response = await fetch(`${API_BASE}/content/${blockId}`, {
                credentials: 'include'
            });

            if (response.ok) {
                const data = await response.json();
                if (data.content) {
                    revealContent(block, data.content, false);
                    return;
                }
            }
        } catch (e) {
            console.log('自动解锁失败，需要重新输入密码');
        }

        // 如果自动获取失败，显示解锁表单
        setupUnlockForm(block, blockId, block.dataset.type);
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
                handlePasswordUnlock(block, blockId, passwordInput, unlockBtn, errorMsg);
            });

            // 回车键提交
            if (passwordInput) {
                passwordInput.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        handlePasswordUnlock(block, blockId, passwordInput, unlockBtn, errorMsg);
                    }
                });

                // 输入时隐藏错误
                passwordInput.addEventListener('input', () => {
                    hideError(errorMsg);
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
     * 处理密码解锁（后端验证）
     */
    async function handlePasswordUnlock(block, blockId, passwordInput, unlockBtn, errorMsg) {
        const password = passwordInput ? passwordInput.value.trim() : '';

        if (!password) {
            showError(errorMsg, '请输入密码');
            passwordInput && shakeInput(passwordInput);
            passwordInput && passwordInput.focus();
            return;
        }

        // 显示加载状态
        unlockBtn.classList.add('loading');
        unlockBtn.disabled = true;
        hideError(errorMsg);

        try {
            // 获取客户端 IP（用于锁定）
            const response = await fetch(`${API_BASE}/unlock`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                credentials: 'include',
                body: JSON.stringify({
                    blockId: blockId,
                    password: password
                })
            });

            const data = await response.json();

            if (data.success) {
                // 解锁成功
                markAsUnlocked(blockId);

                // 触发解锁动画
                block.classList.add('unlocking');

                setTimeout(() => {
                    block.classList.remove('unlocking');
                    block.classList.add('unlock-success');

                    setTimeout(() => {
                        revealContent(block, data.content, true);
                    }, 400);
                }, 600);
            } else {
                // 解锁失败
                showError(errorMsg, data.message || '密码错误');
                passwordInput && (passwordInput.value = '');
                passwordInput && shakeInput(passwordInput);
                passwordInput && passwordInput.focus();

                // 如果被锁定，显示倒计时
                if (data.locked && data.remainingMinutes > 0) {
                    startLockCountdown(errorMsg, data.remainingMinutes);
                }
            }
        } catch (error) {
            console.error('解锁请求失败:', error);
            showError(errorMsg, '网络错误，请稍后重试');
        } finally {
            unlockBtn.classList.remove('loading');
            unlockBtn.disabled = false;
        }
    }

    /**
     * 输入框抖动效果
     */
    function shakeInput(input) {
        input.style.animation = 'none';
        input.offsetHeight; // 触发重绘
        input.style.animation = 'inputShake 0.5s ease';
    }

    /**
     * 标记为已解锁
     */
    function markAsUnlocked(blockId) {
        try {
            sessionStorage.setItem('unlock_' + blockId, 'true');
        } catch (e) { }
    }

    /**
     * 显示解密内容（带动画）
     */
    function revealContent(block, content, animate = true) {
        // 创建内容容器
        const contentDiv = document.createElement('div');
        contentDiv.className = 'encrypt-content-revealed';
        contentDiv.innerHTML = content;

        if (animate) {
            // 添加解锁动画
            block.classList.add('unlock-success');

            setTimeout(() => {
                block.parentNode.replaceChild(contentDiv, block);
            }, 800);
        } else {
            // 无动画直接替换
            block.parentNode.replaceChild(contentDiv, block);
        }
    }

    /**
     * 显示错误消息
     */
    function showError(errorEl, message) {
        if (!errorEl) return;
        errorEl.textContent = message;
        errorEl.style.display = 'block';
        // 触发动画
        errorEl.style.animation = 'none';
        errorEl.offsetHeight;
        errorEl.style.animation = 'errorShake 0.5s ease';
    }

    /**
     * 隐藏错误消息
     */
    function hideError(errorEl) {
        if (!errorEl) return;
        errorEl.style.display = 'none';
    }

    /**
     * 锁定倒计时
     */
    function startLockCountdown(errorEl, minutes) {
        let remaining = minutes * 60;

        const updateCountdown = () => {
            const mins = Math.floor(remaining / 60);
            const secs = remaining % 60;
            errorEl.textContent = `密码错误次数过多，请在 ${mins}:${secs.toString().padStart(2, '0')} 后重试`;

            if (remaining > 0) {
                remaining--;
                setTimeout(updateCountdown, 1000);
            }
        };

        updateCountdown();
    }

    // 添加输入框抖动动画样式
    const style = document.createElement('style');
    style.textContent = `
        @keyframes inputShake {
            0%, 100% { transform: translateX(0); }
            20%, 60% { transform: translateX(-6px); border-color: #ef4444; }
            40%, 80% { transform: translateX(6px); border-color: #ef4444; }
        }
    `;
    document.head.appendChild(style);

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
