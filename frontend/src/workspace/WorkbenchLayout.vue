<script setup lang="ts">
import { ref } from 'vue';
import { useMediaQuery } from '@vueuse/core';
import Button from '../components/ui/Button.vue';
import ResizablePanels from '../components/ui/ResizablePanels.vue';
const compact = useMediaQuery('(max-width: 1100px)');
const focused = ref(false);
</script>
<template>
  <div
    class="workbench"
    :class="{ compact }"
  >
    <div class="workbench-toolbar">
      <h2>实现工作台</h2><Button
        variant="secondary"
        :aria-pressed="focused"
        @click="focused = !focused"
      >
        {{ focused ? '退出专注，显示全部面板' : '专注实现，收起题目' }}
      </Button>
    </div>
    <p>题目与文件、编辑与验证、AI 协作相互独立。专注模式不停止 AI 任务，不暂停演练；暂停状态始终显示，AI 停止与审批操作不会被专注模式隐藏。</p>
    <div class="workbench-viewport">
      <ResizablePanels
        first-label="题目与文件面板"
        second-label="实现与协作面板"
        resize-label="调整题目面板宽度"
        :first-size="20"
        :min-size="12"
        :direction="compact ? 'vertical' : 'horizontal'"
        :first-hidden="focused"
      >
        <template #first>
          <div class="pane files">
            <slot name="files" />
          </div>
        </template>
        <template #second>
          <ResizablePanels
            first-label="代码与验证面板"
            second-label="AI 协作面板"
            resize-label="调整编辑器与协作面板"
            :first-size="60"
            :min-size="25"
            :direction="compact ? 'vertical' : 'horizontal'"
          >
            <template #first>
              <div class="pane editor">
                <slot name="editor" /><div class="tools">
                  <h2>验证、命令与修改历史</h2><slot name="tools" />
                </div>
              </div>
            </template>
            <template #second>
              <div class="pane assistant">
                <slot name="assistant" />
              </div>
            </template>
          </ResizablePanels>
        </template>
      </ResizablePanels>
    </div>
  </div>
</template>
<style scoped>
.workbench { margin:24px 0; min-width:0; } .workbench-toolbar { display:flex; gap:16px; flex-wrap:wrap; align-items:center; justify-content:space-between; } .workbench > p { color:var(--muted); font-size:13px; line-height:1.6; } .pane { min-width:0; padding:12px; height:100%; overflow:auto; overflow-wrap:anywhere; } .files,.assistant { background:var(--surface); } .workbench-viewport { height:calc(100dvh - 220px); min-height:700px; border:1px solid var(--border); border-radius:var(--radius-panel); } .tools { border-top:1px solid var(--border); margin-top:24px; } .tools h2 { font-size:16px; } .compact .workbench-viewport { height:1800px; min-height:1000px; } .compact .pane { min-height:120px; }
</style>
