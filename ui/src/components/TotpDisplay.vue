<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { VButton, VSpace, Toast } from "@halo-dev/components";
import axios from "axios";

interface TotpResponse {
  enabled: boolean;
  code: string | null;
  expiresAt: string | null;
  remaining: string | null;
  periodDescription: string | null;
  error: string | null;
}

interface GenerateResponse {
  success: boolean;
  message: string;
  secret: string | null;
}

const totpData = ref<TotpResponse | null>(null);
const loading = ref(false);
const refreshInterval = ref<number | null>(null);

// 获取当前 TOTP 密码
async function fetchCurrentCode() {
  try {
    const response = await axios.get<TotpResponse>(
      "/apis/encrypt.halo.run/v1alpha1/totp/current"
    );
    totpData.value = response.data;
  } catch (error) {
    console.error("获取 TOTP 密码失败", error);
    totpData.value = {
      enabled: false,
      code: null,
      expiresAt: null,
      remaining: null,
      periodDescription: null,
      error: "获取失败，请检查网络连接",
    };
  }
}

// 生成新密钥（自动保存到设置）
async function generateSecret() {
  loading.value = true;
  try {
    const response = await axios.post<GenerateResponse>(
      "/apis/encrypt.halo.run/v1alpha1/totp/generate"
    );
    
    if (response.data.success) {
      Toast.success(response.data.message);
      // 刷新获取最新状态
      setTimeout(fetchCurrentCode, 500);
    } else {
      Toast.error(response.data.message);
    }
  } catch (error) {
    Toast.error("生成密钥失败，请稍后重试");
  } finally {
    loading.value = false;
  }
}

// 复制密码
async function copyCode() {
  if (totpData.value?.code) {
    try {
      await navigator.clipboard.writeText(totpData.value.code);
      Toast.success("密码已复制到剪贴板");
    } catch {
      // 备用复制方法
      const input = document.createElement('input');
      input.value = totpData.value.code;
      document.body.appendChild(input);
      input.select();
      document.execCommand('copy');
      document.body.removeChild(input);
      Toast.success("密码已复制");
    }
  }
}

onMounted(() => {
  fetchCurrentCode();
  // 每10秒刷新一次
  refreshInterval.value = window.setInterval(fetchCurrentCode, 10000);
});

onUnmounted(() => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value);
  }
});
</script>

<template>
  <div class="totp-display-container">
    <h3 class="section-title">🔐 动态密码管理</h3>

    <!-- 未启用或无密钥状态 -->
    <div v-if="!totpData?.enabled || totpData?.error" class="totp-disabled">
      <div class="disabled-icon">🔒</div>
      <p class="disabled-text">
        {{ totpData?.error || "动态密码未启用" }}
      </p>
      <p class="disabled-hint">
        点击下方按钮一键生成密钥并启用动态密码
      </p>
      <VButton type="primary" @click="generateSecret" :loading="loading">
        🔑 生成密钥并启用
      </VButton>
    </div>

    <!-- 已启用状态 -->
    <div v-else class="totp-enabled">
      <div class="period-label">
        {{ totpData.periodDescription }}
      </div>

      <div class="code-display">
        <span class="code-text">{{ totpData.code }}</span>
        <button class="copy-btn" @click="copyCode" title="复制密码">
          📋
        </button>
      </div>

      <div class="expiry-info">
        ⏱️ 剩余时间: {{ totpData.remaining }}
      </div>

      <div class="action-buttons">
        <VButton size="sm" @click="fetchCurrentCode">🔄 刷新</VButton>
        <VButton size="sm" type="danger" @click="generateSecret" :loading="loading">
          🔑 重新生成密钥
        </VButton>
      </div>

      <div class="usage-hint">
        <p>💡 使用提示:</p>
        <ul>
          <li>将上方密码告知需要访问加密内容的用户</li>
          <li>密码会自动在到期后更换</li>
          <li>还可以设置「万能密钥」作为备用固定密码</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.totp-display-container {
  margin: 16px 0;
  padding: 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  color: white;
}

.section-title {
  margin: 0 0 20px 0;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.totp-disabled {
  text-align: center;
  padding: 24px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 8px;
}

.disabled-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.disabled-text {
  font-size: 16px;
  margin: 8px 0;
  font-weight: 500;
}

.disabled-hint {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 20px;
}

.totp-enabled {
  text-align: center;
}

.period-label {
  font-size: 14px;
  opacity: 0.9;
  margin-bottom: 12px;
  font-weight: 500;
}

.code-display {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.2);
  padding: 20px 32px;
  border-radius: 12px;
  margin-bottom: 16px;
}

.code-text {
  font-size: 48px;
  font-family: "Courier New", monospace;
  font-weight: bold;
  letter-spacing: 8px;
}

.copy-btn {
  background: rgba(255, 255, 255, 0.3);
  border: none;
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 24px;
  cursor: pointer;
  transition: all 0.2s;
}

.copy-btn:hover {
  background: rgba(255, 255, 255, 0.5);
  transform: scale(1.05);
}

.expiry-info {
  font-size: 15px;
  opacity: 0.95;
  margin-bottom: 20px;
}

.action-buttons {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-bottom: 20px;
}

.usage-hint {
  text-align: left;
  background: rgba(255, 255, 255, 0.1);
  padding: 16px;
  border-radius: 8px;
  font-size: 13px;
}

.usage-hint p {
  margin: 0 0 8px 0;
  font-weight: 600;
}

.usage-hint ul {
  margin: 0;
  padding-left: 20px;
}

.usage-hint li {
  margin: 4px 0;
  opacity: 0.9;
}
</style>
