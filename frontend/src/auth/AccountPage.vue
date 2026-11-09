<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, useThemeVars } from 'naive-ui';
import { RouterLink, useRouter } from 'vue-router';
import { requestSaveLogout } from '../workspace/logoutIntent';
import { validPassword, validUsername } from './api';
import { useAccount } from './useAccount';

const theme = useThemeVars();
const router = useRouter();
const { query, identity, pending, error, notice, temporaryPassword, createdUsername, createdUserId, unresolvedCreate,
  unresolvedReset, signIn, signOut, password, create, reset } = useAccount();
const username = ref('');
const loginPassword = ref('');
const currentPassword = ref('');
const newPassword = ref('');
const invitedUsername = ref('');
const changingPassword = ref(false);
const logoutRequested = ref(false);
const resetTarget = ref('');
const resetValid = computed(() => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(resetTarget.value));
const showPasswordForm = computed(() => identity.value?.passwordChangeRequired || changingPassword.value);
const loginValid = computed(() => validUsername(username.value) && validPassword(loginPassword.value));
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
  try { await signIn(username.value, loginPassword.value); } finally { loginPassword.value = ''; }
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
    :style="{ '--primary': theme.primaryColor }"
  >
    <header>
      <p class="project-name">
        CodeCraft / 工程底座
      </p>
      <h1>{{ identity ? '账号' : '受邀账号登录' }}</h1>
      <p>内部工程验证入口。AI 与完整面试流程尚未接入。</p>
      <RouterLink to="/">查看服务链路</RouterLink>
      <RouterLink
        v-if="identity && !identity.passwordChangeRequired"
        :to="{ name: 'workspace' }"
      >题目与工作区</RouterLink>
    </header>
    <section
      aria-label="账号操作"
      :aria-busy="pending"
    >
      <p
        v-if="error"
        role="alert"
        class="failure"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
      >
        {{ notice }}
      </p>
      <p
        v-if="query.isPending.value"
        role="status"
      >
        正在检查登录状态…
      </p>
      <div v-else-if="query.isError.value">
        <p
          role="alert"
          class="failure"
        >
          无法确认登录状态，请检查网络后重试。
        </p>
        <NButton
          :disabled="query.isFetching.value"
          @click="query.refetch()"
        >
          重新检查登录
        </NButton>
      </div>
      <form
        v-else-if="!identity"
        class="account-form"
        @submit.prevent="submitLogin"
      >
        <label for="login-username">账号名</label>
        <input
          id="login-username"
          v-model="username"
          autocomplete="username"
          aria-describedby="username-help"
          :disabled="pending"
        >
        <small id="username-help">3–32 位字母、数字、下划线或连字符，不区分大小写。</small>
        <label for="login-password">密码</label>
        <input
          id="login-password"
          v-model="loginPassword"
          type="password"
          autocomplete="current-password"
          aria-describedby="password-help"
          :disabled="pending"
        >
        <small id="password-help">8–128 个 Unicode 码点。空格计入密码。</small>
        <NButton
          attr-type="submit"
          type="primary"
          :disabled="!loginValid || pending"
          :loading="pending"
        >
          登录
        </NButton>
        <p>不开放自助注册。请向管理员获取临时凭证。</p>
      </form>
      <template v-else>
        <p class="account-identity">
          {{ identity.username }} <span>{{ identity.role === 'ADMIN' ? '管理员' : '受邀用户' }}</span>
        </p>
        <form
          v-if="showPasswordForm"
          class="account-form"
          @submit.prevent="submitPassword"
        >
          <h2>{{ identity.passwordChangeRequired ? '先修改临时密码' : '修改密码' }}</h2>
          <p>修改后撤销所有旧登录，需要重新登录。</p>
          <label for="current-password">当前密码</label>
          <input
            id="current-password"
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            :disabled="pending"
          >
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
          <NButton
            attr-type="submit"
            type="primary"
            :disabled="!passwordValid || pending"
            :loading="pending"
          >
            修改密码并重新登录
          </NButton>
          <NButton
            v-if="!identity.passwordChangeRequired"
            :disabled="pending"
            @click="changingPassword = false"
          >
            取消改密
          </NButton>
        </form>
        <template v-else>
          <form
            v-if="identity.role === 'ADMIN'"
            class="account-form"
            @submit.prevent="create(invitedUsername)"
          >
            <h2>创建受邀账号</h2>
            <p>只管理账号，不提供用户代码、对话或模型凭证的浏览入口。</p>
            <label for="invited-username">新账号名</label>
            <input
              id="invited-username"
              v-model="invitedUsername"
              autocomplete="off"
              :disabled="pending || unresolvedCreate"
            >
            <NButton
              attr-type="submit"
              type="primary"
              :disabled="pending || (!unresolvedCreate && !validUsername(invitedUsername))"
              :loading="pending"
            >
              {{ unresolvedCreate ? '用原请求核对并重试' : '创建账号' }}
            </NButton>
          </form>
          <p v-else>
            账号已就绪，可进入题目与工作区工程底座。
          </p>
          <form
            v-if="identity.role === 'ADMIN'"
            id="password-reset-form"
            class="account-form"
            @submit.prevent="reset(resetTarget)"
          >
            <h2>重置账号密码</h2>
            <p>撤销目标全部旧登录并强制改密，不停止演练任务。重置自己会立即退出登录。</p>
            <p v-if="createdUserId">
              最近操作的用户标识：{{ createdUserId }}
            </p>
            <label for="reset-target">目标用户标识</label>
            <input
              id="reset-target"
              v-model="resetTarget"
              autocomplete="off"
              :disabled="pending || unresolvedReset"
            >
            <NButton
              attr-type="submit"
              type="warning"
              :disabled="pending || (!unresolvedReset && !resetValid)"
              :loading="pending"
            >
              {{ unresolvedReset ? '用原请求核对重置' : '确认重置密码' }}
            </NButton>
          </form>
          <NButton
            :disabled="pending"
            @click="changingPassword = true"
          >
            修改密码
          </NButton>
        </template>
        <NButton
          class="logout-button"
          :disabled="pending"
          @click="logoutRequested = true"
        >
          退出登录
        </NButton>
        <section
          v-if="logoutRequested"
          role="dialog"
          aria-modal="true"
          aria-label="确认退出与草稿处理"
        >
          <h2>退出前处理本地草稿</h2>
          <p>保存后退出会进入工作区逐份处理账号草稿；需要显式操作权，不自动选择冲突版本。保存失败或仍有未处理备份时不会退出。</p>
          <NButton
            v-if="!identity.passwordChangeRequired"
            :disabled="pending"
            @click="saveThenLogout"
          >
            保存后退出
          </NButton>
          <NButton
            :disabled="pending"
            @click="signOut()"
          >
            丢弃账号草稿并退出
          </NButton>
          <NButton
            :disabled="pending"
            @click="logoutRequested = false"
          >
            取消退出
          </NButton>
        </section>
      </template>
      <div
        v-if="temporaryPassword"
        class="credential-delivery"
        role="status"
      >
        <h2>临时凭证只展示这一次</h2>
        <p>账号：{{ createdUsername }}</p>
        <p>用户标识：{{ createdUserId }}</p>
        <p>请先通过可信渠道交付，再关闭此区域。刷新或退出后不能找回明文。</p>
        <label for="temporary-password">临时密码</label>
        <input
          id="temporary-password"
          :value="temporaryPassword"
          readonly
          autocomplete="off"
          spellcheck="false"
        >
        <NButton @click="temporaryPassword = null">
          已交付，清除显示
        </NButton>
      </div>
    </section>
    <footer>登录只确认身份，不代表测试容器、模型或完整演练已准备完成。</footer>
  </main>
</template>

<style scoped>
.account-page { max-width: 760px; margin: 5vh auto; padding: 0 24px; }
.account-form { display: grid; gap: 12px; max-width: 480px; }
.account-form h2 { margin: 16px 0 0; font-size: 22px; }
.account-form p { margin: 0 0 8px; }
.account-form label { margin-top: 8px; font-weight: 600; }
input { width: 100%; padding: 10px 12px; border: 1px solid var(--line); border-radius: 4px; font: inherit; color: inherit; background: white; }
input:disabled { background: #edf2f6; }
small { color: var(--muted); line-height: 1.6; }
.account-identity { font-size: 22px; overflow-wrap: anywhere; }
.account-identity span { color: var(--muted); font-size: 14px; margin-left: 12px; }
.credential-delivery { border-left: 4px solid var(--primary); padding: 0 0 0 16px; margin: 24px 0; }
.credential-delivery input { margin: 8px 0 16px; }
.logout-button { margin: 24px 0 0 12px; }
@media (max-width: 480px) { .account-page { margin: 24px auto; padding: 0 16px; } }
</style>
