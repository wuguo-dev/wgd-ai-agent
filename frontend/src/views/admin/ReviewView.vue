<script setup>
import { computed, reactive, ref, watch } from 'vue';
import {
    isNavigationFailure,
    NavigationFailureType,
    useRoute,
    useRouter,
} from 'vue-router';
import {
    ElAlert,
    ElButton,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
    ElTag,
} from 'element-plus';
import {
    getManagedContent,
    getManagedDetail,
    deleteContent,
    restoreContent,
} from '../../api/admin.js';
import { getAdminCategories, reviewContent } from '../../api/community.js';
import {
    formatTime,
    statusLabel,
    useCommunityPage,
} from '../../composables/useCommunityPage.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
const route = useRoute();
const router = useRouter();
const kind = computed(() => route.meta.reviewKind);
const label = computed(() => (kind.value === 'questions' ? '问题' : '回答'));
const defaults = {
    keyword: '',
    author: '',
    categoryId: '',
    questionId: '',
    status: '',
    deleted: 'ALL',
    sort: 'NEWEST',
};
const filters = reactive({ ...defaults });
const categories = ref([]);
const categoryError = ref('');
const list = useCommunityPage((params) =>
    getManagedContent(kind.value, { ...route.query, ...params })
);
const selected = ref(null);
const detailLoading = ref(false);
const detailError = ref('');
const action = ref('');
const busy = ref(false);
const error = ref('');
const notice = ref('');
const input = reactive({ reason: '' });
const form = ref();
let detailGeneration = 0;
async function open(item) {
    const current = ++detailGeneration;
    selected.value = item;
    detailLoading.value = true;
    detailError.value = '';
    action.value = '';
    error.value = '';
    input.reason = '';
    try {
        const result = await getManagedDetail(kind.value, item.id);
        if (current === detailGeneration) selected.value = result;
    } catch (failure) {
        if (current === detailGeneration) detailError.value = failure.message;
    } finally {
        if (current === detailGeneration) detailLoading.value = false;
    }
}
function close() {
    if (busy.value) return;
    detailGeneration++;
    selected.value = null;
}
function choose(value) {
    action.value = value;
    error.value = '';
    input.reason = '';
}
async function search() {
    const query = Object.fromEntries(
        Object.entries(filters).filter(
            ([, value]) => value !== '' && value != null
        )
    );
    const failure = await router.push({ path: route.path, query });
    if (isNavigationFailure(failure, NavigationFailureType.duplicated)) {
        list.page.value = 1;
        await list.load();
    }
}
function reset() {
    Object.assign(filters, defaults);
    search();
}
async function submit() {
    if (busy.value || !selected.value || !action.value) return;
    busy.value = true;
    error.value = '';
    const startedKind = kind.value;
    const current = selected.value;
    try {
        if (
            ['delete', 'reject'].includes(action.value) &&
            !(await form.value.validate().catch(() => false))
        )
            return;
        if (action.value === 'delete')
            await deleteContent(startedKind, current.id, input.reason.trim());
        else if (action.value === 'restore')
            await restoreContent(startedKind, current.id);
        else
            await reviewContent(
                startedKind,
                current.id,
                action.value === 'approve',
                input.reason.trim()
            );
        if (kind.value !== startedKind) return;
        selected.value = null;
        notice.value = '操作已完成';
        await list.load();
    } catch (failure) {
        if (kind.value === startedKind) error.value = failure.message;
    } finally {
        busy.value = false;
    }
}
watch(
    () => route.fullPath,
    () => {
        detailGeneration++;
        selected.value = null;
        notice.value = '';
        Object.assign(filters, defaults, route.query);
        list.page.value = 1;
        list.load();
    },
    { immediate: true }
);
getAdminCategories()
    .then((result) => {
        categories.value = result;
    })
    .catch((failure) => {
        categoryError.value = failure.message;
    });
</script>
<template>
    <section class="community">
        <div class="page-heading">
            <div>
                <h1>{{ label }}管理</h1>
                <p class="admin-description">
                    查询全部{{ label }}，处理审核、删除与恢复。
                </p>
            </div>
        </div>
        <ElAlert
            v-if="notice"
            :title="notice"
            type="success"
            :closable="false"
            role="status"
        />
        <ElAlert
            v-if="categoryError"
            :title="categoryError"
            type="error"
            :closable="false"
        />
        <div class="panel">
            <ElForm
                :model="filters"
                label-position="top"
                class="admin-filters"
                @submit.prevent="search"
                ><ElFormItem
                    :label="kind === 'questions' ? '问题标题' : '所属问题标题'"
                    ><ElInput
                        v-model="filters.keyword"
                        maxlength="200"
                        clearable
                        placeholder="输入标题关键词" /></ElFormItem
                ><ElFormItem label="作者"
                    ><ElInput
                        v-model="filters.author"
                        maxlength="200"
                        clearable
                        placeholder="输入用户名" /></ElFormItem
                ><ElFormItem v-if="kind === 'questions'" label="分类"
                    ><ElSelect
                        v-model="filters.categoryId"
                        clearable
                        placeholder="全部分类"
                        ><ElOption
                            v-for="item in categories"
                            :key="item.id"
                            :label="item.name"
                            :value="item.id" /></ElSelect></ElFormItem
                ><ElFormItem v-else label="问题 ID"
                    ><ElInput
                        v-model="filters.questionId"
                        placeholder="输入问题 ID"
                        inputmode="numeric"
                        clearable /></ElFormItem
                ><ElFormItem label="审核状态"
                    ><ElSelect
                        v-model="filters.status"
                        clearable
                        placeholder="全部审核状态"
                        ><ElOption
                            v-for="(text, value) in statusLabel"
                            :key="value"
                            :label="text"
                            :value="value" /></ElSelect></ElFormItem
                ><ElFormItem label="删除状态"
                    ><ElSelect v-model="filters.deleted"
                        ><ElOption label="全部记录" value="ALL" /><ElOption
                            label="未删除"
                            value="ACTIVE" /><ElOption
                            label="已删除"
                            value="DELETED" /></ElSelect></ElFormItem
                ><ElFormItem label="排序"
                    ><ElSelect v-model="filters.sort"
                        ><ElOption label="最新发布" value="NEWEST" /><ElOption
                            label="最早发布"
                            value="OLDEST" /></ElSelect
                ></ElFormItem>
                <div class="admin-filter-actions">
                    <ElButton type="primary" native-type="submit">查询</ElButton
                    ><ElButton @click="reset">重置</ElButton>
                </div></ElForm
            >
            <PageFeedback
                reserve-space
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
                ><div class="table-scroll">
                    <ElTable :data="list.items.value"
                        ><ElTableColumn
                            label="ID"
                            prop="id"
                            width="80"
                        /><ElTableColumn
                            :label="
                                kind === 'questions' ? '问题标题' : '所属问题'
                            "
                            min-width="240"
                            ><template #default="{ row }"
                                ><strong>{{
                                    row.title || row.questionTitle
                                }}</strong>
                                <p
                                    v-if="kind === 'answers'"
                                    class="answer-preview"
                                >
                                    {{ row.content.slice(0, 80) }}
                                </p></template
                            ></ElTableColumn
                        ><ElTableColumn label="作者" min-width="120"
                            ><template #default="{ row }">{{
                                row.author.username
                            }}</template></ElTableColumn
                        ><ElTableColumn label="状态" min-width="130"
                            ><template #default="{ row }"
                                ><ElTag
                                    :type="
                                        row.status === 'APPROVED'
                                            ? 'success'
                                            : row.status === 'REJECTED'
                                              ? 'danger'
                                              : 'warning'
                                    "
                                    >{{ statusLabel[row.status] }}</ElTag
                                ><ElTag v-if="row.deletedAt" type="info"
                                    >已删除</ElTag
                                ></template
                            ></ElTableColumn
                        ><ElTableColumn label="发布时间" min-width="180"
                            ><template #default="{ row }">{{
                                formatTime(row.createdAt)
                            }}</template></ElTableColumn
                        ><ElTableColumn label="操作" width="150"
                            ><template #default="{ row }"
                                ><ElButton @click="open(row)"
                                    >详情与管理</ElButton
                                ></template
                            ></ElTableColumn
                        ></ElTable
                    >
                </div></PageFeedback
            >
        </div>
        <ElDialog
            :model-value="!!selected"
            class="community-dialog"
            :title="`${label}详情与管理`"
            :close-on-click-modal="false"
            :show-close="!busy"
            :close-on-press-escape="!busy"
            @update:model-value="close"
        >
            <PageFeedback
                :loading="detailLoading"
                :error="detailError"
                :empty="!selected"
                @retry="open(selected)"
                ><template v-if="selected"
                    ><h2>{{ selected.title || selected.questionTitle }}</h2>
                    <dl class="detail-fields">
                        <div>
                            <dt>作者</dt>
                            <dd>{{ selected.author.username }}</dd>
                        </div>
                        <div>
                            <dt>发布时间</dt>
                            <dd>{{ formatTime(selected.createdAt) }}</dd>
                        </div>
                        <div>
                            <dt>审核状态</dt>
                            <dd>{{ statusLabel[selected.status] }}</dd>
                        </div>
                        <div>
                            <dt>删除状态</dt>
                            <dd>
                                {{ selected.deletedAt ? '已删除' : '未删除' }}
                            </dd>
                        </div>
                        <div v-if="selected.category">
                            <dt>分类</dt>
                            <dd>{{ selected.category.name }}</dd>
                        </div>
                    </dl>
                    <p class="text-content">{{ selected.content }}</p>
                    <ElAlert
                        v-if="selected.questionDeleted"
                        title="所属问题已删除，恢复回答后仍受问题可见性规则限制。"
                        type="warning"
                        :closable="false"
                    />
                    <p v-if="selected.rejectReason" class="reject-reason">
                        驳回原因 · {{ selected.rejectReason }}
                    </p>
                    <div v-if="selected.deletedAt" class="deletion-record">
                        <h3>删除记录</h3>
                        <p>
                            {{ formatTime(selected.deletedAt) }} · 操作人 ID
                            {{ selected.deletedBy }}
                        </p>
                        <p class="text-content">{{ selected.deleteReason }}</p>
                    </div>
                    <RouterLink :to="`/admin/operation-logs`"
                        >查看操作日志 →</RouterLink
                    >
                    <div class="management-actions">
                        <template v-if="!selected.deletedAt"
                            ><ElButton
                                v-if="selected.status === 'PENDING'"
                                :disabled="busy"
                                type="primary"
                                @click="choose('approve')"
                                >审核通过</ElButton
                            ><ElButton
                                v-if="selected.status === 'PENDING'"
                                :disabled="busy"
                                @click="choose('reject')"
                                >审核驳回</ElButton
                            ><ElButton
                                type="danger"
                                plain
                                :disabled="busy"
                                @click="choose('delete')"
                                >删除{{ label }}</ElButton
                            ></template
                        ><ElButton
                            v-else
                            type="success"
                            plain
                            :disabled="busy"
                            @click="choose('restore')"
                            >恢复{{ label }}</ElButton
                        >
                    </div>
                    <ElForm
                        v-if="action"
                        ref="form"
                        :model="input"
                        label-position="top"
                        class="action-confirmation"
                        @submit.prevent="submit"
                        ><h3>
                            {{
                                {
                                    approve: '确认审核通过',
                                    reject: '确认审核驳回',
                                    delete: '确认删除',
                                    restore: '确认恢复',
                                }[action]
                            }}此{{ label }}？
                        </h3>
                        <p v-if="action === 'delete'">
                            删除后将从学生端隐藏，关联记录会被保留。
                        </p>
                        <p v-if="action === 'restore'">
                            恢复后保留原审核状态，按原可见性规则展示。
                        </p>
                        <ElAlert
                            v-if="error"
                            :title="error"
                            type="error"
                            :closable="false"
                            role="alert"
                        /><ElFormItem
                            v-if="['delete', 'reject'].includes(action)"
                            :label="
                                action === 'delete' ? '删除原因' : '驳回原因'
                            "
                            prop="reason"
                            :rules="[
                                {
                                    required: true,
                                    whitespace: true,
                                    message: '请填写原因',
                                    trigger: ['blur', 'change'],
                                },
                                {
                                    max: 1000,
                                    message: '最多1000字',
                                    trigger: 'change',
                                },
                            ]"
                            ><ElInput
                                v-model="input.reason"
                                type="textarea"
                                :rows="4"
                                maxlength="1000"
                                show-word-limit
                                :disabled="busy" /></ElFormItem
                        ><ElButton
                            :type="action === 'delete' ? 'danger' : 'primary'"
                            native-type="submit"
                            :loading="busy"
                            :disabled="busy"
                            >确认操作</ElButton
                        ><ElButton :disabled="busy" @click="action = ''"
                            >取消操作</ElButton
                        ></ElForm
                    >
                </template></PageFeedback
            ></ElDialog
        >
    </section>
</template>
<style scoped>
.answer-preview {
    font-size: 13px;
    color: var(--color-muted);
    margin: 8px 0 0;
    overflow-wrap: anywhere;
}
.management-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    margin: 24px 0;
}
.management-actions .el-button {
    margin: 0;
}
.action-confirmation {
    padding: 20px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-medium);
    background: var(--color-background);
}
.deletion-record {
    padding: 16px;
    background: var(--color-background);
    margin: 20px 0;
    border-radius: 12px;
}
</style>
