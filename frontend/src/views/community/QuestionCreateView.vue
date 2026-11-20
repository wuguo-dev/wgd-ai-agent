<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
    ElAlert,
    ElButton,
    ElForm,
    ElFormItem,
    ElInput,
    ElOption,
    ElSelect,
} from 'element-plus';
import { createQuestion, getCategories } from '../../api/community.js';
const router = useRouter();
const form = ref();
const input = reactive({ title: '', categoryId: null, content: '' });
const categories = ref([]);
const loading = ref(true);
const submitting = ref(false);
const error = ref('');
const categoryError = ref('');
const rules = {
    title: [
        {
            required: true,
            whitespace: true,
            message: '请输入问题标题',
            trigger: ['blur', 'change'],
        },
        { max: 200, message: '标题最多200字', trigger: 'change' },
    ],
    categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
    content: [
        {
            required: true,
            whitespace: true,
            message: '请输入问题正文',
            trigger: ['blur', 'change'],
        },
        { max: 20000, message: '正文最多20000字', trigger: 'change' },
    ],
};
async function loadCategories() {
    loading.value = true;
    categoryError.value = '';
    try {
        categories.value = (await getCategories()).filter(
            (item) => item.status === 'ENABLED'
        );
    } catch (failure) {
        categoryError.value = failure.message;
    } finally {
        loading.value = false;
    }
}
async function submit() {
    if (submitting.value) return;
    submitting.value = true;
    error.value = '';
    try {
        if (!(await form.value.validate().catch(() => false))) return;
        const question = await createQuestion({
            title: input.title.trim(),
            content: input.content.trim(),
            categoryId: input.categoryId,
        });
        await router.replace({
            path: `/community/questions/${question.id}`,
            query: { submitted: '1' },
        });
    } catch (failure) {
        error.value = failure.message;
    } finally {
        submitting.value = false;
    }
}
loadCategories();
</script>
<template>
    <section class="community question-form">
        <RouterLink to="/community">返回社区</RouterLink>
        <h1>发布问题</h1>
        <div class="publish-grid">
            <div class="panel">
                <ElAlert
                    title="发布后进入待审核状态，审核通过后在社区展示。"
                    type="info"
                    :closable="false"
                />
                <ElAlert
                    v-if="error"
                    :title="error"
                    type="error"
                    :closable="false"
                    role="alert"
                    ><p>提交超时后，请先在个人中心检查提问记录。</p>
                    <RouterLink to="/profile?tab=questions"
                        >查看我的提问</RouterLink
                    ></ElAlert
                >
                <ElAlert
                    v-if="categoryError"
                    :title="categoryError"
                    type="error"
                    :closable="false"
                    ><ElButton text @click="loadCategories"
                        >重试分类</ElButton
                    ></ElAlert
                >
                <ElAlert
                    v-else-if="!loading && !categories.length"
                    title="暂无可用分类，请联系管理员。"
                    type="warning"
                    :closable="false"
                />
                <ElForm
                    ref="form"
                    :model="input"
                    :rules="rules"
                    label-position="top"
                    size="large"
                    @submit.prevent="submit"
                >
                    <ElFormItem label="标题" prop="title" for="question-title"
                        ><ElInput
                            id="question-title"
                            v-model="input.title"
                            :disabled="submitting"
                            maxlength="200"
                            show-word-limit
                    /></ElFormItem>
                    <ElFormItem
                        label="分类"
                        prop="categoryId"
                        for="question-category"
                        ><ElSelect
                            id="question-category"
                            v-model="input.categoryId"
                            :loading="loading"
                            :disabled="loading || submitting"
                            placeholder="请选择问题分类"
                            ><ElOption
                                v-for="category in categories"
                                :key="category.id"
                                :value="category.id"
                                :label="category.name" /></ElSelect
                    ></ElFormItem>
                    <ElFormItem
                        label="正文"
                        prop="content"
                        for="question-content"
                        ><ElInput
                            id="question-content"
                            v-model="input.content"
                            type="textarea"
                            :rows="10"
                            maxlength="20000"
                            show-word-limit
                            :disabled="submitting"
                    /></ElFormItem>
                    <div class="form-actions">
                        <ElButton
                            type="primary"
                            native-type="submit"
                            :loading="submitting"
                            :disabled="
                                loading || submitting || !categories.length
                            "
                            >提交审核</ElButton
                        ><ElButton
                            :disabled="submitting"
                            @click="router.push('/community')"
                            >返回社区</ElButton
                        >
                    </div>
                </ElForm>
            </div>
            <aside class="publish-help">
                <h2>让问题更容易被理解</h2>
                <ol>
                    <li>用标题概括你遇到的具体问题。</li>
                    <li>选择最贴近问题主题的分类。</li>
                    <li>在正文补充背景、已尝试的方法和疑惑。</li>
                </ol>
                <h3>发布与审核</h3>
                <p>
                    提交后可在个人中心查看状态。审核通过后，问题将出现在社区中。
                </p>
                <RouterLink to="/profile?tab=questions"
                    >查看我的提问 →</RouterLink
                >
            </aside>
        </div>
    </section>
</template>
<style scoped>
.question-form {
    max-width: 1100px;
    margin: auto;
}
h1 {
    margin-top: 20px;
}
.publish-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 260px;
    gap: 28px;
    align-items: start;
}
.publish-help {
    padding: 24px;
    background: var(--el-color-primary-light-9);
    border-radius: var(--radius-card);
    font-size: 14px;
}
.publish-help h2 {
    font-size: 18px;
}
.publish-help ol {
    padding-left: 20px;
}
.publish-help li {
    margin-bottom: 16px;
}
.publish-help p {
    color: var(--color-muted);
}
@media (max-width: 768px) {
    .publish-grid {
        grid-template-columns: minmax(0, 1fr);
    }
    .publish-help {
        order: 2;
    }
}
</style>
