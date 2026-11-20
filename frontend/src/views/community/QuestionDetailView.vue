<script setup>
import { onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import {
    ElAlert,
    ElButton,
    ElForm,
    ElFormItem,
    ElInput,
    ElTag,
} from 'element-plus';
import {
    getQuestion,
    getAnswers,
    createAnswer,
    setFavorite,
} from '../../api/community.js';
import {
    formatTime,
    statusLabel,
    useCommunityPage,
} from '../../composables/useCommunityPage.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
const route = useRoute();
const question = ref(null);
const loading = ref(false);
const error = ref('');
const actionError = ref('');
const notice = ref('');
const favoriteBusy = ref(false);
const answerBusy = ref(false);
const answer = ref('');
const form = ref();
let generation = 0;
const answers = useCommunityPage((params) =>
    getAnswers(route.params.id, params)
);
async function load() {
    const current = ++generation;
    loading.value = true;
    question.value = null;
    error.value = '';
    try {
        const result = await getQuestion(route.params.id);
        if (current === generation) question.value = result;
    } catch (failure) {
        if (current === generation) error.value = failure.message;
    } finally {
        if (current === generation) loading.value = false;
    }
}
async function favorite() {
    if (favoriteBusy.value) return;
    favoriteBusy.value = true;
    actionError.value = '';
    const current = question.value;
    const desired = !current.favorited;
    try {
        await setFavorite(current.id, desired);
        if (question.value?.id === current.id)
            question.value.favorited = desired;
    } catch (failure) {
        actionError.value = failure.message;
    } finally {
        favoriteBusy.value = false;
    }
}
async function submitAnswer() {
    if (answerBusy.value) return;
    answerBusy.value = true;
    actionError.value = '';
    notice.value = '';
    const id = question.value.id;
    try {
        if (!(await form.value.validate().catch(() => false))) return;
        await createAnswer(id, answer.value.trim());
        if (String(id) === String(route.params.id)) {
            answer.value = '';
            notice.value =
                '回答已提交，等待管理员审核。可在个人中心查看审核状态。';
        }
    } catch (failure) {
        actionError.value = failure.message;
    } finally {
        answerBusy.value = false;
    }
}
watch(
    () => route.params.id,
    () => {
        answer.value = '';
        notice.value = '';
        actionError.value = '';
        answers.page.value = 1;
        load();
        answers.load();
    },
    { immediate: true }
);
onUnmounted(() => {
    generation++;
});
</script>
<template>
    <section class="community detail">
        <RouterLink to="/community">返回社区</RouterLink>
        <ElAlert
            v-if="
                !loading &&
                question?.status === 'PENDING' &&
                route.query.submitted === '1'
            "
            title="问题已提交，等待管理员审核。"
            type="success"
            :closable="false"
            role="status"
        />
        <div class="panel">
            <PageFeedback
                :loading="loading"
                :error="error"
                :empty="!question"
                @retry="load"
            >
                <template v-if="question"
                    ><div class="question-meta">
                        <span>{{ question.category.name }}</span
                        ><span>{{ question.author.username }}</span
                        ><time>{{ formatTime(question.createdAt) }}</time>
                    </div>
                    <h1>{{ question.title }}</h1>
                    <p class="detail-source">
                        来自学生社区 · {{ question.category.name }}
                    </p>
                    <ElTag
                        v-if="question.status !== 'APPROVED'"
                        type="warning"
                        >{{ statusLabel[question.status] }}</ElTag
                    >
                    <p v-if="question.rejectReason" class="reject-reason">
                        审核原因 · {{ question.rejectReason }}
                    </p>
                    <p class="text-content">{{ question.content }}</p>
                    <ElButton
                        v-if="question.status === 'APPROVED'"
                        :type="question.favorited ? 'primary' : 'default'"
                        :loading="favoriteBusy"
                        :disabled="favoriteBusy"
                        :aria-pressed="question.favorited"
                        @click="favorite"
                        >{{
                            question.favorited ? '取消收藏' : '收藏问题'
                        }}</ElButton
                    >
                </template>
            </PageFeedback>
        </div>
        <ElAlert
            v-if="actionError"
            :title="actionError"
            type="error"
            :closable="false"
            role="alert"
            ><p>提交超时后，请先在个人中心检查回答或收藏记录。</p>
            <RouterLink to="/profile">查看个人中心</RouterLink></ElAlert
        >
        <template v-if="question?.status === 'APPROVED'">
            <div class="panel">
                <h2>回答</h2>
                <PageFeedback
                    :loading="answers.loading.value"
                    :error="answers.error.value"
                    :empty="!answers.items.value.length"
                    :total="answers.total.value"
                    :page="answers.page.value"
                    @retry="answers.load"
                    @page="
                        answers.page.value = $event;
                        answers.load();
                    "
                >
                    <article
                        v-for="item in answers.items.value"
                        :key="item.id"
                        class="question-row"
                    >
                        <div class="question-meta">
                            <span>{{ item.author.username }}</span
                            ><time>{{ formatTime(item.createdAt) }}</time>
                        </div>
                        <p class="text-content">{{ item.content }}</p>
                    </article>
                </PageFeedback>
            </div>
            <div class="panel">
                <h2>写下你的回答</h2>
                <ElAlert
                    v-if="notice"
                    :title="notice"
                    type="success"
                    :closable="false"
                    role="status"
                />
                <ElForm
                    ref="form"
                    :model="{ content: answer }"
                    label-position="top"
                    @submit.prevent="submitAnswer"
                    ><ElFormItem
                        label="回答正文"
                        prop="content"
                        for="answer-content"
                        :rules="[
                            {
                                required: true,
                                whitespace: true,
                                message: '请输入回答内容',
                                trigger: ['blur', 'change'],
                            },
                            {
                                max: 10000,
                                message: '回答最多10000字',
                                trigger: 'change',
                            },
                        ]"
                        ><ElInput
                            id="answer-content"
                            v-model="answer"
                            type="textarea"
                            :rows="6"
                            maxlength="10000"
                            show-word-limit
                            :disabled="answerBusy" /></ElFormItem
                    ><ElButton
                        type="primary"
                        native-type="submit"
                        :disabled="answerBusy"
                        :loading="answerBusy"
                        >提交回答审核</ElButton
                    ></ElForm
                >
            </div>
        </template>
    </section>
</template>
<style scoped>
.detail {
    max-width: var(--reading-max);
    margin: auto;
}
.detail > a {
    display: inline-block;
    margin-bottom: 20px;
}
.detail > .el-alert {
    margin-top: 20px;
}
.text-content {
    margin-top: 24px;
}
.detail-source {
    color: var(--color-muted);
    font-size: 13px;
    border-bottom: 1px solid var(--color-border);
    padding-bottom: 20px;
}
</style>
