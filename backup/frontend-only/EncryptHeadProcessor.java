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
 * 头部资源注入处理器
 * 向页面注入加密功能所需的 CSS 和 JS（内联方式）
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

        // 内联 JS
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
                """;
    }

    private String getInlineJS() {
        return """
                (function() {
                  'use strict';

                  function initEncryptBlocks() {
                    var blocks = document.querySelectorAll('.encrypt-block');
                    blocks.forEach(function(block) {
                      var blockId = block.dataset.blockId;
                      var type = block.dataset.type;
                      if (!blockId) return;

                      if (isUnlockedLocally(blockId)) {
                        var content = block.dataset.content;
                        if (content) {
                          revealContent(block, decodeContent(content));
                        }
                      } else {
                        setupUnlockForm(block, blockId, type);
                      }
                    });
                  }

                  function isUnlockedLocally(blockId) {
                    try {
                      return localStorage.getItem('encrypt_unlocked_' + blockId) === 'true';
                    } catch (e) { return false; }
                  }

                  function markAsUnlocked(blockId) {
                    try { localStorage.setItem('encrypt_unlocked_' + blockId, 'true'); } catch (e) {}
                  }

                  function decodeContent(encodedContent) {
                    try {
                      return decodeURIComponent(escape(atob(encodedContent)));
                    } catch (e) { console.error('解码失败:', e); return ''; }
                  }

                  function simpleHash(input) {
                    if (!input) return '';
                    var hash = 0;
                    for (var i = 0; i < input.length; i++) {
                      hash = 31 * hash + input.charCodeAt(i);
                      hash = hash | 0;
                    }
                    return (hash >>> 0).toString(16);
                  }

                  function setupUnlockForm(block, blockId, type) {
                    var passwordInput = block.querySelector('.encrypt-password-input');
                    var unlockBtn = block.querySelector('.encrypt-unlock-btn');
                    var errorMsg = block.querySelector('.encrypt-error-msg');

                    if (!unlockBtn) return;

                    if (type === 'password') {
                      unlockBtn.addEventListener('click', function() {
                        handlePasswordUnlock(block, blockId, passwordInput, errorMsg);
                      });

                      if (passwordInput) {
                        passwordInput.addEventListener('keypress', function(e) {
                          if (e.key === 'Enter') {
                            e.preventDefault();
                            handlePasswordUnlock(block, blockId, passwordInput, errorMsg);
                          }
                        });
                      }
                    }

                    if (type === 'paid') {
                      unlockBtn.textContent = '付费解锁';
                      unlockBtn.addEventListener('click', function() {
                        alert('付费解锁功能开发中！');
                      });
                    }
                  }

                  function handlePasswordUnlock(block, blockId, passwordInput, errorMsg) {
                    var password = passwordInput ? passwordInput.value.trim() : '';

                    if (!password) {
                      showError(errorMsg, '请输入密码');
                      if (passwordInput) passwordInput.focus();
                      return;
                    }

                    var storedHash = block.dataset.hash;
                    var inputHash = simpleHash(password);

                    hideError(errorMsg);

                    if (inputHash === storedHash) {
                      markAsUnlocked(blockId);
                      var content = block.dataset.content;
                      revealContent(block, decodeContent(content));
                    } else {
                      showError(errorMsg, '密码错误，请重试');
                      if (passwordInput) {
                        passwordInput.value = '';
                        passwordInput.focus();
                      }
                    }
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
