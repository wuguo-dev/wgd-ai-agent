<script setup>
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { ElAlert, ElButton, ElDrawer, ElDialog, ElInput } from 'element-plus';
import 'element-plus/theme-chalk/el-alert.css';
import 'element-plus/theme-chalk/el-button.css';
import 'element-plus/es/components/drawer/style/css.mjs';
import 'element-plus/theme-chalk/el-dialog.css';
import 'element-plus/theme-chalk/el-input.css';
import { useAgentStore } from '../../stores/agent.js';
import ConversationSidebar from '../../components/agent/ConversationSidebar.vue';
import ChatMessage from '../../components/agent/ChatMessage.vue';

const agent = useAgentStore();
const draft = ref('');
const drawer = ref(false);
const scrollArea = ref(null);
const input = ref(null);
const follow = ref(true);
const operation = ref(false);
const dialog = ref(null);
const title = ref('');
const deleting = ref(false);
const composing = ref(false);
const generationStatus = ref('问答已就绪');
const sidebarEvents = {
    create,
    select,
    rename: (item) => openDialog(item, false),
    remove: (item) => openDialog(item, true),
    more: () => agent.loadConversations(true),
};

async function create() {
    if (operation.value) return;
    operation.value = true;
    await agent.create();
    operation.value = false;
    drawer.value = false;
    await nextTick();
    input.value?.focus();
}
async function select(id) {
    drawer.value = false;
    await agent.select(id);
    follow.value = true;
    await bottom();
}
function openDialog(item, remove) {
    if (operation.value) return;
    dialog.value = item;
    title.value = item.title;
    deleting.value = remove;
}
function closeDialog(done) {
    if (!operation.value) done();
}
async function confirm() {
    if (
        operation.value ||
        !dialog.value ||
        (!deleting.value && !title.value.trim())
    )
        return;
    const item = dialog.value;
    const remove = deleting.value;
    const nextTitle = title.value.trim();
    operation.value = true;
    try {
        const succeeded = remove
            ? await agent.remove(item.id)
            : await agent.rename(item.id, nextTitle);
        if (succeeded && dialog.value === item) dialog.value = null;
    } finally {
        operation.value = false;
    }
}
async function send() {
    if (
        agent.sending ||
        agent.loading ||
        operation.value ||
        !draft.value.trim()
    )
        return;
    if (!agent.activeId) {
        await create();
        if (!agent.activeId) return;
    }
    const text = draft.value.trim();
    draft.value = '';
    follow.value = true;
    await agent.send(text);
}
function keydown(event) {
    if (
        event.key === 'Enter' &&
        !event.shiftKey &&
        !event.isComposing &&
        !composing.value
    ) {
        event.preventDefault();
        void send();
    }
}
async function retry(index) {
    if (agent.sending || agent.loading) return;
    const question = agent.messages
        .slice(0, index)
        .reverse()
        .find((item) => item.role === 'user');
    if (question) {
        draft.value = question.content;
        if (agent.messages[index].unsaved) agent.messages.splice(index - 1, 2);
        await send();
    }
}
function onScroll() {
    const el = scrollArea.value;
    follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100;
}
async function bottom() {
    await nextTick();
    const el = scrollArea.value;
    if (el) el.scrollTop = el.scrollHeight;
}
watch(
    () => agent.messages.at(-1)?.content,
    () => {
        if (follow.value) void bottom();
    }
);
watch(
    () => agent.sending,
    (sending) => {
        generationStatus.value = sending ? '正在生成回答' : '回答生成已结束';
    }
);
onMounted(async () => {
    await agent.loadConversations();
    if (agent.conversations.length) await select(agent.conversations[0].id);
});
onUnmounted(() => {
    void agent.stop();
});
</script>
<template>
    <section class="agent-workspace" aria-label="AI 智能问答">
        <aside class="desktop-sidebar">
            <ConversationSidebar
                :conversations="agent.conversations"
                :active-id="agent.activeId"
                :loading="agent.listing"
                :has-more="agent.hasMore"
                :busy="operation || agent.sending"
                v-on="sidebarEvents"
            />
        </aside>
        <ElDrawer
            v-model="drawer"
            title="历史会话"
            direction="ltr"
            size="min(320px, 88vw)"
        >
            <ConversationSidebar
                :conversations="agent.conversations"
                :active-id="agent.activeId"
                :loading="agent.listing"
                :has-more="agent.hasMore"
                :busy="operation || agent.sending"
                v-on="sidebarEvents"
            />
        </ElDrawer>
        <div class="chat-panel">
            <header class="chat-header">
                <ElButton class="mobile-history" @click="drawer = true"
                    >历史会话</ElButton
                >
                <div class="chat-heading">
                    <h1 :title="agent.active?.title">
                        {{ agent.active?.title || 'AI 智能问答' }}
                    </h1>
                    <span aria-hidden="true">{{
                        agent.sending
                            ? '正在生成回答…'
                            : '学习探索，从一个好问题开始'
                    }}</span>
                </div>
                <ElButton :disabled="operation" @click="create"
                    >新建对话</ElButton
                >
            </header>
            <p
                class="sr-only"
                role="status"
                aria-live="polite"
                aria-atomic="true"
            >
                {{ generationStatus }}
            </p>
            <ElAlert
                v-if="agent.error"
                :title="agent.error"
                type="error"
                :closable="false"
                show-icon
                role="alert"
            />
            <div
                ref="scrollArea"
                class="message-scroll"
                role="region"
                aria-label="会话消息"
                tabindex="0"
                @scroll="onScroll"
            >
                <div class="message-content">
                    <ElButton
                        v-if="agent.hasOlder"
                        :loading="agent.loading"
                        :disabled="agent.sending"
                        @click="agent.older"
                        >加载更早消息</ElButton
                    >
                    <p
                        v-if="agent.loading && !agent.messages.length"
                        role="status"
                    >
                        正在加载历史消息…
                    </p>
                    <div v-else-if="!agent.messages.length" class="chat-empty">
                        <p class="empty-eyebrow">AI 学习助手</p>
                        <h2>今天，想探索什么？</h2>
                        <p>
                            从一个问题开始，梳理知识、拆解思路。<br />选择下方方向，编辑后发送。
                        </p>
                        <div class="suggestions">
                            <button
                                @click="
                                    draft = '请帮我梳理一个知识点的学习思路';
                                    input?.focus();
                                "
                            >
                                <strong>梳理学习思路</strong
                                ><span
                                    >从概念到方法，建立清晰的知识框架 ↗</span
                                ></button
                            ><button
                                @click="
                                    draft = '如何把复杂问题拆解成几个步骤？';
                                    input?.focus();
                                "
                            >
                                <strong>拆解复杂问题</strong
                                ><span>把复杂任务变成可执行的学习步骤 ↗</span>
                            </button>
                        </div>
                    </div>
                    <ChatMessage
                        v-for="(message, index) in agent.messages"
                        :key="message.id"
                        :message="message"
                        @retry="retry(index)"
                    />
                </div>
            </div>
            <button
                v-if="!follow"
                class="to-bottom"
                @click="
                    follow = true;
                    bottom();
                "
            >
                回到底部 ↓
            </button>
            <form class="composer" @submit.prevent="send">
                <div class="composer-input">
                    <label for="agent-question">输入问题</label>
                    <ElInput
                        id="agent-question"
                        ref="input"
                        v-model="draft"
                        type="textarea"
                        :autosize="{ minRows: 2, maxRows: 6 }"
                        maxlength="2000"
                        show-word-limit
                        placeholder="输入你的问题…"
                        @keydown="keydown"
                        @compositionstart="composing = true"
                        @compositionend="composing = false"
                    />
                    <div class="composer-actions">
                        <small>Enter 发送 · Shift+Enter 换行</small
                        ><ElButton v-if="agent.sending" @click="agent.stop"
                            >停止生成</ElButton
                        ><ElButton
                            v-else
                            native-type="submit"
                            type="primary"
                            :loading="operation"
                            :disabled="
                                !draft.trim() || agent.loading || operation
                            "
                            >发送问题 ↑</ElButton
                        >
                    </div>
                    <p class="disclaimer">
                        AI 回答仅供参考，请结合引用资料核实。
                    </p>
                </div>
            </form>
        </div>
        <ElDialog
            :model-value="!!dialog"
            :title="deleting ? '删除会话' : '重命名会话'"
            width="min(440px, 92vw)"
            :show-close="!operation"
            :close-on-click-modal="!operation"
            :close-on-press-escape="!operation"
            :before-close="closeDialog"
            @close="dialog = null"
        >
            <p v-if="deleting">删除“{{ dialog?.title }}”及其所有消息？</p>
            <ElInput
                v-else
                v-model="title"
                :disabled="operation"
                aria-label="会话标题"
                maxlength="100"
                show-word-limit
                @keydown.enter.prevent="confirm"
            />
            <template #footer
                ><ElButton :disabled="operation" @click="dialog = null"
                    >取消</ElButton
                ><ElButton
                    :type="deleting ? 'danger' : 'primary'"
                    :loading="operation"
                    :disabled="!deleting && !title.trim()"
                    @click="confirm"
                    >{{ deleting ? '删除' : '保存' }}</ElButton
                ></template
            >
        </ElDialog>
    </section>
</template>
<style scoped>
.agent-workspace {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr);
    height: 100%;
    min-height: 0;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
    overflow: hidden;
}
.desktop-sidebar {
    background: var(--color-surface);
    border-right: 1px solid var(--color-border);
    min-height: 0;
}
.chat-panel {
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    position: relative;
}
.chat-header {
    display: flex;
    align-items: center;
    gap: 16px;
    border-bottom: 1px solid var(--color-border);
    padding: 18px 24px;
}
.chat-heading {
    flex: 1;
    min-width: 0;
}
h1 {
    font-size: 18px;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.chat-heading span {
    color: var(--color-muted);
    font-size: 12px;
}
.mobile-history {
    display: none;
}
.sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
    border: 0;
}
.message-scroll {
    overflow-y: auto;
    flex: 1;
    min-height: 0;
}
.message-content {
    max-width: 860px;
    padding: 28px 32px;
    margin: auto;
}
.chat-empty {
    text-align: center;
    margin: clamp(20px, 8vh, 100px) 0 32px;
}
.empty-mark {
    display: inline-grid;
    place-items: center;
    width: 56px;
    height: 56px;
    border-radius: 18px;
    background: var(--el-color-primary-light-9);
    color: var(--color-primary);
    font-size: 28px;
    margin-bottom: 20px;
}
h2 {
    font-size: 26px;
    margin-bottom: 12px;
}
.chat-empty p {
    color: var(--color-muted);
}
.suggestions {
    display: flex;
    gap: 12px;
    justify-content: center;
    flex-wrap: wrap;
}
.suggestions button {
    border: 1px solid var(--color-border);
    border-radius: 12px;
    background: white;
    padding: 12px 16px;
    min-height: 44px;
    color: var(--color-text);
    cursor: pointer;
}
.composer {
    width: 100%;
    max-width: 860px;
    margin: auto;
    padding: 16px 32px max(10px, env(safe-area-inset-bottom));
}
.composer label {
    display: block;
    font-size: 12px;
    color: var(--color-muted);
    margin-bottom: 6px;
}
.composer-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    margin-top: 10px;
}
.composer small {
    color: var(--color-muted);
    font-size: 12px;
}
.disclaimer {
    text-align: center;
    color: var(--color-muted);
    font-size: 11px;
    margin: 10px 0 0;
}
.to-bottom {
    align-self: center;
    border: 1px solid var(--color-border);
    background: white;
    padding: 8px 16px;
    border-radius: 20px;
    min-height: 44px;
    color: var(--color-primary);
    cursor: pointer;
}
@media (max-width: 1023px) {
    .agent-workspace {
        grid-template-columns: minmax(0, 1fr);
    }
    .desktop-sidebar {
        display: none;
    }
    .mobile-history {
        display: inline-flex;
    }
}
@media (max-width: 600px) {
    .agent-workspace {
        border-radius: 0;
        border-left: 0;
        border-right: 0;
    }
    .chat-header {
        padding: 12px;
        gap: 8px;
        flex-wrap: wrap;
    }
    .chat-heading {
        min-width: 130px;
    }
    .chat-heading span {
        display: none;
    }
    .message-content {
        padding: 24px 16px;
    }
    .composer {
        padding: 12px 12px max(8px, env(safe-area-inset-bottom));
    }
    h2 {
        font-size: 23px;
    }
    .chat-empty {
        margin-top: 24px;
    }
}
.empty-eyebrow {
    color: var(--color-primary);
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0.08em;
}
.suggestions {
    margin-top: 28px;
}
.suggestions button {
    width: 250px;
    text-align: left;
    padding: 20px;
    border-radius: var(--radius-medium);
    box-shadow: var(--shadow-subtle);
}
.suggestions strong {
    display: block;
    font-size: 14px;
    margin-bottom: 8px;
}
.suggestions span {
    display: block;
    font-size: 12px;
    color: var(--color-muted);
    line-height: 1.8;
}
.composer-input {
    padding: 16px 18px 12px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
    background: var(--color-surface);
    box-shadow: var(--shadow-subtle);
}
.composer-input:focus-within {
    border-color: var(--color-primary);
}
.composer-input :deep(.el-textarea__inner) {
    box-shadow: none;
    padding: 8px 0;
    background: transparent;
}
.composer-input :deep(.el-input__count) {
    right: 0;
}
.composer-actions {
    border-top: 1px solid var(--color-border);
    padding-top: 12px;
}
.message-scroll {
    background: linear-gradient(
        var(--color-background),
        var(--color-surface) 180px
    );
}
@media (max-width: 600px) {
    .suggestions button {
        width: 100%;
    }
    .composer-input {
        padding: 12px;
    }
    .chat-header > .el-button:last-child {
        padding: 8px 12px;
    }
    .composer-actions small {
        font-size: 11px;
    }
}
</style>
