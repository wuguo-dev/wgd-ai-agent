<script setup>
import { ref, watch } from 'vue';
import {
    isNavigationFailure,
    NavigationFailureType,
    useRoute,
    useRouter,
} from 'vue-router';
import { ElAlert, ElButton, ElInput } from 'element-plus';
import { getCategories, getQuestions } from '../../api/community.js';
import { useCommunityPage } from '../../composables/useCommunityPage.js';
import QuestionList from '../../components/community/QuestionList.vue';
import PageFeedback from '../../components/community/PageFeedback.vue';
const route = useRoute();
const router = useRouter();
const categories = ref([]);
const categoryError = ref('');
const keyword = ref(String(route.query.keyword || ''));
const list = useCommunityPage((params) =>
    getQuestions({
        ...params,
        keyword: route.query.keyword || undefined,
        categoryId: route.query.categoryId || undefined,
    })
);
async function loadCategories() {
    categoryError.value = '';
    try {
        categories.value = await getCategories();
    } catch (error) {
        categoryError.value = error.message;
    }
}
async function filter(categoryId = route.query.categoryId) {
    const failure = await router.push({
        path: '/community',
        query: {
            keyword: keyword.value.trim() || undefined,
            categoryId: categoryId || undefined,
        },
    });
    if (isNavigationFailure(failure, NavigationFailureType.duplicated)) {
        list.page.value = 1;
        await list.load();
    }
}
watch(
    () => route.query,
    () => {
        keyword.value = String(route.query.keyword || '');
        list.page.value = 1;
        list.load();
    },
    { immediate: true }
);
loadCategories();
</script>
<template>
    <section class="community community-home">
        <div class="page-heading">
            <div>
                <h1>学生社区</h1>
                <p class="muted">分享问题，交流思考。</p>
            </div>
            <ElButton
                type="primary"
                @click="router.push('/community/questions/new')"
                >发布问题</ElButton
            >
        </div>
        <div class="community-home-grid">
            <div class="community-feed">
                <form class="toolbar" role="search" @submit.prevent="filter()">
                    <ElInput
                        v-model="keyword"
                        maxlength="200"
                        placeholder="搜索问题标题或正文"
                        aria-label="搜索问题"
                        clearable
                    /><ElButton type="primary" native-type="submit"
                        >搜索</ElButton
                    >
                </form>
                <ElAlert
                    v-if="categoryError"
                    :title="categoryError"
                    type="error"
                    :closable="false"
                    ><ElButton text @click="loadCategories"
                        >重试分类</ElButton
                    ></ElAlert
                >
                <nav class="category-filter" aria-label="问题分类">
                    <ElButton
                        :type="!route.query.categoryId ? 'primary' : 'default'"
                        :aria-pressed="!route.query.categoryId"
                        @click="filter(null)"
                        >全部</ElButton
                    >
                    <ElButton
                        v-for="category in categories"
                        :key="category.id"
                        :type="
                            String(category.id) === route.query.categoryId
                                ? 'primary'
                                : 'default'
                        "
                        :aria-pressed="
                            String(category.id) === route.query.categoryId
                        "
                        @click="filter(category.id)"
                        >{{ category.name
                        }}{{
                            category.status === 'DISABLED' ? '（已停用）' : ''
                        }}</ElButton
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
                        ><QuestionList :items="list.items.value"
                    /></PageFeedback>
                </div>
            </div>
            <aside class="community-side">
                <section class="side-card">
                    <h2>发布学习问题</h2>
                    <p>
                        问题提交后进入审核，可在个人中心查看状态。审核通过后向社区展示。
                    </p>
                    <ElButton
                        type="primary"
                        @click="router.push('/community/questions/new')"
                        >发起一个问题</ElButton
                    >
                </section>
                <section class="side-card">
                    <h2>我的社区</h2>
                    <RouterLink to="/profile?tab=questions"
                        >我的提问 →</RouterLink
                    ><RouterLink to="/profile?tab=answers"
                        >我的回答 →</RouterLink
                    ><RouterLink to="/profile?tab=favorites"
                        >我的收藏 →</RouterLink
                    >
                </section>
            </aside>
        </div>
    </section>
</template>

<style scoped>
.community-home-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 280px;
    gap: 28px;
}
.community-feed {
    min-width: 0;
}
.community-side {
    display: grid;
    align-content: start;
    gap: 20px;
}
.side-card {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-card);
    padding: 24px;
}
.side-card h2 {
    font-size: 18px;
    line-height: 1.6;
}
.side-card p {
    font-size: 14px;
    color: var(--color-muted);
}
.side-card a {
    display: block;
    padding: 12px 0;
    border-bottom: 1px solid var(--color-border);
}
.side-card .el-button {
    width: 100%;
}
@media (max-width: 900px) {
    .community-home-grid {
        grid-template-columns: minmax(0, 1fr) 240px;
        gap: 20px;
    }
}
@media (max-width: 768px) {
    .community-home-grid {
        grid-template-columns: minmax(0, 1fr);
    }
    .community-side {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .side-card {
        padding: 20px;
    }
}
@media (max-width: 500px) {
    .community-side {
        grid-template-columns: minmax(0, 1fr);
    }
}
</style>
