<script setup lang="ts">
import { ref, computed, watch } from "vue";
import {
  VButton,
  VModal,
  VSpace,
  Toast,
} from "@halo-dev/components";
import type { Editor } from "@tiptap/core";

// 接收 editor 作为 prop（与示例插件一致）
interface Props {
  editor: Editor;
  isActive?: boolean;
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  isActive: false,
  disabled: false,
});

const modalVisible = ref(false);
const encryptMode = ref<"partial" | "full">("partial"); // 部分加密 or 全文加密
const encryptType = ref<"password" | "paid">("password");
const password = ref("");
const confirmPassword = ref("");
const hint = ref("");
const hintType = ref<"text" | "html" | "image">("text"); // 提示类型
const price = ref("");
const contentToEncrypt = ref("");
const expiresOption = ref<"" | "7" | "30" | "90" | "custom">(""); // 过期选项
const customExpiresDate = ref(""); // 自定义过期日期

// 区块级 TOTP 状态
const enableBlockTotp = ref(false);
const blockTotpDuration = ref("7");
const isGeneratingBlockTotp = ref(false);
const blockTotpGenerated = ref(false);
const generatedBlockId = ref("");

const isPasswordValid = computed(() => {
  if (encryptType.value !== "password") return true;
  
  // 如果启用了 TOTP 且已生成，则密码可选
  if (enableBlockTotp.value && blockTotpGenerated.value) {
    // 如果输入了密码，仍需验证格式
    if (password.value || confirmPassword.value) {
      return password.value.length >= 4 && password.value === confirmPassword.value;
    }
    return true;
  }
  
  // 否则必须设置密码
  return password.value.length >= 4 && password.value === confirmPassword.value;
});

const isPriceValid = computed(() => {
  if (encryptType.value !== "paid") return true;
  const priceNum = parseFloat(price.value);
  return !isNaN(priceNum) && priceNum > 0;
});

const canSubmit = computed(() => {
  // 全文加密不需要输入内容
  if (encryptMode.value === "full") {
    if (encryptType.value === "password") return isPasswordValid.value;
    if (encryptType.value === "paid") return isPriceValid.value;
    return false;
  }
  
  // 部分加密需要内容
  if (!contentToEncrypt.value.trim()) return false;
  if (encryptType.value === "password") return isPasswordValid.value;
  if (encryptType.value === "paid") return isPriceValid.value;
  return false;
});

// 当切换到全文加密时，清空内容
watch(encryptMode, (newMode) => {
  if (newMode === "full") {
    contentToEncrypt.value = "";
  }
});

function openModal() {
  // 检查是否有选中的文本
  if (props.editor) {
    const { from, to } = props.editor.state.selection;
    if (from !== to) {
      const selectedText = props.editor.state.doc.textBetween(from, to);
      contentToEncrypt.value = selectedText;
      encryptMode.value = "partial";
    } else {
      contentToEncrypt.value = "";
    }
  }
  modalVisible.value = true;
}

function closeModal() {
  modalVisible.value = false;
  resetForm();
}

function resetForm() {
  encryptMode.value = "partial";
  encryptType.value = "password";
  password.value = "";
  confirmPassword.value = "";
  hint.value = "";
  hintType.value = "text";
  price.value = "";
  contentToEncrypt.value = "";
  expiresOption.value = "";
  customExpiresDate.value = "";
  // 重置区块 TOTP 状态
  enableBlockTotp.value = false;
  blockTotpDuration.value = "7";
  isGeneratingBlockTotp.value = false;
  blockTotpGenerated.value = false;
  generatedBlockId.value = "";
}

function insertEncryptBlock() {
  if (!canSubmit.value || !props.editor) return;

  if (encryptMode.value === "full") {
    insertFullArticleEncryption();
  } else {
    insertPartialEncryption();
  }
}

// 全文加密：在文章开头插入元数据注释
function insertFullArticleEncryption() {
  // 生成元数据注释（会被后端的 ArticleEncryptProcessor 识别）
  const metaComment = generateMetaComment();
  
  // 在文档开头插入
  props.editor
    .chain()
    .focus()
    .setTextSelection(0)
    .insertContent(metaComment)
    .run();

  Toast.success("全文加密已设置！保存后生效");
  closeModal();
}

// 生成全文加密的元数据注释
function generateMetaComment(): string {
  let meta = `<!--encrypt:full\n`;
  meta += `password="${escapeAttr(password.value)}"\n`;
  if (hint.value) {
    meta += `hint="${escapeAttr(hint.value)}"\n`;
    meta += `hintType="${hintType.value}"\n`;
  }
  const expiresDate = getExpiresDate();
  if (expiresDate) {
    meta += `expires="${expiresDate}"\n`;
  }
  
  // 添加 TOTP ID
  if (enableBlockTotp.value && generatedBlockId.value && blockTotpGenerated.value) {
    meta += `totp-id="${generatedBlockId.value}"\n`;
  }
  
  meta += `-->\n\n`;
  return meta;
}

// 计算过期日期
function getExpiresDate(): string {
  if (!expiresOption.value) return "";
  if (expiresOption.value === "custom") return customExpiresDate.value;
  
  const days = parseInt(expiresOption.value);
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().split('T')[0]; // YYYY-MM-DD
}

// 生成区块级 TOTP
async function generateBlockTotp() {
  // 检查文章是否已保存
  const urlParams = new URLSearchParams(window.location.search);
  const postName = urlParams.get('name');
  
  if (!postName) {
    Toast.warning('请先保存文章后再生成区块动态密码');
    return;
  }
  
  isGeneratingBlockTotp.value = true;
  generatedBlockId.value = `totp-${Date.now().toString(36)}`;
  
  // 尝试获取文章标题
  let articleTitle = "未命名文章";
  try {
    // 方法1: 从URL参数获取文章名称
    const urlParams = new URLSearchParams(window.location.search);
    const postName = urlParams.get('name');
    
    console.log('获取文章标题 - postName:', postName);
    
    if (postName) {
      // 尝试从Halo API获取文章信息
      const response = await fetch(`/apis/content.halo.run/v1alpha1/posts/${postName}`);
      console.log('API响应状态:', response.status);
      
      if (response.ok) {
        const postData = await response.json();
        console.log('文章数据:', postData);
        articleTitle = postData.spec?.title || articleTitle;
        console.log('提取的文章标题:', articleTitle);
      }
    }
    
    // 方法2: 如果仍然是默认值，尝试从editor中获取
    if (articleTitle === "未命名文章" && props.editor) {
      // 尝试从编辑器的view中获取文章标题
      const doc = props.editor.state.doc;
      if (doc && doc.content) {
        // 简单地使用时间戳作为标识
        articleTitle = `文章_${new Date().toLocaleDateString()}`;
      }
    }
  } catch (error) {
    console.error('获取文章标题失败:', error);
  }
  
  // 计算区块序号
  let labelSuffix = "";
  if (encryptMode.value === 'full') {
    labelSuffix = "全文加密";
  } else {
    // 简单地通过DOM查找当前页面已有的加密块数量
    const blockNumber = (document.querySelectorAll('[data-type="encrypt-block"]').length || 0) + 1;
    labelSuffix = `区块${blockNumber}`;
  }
  
  const label = `${articleTitle} - ${labelSuffix}`;
  
  try {
    const response = await fetch('/apis/api.encrypt.halo.run/v1alpha1/block-totp/generate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        blockId: generatedBlockId.value,
        durationDays: parseInt(blockTotpDuration.value),
        label: label
      }),
    });
    
    if (!response.ok) {
      throw new Error('生成失败');
    }
    
    const result = await response.json();
    
    if (result.success) {
      blockTotpGenerated.value = true;
      Toast.success(`区块动态密码已生成 (${result.currentCode})，有效期 ${blockTotpDuration.value} 天`);
    } else {
      throw new Error(result.error || '生成失败');
    }
  } catch (error: any) {
    Toast.error(`生成失败: ${error.message}`);
    console.error('generateBlockTotp error:', error);
  } finally {
    isGeneratingBlockTotp.value = false;
  }
}

// 部分加密：插入 [encrypt] 标签
function insertPartialEncryption() {
  const blockId = `block-${Date.now().toString(36)}`;
  let encryptTag = "";

  const hintAttr = hint.value 
    ? ` hint="${escapeAttr(hint.value)}" hint-type="${hintType.value}"`
    : "";

  const expiresDate = getExpiresDate();
  const expiresAttr = expiresDate ? ` expires="${expiresDate}"` : "";
  
  // 区块 TOTP 属性
  const totpIdAttr = (enableBlockTotp.value && blockTotpGenerated.value && generatedBlockId.value)
    ? ` totp-id="${generatedBlockId.value}"`
    : "";

  if (encryptType.value === "password") {
    encryptTag = `[encrypt type="password" password="${escapeAttr(password.value)}" id="${blockId}"${hintAttr}${expiresAttr}${totpIdAttr}]\n${contentToEncrypt.value}\n[/encrypt]`;
  } else {
    encryptTag = `[encrypt type="paid" price="${price.value}" id="${blockId}"${hintAttr}${expiresAttr}${totpIdAttr}]\n${contentToEncrypt.value}\n[/encrypt]`;
  }

  // 如果有选中文本，替换它；否则在光标处插入
  const { from, to } = props.editor.state.selection;
  
  props.editor
    .chain()
    .focus()
    .deleteRange({ from, to })
    .insertContent(encryptTag)
    .run();

  Toast.success("加密区块已插入");
  closeModal();
}

function escapeAttr(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}
</script>

<template>
  <div class="encrypt-toolbar-item">
    <button
      v-tooltip="'插入加密区块'"
      class="encrypt-toolbar-btn"
      :disabled="disabled"
      @click="openModal"
    >
      <svg viewBox="0 0 24 24" class="h-4 w-4" fill="currentColor">
        <path d="M12 17a2 2 0 0 0 2-2a2 2 0 0 0-2-2a2 2 0 0 0-2 2a2 2 0 0 0 2 2m6-9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a5 5 0 0 1 5-5a5 5 0 0 1 5 5v2h1m-6-5a3 3 0 0 0-3 3v2h6V6a3 3 0 0 0-3-3z"/>
      </svg>
    </button>

    <VModal
      v-model:visible="modalVisible"
      title="插入加密内容"
      :width="550"
      @close="closeModal"
    >
      <div class="encrypt-modal-content">
        <!-- 加密范围选择 -->
        <div class="form-group">
          <label class="form-label">加密范围</label>
          <div class="mode-selector">
            <label class="mode-option" :class="{ active: encryptMode === 'partial' }">
              <input type="radio" v-model="encryptMode" value="partial" />
              <span class="mode-icon">📝</span>
              <div class="mode-info">
                <span class="mode-text">部分加密</span>
                <span class="mode-desc">只加密选中的内容</span>
              </div>
            </label>
            <label class="mode-option" :class="{ active: encryptMode === 'full' }">
              <input type="radio" v-model="encryptMode" value="full" />
              <span class="mode-icon">📄</span>
              <div class="mode-info">
                <span class="mode-text">全文加密</span>
                <span class="mode-desc">加密整篇文章</span>
              </div>
            </label>
          </div>
        </div>

        <!-- 加密类型选择 -->
        <div class="form-group">
          <label class="form-label">加密类型</label>
          <div class="type-selector">
            <label class="type-option" :class="{ active: encryptType === 'password' }">
              <input type="radio" v-model="encryptType" value="password" />
              <span class="type-icon">🔒</span>
              <span class="type-text">密码保护</span>
            </label>
            <label class="type-option" :class="{ active: encryptType === 'paid' }">
              <input type="radio" v-model="encryptType" value="paid" />
              <span class="type-icon">💰</span>
              <span class="type-text">付费解锁</span>
            </label>
          </div>
        </div>

        <!-- 密码设置 -->
        <template v-if="encryptType === 'password'">
          <div class="form-group">
            <label class="form-label">设置密码（可选）</label>
            <input
              type="password"
              v-model="password"
              class="form-input"
              placeholder="留空则仅使用动态密码/万能密钥"
              minlength="4"
            />
            <p class="form-hint">💡 如已启用 TOTP 动态密码，可不设固定密码</p>
          </div>
          <div class="form-group" v-if="password">
            <label class="form-label">确认密码</label>
            <input
              type="password"
              v-model="confirmPassword"
              class="form-input"
              :class="{ error: confirmPassword && password !== confirmPassword }"
              placeholder="请再次输入密码"
            />
            <p v-if="confirmPassword && password !== confirmPassword" class="form-error">
              两次输入的密码不一致
            </p>
          </div>
          
          <!-- 区块级动态密码（仅部分加密模式） -->
          <div class="form-group" v-if="encryptMode === 'partial'">
            <div class="form-checkbox">
              <input type="checkbox" id="enable-block-totp" v-model="enableBlockTotp" />
              <label for="enable-block-totp">启用独立动态密码</label>
            </div>
            <div v-if="enableBlockTotp" class="totp-settings">
              <div class="totp-duration">
                <label>有效期</label>
                <select v-model="blockTotpDuration">
                  <option value="1">1天</option>
                  <option value="7">7天</option>
                  <option value="30">30天</option>
                  <option value="90">90天</option>
                </select>
              </div>
              <VButton 
                type="secondary" 
                size="sm"
                @click="generateBlockTotp" 
                :loading="isGeneratingBlockTotp"
                :disabled="blockTotpGenerated"
              >
                {{ blockTotpGenerated ? '✅ 已生成' : '生成密码' }}
              </VButton>
            </div>
            <p class="form-hint" v-if="enableBlockTotp">💡 此区块会有独立的动态密码，不使用全局动态密码</p>
          </div>
        </template>

        <!-- 全文加密模式 -->
        <template v-if="encryptMode === 'full'">
          <div class="form-group">
            <label class="form-label">访问密码</label>
            <input
              type="password"
              v-model="password"
              class="form-input"
              placeholder="留空则仅使用动态密码/万能密钥"
            />
          </div>
          
          <div class="form-group">
             <div class="form-checkbox">
               <input type="checkbox" id="enable-full-totp" v-model="enableBlockTotp" />
               <label for="enable-full-totp">启用独立动态密码</label>
             </div>
             <div v-if="enableBlockTotp" class="totp-settings">
               <div class="totp-duration">
                 <label>有效期</label>
                 <select v-model="blockTotpDuration">
                   <option value="1">1天</option>
                   <option value="7">7天</option>
                   <option value="30">30天</option>
                   <option value="90">90天</option>
                 </select>
               </div>
               <VButton 
                 type="secondary" 
                 size="sm"
                 @click="generateBlockTotp" 
                 :loading="isGeneratingBlockTotp"
                 :disabled="blockTotpGenerated"
               >
                 {{ blockTotpGenerated ? '✅ 已生成' : '生成密码' }}
               </VButton>
             </div>
             <p class="form-hint" v-if="enableBlockTotp">💡 此文章会有独立的动态密码，不使用全局动态密码</p>
           </div>

          <div class="form-group">
            <label class="form-label">提示信息</label>
            <textarea
              v-model="hint"
              class="form-input"
              rows="2"
              placeholder="请输入提示信息（可选）"
            ></textarea>
          </div>
          
          <div class="form-group">
            <label class="form-label">过期时间（可选）</label>
            <div class="expires-settings">
              <select v-model="expiresOption" class="expires-select">
                <option value="">不过期</option>
                <option value="7">7天后</option>
                <option value="30">30天后</option>
                <option value="custom">自定义</option>
              </select>
              
              <input 
                v-if="expiresOption === 'custom'"
                type="date" 
                v-model="customExpiresDate"
                class="form-input"
              />
            </div>
            <p class="form-hint">
              ⏰ 到期后内容自动变为公开，无需密码
            </p>
          </div>

          <p class="form-hint">
            全文加密将在文章开头插入特殊标记，发布后整篇文章都需要密码才能访问。
          </p>
        </template>

        <!-- 付费设置 -->
        <template v-if="encryptType === 'paid'">
          <div class="form-group">
            <label class="form-label">价格（元）<span class="required">*</span></label>
            <input
              type="number"
              v-model="price"
              class="form-input"
              placeholder="请输入价格"
              min="0.01"
              step="0.01"
            />
          </div>
          <p class="form-hint warning">
            ⚠️ 付费解锁功能需要配置支付接口，请在插件设置中完成配置。
          </p>
        </template>

        <!-- 提示设置 -->
        <div class="form-group">
          <label class="form-label">提示信息（可选）</label>
          <div class="hint-row">
            <select v-model="hintType" class="hint-type-select">
              <option value="text">文字提示</option>
              <option value="html">HTML 链接</option>
              <option value="image">图片提示</option>
            </select>
            <input
              type="text"
              v-model="hint"
              class="form-input hint-input"
              :placeholder="hintType === 'image' ? '输入图片URL' : (hintType === 'html' ? '输入HTML代码' : '输入提示文字')"
            />
          </div>
          <p class="form-hint" v-if="hintType === 'html'">
            💡 支持 HTML，如: &lt;a href="/subscribe"&gt;点击订阅&lt;/a&gt;
          </p>
          <p class="form-hint" v-if="hintType === 'image'">
            💡 输入图片URL，如二维码图片地址
          </p>
        </div>

        <!-- 过期时间设置 -->
        <div class="form-group">
          <label class="form-label">加密期限（可选）</label>
          <div class="expires-row">
            <select v-model="expiresOption" class="expires-select">
              <option value="">永久加密</option>
              <option value="7">7天后公开</option>
              <option value="30">30天后公开</option>
              <option value="90">90天后公开</option>
              <option value="custom">自定义日期</option>
            </select>
            <input
              v-if="expiresOption === 'custom'"
              type="date"
              v-model="customExpiresDate"
              class="form-input expires-date"
              :min="new Date().toISOString().split('T')[0]"
            />
          </div>
          <p class="form-hint">
            ⏰ 到期后内容自动变为公开，无需密码
          </p>
        </div>

        <!-- 加密内容（仅部分加密时显示） -->
        <div class="form-group" v-if="encryptMode === 'partial'">
          <label class="form-label">加密内容 <span class="required">*</span></label>
          <textarea
            v-model="contentToEncrypt"
            class="form-textarea"
            placeholder="请输入要加密的内容..."
            rows="6"
          ></textarea>
          <p class="form-hint">
            支持 HTML 和 Markdown 格式的内容
          </p>
        </div>

        <!-- 全文加密提示 -->
        <div v-if="encryptMode === 'full'" class="full-encrypt-notice">
          <div class="notice-icon">📄</div>
          <div class="notice-content">
            <p class="notice-title">全文加密模式</p>
            <p class="notice-desc">保存文章后，整篇文章内容将被加密。访客需要输入密码才能查看。</p>
          </div>
        </div>
      </div>

      <template #footer>
        <VSpace>
          <VButton @click="closeModal">取消</VButton>
          <VButton
            type="primary"
            :disabled="!canSubmit"
            @click="insertEncryptBlock"
          >
            {{ encryptMode === 'full' ? '设置全文加密' : '插入加密区块' }}
          </VButton>
        </VSpace>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.encrypt-toolbar-item {
  display: inline-block;
}

.encrypt-toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  cursor: pointer;
  width: 32px;
  height: 32px;
  padding: 6px;
  border-radius: 4px;
  color: #6b7280;
  transition: all 0.2s ease;
}

.encrypt-toolbar-btn:hover:not(:disabled) {
  color: #374151;
  background: #f3f4f6;
}

.encrypt-toolbar-btn:disabled {
  color: #9ca3af;
  cursor: not-allowed;
}

.encrypt-modal-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px 0;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 14px;
  font-weight: 500;
  color: #374151;
}

.form-label .required {
  color: #ef4444;
}

.form-input,
.form-textarea {
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  transition: all 0.2s;
  width: 100%;
  box-sizing: border-box;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.form-input.error {
  border-color: #ef4444;
}

.form-textarea {
  resize: vertical;
  min-height: 100px;
  font-family: inherit;
}

.form-error {
  color: #ef4444;
  font-size: 12px;
  margin: 4px 0 0;
}

.form-hint {
  font-size: 12px;
  color: #6b7280;
  margin: 4px 0 0;
}

.form-hint.warning {
  color: #d97706;
  background: #fffbeb;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid #fde68a;
}

/* 加密范围选择 */
.mode-selector {
  display: flex;
  gap: 12px;
}

.mode-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  flex: 1;
}

.mode-option:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}

.mode-option.active {
  border-color: #3b82f6;
  background: #eff6ff;
}

.mode-option input {
  display: none;
}

.mode-icon {
  font-size: 24px;
}

.mode-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mode-text {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.mode-desc {
  font-size: 12px;
  color: #6b7280;
}

/* 加密类型选择 */
.type-selector {
  display: flex;
  gap: 12px;
}

.type-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  flex: 1;
}

.type-option:hover {
  border-color: #93c5fd;
  background: #eff6ff;
}

.type-option.active {
  border-color: #3b82f6;
  background: #eff6ff;
}

.type-option input {
  display: none;
}

.type-icon {
  font-size: 20px;
}

.type-text {
  font-size: 14px;
  font-weight: 500;
}

/* 提示设置 */
.hint-row {
  display: flex;
  gap: 8px;
}

.hint-type-select {
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  background: white;
  cursor: pointer;
  min-width: 110px;
}

.hint-type-select:focus {
  outline: none;
  border-color: #3b82f6;
}

.hint-input {
  flex: 1;
}

/* 全文加密提示 */
.full-encrypt-notice {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: linear-gradient(135deg, #eff6ff, #f0fdf4);
  border-radius: 8px;
  border: 1px solid #93c5fd;
}

.notice-icon {
  font-size: 32px;
}

.notice-content {
  flex: 1;
}

.notice-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e40af;
  margin: 0 0 4px 0;
}

.notice-desc {
  font-size: 13px;
  color: #3b82f6;
  margin: 0;
  line-height: 1.5;
}

/* 过期时间选择 */
.expires-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.expires-select {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  background: white;
  cursor: pointer;
}

.expires-select:focus {
  outline: none;
  border-color: #3b82f6;
}

.expires-date {
  flex: 1;
}

/* 区块级 TOTP */
.totp-settings {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.totp-duration {
  display: flex;
  align-items: center;
  gap: 8px;
}

.totp-duration label {
  font-size: 14px;
  color: #4b5563;
}

.totp-duration select {
  padding: 6px 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  background: white;
  cursor: pointer;
}

.form-checkbox {
  display: flex;
  align-items: center;
  gap: 8px;
}

.form-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.form-checkbox label {
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
}
</style>
