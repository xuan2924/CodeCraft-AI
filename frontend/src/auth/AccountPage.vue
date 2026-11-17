<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import Button from '../components/ui/Button.vue';
import SectionTabs from '../components/ui/SectionTabs.vue';
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

const pipelineSteps = [
  { step: '01', title: '需求澄清与基线分析', desc: '梳理题目约束与现有仓库结构' },
  { step: '02', title: '真实沙箱与 AI 结对实现', desc: '在隔离工作区编码、运行与迭代' },
  { step: '03', title: '确定性验证与四维评估', desc: '基于真实测试输出生成工程报告' },
];
</script>

<template>
  <main
    class="account-page"
    :class="{ 'login-layout': !identity }"
  >
    <header class="account-hero">
      <div
        v-if="!identity"
        class="login-brand-row"
      >
        <div class="login-brand-mark">
          <Terminal
            :size="18"
            aria-hidden="true"
          />
        </div>
        <span
          class="ui-badge"
          data-tone="accent"
        >仅受邀访问</span>
      </div>
      <h1>{{ identity ? '设置' : '受邀账号登录' }}</h1>
      <SectionTabs
        v-if="identity && !identity.passwordChangeRequired"
        label="设置切换"
        :active="routes.account.name"
        :items="[
          { name: routes.account.name, label: identity.role === 'ADMIN' ? '账号管理' : '账号设置' },
          { name: routes.status.name, label: '服务状态' },
        ]"
      />
      <p
        v-if="!identity"
        class="account-lead"
      >
        面向工程演练与评估的 AI 结对编程工作台。
      </p>
      <ol
        v-if="!identity"
        class="login-pipeline"
        aria-label="工程演练链路"
      >
        <li
          v-for="item in pipelineSteps"
          :key="item.step"
          class="pipeline-step"
        >
          <span class="pipeline-index">{{ item.step }}</span>
          <div>
            <strong>{{ item.title }}</strong>
            <span>{{ item.desc }}</span>
          </div>
        </li>
      </ol>
      <RouterLink
        v-if="!identity"
        to="/"
        class="status-link"
      >
        <Activity
          :size="14"
          aria-hidden="true"
        />
        <span>查看服务链路</span>
        <ArrowRight
          :size="13"
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
        class="panel-card session-error-card"
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
        <div class="login-form-header">
          <h2>凭证登录</h2>
          <span>由管理员分配临时账号与初始密码</span>
        </div>
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
          <small id="username-help">3–32 位字母、数字、下划线或连字符，不区分大小写</small>
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
          <small id="password-help">8–128 位字符，空格计入密码</small>
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
          不开放自助注册，请联系管理员获取临时凭证。
        </p>
      </form>
      <template v-else>
        <section
          class="panel-card profile-card"
          aria-label="个人资料"
        >
          <div class="avatar">
            <UserRound
              :size="20"
              aria-hidden="true"
            />
          </div>
          <div class="profile-meta">
            <h2>个人资料</h2>
            <p class="account-identity">
              <span>{{ identity.username }}</span>
              <span
                class="ui-pill"
                data-tone="accent"
              >{{ identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</span>
            </p>
          </div>
        </section>
        <section
          class="panel-card security-card"
          aria-label="账号安全"
        >
          <div class="security-header">
            <h2>账号安全</h2>
          </div>
          <form
            v-if="showPasswordForm"
            class="account-form security-subcard"
            @submit.prevent="submitPassword"
          >
            <h3>{{ identity.passwordChangeRequired ? '先修改临时密码' : '修改密码' }}</h3>
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
              <small id="new-password-help">8–128 位字符，空格计入密码</small>
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
              class="admin-panel-grid"
            >
              <form
                class="account-form security-subcard"
                @submit.prevent="create(invitedUsername)"
              >
                <h3>创建受邀账号</h3>
                <p class="security-description">
                  仅管理账号凭证，不提供用户代码或对话浏览入口。
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
                <h3>重置账号密码</h3>
                <p class="security-description">
                  撤销目标旧登录并强制改密；重置自己将立即退出。
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
            <h3>退出前处理本地草稿</h3>
            <p>保存后退出会进入工作区逐份处理账号草稿；保存失败或仍有未处理备份时不会退出。</p>
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
        </section>
      </template>
      <div
        v-if="temporaryPassword"
        class="panel-card credential-delivery"
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
  max-width: 720px;
  margin: 0 auto;
  padding: 28px 32px 56px;
}

.account-hero {
  margin-bottom: 22px;
}

.account-hero h1 {
  margin: 0 0 6px;
  font-size: clamp(22px, 2.5vw, 26px);
  font-weight: 650;
  letter-spacing: -0.025em;
}

.account-lead {
  margin: 0;
  color: var(--oc-fg-muted);
  font-size: 13.5px;
  line-height: 1.6;
  max-width: 62ch;
}

/* Dual-column Precision Engineering Portal (Signed-out) */
.login-layout {
  max-width: 940px;
  margin: 40px auto;
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  gap: 0;
  padding: 0;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border-strong);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-lg), var(--oc-hairline-top);
  overflow: hidden;
}

.login-layout > header {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  margin: 0;
  padding: 36px 32px;
  background: var(--oc-bg-subtle);
  border-right: 1px solid var(--oc-border);
}

.login-brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.login-brand-mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  color: var(--oc-accent);
}

.login-layout > header h1 {
  font-size: clamp(24px, 2.8vw, 28px);
  line-height: 1.2;
  margin-bottom: 8px;
}

.login-pipeline {
  list-style: none;
  margin: 24px 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.pipeline-step {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
}

.pipeline-index {
  font-family: var(--oc-font-mono);
  font-size: 11.5px;
  font-weight: 600;
  color: var(--oc-accent);
  background: var(--oc-accent-soft);
  border: 1px solid var(--oc-border-accent);
  border-radius: 5px;
  padding: 2px 6px;
  line-height: 1.4;
  flex-shrink: 0;
}

.pipeline-step div {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.pipeline-step strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--oc-fg);
}

.pipeline-step span {
  font-size: 12px;
  color: var(--oc-fg-muted);
  line-height: 1.45;
}

.status-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-top: auto;
  padding: 6px 12px;
  width: fit-content;
  border-radius: 999px;
  border: 1px solid var(--oc-border-strong);
  background: var(--oc-surface-2);
  color: var(--oc-fg-muted);
  text-decoration: none;
  font-size: 12.5px;
  font-weight: 500;
}

.status-link:hover {
  color: var(--oc-fg);
  border-color: var(--oc-border-hover);
  background: var(--oc-surface-hover);
}

.login-layout .account-body {
  padding: 36px 32px;
  align-content: center;
}

.login-form-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--oc-border);
}

.login-form-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.login-form-header span {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

/* Signed-in Dual-column Engineering Panels */
.panel-card {
  padding: 20px 22px;
  background: var(--oc-surface);
  border: 1px solid var(--oc-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--oc-shadow-xs), var(--oc-hairline-top);
}

.account-panel-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
  gap: 16px;
}

.profile-card {
  display: flex;
  align-items: center;
  gap: 16px;
}

.profile-meta {
  min-width: 0;
  flex: 1;
}

.profile-card h2,
.quick-links-card h2,
.security-card h2 {
  font-size: 14.5px;
  font-weight: 600;
  margin: 0;
}

.avatar {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border-strong);
  color: var(--oc-accent);
  flex-shrink: 0;
}

.account-identity {
  font-size: 18px;
  font-weight: 600;
  margin: 6px 0 0;
  overflow-wrap: anywhere;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.account-ready-note {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--oc-fg-muted);
}

.panel-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.panel-card-sub {
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.quick-links-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.quick-link-tile {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: var(--radius-control);
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-border);
  color: var(--oc-fg);
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  transition:
    background-color 150ms var(--oc-ease),
    border-color 150ms var(--oc-ease);
}

.quick-link-tile:hover {
  background: var(--oc-surface-hover);
  border-color: var(--oc-border-strong);
  color: var(--oc-fg);
}

.quick-link-tile svg {
  color: var(--oc-accent);
  flex-shrink: 0;
}

.security-card {
  display: grid;
  gap: 16px;
}

.security-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.security-description,
.security-note {
  color: var(--oc-fg-muted);
  font-size: 12.5px;
  line-height: 1.55;
  margin: 0;
}

.security-note {
  padding-top: 14px;
  border-top: 1px solid var(--oc-border);
}

.admin-panel-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.security-subcard {
  padding: 18px;
  border-radius: 8px;
  background: var(--oc-bg-subtle);
  border: 1px solid var(--oc-border);
}

.security-subcard h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
}

.security-actions-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.logout-dialog-panel {
  padding: 18px;
  border-radius: 8px;
  background: var(--oc-surface-2);
  border: 1px solid var(--oc-danger-border);
  box-shadow: var(--oc-shadow-md);
  display: grid;
  gap: 10px;
}

.logout-dialog-panel h3 {
  margin: 0;
  font-size: 15px;
}

.logout-dialog-panel p {
  margin: 0;
  font-size: 13px;
  color: var(--oc-fg-muted);
  line-height: 1.6;
}

.session-error-card {
  display: grid;
  gap: 12px;
  justify-items: start;
}

.session-error-card p {
  margin: 0;
  font-size: 13.5px;
}

.failure {
  color: var(--oc-danger);
}

.account-body {
  min-width: 0;
  display: grid;
  gap: 16px;
}

.account-form {
  display: grid;
  gap: 14px;
  max-width: 480px;
}

.login-form {
  max-width: none;
}

.field-group {
  display: grid;
  gap: 6px;
}

.account-form label,
.field-group label {
  font-size: 13px;
  font-weight: 550;
  color: var(--oc-fg);
}

input {
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--oc-border-input);
  border-radius: var(--radius-control);
  font: inherit;
  color: var(--oc-fg);
  background: var(--oc-bg-input);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.35);
}

small {
  color: var(--oc-fg-muted);
  font-size: 11.5px;
  line-height: 1.45;
}

.login-submit {
  width: 100%;
  min-height: 38px;
  margin-top: 2px;
}

.form-footnote {
  margin: 0;
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.banner {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-control);
  font-size: 13px;
  line-height: 1.5;
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
  font-size: 12px;
  color: var(--oc-fg-muted);
}

.credential-delivery {
  border-left: 3px solid var(--oc-accent);
  display: grid;
  gap: 10px;
}

.credential-delivery h2,
.credential-delivery p {
  margin: 0;
}

@media (max-width: 860px) {
  .login-layout {
    grid-template-columns: minmax(0, 1fr);
    margin: 20px 16px;
  }
  .login-layout > header {
    border-right: 0;
    border-bottom: 1px solid var(--oc-border);
    padding: 24px 20px;
  }
  .login-layout .account-body {
    padding: 24px 20px;
  }
  .account-panel-grid,
  .admin-panel-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .account-page:not(.login-layout) {
    padding: 20px 16px 40px;
  }
  .quick-links-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .panel-card {
    padding: 16px;
  }
}
</style>
