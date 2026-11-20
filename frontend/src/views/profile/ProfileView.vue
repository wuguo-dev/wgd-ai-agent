<script setup>
import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton, ElTag } from 'element-plus';
import { useAuthStore } from '../../stores/auth.js';
import { getMyRecords } from '../../api/community.js';
import {
    formatTime,
    statusLabel,
    useCommunityPage,
} from '../../composables/useCommunityPage.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
import QuestionList from '../../components/community/QuestionList.vue';
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const tabs = {
    questions: '我的提问',
    answers: '我的回答',
    favorites: '我的收藏',
};
const tab = computed(() =>
    Object.hasOwn(tabs, route.query.tab) ? route.query.tab : 'questions'
);
const list = useCommunityPage((params) => getMyRecords(tab.value, params));
watch(
    tab,
    () => {
        list.page.value = 1;
        list.load();
    },
    { immediate: true }
);
</script>
<template>
    <section class="community profile-page">
        <div class="page-heading">
            <div>
                <h1>个人中心</h1>
                <p class="muted">
                    {{ auth.user?.username }} ·
                    {{ auth.user?.role === 'ADMIN' ? '管理员' : '学生' }}
                </p>
            </div>
        </div>
        <div class="profile-grid">
            <aside class="profile-card">
                <span class="profile-avatar">{{
                    auth.user?.username?.slice(0, 1).toUpperCase()
                }}</span>
                <h2>{{ auth.user?.username }}</h2>
                <p class="muted">
                    {{ auth.user?.role === 'ADMIN' ? '管理员' : '学生' }}
                </p>
                <RouterLink to="/community/questions/new"
                    >发布新的问题 →</RouterLink
                >
                <p class="profile-help">
                    在这里查看自己的提问、回答与收藏。待审核和驳回记录仅向本人及管理员开放。
                </p>
            </aside>
            <div class="profile-records">
                <nav class="category-filter" aria-label="个人记录">
                    <ElButton
                        v-for="(label, key) in tabs"
                        :key="key"
                        :type="tab === key ? 'primary' : 'default'"
                        :aria-pressed="tab === key"
                        @click="
                            router.push({
                                path: '/profile',
                                query: { tab: key },
                            })
                        "
                        >{{ label }}</ElButton
                    >
                </nav>
                <div class="panel">
                    <PageFeedback
                        :loading="list.loading.value"
                        :error="list.error.value"
                        :empty="!list.items.value.length"
                        :total="list.total.value"
                        :page="list.page.value"
                        @retry="list.load"
                        @page="
                            list.page.value = $event;
                            list.load();
                        "
                    >
                        <template v-if="tab === 'answers'"
                            ><article
                                v-for="item in list.items.value"
                                :key="item.id"
                                class="question-row"
                            >
                                <h2>
                                    <RouterLink
                                        :to="`/community/questions/${item.questionId}`"
                                        >{{ item.questionTitle }}</RouterLink
                                    >
                                </h2>
                                <div class="question-meta">
                                    <time>{{
                                        formatTime(item.createdAt)
                                    }}</time>
                                </div>
                                <p class="text-content">{{ item.content }}</p>
                                <ElTag
                                    :type="
                                        item.status === 'REJECTED'
                                            ? 'danger'
                                            : item.status === 'APPROVED'
                                              ? 'success'
                                              : 'warning'
                                    "
                                    >{{ statusLabel[item.status] }}</ElTag
                                >
                                <p
                                    v-if="item.rejectReason"
                                    class="reject-reason"
                                >
                                    审核原因 · {{ item.rejectReason }}
                                </p>
                            </article></template
                        >
                        <QuestionList
                            v-else
                            :items="list.items.value"
                            :show-status="tab === 'questions'"
                        />
                    </PageFeedback>
                </div>
            </div>
        </div>
    </section>
</template>

<style scoped>
.profile-grid {
    display: grid;
    grid-template-columns: 250px minmax(0, 1fr);
    gap: 28px;
    align-items: start;
}
.profile-card {
    padding: 28px 24px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
    background: var(--color-surface);
    overflow-wrap: anywhere;
}
.profile-avatar {
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    border-radius: 20px;
    background: var(--el-color-primary-light-9);
    color: var(--color-primary);
    font-size: 28px;
    margin-bottom: 20px;
}
.profile-help {
    font-size: 13px;
    color: var(--color-muted);
    margin: 24px 0 0;
    border-top: 1px solid var(--color-border);
    padding-top: 20px;
}
.profile-records {
    min-width: 0;
}
@media (max-width: 768px) {
    .profile-grid {
        grid-template-columns: minmax(0, 1fr);
    }
    .profile-card {
        padding: 24px;
    }
    .profile-avatar {
        float: left;
        margin: 0 20px 16px 0;
    }
    .profile-help {
        clear: both;
    }
}
</style>
