<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import { RouterLink, useRouter } from 'vue-router';
import { Activity, ArrowRight, Terminal, UserRound } from '@lucide/vue';
import { routes } from '../routes';
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
    :class="{ 'is-login': !identity }"
  >
    <div
      v-if="!identity"
      class="login-shell"
    >
      <header class="login-head">
        <div class="login-brand">
          <Terminal
            :size="16"
            aria-hidden="true"
          />
          <span>CodeCraft</span>
        </div>
        <h1>受邀账号登录</h1>
      </header>

      <p
        v-if="error"
        role="alert"
        class="inline-alert"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="inline-notice"
      >
        {{ notice }}
      </p>

      <p
        v-if="query.isPending.value"
        role="status"
        class="inline-status"
      >
        正在检查登录状态…
      </p>
      <div
        v-else-if="query.isError.value"
        class="gate-card"
      >
        <p
          role="alert"
          class="failure-text"
        >
          无法确认登录状态，请检查网络后重试。
        </p>
        <Button
          size="small"
          :disabled="query.isFetching.value"
          @click="query.refetch()"
        >
          重新检查登录
        </Button>
      </div>
      <form
        v-else
        class="login-form"
        :aria-busy="pending"
        @submit.prevent="submitLogin"
      >
        <div class="field-group">
          <label for="login-username">账号名</label>
          <input
            id="login-username"
            v-model="username"
            autocomplete="username"
            placeholder="输入受邀账号名"
            :disabled="pending"
          >
        </div>
        <div class="field-group">
          <label for="login-password">密码</label>
          <input
            id="login-password"
            v-model="loginPassword"
            type="password"
            autocomplete="current-password"
            placeholder="输入登录密码"
            :disabled="pending"
          >
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
      </form>

      <footer class="login-foot">
        <RouterLink
          to="/"
          class="status-link"
        >
          <Activity
            :size="13"
            aria-hidden="true"
          />
          <span>查看服务状态</span>
          <ArrowRight
            :size="12"
            aria-hidden="true"
          />
        </RouterLink>
      </footer>
    </div>

    <div
      v-else
      class="settings-shell"
    >
      <header class="settings-header">
        <div class="header-top-row">
          <h1>设置</h1>
          <span class="owner-pill font-mono-num">{{ identity.username }}</span>
        </div>
        <nav
          v-if="!identity.passwordChangeRequired"
          class="ui-underline-tabs"
          aria-label="设置切换"
        >
          <RouterLink
            class="ui-underline-tab is-active"
            aria-current="page"
            :to="{ name: routes.account.name }"
          >
            {{ identity.role === 'ADMIN' ? '账号管理' : '账号设置' }}
          </RouterLink>
          <RouterLink
            class="ui-underline-tab"
            :to="{ name: routes.status.name }"
          >
            服务状态
          </RouterLink>
        </nav>
      </header>

      <section
        class="account-body"
        aria-label="账号操作"
        :aria-busy="pending"
      >
        <p
          v-if="error"
          role="alert"
          class="inline-alert"
        >
          {{ error }}
        </p>
        <p
          v-if="notice"
          role="status"
          class="inline-notice"
        >
          {{ notice }}
        </p>

        <section
          class="surface-block profile-row"
          aria-label="个人资料"
        >
          <div class="profile-left">
            <div class="avatar">
              <UserRound
                :size="18"
                aria-hidden="true"
              />
            </div>
            <div>
              <h2>个人资料</h2>
              <p class="identity-line">
                <span>{{ identity.username }}</span>
                <span class="role-chip">{{ identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</span>
              </p>
            </div>
          </div>
          <div class="profile-actions">
            <Button
              v-if="!showPasswordForm"
              size="small"
              variant="secondary"
              :disabled="pending"
              @click="changingPassword = true"
            >
              修改密码
            </Button>
            <Button
              class="logout-button"
              size="small"
              variant="danger"
              :disabled="pending"
              @click="logoutRequested = true"
            >
              退出登录
            </Button>
          </div>
        </section>

        <section
          class="surface-block"
          aria-label="账号安全"
        >
          <form
            v-if="showPasswordForm"
            class="inner-form"
            @submit.prevent="submitPassword"
          >
            <h3>{{ identity.passwordChangeRequired ? '先修改临时密码' : '修改密码' }}</h3>
            <div class="form-grid-2">
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
                  placeholder="8–128 位字符"
                  :disabled="pending"
                >
              </div>
            </div>
            <div class="form-actions">
              <Button
                type="submit"
                size="small"
                variant="primary"
                :disabled="!passwordValid || pending"
                :loading="pending"
              >
                修改密码并重新登录
              </Button>
              <Button
                v-if="!identity.passwordChangeRequired"
                size="small"
                variant="ghost"
                :disabled="pending"
                @click="changingPassword = false"
              >
                取消改密
              </Button>
            </div>
          </form>

          <div
            v-else-if="identity.role === 'ADMIN'"
            class="ui-divide-list admin-rows"
          >
            <form
              class="ui-divide-row admin-form-row"
              @submit.prevent="create(invitedUsername)"
            >
              <div class="admin-form-head">
                <h3>创建受邀账号</h3>
              </div>
              <div class="admin-inline-row">
                <div class="field-group inline-field">
                  <label for="invited-username">新账号名</label>
                  <input
                    id="invited-username"
                    v-model="invitedUsername"
                    autocomplete="off"
                    placeholder="3–32 位字母数字、下划线或连字符"
                    :disabled="pending || unresolvedCreate"
                  >
                </div>
                <Button
                  type="submit"
                  size="small"
                  variant="primary"
                  :disabled="pending || (!unresolvedCreate && !validUsername(invitedUsername))"
                  :loading="pending"
                >
                  {{ unresolvedCreate ? '用原请求核对并重试' : '创建账号' }}
                </Button>
              </div>
            </form>

            <form
              id="password-reset-form"
              class="ui-divide-row admin-form-row"
              @submit.prevent="reset(resetTarget)"
            >
              <div class="admin-form-head">
                <h3>重置账号密码</h3>
                <span
                  v-if="createdUserId"
                  class="recent-user font-mono"
                >最近 ID：{{ createdUserId }}</span>
              </div>
              <div class="admin-inline-row">
                <div class="field-group inline-field">
                  <label for="reset-target">目标用户标识</label>
                  <input
                    id="reset-target"
                    v-model="resetTarget"
                    autocomplete="off"
                    class="font-mono"
                    placeholder="目标用户 UUID"
                    :disabled="pending || unresolvedReset"
                  >
                </div>
                <Button
                  type="submit"
                  size="small"
                  variant="danger"
                  :disabled="pending || (!unresolvedReset && !resetValid)"
                  :loading="pending"
                >
                  {{ unresolvedReset ? '用原请求核对重置' : '确认重置密码' }}
                </Button>
              </div>
            </form>
          </div>
          <p
            v-else
            class="security-ok-line"
          >
            登录凭证有效，点击上方“修改密码”可轮换当前密码。
          </p>

          <section
            v-if="logoutRequested"
            class="logout-confirm-bar"
            role="dialog"
            aria-modal="true"
            aria-label="确认退出与草稿处理"
          >
            <div class="logout-copy">
              <h3>退出前处理本地草稿</h3>
              <p>未提交的代码草稿仅保留在当前浏览器会话，可根据需要保存后再退出。</p>
            </div>
            <div class="form-actions">
              <Button
                v-if="!identity.passwordChangeRequired"
                size="small"
                variant="primary"
                :disabled="pending"
                @click="saveThenLogout"
              >
                保存后退出
              </Button>
              <Button
                size="small"
                variant="danger"
                :disabled="pending"
                @click="signOut()"
              >
                丢弃账号草稿并退出
              </Button>
              <Button
                size="small"
                variant="ghost"
                :disabled="pending"
                @click="logoutRequested = false"
              >
                取消退出
              </Button>
            </div>
          </section>
        </section>
      </section>
    </div>

    <div
      v-if="temporaryPassword"
      class="surface-block credential-delivery"
      :class="{ 'is-login-width': !identity, 'is-settings-width': !!identity }"
      role="status"
    >
      <div class="cred-head">
        <h2>临时凭证只展示这一次</h2>
        <span class="font-mono">{{ createdUsername }} · {{ createdUserId }}</span>
      </div>
      <div class="admin-inline-row">
        <div class="field-group inline-field">
          <label for="temporary-password">临时密码</label>
          <input
            id="temporary-password"
            :value="temporaryPassword"
            class="font-mono"
            readonly
            autocomplete="off"
            spellcheck="false"
          >
        </div>
        <Button
          size="small"
          variant="secondary"
          @click="temporaryPassword = null"
        >
          已交付，清除显示
        </Button>
      </div>
    </div>
  </main>
</template>

<style scoped>
.account-page {
  min-height: 100%;
  min-width: 0;
}

.account-page.is-login {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: calc(100dvh - 40px);
  padding: 24px 16px;
}

.login-shell {
  width: min(380px, 100%);
  display: grid;
  gap: 16px;
  padding: 24px;
  border-radius: 12px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
}

.login-head {
  display: grid;
  gap: 8px;
}

.login-brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--oc-fg-muted);
}

.login-head h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.login-form {
  display: grid;
  gap: 14px;
}

.login-submit {
  width: 100%;
  justify-content: center;
}

.login-foot {
  padding-top: 12px;
  border-top: 1px solid var(--oc-border);
}

.status-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--oc-fg-muted);
  text-decoration: none;
}

.status-link:hover {
  color: var(--oc-fg);
}

.settings-shell {
  max-width: 768px;
  margin: 0 auto;
  padding: 32px 24px 64px;
  min-width: 0;
}

.settings-header {
  display: grid;
  gap: 12px;
  margin-bottom: 24px;
}

.header-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.settings-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.owner-pill {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.ui-underline-tabs a {
  text-decoration: none;
}

.account-body {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.surface-block {
  border-radius: 10px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  overflow: hidden;
  min-width: 0;
}

.profile-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  flex-wrap: wrap;
}

.profile-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  border: 1px solid var(--oc-border);
}

.profile-left h2 {
  margin: 0 0 2px;
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.identity-line {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
}

.role-chip {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg-muted);
}

.profile-actions,
.form-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.inner-form {
  padding: 16px;
  display: grid;
  gap: 14px;
}

.inner-form h3,
.admin-form-head h3,
.logout-copy h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.admin-form-row {
  padding: 16px;
  display: grid;
  gap: 10px;
}

.admin-form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.recent-user {
  font-size: 11.5px;
  color: var(--oc-fg-dim);
}

.admin-inline-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.inline-field {
  flex: 1;
  min-width: 0;
}

.security-ok-line {
  margin: 0;
  padding: 14px 16px;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.logout-confirm-bar {
  padding: 16px;
  border-top: 1px solid var(--oc-border);
  background: var(--oc-bg-subtle);
  display: grid;
  gap: 12px;
}

.logout-copy p {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.credential-delivery {
  padding: 16px;
  display: grid;
  gap: 12px;
  border-color: var(--oc-accent-border);
}

.credential-delivery.is-login-width {
  width: min(380px, 100%);
}

.credential-delivery.is-settings-width {
  max-width: 720px;
  margin: 0 auto 48px;
}

.cred-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.cred-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.cred-head span {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.field-group {
  display: grid;
  gap: 5px;
}

.field-group label {
  font-size: 12px;
  font-weight: 500;
  color: var(--oc-fg-muted);
}

.field-group input {
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 7px;
  background: var(--oc-bg-input);
  color: var(--oc-fg);
  border: 1px solid var(--oc-border-input);
  font-size: 13px;
}

.inline-alert {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-danger-bg);
  color: var(--oc-danger);
  font-size: 13px;
}

.inline-notice {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-accent-bg);
  color: var(--oc-fg);
  font-size: 13px;
}

.inline-status {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
}

.gate-card {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
  font-size: 13px;
}

.failure-text {
  margin: 0;
  color: var(--oc-danger);
}

@media (max-width: 640px) {
  .settings-shell {
    padding: 20px 16px 40px;
  }
  .form-grid-2 {
    grid-template-columns: 1fr;
  }
  .admin-inline-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
