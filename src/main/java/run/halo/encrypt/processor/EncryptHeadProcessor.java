package run.halo.encrypt.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * 头部资源注入处理器（后端验证版）
 * JS 通过 API 验证密码并获取内容
 * 
 * @author Developer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptHeadProcessor implements TemplateHeadProcessor {

  @Override
  public Mono<Void> process(ITemplateContext context, IModel model,
      IElementModelStructureHandler structureHandler) {

    final IModelFactory modelFactory = context.getModelFactory();

    StringBuilder sb = new StringBuilder();
    sb.append("<!-- halo-plugin-encrypt start -->\n");

    // 内联 CSS
    sb.append("<style>\n");
    sb.append(getInlineCSS());
    sb.append("</style>\n");

    // 内联 JS（调用后端 API）
    sb.append("<script>\n");
    sb.append(getInlineJS());
    sb.append("</script>\n");

    sb.append("<!-- halo-plugin-encrypt end -->\n");

    model.add(modelFactory.createText(sb.toString()));

    return Mono.empty();
  }

  private String getInlineCSS() {
    return """
        .encrypt-block {
          --encrypt-primary: #4F8DFD;
          --encrypt-bg: #f8fafc;
          --encrypt-text: #334155;
          --encrypt-border: #e2e8f0;
          --encrypt-shadow: 0 4px 20px rgba(79, 141, 253, 0.1);
          --encrypt-radius: 12px;
          position: relative;
          margin: 24px 0;
          padding: 32px;
          background: var(--encrypt-bg);
          border: 1px solid var(--encrypt-border);
          border-radius: var(--encrypt-radius);
          box-shadow: var(--encrypt-shadow);
          text-align: center;
          overflow: hidden;
          transition: all 0.3s ease;
        }
        .encrypt-block::before {
          content: '';
          position: absolute;
          top: 0;
          left: 0;
          right: 0;
          height: 4px;
          background: linear-gradient(90deg, var(--encrypt-primary), #a78bfa);
        }
        .encrypt-lock-icon {
          color: var(--encrypt-primary);
          margin-bottom: 16px;
        }
        .encrypt-lock-icon svg {
          width: 48px;
          height: 48px;
        }
        .encrypt-info {
          margin-bottom: 24px;
        }
        .encrypt-type-label {
          display: inline-block;
          padding: 4px 12px;
          background: linear-gradient(135deg, var(--encrypt-primary), #a78bfa);
          color: white;
          font-size: 12px;
          font-weight: 600;
          border-radius: 20px;
          margin-bottom: 12px;
        }
        .encrypt-desc {
          color: var(--encrypt-text);
          font-size: 15px;
          margin: 0 0 8px 0;
          line-height: 1.6;
        }
        .encrypt-hint {
          color: #64748b;
          font-size: 13px;
          margin: 8px 0 0 0;
          font-style: italic;
        }
        .encrypt-unlock-form {
          display: flex;
          gap: 12px;
          justify-content: center;
          align-items: center;
          max-width: 400px;
          margin: 0 auto;
        }
        .encrypt-password-input {
          flex: 1;
          padding: 12px 16px;
          border: 2px solid var(--encrypt-border);
          border-radius: 8px;
          font-size: 14px;
          outline: none;
          transition: all 0.2s ease;
          background: white;
        }
        .encrypt-password-input:focus {
          border-color: var(--encrypt-primary);
          box-shadow: 0 0 0 3px rgba(79, 141, 253, 0.1);
        }
        .encrypt-unlock-btn {
          padding: 12px 24px;
          background: linear-gradient(135deg, var(--encrypt-primary), #3b82f6);
          color: white;
          border: none;
          border-radius: 8px;
          font-size: 14px;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.2s ease;
          white-space: nowrap;
        }
        .encrypt-unlock-btn:hover {
          transform: translateY(-1px);
          box-shadow: 0 4px 12px rgba(79, 141, 253, 0.3);
        }
        .encrypt-unlock-btn:disabled {
          opacity: 0.6;
          cursor: not-allowed;
          transform: none;
        }
        .encrypt-unlock-btn.loading::after {
          content: '...';
        }
        .encrypt-error-msg {
          margin-top: 16px;
          padding: 8px 16px;
          background: #fef2f2;
          color: #dc2626;
          font-size: 13px;
          border-radius: 6px;
          border: 1px solid #fecaca;
        }
        .encrypt-content-revealed {
          animation: revealContent 0.5s ease forwards;
        }
        @keyframes revealContent {
          from { opacity: 0; transform: translateY(10px); }
          to { opacity: 1; transform: translateY(0); }
        }
        @media (prefers-color-scheme: dark) {
          .encrypt-block {
            --encrypt-bg: #1e293b;
            --encrypt-text: #e2e8f0;
            --encrypt-border: #334155;
          }
          .encrypt-password-input {
            background: #0f172a;
            color: #e2e8f0;
            border-color: #334155;
          }
        }
        @media (max-width: 480px) {
          .encrypt-block { padding: 24px 16px; }
          .encrypt-unlock-form { flex-direction: column; }
          .encrypt-password-input, .encrypt-unlock-btn { width: 100%; }
        }
        .encrypt-block.encrypt-locked {
          border-color: #f87171;
          border-width: 2px;
        }
        .encrypt-block.encrypt-locked::before {
          background: linear-gradient(90deg, #ef4444, #dc2626);
        }
        .encrypt-unlock-btn.locked {
          background: linear-gradient(135deg, #ef4444, #dc2626);
        }
        .encrypt-password-input:disabled {
          background: #f1f5f9;
          cursor: not-allowed;
          opacity: 0.7;
        }
        """;
  }

  private String getInlineJS() {
    return """
        (function() {
          'use strict';

          var API_BASE = '/apis/api.encrypt.halo.run/v1alpha1';
          var COOKIE_PREFIX = 'encrypt_unlocked_';

          function initEncryptBlocks() {
            var blocks = document.querySelectorAll('.encrypt-block');
            blocks.forEach(function(block) {
              var blockId = block.dataset.blockId;
              if (!blockId) return;

              // 先检查是否已有解锁 Cookie
              if (hasUnlockCookie(blockId)) {
                autoUnlock(block, blockId);
              } else {
                setupUnlockForm(block, blockId);
              }
            });
          }

          // 检查是否有解锁 Cookie
          function hasUnlockCookie(blockId) {
            var cookieName = COOKIE_PREFIX + blockId;
            return document.cookie.split(';').some(function(c) {
              return c.trim().startsWith(cookieName + '=');
            });
          }

          // 自动解锁（已有 Cookie）
          function autoUnlock(block, blockId) {
            fetch(API_BASE + '/get-content/' + blockId, {
              method: 'GET',
              credentials: 'include'
            })
            .then(function(response) {
              return response.json();
            })
            .then(function(data) {
              if (data.success && data.content) {
                revealContent(block, data.content);
              } else {
                // Cookie 无效或过期，显示密码框
                setupUnlockForm(block, blockId);
              }
            })
            .catch(function(err) {
              console.error('自动解锁失败:', err);
              setupUnlockForm(block, blockId);
            });
          }

          function setupUnlockForm(block, blockId) {
            var passwordInput = block.querySelector('.encrypt-password-input');
            var unlockBtn = block.querySelector('.encrypt-unlock-btn');
            var errorMsg = block.querySelector('.encrypt-error-msg');

            if (!unlockBtn) return;

            unlockBtn.addEventListener('click', function() {
              handleUnlock(block, blockId, passwordInput, unlockBtn, errorMsg);
            });

            if (passwordInput) {
              passwordInput.addEventListener('keypress', function(e) {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleUnlock(block, blockId, passwordInput, unlockBtn, errorMsg);
                }
              });
            }
          }

          function handleUnlock(block, blockId, passwordInput, unlockBtn, errorMsg) {
            var password = passwordInput ? passwordInput.value.trim() : '';

            if (!password) {
              showError(errorMsg, '请输入密码');
              if (passwordInput) passwordInput.focus();
              return;
            }

            // 禁用按钮，显示加载状态
            unlockBtn.disabled = true;
            unlockBtn.classList.add('loading');
            var originalText = unlockBtn.textContent;
            unlockBtn.textContent = '验证中';
            hideError(errorMsg);

            // 调用后端 API 验证密码
            fetch(API_BASE + '/unlock', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json'
              },
              credentials: 'include',
              body: JSON.stringify({
                blockId: blockId,
                password: password
              })
            })
            .then(function(response) {
              return response.json();
            })
            .then(function(data) {
              if (data.success && data.content) {
                // 解锁成功，显示内容
                revealContent(block, data.content);
              } else {
                // 解锁失败
                showError(errorMsg, data.message || '密码错误');
                if (passwordInput) {
                  passwordInput.value = '';
                }

                // 检查是否被锁定
                if (data.locked && data.lockRemainingMinutes > 0) {
                  handleLockState(block, passwordInput, unlockBtn, errorMsg, data.lockRemainingMinutes);
                  return;
                } else {
                  if (passwordInput) passwordInput.focus();
                }
              }
            })
            .catch(function(err) {
              console.error('解锁请求失败:', err);
              showError(errorMsg, '网络错误，请重试');
            })
            .finally(function() {
              if (!unlockBtn.classList.contains('locked')) {
                unlockBtn.disabled = false;
                unlockBtn.classList.remove('loading');
                unlockBtn.textContent = originalText;
              }
            });
          }

          function handleLockState(block, passwordInput, unlockBtn, errorMsg, lockMinutes) {
            if (passwordInput) {
              passwordInput.disabled = true;
              passwordInput.placeholder = '已锁定';
            }
            unlockBtn.disabled = true;
            unlockBtn.classList.add('locked');
            unlockBtn.textContent = '已锁定';
            block.classList.add('encrypt-locked');

            var remainingSeconds = lockMinutes * 60;
            function updateCountdown() {
              if (remainingSeconds <= 0) {
                if (passwordInput) {
                  passwordInput.disabled = false;
                  passwordInput.placeholder = '请输入密码';
                }
                unlockBtn.disabled = false;
                unlockBtn.classList.remove('locked');
                unlockBtn.textContent = '解锁';
                block.classList.remove('encrypt-locked');
                hideError(errorMsg);
                return;
              }

              var mins = Math.floor(remainingSeconds / 60);
              var secs = remainingSeconds % 60;
              var timeStr = mins + ':' + (secs < 10 ? '0' : '') + secs;
              showError(errorMsg, '⏱️ 请在 ' + timeStr + ' 后重试');
              unlockBtn.textContent = '锁定中 ' + timeStr;
              remainingSeconds--;
              setTimeout(updateCountdown, 1000);
            }
            updateCountdown();
          }

          function revealContent(block, content) {
            var contentDiv = document.createElement('div');
            contentDiv.className = 'encrypt-content-revealed';
            contentDiv.innerHTML = content;
            block.parentNode.replaceChild(contentDiv, block);
          }

          function showError(errorEl, message) {
            if (!errorEl) return;
            errorEl.textContent = message;
            errorEl.style.display = 'block';
          }

          function hideError(errorEl) {
            if (!errorEl) return;
            errorEl.style.display = 'none';
          }

          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', initEncryptBlocks);
          } else {
            initEncryptBlocks();
          }
        })();
        """;
  }
}
