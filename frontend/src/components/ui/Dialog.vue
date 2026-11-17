<script setup lang="ts">
import { DialogRoot, DialogTrigger, DialogPortal, DialogOverlay, DialogContent, DialogTitle, DialogDescription, DialogClose } from 'reka-ui';
import { X } from '@lucide/vue';
import Button from './Button.vue';

defineOptions({ name: 'UiDialog' });
withDefaults(defineProps<{
  title: string;
  description?: string;
  busy?: boolean;
  persistent?: boolean;
  variant?: 'modal' | 'drawer';
  width?: 'default' | 'wide';
}>(), {
  description: '',
  variant: 'modal',
  width: 'default',
});
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
        :class="{
          'ui-dialog-drawer': variant === 'drawer',
          'ui-dialog-wide': variant === 'modal' && width === 'wide',
        }"
        :aria-busy="busy || undefined"
      >
        <template v-if="variant === 'drawer'">
          <div class="ui-drawer-header">
            <div class="min-w-0 flex-1">
              <DialogTitle class="ui-dialog-title">
                {{ title }}
              </DialogTitle>
              <DialogDescription
                v-if="description"
                class="ui-dialog-description"
              >
                {{ description }}
              </DialogDescription>
              <slot name="header-extra" />
            </div>
          </div>
          <div class="ui-drawer-body">
            <slot />
          </div>
          <div
            v-if="$slots.footer"
            class="ui-drawer-footer"
          >
            <slot name="footer" />
          </div>
        </template>
        <template v-else>
          <DialogTitle class="ui-dialog-title">
            {{ title }}
          </DialogTitle>
          <DialogDescription
            v-if="description"
            class="ui-dialog-description"
          >
            {{ description }}
          </DialogDescription>
          <div class="ui-dialog-body">
            <slot />
          </div>
          <div
            v-if="$slots.footer"
            class="ui-dialog-footer"
          >
            <slot name="footer" />
          </div>
        </template>
        <DialogClose as-child>
          <Button
            class="ui-dialog-close"
            variant="ghost"
            size="icon"
            :disabled="busy"
            aria-label="关闭对话框"
          >
            <X
              :size="16"
              aria-hidden="true"
            />
          </Button>
        </DialogClose>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
