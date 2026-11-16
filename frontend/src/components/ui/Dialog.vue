<script setup lang="ts">
import { DialogRoot, DialogTrigger, DialogPortal, DialogOverlay, DialogContent, DialogTitle, DialogDescription, DialogClose } from 'reka-ui';
import { X } from '@lucide/vue';
import Button from './Button.vue';

defineOptions({ name: 'UiDialog' });
defineProps<{ title: string; description: string; busy?: boolean; persistent?: boolean }>();
const open = defineModel<boolean>('open', { default: false });
</script>

<template>
  <DialogRoot
    :open="open"
    @update:open="!busy && (open = $event)"
  >
    <DialogTrigger
      v-if="$slots.trigger"
      as-child
    >
      <slot name="trigger" />
    </DialogTrigger>
    <DialogPortal :force-mount="persistent || undefined">
      <DialogOverlay
        v-show="open"
        :force-mount="persistent || undefined"
        class="ui-dialog-overlay"
      />
      <DialogContent
        v-show="open"
        :force-mount="persistent || undefined"
        class="ui-dialog"
        :aria-busy="busy || undefined"
      >
        <DialogTitle class="ui-dialog-title">
          {{ title }}
        </DialogTitle>
        <DialogDescription class="ui-dialog-description">
          {{ description }}
        </DialogDescription>
        <slot />
        <DialogClose as-child>
          <Button
            class="ui-dialog-close"
            variant="ghost"
            size="icon"
            :disabled="busy"
            aria-label="关闭对话框"
          >
            <X
              :size="18"
              aria-hidden="true"
            />
          </Button>
        </DialogClose>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
