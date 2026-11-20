<script setup>
import { ref } from 'vue';
import { ElInput } from 'element-plus';
import 'element-plus/theme-chalk/el-input.css';

const model = defineModel({ type: String, default: '' });
defineProps({
    id: { type: String, required: true },
    autocomplete: { type: String, default: 'current-password' },
    placeholder: { type: String, default: '请输入密码' },
    disabled: Boolean,
});
const visible = ref(false);
</script>

<template>
    <ElInput
        :id="id"
        v-model="model"
        :name="id"
        :type="visible ? 'text' : 'password'"
        :autocomplete="autocomplete"
        :placeholder="placeholder"
        :maxlength="72"
        :disabled="disabled"
    >
        <template #suffix>
            <button
                class="password-toggle"
                type="button"
                :aria-controls="id"
                :aria-label="visible ? '隐藏密码' : '显示密码'"
                :aria-pressed="visible"
                :disabled="disabled"
                @click="visible = !visible"
            >{{ visible ? '隐藏' : '显示' }}</button>
        </template>
    </ElInput>
</template>

<style scoped>
.password-toggle { border: 0; background: transparent; color: var(--color-muted); cursor: pointer; min-width: 44px; min-height: 44px; padding: 4px; font-size: 12px; border-radius: 6px; }
.password-toggle:hover { color: var(--color-primary); }
</style>
