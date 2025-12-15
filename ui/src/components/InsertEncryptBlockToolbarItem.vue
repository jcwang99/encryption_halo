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

const isPasswordValid = computed(() => {
  if (encryptType.value !== "password") return true;
  // 密码可以为空（使用 TOTP 动态密码）
  // 如果输入了密码，则必须 >= 4 位且两次一致
  if (!password.value && !confirmPassword.value) return true; // 允许不设密码
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

// 部分加密：插入 [encrypt] 标签
function insertPartialEncryption() {
  const blockId = `block-${Date.now().toString(36)}`;
  let encryptTag = "";

  const hintAttr = hint.value 
    ? ` hint="${escapeAttr(hint.value)}" hint-type="${hintType.value}"`
    : "";

  const expiresDate = getExpiresDate();
  const expiresAttr = expiresDate ? ` expires="${expiresDate}"` : "";

  if (encryptType.value === "password") {
    encryptTag = `[encrypt type="password" password="${escapeAttr(password.value)}" id="${blockId}"${hintAttr}${expiresAttr}]\n${contentToEncrypt.value}\n[/encrypt]`;
  } else {
    encryptTag = `[encrypt type="paid" price="${price.value}" id="${blockId}"${hintAttr}${expiresAttr}]\n${contentToEncrypt.value}\n[/encrypt]`;
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
</style>
