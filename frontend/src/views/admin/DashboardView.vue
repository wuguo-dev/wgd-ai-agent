<script setup>
import { onUnmounted, ref } from 'vue';
import { ElAlert, ElButton, ElSkeleton } from 'element-plus';
import {
    User,
    Document,
    ChatDotRound,
    Clock,
    ChatLineSquare,
    CircleCheck,
    CircleClose,
    Delete,
    Remove,
} from '@element-plus/icons-vue';
import { getStats } from '../../api/admin.js';
const statIcons = {
    studentTotal: User,
    questionTotal: Document,
    answerTotal: ChatDotRound,
    pendingQuestions: Clock,
    pendingAnswers: ChatLineSquare,
    activeStudents: CircleCheck,
    disabledStudents: CircleClose,
    deletedQuestions: Delete,
    deletedAnswers: Remove,
};
const stats = ref(null);
const loading = ref(false);
const error = ref('');
let active = true;
const primary = [
    ['studentTotal', '学生总数', '/admin/users'],
    ['questionTotal', '问题总数', '/admin/questions'],
    ['answerTotal', '回答总数', '/admin/answers'],
    [
        'pendingQuestions',
        '待审核问题',
        '/admin/questions?status=PENDING&deleted=ACTIVE',
    ],
    [
        'pendingAnswers',
        '待审核回答',
        '/admin/answers?status=PENDING&deleted=ACTIVE',
    ],
];
const secondary = [
    ['activeStudents', '启用学生', '/admin/users?status=ACTIVE&role=STUDENT'],
    [
        'disabledStudents',
        '禁用学生',
        '/admin/users?status=DISABLED&role=STUDENT',
    ],
    ['deletedQuestions', '已删除问题', '/admin/questions?deleted=DELETED'],
    ['deletedAnswers', '已删除回答', '/admin/answers?deleted=DELETED'],
];
async function load() {
    loading.value = true;
    error.value = '';
    try {
        const result = await getStats();
        if (active) stats.value = result;
    } catch (failure) {
        if (active) error.value = failure.message;
    } finally {
        if (active) loading.value = false;
    }
}
onUnmounted(() => {
    active = false;
});
load();
</script>
<template>
    <section class="community dashboard">
        <div class="page-heading">
            <div>
                <h1>控制台</h1>
                <p class="admin-description">查看社区运行情况与待处理事项。</p>
            </div>
            <ElButton :loading="loading" @click="load">刷新数据</ElButton>
        </div>
        <ElAlert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
            role="alert"
            ><ElButton text @click="load">重试</ElButton></ElAlert
        >
        <ElSkeleton
            v-if="loading"
            :rows="5"
            animated
            aria-label="正在加载统计"
        />
        <template v-else
            ><div class="primary-stats">
                <RouterLink
                    v-for="[key, label, path] in primary"
                    :key="key"
                    :to="path"
                    class="stat-card"
                    ><span class="stat-label"
                        ><component
                            :is="statIcons[key]"
                            class="stat-icon"
                            aria-hidden="true"
                        />{{ label }}</span
                    ><strong>{{ stats?.[key] ?? '—' }}</strong
                    ><small>查看记录 ↗</small></RouterLink
                >
            </div>
            <div class="secondary-stats">
                <RouterLink
                    v-for="[key, label, path] in secondary"
                    :key="key"
                    :to="path"
                    ><span class="stat-label"
                        ><component
                            :is="statIcons[key]"
                            class="stat-icon"
                            aria-hidden="true"
                        />{{ label }}</span
                    ><strong>{{ stats?.[key] ?? '—' }}</strong></RouterLink
                >
            </div></template
        >
        <div class="dashboard-bottom">
            <section class="panel">
                <h2>待处理事项</h2>
                <RouterLink
                    class="todo-link"
                    to="/admin/questions?status=PENDING&deleted=ACTIVE"
                    ><span>审核学生提问</span
                    ><span
                        >{{ stats?.pendingQuestions ?? '—' }} →</span
                    ></RouterLink
                ><RouterLink
                    class="todo-link"
                    to="/admin/answers?status=PENDING&deleted=ACTIVE"
                    ><span>审核学生回答</span
                    ><span
                        >{{ stats?.pendingAnswers ?? '—' }} →</span
                    ></RouterLink
                >
            </section>
            <section class="panel">
                <h2>管理入口</h2>
                <div class="quick-links">
                    <RouterLink to="/admin/users">用户与账号状态 →</RouterLink
                    ><RouterLink to="/admin/categories"
                        >分类与发布范围 →</RouterLink
                    ><RouterLink to="/admin/operation-logs"
                        >查看操作审计 →</RouterLink
                    >
                </div>
                <p class="muted stats-note">
                    内容总数包含已删除记录。待审核统计排除已删除内容与已删除问题下的回答。
                </p>
            </section>
        </div>
    </section>
</template>
<style scoped>
.primary-stats {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 16px;
}
.stat-card {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-medium);
    padding: 24px 20px;
    color: var(--color-muted);
    box-shadow: var(--shadow-subtle);
}
.stat-card strong {
    display: block;
    font-size: 32px;
    line-height: 1.3;
    color: var(--color-text);
    margin: 16px 0;
}
.stat-card small {
    color: var(--color-primary);
}
.secondary-stats {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
    margin: 24px 0;
}
.secondary-stats a {
    padding: 18px 20px;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 12px;
    display: flex;
    justify-content: space-between;
    color: var(--color-muted);
    gap: 16px;
}
.secondary-stats strong {
    color: var(--color-text);
}
.dashboard-bottom {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 24px;
}
.dashboard-bottom .panel {
    margin: 0;
}
.todo-link {
    display: flex;
    justify-content: space-between;
    padding: 18px 0;
    border-bottom: 1px solid var(--color-border);
}
.quick-links {
    display: grid;
    gap: 16px;
}
.stats-note {
    margin: 24px 0 0;
    font-size: 13px;
}
@media (max-width: 1200px) {
    .primary-stats {
        grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .secondary-stats {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}
@media (max-width: 600px) {
    .primary-stats {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .stat-card {
        padding: 20px 16px;
    }
    .stat-card strong {
        font-size: 28px;
    }
    .secondary-stats,
    .dashboard-bottom {
        grid-template-columns: minmax(0, 1fr);
    }
}
.stat-label {
    display: flex;
    align-items: center;
    gap: 8px;
}
.stat-icon {
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    color: var(--color-primary);
}
</style>
