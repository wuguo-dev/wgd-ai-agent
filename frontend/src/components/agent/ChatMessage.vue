<script setup>
import { computed, ref } from 'vue';
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/common';
import 'highlight.js/styles/github.css';
import CitationPanel from './CitationPanel.vue';

const props = defineProps({ message: { type: Object, required: true } });
defineEmits(['retry']);
const feedback = ref('');
const markdown = new MarkdownIt({
    html: false,
    linkify: true,
    breaks: true,
    highlight(code, language) {
        if (language && hljs.getLanguage(language))
            return hljs.highlight(code, { language }).value;
        return '';
    },
});
const fence = markdown.renderer.rules.fence;
markdown.renderer.rules.fence = (...args) => {
    const language = args[0][args[1]].info.trim().split(/\s+/)[0] || '代码';
    const escapedLanguage = markdown.utils.escapeHtml(language);
    return `<div class="code-frame"><div class="code-toolbar"><span class="code-language">${escapedLanguage}</span><button type="button" class="copy-code">复制代码</button></div>${fence(...args)}</div>`;
};
const linkOpen =
    markdown.renderer.rules.link_open ||
    ((tokens, index, options, env, renderer) =>
        renderer.renderToken(tokens, index, options));
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
    tokens[index].attrSet('target', '_blank');
    tokens[index].attrSet('rel', 'noopener noreferrer');
    return linkOpen(tokens, index, options, env, renderer);
};
const html = computed(() =>
    DOMPurify.sanitize(markdown.render(props.message.content || ''), {
        ADD_ATTR: ['target'],
        FORBID_TAGS: ['img', 'iframe', 'form', 'input', 'style'],
    })
);
const status = computed(
    () =>
        ({
            generating: '正在生成…',
            failed: '生成失败',
            cancelled: '已停止',
            interrupted: '生成已中断',
        })[props.message.status]
);
async function copy(text) {
    try {
        await navigator.clipboard.writeText(text);
        feedback.value = '已复制';
    } catch {
        feedback.value = '复制失败，请手动选择文字复制';
    }
}
function copyCode(event) {
    if (event.target.matches('button.copy-code'))
        copy(
            event.target.closest('.code-frame').querySelector('code')
                ?.textContent || ''
        );
}
</script>
<template>
    <article :class="['chat-message', message.role]">
        <div class="role-label">
            {{ message.role === 'user' ? '你' : 'AI 助手' }}
        </div>
        <div v-if="message.role === 'user'" class="user-content">
            {{ message.content }}
        </div>
        <div v-else class="markdown" @click="copyCode" v-html="html" />
        <CitationPanel :citations="message.citations || []" />
        <div v-if="message.role === 'assistant'" class="message-actions">
            <span v-if="status">{{ status }}</span>
            <button
                v-if="message.content"
                type="button"
                @click="copy(message.content)"
            >
                复制回答
            </button>
            <button
                v-if="
                    ['failed', 'cancelled', 'interrupted'].includes(
                        message.status
                    )
                "
                type="button"
                @click="$emit('retry')"
            >
                重试
            </button>
            <span role="status">{{ feedback }}</span>
        </div>
    </article>
</template>
<style scoped>
.chat-message {
    margin: 0 0 28px;
    min-width: 0;
    overflow-wrap: anywhere;
}
.role-label {
    font-size: 12px;
    color: var(--color-muted);
    margin-bottom: 8px;
    font-weight: 600;
}
.user {
    margin-left: 12%;
    text-align: right;
}
.user-content {
    display: inline-block;
    text-align: left;
    background: var(--el-color-primary-light-9);
    border-radius: 18px 4px 18px 18px;
    padding: 14px 18px;
    white-space: pre-wrap;
}
.markdown {
    line-height: 1.8;
    min-width: 0;
}
.markdown :deep(p:last-child) {
    margin-bottom: 0;
}
.markdown :deep(pre) {
    overflow-x: auto;
    padding: 36px 16px 16px;
    border-radius: 12px;
    background: var(--color-background);
    font-size: 13px;
}
.markdown :deep(.code-frame) {
    position: relative;
    min-width: 0;
}
.markdown :deep(.copy-code) {
    position: absolute;
    right: 8px;
    top: 4px;
}
.markdown :deep(table) {
    display: block;
    overflow-x: auto;
    border-collapse: collapse;
}
.markdown :deep(th),
.markdown :deep(td) {
    border: 1px solid var(--color-border);
    padding: 8px 12px;
}
.message-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
    color: var(--color-muted);
    font-size: 12px;
    margin-top: 8px;
}
button,
.markdown :deep(button) {
    border: 0;
    background: transparent;
    color: var(--color-primary);
    cursor: pointer;
    min-height: 44px;
    padding: 4px 8px;
}
.assistant {
    padding: 24px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
    background: var(--color-surface);
}
.role-label {
    display: flex;
    align-items: center;
    gap: 8px;
}
.assistant .role-label::before {
    content: 'AI';
    display: grid;
    place-items: center;
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: var(--el-color-primary-light-9);
    color: var(--color-primary);
    font-size: 11px;
}
.markdown :deep(.code-toolbar) {
    display: flex;
    justify-content: space-between;
    align-items: center;
    background: var(--color-background);
    border: 1px solid var(--color-border);
    border-radius: 12px 12px 0 0;
    padding: 4px 12px;
    font-size: 12px;
}
.markdown :deep(.copy-code) {
    position: static;
}
.markdown :deep(pre) {
    margin-top: 0;
    padding: 16px;
    border: 1px solid var(--color-border);
    border-top: 0;
    border-radius: 0 0 12px 12px;
}
.markdown :deep(.code-language) {
    color: var(--color-muted);
}
.message-actions {
    border-top: 1px solid var(--color-border);
    padding-top: 8px;
    margin-top: 20px;
}
@media (max-width: 600px) {
    .assistant {
        padding: 18px 16px;
    }
    .user {
        margin-left: 8%;
    }
}
</style>
