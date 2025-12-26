<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { VButton, Toast } from "@halo-dev/components";
import axios from "axios";

interface PasswordInfo {
  id: string;
  name: string;
  code: string;
  durationDays: number;
  remaining: string;
  expiresAt: string;
  createdAt: string;
}

interface ListResponse {
  success: boolean;
  passwords: PasswordInfo[];
  error: string | null;
}

const passwords = ref<PasswordInfo[]>([]);
const loading = ref(false);
const showCreateModal = ref(false);
const newPasswordName = ref("");
const newPasswordDays = ref(7);
const refreshInterval = ref<number | null>(null);

// 区块级 TOTP
interface BlockTotpInfo {
  blockId: string;
  label: string;
  currentCode: string;
  remainingTime: string;
  durationDays: number;
  createdAt: string;
}

const showBlockSection = ref(false);
const blockTotps = ref<BlockTotpInfo[]>([]);
const showArticleSection = ref(false);
const articleTotps = ref<BlockTotpInfo[]>([]);
const loadingBlocks = ref(false);

// 分类 TOTP
interface CategoryTotpInfo {
  slug: string;
  name: string;      // 显示名称 (slug)
  hint?: string;
  enabled: boolean;  // 设置中是否启用
  hasKey: boolean;   // 是否已生成密钥
  currentCode?: string;
  remainingTime?: string;
  durationDays?: number;
}

const showCategorySection = ref(false);
const categoryTotps = ref<CategoryTotpInfo[]>([]);
const loadingCategories = ref(false);

// 获取密码列表
async function fetchPasswords() {
  try {
    const response = await axios.get<ListResponse>(
      "/apis/encrypt.halo.run/v1alpha1/totp/list"
    );
    if (response.data.success) {
      passwords.value = response.data.passwords;
    }
  } catch (error) {
    console.error("获取密码列表失败", error);
  }
}

// 创建新密码
async function createPassword() {
  if (!newPasswordName.value.trim()) {
    Toast.warning("请输入密码名称");
    return;
  }
  
  loading.value = true;
  try {
    await axios.post(
      "/apis/encrypt.halo.run/v1alpha1/totp/create",
      {
        name: newPasswordName.value,
        durationDays: newPasswordDays.value
      }
    );
    Toast.success("密码创建成功");
    showCreateModal.value = false;
    newPasswordName.value = "";
    newPasswordDays.value = 7;
    await fetchPasswords();
  } catch {
    Toast.error("创建失败，请稍后重试");
  } finally {
    loading.value = false;
  }
}

// 删除密码
async function deletePassword(id: string, name: string) {
  if (!confirm(`确定要删除密码「${name}」吗？`)) {
    return;
  }
  
  try {
    await axios.delete(`/apis/encrypt.halo.run/v1alpha1/totp/${id}`);
    Toast.success("删除成功");
    await fetchPasswords();
  } catch {
    Toast.error("删除失败");
  }
}

// 复制密码
async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code);
    Toast.success("密码已复制到剪贴板");
  } catch {
    const input = document.createElement('input');
    input.value = code;
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    document.body.removeChild(input);
    Toast.success("密码已复制");
  }
}

// 获取分类 TOTP 列表
async function fetchCategoryTotps() {
  loadingCategories.value = true;
  try {
    // 1. 从后端 API 直接获取启用了 TOTP 的分类列表
    const categoriesResponse = await axios.get(
      "/apis/api.encrypt.halo.run/v1alpha1/category-totp/list"
    );
    
    // 2. 获取现有的 TOTP 列表 (ConfigMap)
    const listResponse = await axios.get(
      "/apis/api.encrypt.halo.run/v1alpha1/block-totp/list"
    );
    
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const existingKeys = new Map<string, any>();
     
    if (Array.isArray(listResponse.data)) {
       // eslint-disable-next-line @typescript-eslint/no-explicit-any
       listResponse.data.forEach((block: any) => {
         existingKeys.set(block.blockId, block);
       });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } else if (listResponse.data && Array.isArray((listResponse.data as any).blocks)) {
       // 兼容性保留
       // eslint-disable-next-line @typescript-eslint/no-explicit-any
       (listResponse.data as any).blocks.forEach((block: any) => {
         existingKeys.set(block.blockId, block);
       });
    }

    // 3. 构建列表
    const categories: CategoryTotpInfo[] = [];
     
    const enabledCats = categoriesResponse.data || [];
    
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    enabledCats.forEach((item: any) => {
       const slug = item.slug;
       const totpId = `category-${slug}`;
       const keyInfo = existingKeys.get(totpId);
       
       categories.push({
         slug: slug,
         name: slug,
         hint: item.hint,
         enabled: true,
         hasKey: !!keyInfo,
         currentCode: keyInfo?.currentCode,
         remainingTime: keyInfo?.remainingTime,
         durationDays: item.totpDuration ? parseInt(item.totpDuration) : 7
       });
    });
    
    categoryTotps.value = categories;
  } catch (error) {
    console.error("获取分类 TOTP 失败", error);
  } finally {
    loadingCategories.value = false;
  }
}

// 生成分类 TOTP
async function generateCategoryTotp(category: CategoryTotpInfo) {
  try {
    const totpId = `category-${category.slug}`;
    const label = `分类: ${category.slug}`;
    
    const response = await axios.post(
      "/apis/api.encrypt.halo.run/v1alpha1/block-totp/generate",
      {
        blockId: totpId,
        durationDays: category.durationDays || 7,
        label: label
      }
    );

    if (response.data.success) {
      Toast.success(`生成成功: ${response.data.currentCode}`);
      await fetchCategoryTotps();
    } else {
      Toast.error("生成失败");
    }
  } catch (error) {
    Toast.error("生成失败");
    console.error(error);
  }
}

// 删除分类 TOTP (实际上是删除了 ConfigMap 中的 key)
async function deleteCategoryTotp(category: CategoryTotpInfo) {
  if (!confirm(`确定要删除分类 ${category.slug} 的动态密码吗？删除后需重新生成。`)) {
    return;
  }
  
  try {
    const totpId = `category-${category.slug}`;
    await axios.delete(`/apis/api.encrypt.halo.run/v1alpha1/block-totp/${totpId}`);
    Toast.success("删除成功");
    await fetchCategoryTotps();
  } catch {
    Toast.error("删除失败");
  }
}


// 获取区块级 TOTP 列表
async function fetchBlockTotps() {
  loadingBlocks.value = true;
  try {
    const response = await axios.get<BlockTotpInfo[]>(
      "/apis/api.encrypt.halo.run/v1alpha1/block-totp/list"
    );
    
    const allBlocks = response.data;
    const blocks: BlockTotpInfo[] = [];
    const articles: BlockTotpInfo[] = [];
    
     
    if (Array.isArray(allBlocks)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        allBlocks.forEach((item: any) => {
            if (item.blockId.startsWith('category-')) {
                // Ignore, handled by Category section
                return; 
            }
            if (item.blockId.startsWith('article-')) {
                articles.push(item);
            } else {
                blocks.push(item);
            }
        });
    }
    
    blockTotps.value = blocks;
    articleTotps.value = articles;
  } catch (error) {
    console.error("获取区块 TOTP 列表失败", error);
    blockTotps.value = [];
  } finally {
    loadingBlocks.value = false;
  }
}

// 复制区块密码
function copyBlockCode(code: string) {
  copyCode(code);
}

// 删除区块 TOTP
async function deleteBlockTotp(blockId: string, label: string) {
  if (!confirm(`确定要删除区块密码「${label}」吗？`)) {
    return;
  }
  
  try {
    await axios.delete(`/apis/api.encrypt.halo.run/v1alpha1/block-totp/${blockId}`);
    Toast.success("删除成功");
    await fetchBlockTotps();
  } catch {
    Toast.error("删除失败");
  }
}

onMounted(() => {
  fetchPasswords();
  fetchBlockTotps();
  fetchCategoryTotps();
  refreshInterval.value = window.setInterval(() => {
    fetchPasswords();
    fetchBlockTotps();
    fetchCategoryTotps();
  }, 30000);
});

onUnmounted(() => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value);
  }
});
</script>

<template>
  <div class="totp-container">
    <div class="header">
      <h2>🔐 动态密码管理</h2>
      <VButton type="primary" @click="showCreateModal = true">
        ➕ 添加新密码
      </VButton>
    </div>

    <!-- 密码列表 -->
    <div v-if="passwords.length > 0" class="password-list">
      <div v-for="pwd in passwords" :key="pwd.id" class="password-card">
        <div class="card-header">
          <span class="password-name">{{ pwd.name }}</span>
          <span class="duration-badge">{{ pwd.durationDays }}天有效</span>
        </div>
        
        <div class="code-section">
          <span class="code">{{ pwd.code }}</span>
          <button class="copy-btn" @click="copyCode(pwd.code)" title="复制">
            📋
          </button>
        </div>
        
        <div class="meta-info">
          <span>⏱️ 剩余: {{ pwd.remaining }}</span>
        </div>
        
        <div class="card-actions">
          <VButton size="sm" type="danger" @click="deletePassword(pwd.id, pwd.name)">
            删除
          </VButton>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <div class="empty-icon">🔑</div>
      <p>还没有动态密码</p>
      <p class="hint">点击上方按钮创建第一个动态密码</p>
    </div>

    <!-- 创建密码弹窗 -->
    <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
      <div class="modal-content">
        <h3>创建新密码</h3>
        
        <div class="form-group">
          <label>密码名称</label>
          <input v-model="newPasswordName" placeholder="如: VIP周密码" />
        </div>
        
        <div class="form-group">
          <label>有效期（天）</label>
          <select v-model="newPasswordDays">
            <option :value="1">1 天</option>
            <option :value="7">7 天（一周）</option>
            <option :value="30">30 天（一月）</option>
            <option :value="90">90 天（一季度）</option>
            <option :value="365">365 天（一年）</option>
          </select>
        </div>
        
        <div class="modal-actions">
          <VButton @click="showCreateModal = false">取消</VButton>
          <VButton type="primary" @click="createPassword" :loading="loading">
            创建
          </VButton>
        </div>
      </div>
    </div>

    <!-- 使用说明 -->
    <div class="usage-info">
      <h4>💡 使用说明</h4>
      <ul>
        <li>每个密码从<strong>创建时刻</strong>开始计算有效期</li>
        <li>到期后密码会自动更换，用户需使用新密码</li>
        <li>可创建多个不同用途的密码（如 VIP 周密码、临时密码等）</li>
        <li>任意一个有效密码都可以解锁加密内容</li>
      </ul>
    </div>

    <!-- 分类动态密码 -->
    <div class="category-totp-section block-totp-section">
      <div class="section-header" @click="showCategorySection = !showCategorySection">
        <h3>📂 分类动态密码</h3>
        <span class="toggle-icon">{{ showCategorySection ? '▼' : '▶' }}</span>
      </div>
      
      <div v-if="showCategorySection" class="block-content">
        <p class="section-desc">已在插件设置中启用动态密码的分类</p>
        
        <!-- Loading 状态 -->
        <div v-if="loadingCategories" class="loading-state">
          <div class="spinner"></div>
          <p>加载中...</p>
        </div>
        
        <!-- 分类列表 -->
        <div v-else-if="categoryTotps.length > 0" class="block-list">
          <div v-for="cat in categoryTotps" :key="cat.slug" class="block-card">
            <div class="block-header">
              <h4>{{ cat.name }}</h4>
              <span class="duration-badge" v-if="cat.hasKey">{{ cat.durationDays }}天有效</span>
              <span class="status-badge warning" v-else>未生成</span>
            </div>
            
            <div class="block-meta" v-if="cat.hint">
              <span>提示: {{ cat.hint }}</span>
            </div>

            <!-- 已生成密钥 -->
            <div v-if="cat.hasKey" class="block-code-section">
              <div class="code-display">{{ cat.currentCode }}</div>
              <button class="copy-btn" @click="copyBlockCode(cat.currentCode!)" title="复制密码">
                📋
              </button>
              <button class="delete-btn" @click="deleteCategoryTotp(cat)" title="删除">
                🗑️
              </button>
            </div>
            
            <!-- 未生成密钥 -->
            <div v-else class="generate-section">
              <VButton size="sm" @click="generateCategoryTotp(cat)">
                生成动态密码
              </VButton>
            </div>

            <div class="block-meta" v-if="cat.hasKey">
              <span>剩余时间: {{ cat.remainingTime }}</span>
              <span class="block-id">ID: category-{{ cat.slug }}</span>
            </div>
          </div>
        </div>
        
        <!-- 空状态 -->
        <div v-else class="empty-state">
          <div class="empty-icon">📂</div>
          <p>没有启用动态密码的分类</p>
          <p class="hint">请先在插件设置 -> 分类加密中开启</p>
        </div>
      </div>
    </div>
    
    <!-- 全文动态密码 -->
    <div class="article-totp-section block-totp-section">
      <div class="section-header" @click="showArticleSection = !showArticleSection">
        <h3>📄 全文动态密码</h3>
        <span class="toggle-icon">{{ showArticleSection ? '▼' : '▶' }}</span>
      </div>
      
      <div v-if="showArticleSection" class="block-content">
        <p class="section-desc">全文加密文章的动态密码列表</p>
        
        <!-- Loading 状态 -->
        <div v-if="loadingBlocks" class="loading-state">
          <div class="spinner"></div>
          <p>加载中...</p>
        </div>
        
        <!-- 列表 -->
        <div v-else-if="articleTotps.length > 0" class="block-list">
          <div v-for="block in articleTotps" :key="block.blockId" class="block-card">
            <div class="block-header">
              <h4>{{ block.label }}</h4>
              <span class="duration-badge">{{ block.durationDays }}天有效</span>
            </div>
            <div class="block-code-section">
              <div class="code-display">{{ block.currentCode }}</div>
              <button class="copy-btn" @click="copyBlockCode(block.currentCode)" title="复制密码">
                📋
              </button>
              <button class="delete-btn" @click="deleteBlockTotp(block.blockId, block.label)" title="删除">
                🗑️
              </button>
            </div>
            <div class="block-meta">
              <span>剩余时间: {{ block.remainingTime }}</span>
              <span class="block-id">ID: {{ block.blockId }}</span>
            </div>
          </div>
        </div>
        
        <!-- 空状态 -->
        <div v-else class="empty-state">
          <div class="empty-icon">📄</div>
          <p>还没有全文动态密码</p>
          <p class="hint">对整篇文章进行加密时生成的动态密码会显示在这里</p>
        </div>
      </div>
    </div>

    <!-- 区块级动态密码 -->
    <div class="block-totp-section">
      <div class="section-header" @click="showBlockSection = !showBlockSection">
        <h3>📦 区块动态密码</h3>
        <span class="toggle-icon">{{ showBlockSection ? '▼' : '▶' }}</span>
      </div>
      
      <div v-if="showBlockSection" class="block-content">
        <p class="section-desc">独立加密区块的动态密码列表</p>
        
        <!-- Loading 状态 -->
        <div v-if="loadingBlocks" class="loading-state">
          <div class="spinner"></div>
          <p>加载中...</p>
        </div>
        
        <!-- 区块列表 -->
        <div v-else-if="blockTotps.length > 0" class="block-list">
          <div v-for="block in blockTotps" :key="block.blockId" class="block-card">
            <div class="block-header">
              <h4>{{ block.label }}</h4>
              <span class="duration-badge">{{ block.durationDays }}天有效</span>
            </div>
            <div class="block-code-section">
              <div class="code-display">{{ block.currentCode }}</div>
              <button class="copy-btn" @click="copyBlockCode(block.currentCode)" title="复制密码">
                📋
              </button>
              <button class="delete-btn" @click="deleteBlockTotp(block.blockId, block.label)" title="删除">
                🗑️
              </button>
            </div>
            <div class="block-meta">
              <span>剩余时间: {{ block.remainingTime }}</span>
              <span class="block-id">ID: {{ block.blockId }}</span>
            </div>
          </div>
        </div>
        
        <!-- 空状态 -->
        <div v-else class="empty-state">
          <div class="empty-icon">📦</div>
          <p>还没有区块动态密码</p>
          <p class="hint">在编辑器中插入加密区块时，可以启用独立动态密码</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.totp-container {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.header h2 {
  margin: 0;
  font-size: 20px;
}

.password-list {
  display: grid;
  gap: 16px;
}

.password-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  padding: 20px;
  color: white;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.password-name {
  font-size: 16px;
  font-weight: 600;
}

.duration-badge {
  background: rgba(255, 255, 255, 0.25);
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 12px;
}

.code-section {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.15);
  padding: 16px;
  border-radius: 8px;
  margin-bottom: 12px;
}

.code {
  font-size: 36px;
  font-family: "Courier New", monospace;
  font-weight: bold;
  letter-spacing: 6px;
}

.copy-btn {
  background: rgba(255, 255, 255, 0.3);
  border: none;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 20px;
  cursor: pointer;
  transition: 0.2s;
}

.copy-btn:hover {
  background: rgba(255, 255, 255, 0.5);
}

.meta-info {
  text-align: center;
  margin-bottom: 12px;
  font-size: 14px;
  opacity: 0.9;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  background: #f8fafc;
  border-radius: 12px;
  color: #64748b;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.empty-state p {
  margin: 8px 0;
}

.hint {
  font-size: 14px;
  opacity: 0.8;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  padding: 24px;
  border-radius: 12px;
  width: 400px;
  max-width: 90vw;
}

.modal-content h3 {
  margin: 0 0 20px 0;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
  color: #374151;
}

.form-group input,
.form-group select {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}

/* 使用说明 */
.usage-info {
  margin-top: 32px;
  padding: 20px;
  background: #f1f5f9;
  border-radius: 12px;
}

.usage-info h4 {
  margin: 0 0 12px 0;
  color: #334155;
}

.usage-info ul {
  margin: 0;
  padding-left: 20px;
  color: #64748b;
  font-size: 14px;
}

.usage-info li {
  margin: 6px 0;
}

/* 深色模式 */
@media (prefers-color-scheme: dark) {
  .empty-state {
    background: #1e293b;
    color: #94a3b8;
  }
  
  .usage-info {
    background: #1e293b;
  }
  
  .usage-info h4 {
    color: #e2e8f0;
  }
  
  .usage-info ul {
    color: #94a3b8;
  }
  
  .modal-content {
    background: #1e293b;
    color: #e2e8f0;
  }
  
  .form-group label {
    color: #e2e8f0;
  }
  
  .form-group input,
  .form-group select {
    background: #0f172a;
    border-color: #334155;
    color: #e2e8f0;
  }
}

/* 区块级动态密码 */
.block-totp-section {
  margin-top: 32px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #f8fafc;
  border-radius: 8px;
  cursor: pointer;
  transition: 0.2s;
}

.section-header:hover {
  background: #f1f5f9;
}

.section-header h3 {
  margin: 0;
  font-size: 18px;
  color: #1e293b;
}

.toggle-icon {
  font-size: 14px;
  color: #64748b;
}

.block-content {
  padding: 16px 0;
}

.section-desc {
  color: #64748b;
  margin-bottom: 16px;
}

.loading-state {
  text-align: center;
  padding: 40px;
  color: #64748b;
}

.spinner {
  display: inline-block;
  width: 24px;
  height: 24px;
  border: 3px solid #e2e8f0;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.block-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.block-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  transition: 0.2s;
}

.block-card:hover {
  border-color: #cbd5e1;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.block-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.block-header h4 {
  margin: 0;
  font-size: 16px;
  color: #1e293b;
}

.duration-badge {
  background: #dbeafe;
  color: #1e40af;
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.block-code-section {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.code-display {
  flex: 1;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 16px;
  border-radius: 8px;
  font-size: 24px;
  font-weight: 600;
  text-align: center;
  letter-spacing: 4px;
}

.block-meta {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #64748b;
}

.block-id {
  font-family: monospace;
  font-size: 11px;
}

.delete-btn {
  background: #fee2e2;
  color: #dc2626;
  border: none;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 20px;
  cursor: pointer;
  transition: 0.2s;
}

.delete-btn:hover {
  background: #fecaca;
}

.category-totp-section {
  margin-top: 32px;
}

.status-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
  background: #e2e8f0;
  color: #64748b;
}

.status-badge.warning {
  background: #fef3c7;
  color: #b45309;
}

.generate-section {
  display: flex;
  justify-content: center;
  margin-bottom: 12px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px dashed #cbd5e1;
}
</style>
