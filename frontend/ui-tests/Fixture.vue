<script setup lang="ts">
import { ref } from 'vue';
import { useMediaQuery } from '@vueuse/core';
import AppShell from '../src/shell/AppShell.vue';
import ConnectionForm from '../src/modelaccess/ConnectionForm.vue';
import UiButton from '../src/components/ui/Button.vue';
import UiDialog from '../src/components/ui/Dialog.vue';
import TextField from '../src/components/ui/TextField.vue';
import StatePanel from '../src/components/ui/StatePanel.vue';
import ResizablePanels from '../src/components/ui/ResizablePanels.vue';

defineOptions({ name: 'UiFixture' });
const narrow = useMediaQuery('(max-width: 900px)');
const open = ref(false);
const text = ref('');
const identity = { id: 'fixture', username: '这是用于布局检查的很长受邀账号名称_without_spaces_123456789', role: 'USER' as const,
  passwordChangeRequired: false, expiresAt: '2030-01-01T00:00:00Z' };
</script>

<template>
  <AppShell
    :identity="identity"
    :pending="false"
    :failed="false"
  >
    <main class="fixture-main">
      <h1>工程演练组件检查</h1>
      <p>仅检查展示与交互，不代表任何后端功能已完成。</p>
      <UiDialog
        v-model:open="open"
        title="确认操作"
        description="本测试对话框不会写入服务端。"
      >
        <template #trigger>
          <UiButton variant="primary">
            打开确认
          </UiButton>
        </template>
        <TextField
          v-model="text"
          label="说明"
          help="输入内容用于检查键盘焦点。"
        />
        <UiButton @click="open = false">
          确认关闭
        </UiButton>
      </UiDialog>
      <div class="fixture-panels">
        <ResizablePanels
          :direction="narrow ? 'vertical' : 'horizontal'"
          first-label="题目"
          second-label="代码"
          resize-label="调整题目与代码面板"
        >
          <template #first>
            <StatePanel
              title="暂无题目"
              description="这是用于测试超长内容折行的文字_without_spaces_abcdefghijklmnopqrstuvwxyz_0123456789_abcdefghijklmnopqrstuvwxyz_0123456789"
            />
          </template>
          <template #second>
            <StatePanel
              kind="error"
              title="无法取得代码"
              description="这里是显式失败状态，不是空数据。"
            >
              <UiButton
                disabled
                aria-describedby="disabled-help"
              >
                保存
              </UiButton><p id="disabled-help">
                只读状态不能保存。
              </p>
            </StatePanel>
          </template>
        </ResizablePanels>
      </div>
      <details>
        <summary>迁移期表单控件</summary>
        <ConnectionForm
          owner="fixture"
          :connection="null"
        />
      </details>
    </main>
  </AppShell>
</template>

<style scoped>
.fixture-main { padding: 24px; }
h1 { font-size: 26px; }
p { color: var(--muted); line-height: 1.6; }
.fixture-panels { height: 560px; margin-top: 24px; border: 1px solid var(--border); border-radius: 10px; }
</style>
