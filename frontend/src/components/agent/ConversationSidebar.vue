<script setup>
import { ElButton } from 'element-plus';
import 'element-plus/theme-chalk/el-button.css';
defineProps({
    conversations: Array,
    activeId: String,
    loading: Boolean,
    hasMore: Boolean,
    busy: Boolean,
});
defineEmits(['create', 'select', 'rename', 'remove', 'more']);
</script>
<template>
    <nav class="conversation-sidebar" aria-label="历史会话">
        <div class="history-heading">
            <h2>我的对话</h2>
            <span>学习记录</span>
        </div>
        <ElButton type="primary" :disabled="busy" @click="$emit('create')"
            >＋ 新建对话</ElButton
        >
        <p class="sidebar-label">历史会话</p>
        <div class="conversation-list">
            <p v-if="!conversations.length" class="muted">
                {{ loading ? '加载中…' : '还没有会话' }}
            </p>
            <div
                v-for="item in conversations"
                :key="item.id"
                :class="['conversation', { selected: item.id === activeId }]"
            >
                <button
                    class="conversation-title"
                    :aria-current="item.id === activeId ? 'true' : undefined"
                    :title="item.title"
                    @click="$emit('select', item.id)"
                >
                    {{ item.title }}
                </button>
                <div class="conversation-tools">
                    <button
                        :aria-label="`重命名 ${item.title}`"
                        :disabled="busy"
                        @click="$emit('rename', item)"
                    >
                        重命名
                    </button>
                    <button
                        :aria-label="`删除 ${item.title}`"
                        :disabled="busy"
                        @click="$emit('remove', item)"
                    >
                        删除
                    </button>
                </div>
            </div>
            <ElButton v-if="hasMore" :loading="loading" @click="$emit('more')"
                >加载更多</ElButton
            >
        </div>
    </nav>
</template>
<style scoped>
.conversation-sidebar {
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: 20px 14px;
    min-height: 0;
}
.sidebar-label {
    color: var(--color-muted);
    font-size: 12px;
    margin: 24px 10px 10px;
}
.conversation-list {
    overflow-y: auto;
    min-height: 0;
}
.conversation {
    border-radius: 12px;
    padding: 4px 8px;
    margin-bottom: 6px;
}
.selected {
    background: var(--el-color-primary-light-9);
}
.conversation-title,
.conversation-tools button {
    border: 0;
    background: transparent;
    cursor: pointer;
    color: var(--color-muted);
    min-height: 44px;
}
.conversation-sidebar > .el-button {
    min-height: 44px;
}
.conversation-title {
    display: block;
    text-align: left;
    width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--color-text);
}
.selected .conversation-title {
    color: var(--color-primary);
    font-weight: 600;
}
.conversation-tools {
    display: flex;
    gap: 12px;
    font-size: 12px;
}
.conversation-tools button {
    font-size: inherit;
}
.history-heading {
    padding: 4px 10px 20px;
}
.history-heading h2 {
    font-size: 17px;
    margin: 0 0 4px;
}
.history-heading span {
    font-size: 12px;
    color: var(--color-muted);
}
.conversation {
    border: 1px solid transparent;
}
.selected {
    border-color: var(--el-color-primary-light-8);
}
.conversation-tools {
    justify-content: flex-end;
    gap: 8px;
}
</style>
