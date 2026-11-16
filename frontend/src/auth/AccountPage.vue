<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import { RouterLink, useRouter } from 'vue-router';
import { Activity, ArrowRight, BookOpen, Braces, Settings2, Terminal, UserRound } from '@lucide/vue';
import { requestSaveLogout } from '../workspace/logoutIntent';
import { validPassword, validUsername } from './api';
import { useAccount } from './useAccount';
import CaptchaField from './CaptchaField.vue';

const router = useRouter();
const { query, identity, pending, error, notice, temporaryPassword, createdUsername, createdUserId, unresolvedCreate,
  unresolvedReset, signIn, signOut, password, create, reset } = useAccount();
const username = ref('');
const loginPassword = ref('');
const captchaId = ref('');
const captchaAnswer = ref('');
const captchaEpoch = ref(0);
function updateCaptcha(id: string, answer: string) { captchaId.value = id; captchaAnswer.value = answer; }
const currentPassword = ref('');
const newPassword = ref('');
const invitedUsername = ref('');
const changingPassword = ref(false);
const logoutRequested = ref(false);
const resetTarget = ref('');
const resetValid = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(resetTarget.value));
const showPasswordForm = computed(() => identity.value?.passwordChangeRequired || changingPassword.value);
const loginValid = computed(() => validUsername(username.value) && validPassword(loginPassword.value)
  && !!captchaId.value && captchaAnswer.value.trim().length === 5);
const passwordValid = computed(() => validPassword(currentPassword.value) && validPassword(newPassword.value));
watch(() => identity.value?.id, () => {
  loginPassword.value = '';
  currentPassword.value = '';
  newPassword.value = '';
  changingPassword.value = false;
  logoutRequested.value = false;
  temporaryPassword.value = null;
});
async function saveThenLogout() {
  if (!identity.value || pending.value) return;
  requestSaveLogout(identity.value.id);
  await router.push({ name: 'workspace' });
}
async function submitLogin() {
  if (!loginValid.value || pending.value) return;
  try { await signIn(username.value, loginPassword.value, captchaId.value, captchaAnswer.value); }
  finally { loginPassword.value = ''; updateCaptcha('', ''); captchaEpoch.value++; }
}
async function submitPassword() {
  if (!passwordValid.value || pending.value) return;
  try { await password(currentPassword.value, newPassword.value); }
  finally { currentPassword.value = ''; newPassword.value = ''; }
}
</script>

<template>
  <main
    class="account-page"
    :class="{ 'login-layout': !identity }"
  >
    <header class="account-hero">
      <div
        v-if="!identity"
        class="login-brand-mark"
      >
        <Terminal
          :size="20"
          aria-hidden="true"
        />
      </div>
      <p class="account-eyebrow">
        CodeCraft / 工程演练
      </p>
      <h1>{{ identity ? '账号设置' : '受邀账号登录' }}</h1>
      <p class="account-lead">
        {{ identity ? '管理个人资料与账号安全；在真实工作区中练习分析、编码与验证，保留每一次工程决策。' : '在真实工作区中练习分析、编码与验证，保留每一次工程决策。' }}
      </p>
      <RouterLink
        v-if="!identity"
        to="/"
        class="status-link"
      >
        <Activity
          :size="15"
          aria-hidden="true"
        />
        <span>查看服务链路</span>
        <ArrowRight
          :size="14"
          aria-hidden="true"
        />
      </RouterLink>
    </header>
    <section
      class="account-body"
      aria-label="账号操作"
      :aria-busy="pending"
    >
      <p
        v-if="error"
        role="alert"
        class="banner banner-error failure"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="banner banner-status"
      >
        {{ notice }}
      </p>
      <p
        v-if="query.isPending.value"
        role="status"
        class="banner banner-status"
      >
        正在检查登录状态…
      </p>
      <div
        v-else-if="query.isError.value"
        class="bento-card session-error-card"
      >
        <p
          role="alert"
          class="failure"
        >
          无法确认登录状态，请检查网络后重试。
        </p>
        <Button
          :disabled="query.isFetching.value"
          @click="query.refetch()"
        >
          重新检查登录
        </Button>
      </div>
      <form
        v-else-if="!identity"
        class="account-form login-form"
        @submit.prevent="submitLogin"
      >
        <div class="field-group">
          <label for="login-username">账号名</label>
          <input
            id="login-username"
            v-model="username"
            autocomplete="username"
            placeholder="输入受邀账号名"
            aria-describedby="username-help"
            :disabled="pending"
          >
          <small id="username-help">3–32 位字母、数字、下划线或连字符，不区分大小写。</small>
        </div>
        <div class="field-group">
          <label for="login-password">密码</label>
          <input
            id="login-password"
            v-model="loginPassword"
            type="password"
            autocomplete="current-password"
            placeholder="输入登录密码"
            aria-describedby="password-help"
            :disabled="pending"
          >
          <small id="password-help">8–128 个 Unicode 码点。空格计入密码。</small>
        </div>
        <CaptchaField
          :key="captchaEpoch"
          :disabled="pending"
          @change="updateCaptcha"
        />
        <Button
          type="submit"
          variant="primary"
          class="login-submit"
          :disabled="!loginValid || pending"
          :loading="pending"
        >
          登录
        </Button>
        <p class="form-footnote">
          不开放自助注册。请向管理员获取临时凭证。
        </p>
      </form>
      <template v-else>
        <div class="account-bento-grid">
          <section
            class="bento-card profile-card"
            aria-label="个人资料"
          >
            <div class="avatar">
              <UserRound
                :size="26"
                aria-hidden="true"
              />
            </div>
            <div class="profile-meta">
              <h2>个人资料</h2>
              <p class="account-identity">
                {{ identity.username }}
                <span class="role-badge">{{ identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</span>
              </p>
              <p
                v-if="!showPasswordForm"
                class="account-ready-note"
              >
                账号已就绪，可进入题目与工作区工程底座。
              </p>
            </div>
          </section>
          <nav
            v-if="identity && !identity.passwordChangeRequired"
            class="bento-card quick-links-card"
            aria-label="工作区快捷入口"
          >
            <h2>工程入口</h2>
            <p class="quick-links-desc">
              进入题库、个人模型连接或 Skill 配置。
            </p>
            <div class="quick-links-grid">
              <RouterLink
                :to="{ name: 'workspace' }"
                class="quick-link-tile"
              >
                <Braces
                  :size="16"
                  aria-hidden="true"
                />
                <span>题目与工作区</span>
              </RouterLink>
              <RouterLink
                v-if="identity.role === 'USER'"
                :to="{ name: 'models' }"
                class="quick-link-tile"
              >
                <Settings2
                  :size="16"
                  aria-hidden="true"
                />
                <span>个人模型连接</span>
              </RouterLink>
              <RouterLink
                v-if="identity.role === 'USER'"
                :to="{ name: 'skills' }"
                class="quick-link-tile"
              >
                <BookOpen
                  :size="16"
                  aria-hidden="true"
                />
                <span>个人 Skill</span>
              </RouterLink>
              <RouterLink
                to="/"
                class="quick-link-tile"
              >
                <Activity
                  :size="16"
                  aria-hidden="true"
                />
                <span>查看服务链路</span>
              </RouterLink>
            </div>
          </nav>
        </div>
        <section
          class="bento-card security-card"
          aria-label="账号安全"
        >
          <div class="security-header">
            <div>
              <h2>账号安全</h2>
              <p class="security-description">
                定期更新密码，退出前处理好本地草稿。
              </p>
            </div>
          </div>
          <form
            v-if="showPasswordForm"
            class="account-form security-subcard"
            @submit.prevent="submitPassword"
          >
            <h2>{{ identity.passwordChangeRequired ? '先修改临时密码' : '修改密码' }}</h2>
            <p class="security-description">
              修改后撤销所有旧登录，需要重新登录。
            </p>
            <div class="field-group">
              <label for="current-password">当前密码</label>
              <input
                id="current-password"
                v-model="currentPassword"
                type="password"
                autocomplete="current-password"
                :disabled="pending"
              >
            </div>
            <div class="field-group">
              <label for="new-password">新密码</label>
              <input
                id="new-password"
                v-model="newPassword"
                type="password"
                autocomplete="new-password"
                aria-describedby="new-password-help"
                :disabled="pending"
              >
              <small id="new-password-help">8–128 个 Unicode 码点，不要求字符组合，不去除空格。</small>
            </div>
            <div class="form-actions">
              <Button
                type="submit"
                variant="primary"
                :disabled="!passwordValid || pending"
                :loading="pending"
              >
                修改密码并重新登录
              </Button>
              <Button
                v-if="!identity.passwordChangeRequired"
                :disabled="pending"
                @click="changingPassword = false"
              >
                取消改密
              </Button>
            </div>
          </form>
          <template v-else>
            <div
              v-if="identity.role === 'ADMIN'"
              class="admin-bento-grid"
            >
              <form
                class="account-form security-subcard"
                @submit.prevent="create(invitedUsername)"
              >
                <h2>创建受邀账号</h2>
                <p class="security-description">
                  只管理账号，不提供用户代码、对话或模型凭证的浏览入口。
                </p>
                <div class="field-group">
                  <label for="invited-username">新账号名</label>
                  <input
                    id="invited-username"
                    v-model="invitedUsername"
                    autocomplete="off"
                    :disabled="pending || unresolvedCreate"
                  >
                </div>
                <Button
                  type="submit"
                  variant="primary"
                  :disabled="pending || (!unresolvedCreate && !validUsername(invitedUsername))"
                  :loading="pending"
                >
                  {{ unresolvedCreate ? '用原请求核对并重试' : '创建账号' }}
                </Button>
              </form>
              <form
                id="password-reset-form"
                class="account-form security-subcard"
                @submit.prevent="reset(resetTarget)"
              >
                <h2>重置账号密码</h2>
                <p class="security-description">
                  撤销目标全部旧登录并强制改密，不停止演练任务。重置自己会立即退出登录。
                </p>
                <p
                  v-if="createdUserId"
                  class="recent-user-id"
                >
                  最近操作的用户标识：<code>{{ createdUserId }}</code>
                </p>
                <div class="field-group">
                  <label for="reset-target">目标用户标识</label>
                  <input
                    id="reset-target"
                    v-model="resetTarget"
                    autocomplete="off"
                    :disabled="pending || unresolvedReset"
                  >
                </div>
                <Button
                  type="submit"
                  variant="danger"
                  :disabled="pending || (!unresolvedReset && !resetValid)"
                  :loading="pending"
                >
                  {{ unresolvedReset ? '用原请求核对重置' : '确认重置密码' }}
                </Button>
              </form>
            </div>
            <div class="security-actions-bar">
              <Button
                :disabled="pending"
                @click="changingPassword = true"
              >
                修改密码
              </Button>
              <Button
                class="logout-button"
                variant="danger"
                :disabled="pending"
                @click="logoutRequested = true"
              >
                退出登录
              </Button>
            </div>
          </template>
          <div
            v-if="showPasswordForm"
            class="security-actions-bar"
          >
            <Button
              class="logout-button"
              variant="danger"
              :disabled="pending"
              @click="logoutRequested = true"
            >
              退出登录
            </Button>
          </div>
          <section
            v-if="logoutRequested"
            class="logout-dialog-panel"
            role="dialog"
            aria-modal="true"
            aria-label="确认退出与草稿处理"
          >
            <h2>退出前处理本地草稿</h2>
            <p>保存后退出会进入工作区逐份处理账号草稿；需要显式操作权，不自动选择冲突版本。保存失败或仍有未处理备份时不会退出。</p>
            <div class="form-actions">
              <Button
                v-if="!identity.passwordChangeRequired"
                variant="primary"
                :disabled="pending"
                @click="saveThenLogout"
              >
                保存后退出
              </Button>
              <Button
                variant="danger"
                :disabled="pending"
                @click="signOut()"
              >
                丢弃账号草稿并退出
              </Button>
              <Button
                :disabled="pending"
                @click="logoutRequested = false"
              >
                取消退出
              </Button>
            </div>
          </section>
          <p class="security-note">
            登录只确认身份，不代表测试容器、模型或完整演练已准备完成。
          </p>
        </section>
      </template>
      <div
        v-if="temporaryPassword"
        class="bento-card credential-delivery"
        role="status"
      >
        <h2>临时凭证只展示这一次</h2>
        <p>账号：<strong>{{ createdUsername }}</strong> · 用户标识：<code>{{ createdUserId }}</code></p>
        <p class="security-description">
          请先通过可信渠道交付，再关闭此区域。刷新或退出后不能找回明文。
        </p>
        <div class="field-group">
          <label for="temporary-password">临时密码</label>
          <input
            id="temporary-password"
            :value="temporaryPassword"
            readonly
            autocomplete="off"
            spellcheck="false"
          >
        </div>
        <Button @click="temporaryPassword = null">
          已交付，清除显示
        </Button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.account-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 36px 32px 64px;
}

.account-hero {
  margin-bottom: 28px;
}

.account-eyebrow {
  margin: 0 0 8px;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--oc-accent);
}

.account-hero h1 {
  margin: 0 0 8px;
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 650;
  letter-spacing: -0.03em;
}

.account-lead {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 14px;
  line-height: 1.65;
  max-width: 62ch;
}

.bento-card {
  padding: 26px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-sm), var(--oc-hairline-top);
}

.account-bento-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
  gap: 20px;
  margin-bottom: 20px;
}

.profile-card {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-meta {
  min-width: 0;
  flex: 1;
}

.profile-card h2,
.quick-links-card h2,
.security-card h2 {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 8px;
}

.avatar {
  width: 58px;
  height: 58px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  box-shadow: var(--oc-hairline-top), 0 0 24px -6px var(--oc-accent-glow);
  color: var(--oc-accent);
  flex-shrink: 0;
}

.account-identity {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  overflow-wrap: anywhere;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.role-badge {
  display: inline-flex;
  align-items: center;
  color: var(--oc-accent);
  font-size: 11.5px;
  font-weight: 550;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
}

.account-ready-note {
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.quick-links-desc {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.quick-links-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.quick-link-tile {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 14px;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg);
  text-decoration: none;
  font-size: 13px;
  font-weight: 550;
  box-shadow: var(--oc-hairline-top);
  transition:
    background-color 180ms var(--oc-ease),
    border-color 180ms var(--oc-ease),
    transform 160ms var(--oc-ease);
}

.quick-link-tile:hover {
  background: var(--oc-surface-hover);
  border-color: var(--oc-border-strong);
  color: var(--oc-fg);
  transform: translateY(-1px);
}

.quick-link-tile svg {
  color: var(--oc-accent);
  flex-shrink: 0;
}

.security-card {
  display: grid;
  gap: 20px;
}

.security-description,
.security-note {
  color: var(--oc-fg-muted);
  font-size: 13px;
  line-height: 1.65;
  margin: 0;
}

.security-note {
  padding-top: 18px;
  border-top: 1px solid var(--oc-border);
}

.admin-bento-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.security-subcard {
  padding: 20px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid var(--oc-border);
}

.security-actions-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.logout-dialog-panel {
  padding: 20px;
  border-radius: 12px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-danger-border);
  box-shadow: var(--oc-shadow-md);
  display: grid;
  gap: 12px;
}

.logout-dialog-panel h2 {
  margin: 0;
  font-size: 16px;
}

.logout-dialog-panel p {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
  line-height: 1.65;
}

/* Split Card Login Layout (Vercel / Stripe inspired) */
.login-layout {
  max-width: 980px;
  margin: 48px auto;
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.1fr);
  gap: 48px;
  padding: 44px;
  background:
    radial-gradient(circle at 12% 18%, rgba(160, 196, 165, 0.07), transparent 55%),
    var(--oc-surface);
  border: 1px solid var(--oc-border-strong);
  border-radius: 18px;
  box-shadow: var(--oc-shadow-lg), var(--oc-hairline-top);
}

.login-layout > header {
  display: flex;
  flex-direction: column;
  justify-content: center;
  margin: 0;
  border-right: 1px solid var(--oc-border);
  padding-right: 40px;
}

.login-brand-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  box-shadow: var(--oc-hairline-top), var(--oc-shadow-xs);
  color: var(--oc-accent);
  margin-bottom: 20px;
}

.login-layout > header h1 {
  font-size: clamp(26px, 3.2vw, 36px);
  line-height: 1.15;
  margin-bottom: 12px;
}

.status-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-top: 24px;
  padding: 7px 12px;
  width: fit-content;
  border-radius: 999px;
  border: 1px solid var(--oc-border);
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  text-decoration: none;
  font-size: 12.5px;
  font-weight: 500;
}

.status-link:hover {
  color: var(--oc-fg);
  border-color: var(--oc-border-strong);
}

.account-body {
  min-width: 0;
  display: grid;
  gap: 16px;
}

.account-form {
  display: grid;
  gap: 16px;
  max-width: 480px;
}

.login-form {
  max-width: none;
}

.account-form h2 {
  margin: 0;
  font-size: 18px;
}

.field-group {
  display: grid;
  gap: 7px;
}

.account-form label,
.field-group label {
  font-size: 13px;
  font-weight: 550;
  color: var(--oc-fg);
}

input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  font: inherit;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.35);
  transition:
    border-color 180ms var(--oc-ease),
    box-shadow 180ms var(--oc-ease);
}

input:hover:not(:disabled) {
  border-color: #7c8492;
}

input:focus-visible {
  border-color: var(--oc-accent);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.35), 0 0 0 3px var(--oc-accent-glow);
}

input:disabled {
  background: var(--oc-surface-2);
  opacity: 0.65;
}

small {
  color: var(--oc-fg-muted);
  font-size: 12.5px;
  line-height: 1.55;
}

.login-submit {
  width: 100%;
  min-height: 42px;
  margin-top: 4px;
}

.form-footnote {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.banner {
  margin: 0;
  padding: 12px 14px;
  border-radius: 10px;
  font-size: 13.5px;
  line-height: 1.55;
}

.banner-error {
  color: var(--oc-danger);
  background: var(--oc-danger-bg);
  border: 1px solid var(--oc-danger-border);
}

.banner-status {
  color: var(--oc-accent);
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
}

.recent-user-id {
  margin: 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.credential-delivery {
  border-left: 3px solid var(--oc-accent);
  display: grid;
  gap: 12px;
}

.credential-delivery h2,
.credential-delivery p {
  margin: 0;
}

@media (max-width: 860px) {
  .login-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 28px;
    padding: 28px 24px;
    margin: 20px 16px;
  }
  .login-layout > header {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
    padding: 0 0 24px;
  }
  .account-bento-grid,
  .admin-bento-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .account-page:not(.login-layout) {
    padding: 20px 16px 40px;
  }
  .bento-card {
    padding: 20px;
  }
}
</style>
