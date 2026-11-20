<script setup>
import { reactive, ref, watch } from 'vue';
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
import { getUsers, getUser, changeUserStatus } from '../../api/admin.js';
import { useAuthStore } from '../../stores/auth.js';
import {
    formatTime,
    useCommunityPage,
} from '../../composables/useCommunityPage.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const defaults = { keyword: '', role: '', status: '', sort: 'NEWEST' };
const filters = reactive({ ...defaults });
const list = useCommunityPage((params) =>
    getUsers({ ...route.query, ...params })
);
const selected = ref(null);
const confirming = ref(false);
const reason = ref('');
const loading = ref(false);
const detailError = ref('');
const busy = ref(false);
const error = ref('');
const notice = ref('');
let generation = 0;
async function search() {
    const failure = await router.push({
        path: route.path,
        query: Object.fromEntries(
            Object.entries(filters).filter(([, value]) => value !== '')
        ),
    });
    if (isNavigationFailure(failure, NavigationFailureType.duplicated)) {
        list.page.value = 1;
        await list.load();
    }
}
function reset() {
    Object.assign(filters, defaults);
    search();
}
async function open(item) {
    const current = ++generation;
    selected.value = item;
    loading.value = true;
    detailError.value = '';
    error.value = '';
    confirming.value = false;
    reason.value = '';
    try {
        const result = await getUser(item.id);
        if (generation === current) selected.value = result;
    } catch (failure) {
        if (generation === current) detailError.value = failure.message;
    } finally {
        if (generation === current) loading.value = false;
    }
}
function close() {
    if (!busy.value) {
        generation++;
        selected.value = null;
    }
}
async function submit() {
    if (busy.value || loading.value || detailError.value || !selected.value)
        return;
    busy.value = true;
    error.value = '';
    try {
        const result = await changeUserStatus(
            selected.value.id,
            selected.value.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
            reason.value.trim() || undefined
        );
        selected.value = result;
        confirming.value = false;
        notice.value = '账号状态已更新';
        await list.load();
    } catch (failure) {
        error.value = failure.message;
    } finally {
        busy.value = false;
    }
}
watch(
    () => route.fullPath,
    () => {
        Object.assign(filters, defaults, route.query);
        list.page.value = 1;
        list.load();
    },
    { immediate: true }
);
</script>
<template>
    <section class="community">
        <div class="page-heading">
            <div>
                <h1>用户管理</h1>
                <p class="admin-description">
                    查询账号信息，管理学生账号的启用与禁用状态。
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
        <div class="panel">
            <ElForm
                :model="filters"
                class="admin-filters"
                label-position="top"
                @submit.prevent="search"
                ><ElFormItem label="用户名"
                    ><ElInput
                        v-model="filters.keyword"
                        maxlength="200"
                        clearable
                        placeholder="搜索用户名" /></ElFormItem
                ><ElFormItem label="角色"
                    ><ElSelect
                        v-model="filters.role"
                        clearable
                        placeholder="全部角色"
                        ><ElOption label="学生" value="STUDENT" /><ElOption
                            label="管理员"
                            value="ADMIN" /></ElSelect></ElFormItem
                ><ElFormItem label="账号状态"
                    ><ElSelect
                        v-model="filters.status"
                        clearable
                        placeholder="全部状态"
                        ><ElOption label="启用" value="ACTIVE" /><ElOption
                            label="禁用"
                            value="DISABLED" /></ElSelect></ElFormItem
                ><ElFormItem label="排序"
                    ><ElSelect v-model="filters.sort"
                        ><ElOption label="最新注册" value="NEWEST" /><ElOption
                            label="最早注册"
                            value="OLDEST" /></ElSelect
                ></ElFormItem>
                <div class="admin-filter-actions">
                    <ElButton native-type="submit" type="primary">查询</ElButton
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
                            prop="id"
                            label="ID"
                            width="80"
                        /><ElTableColumn
                            prop="username"
                            label="用户名"
                            min-width="160"
                        /><ElTableColumn label="角色" min-width="100"
                            ><template #default="{ row }">{{
                                row.role === 'ADMIN' ? '管理员' : '学生'
                            }}</template></ElTableColumn
                        ><ElTableColumn label="状态" min-width="100"
                            ><template #default="{ row }"
                                ><ElTag
                                    :type="
                                        row.status === 'ACTIVE'
                                            ? 'success'
                                            : 'danger'
                                    "
                                    >{{
                                        row.status === 'ACTIVE'
                                            ? '启用'
                                            : '禁用'
                                    }}</ElTag
                                ></template
                            ></ElTableColumn
                        ><ElTableColumn label="注册时间" min-width="180"
                            ><template #default="{ row }">{{
                                formatTime(row.createdAt)
                            }}</template></ElTableColumn
                        ><ElTableColumn label="操作" width="120"
                            ><template #default="{ row }"
                                ><ElButton @click="open(row)"
                                    >查看详情</ElButton
                                ></template
                            ></ElTableColumn
                        ></ElTable
                    >
                </div></PageFeedback
            >
        </div>
        <ElDialog
            :model-value="!!selected"
            title="用户详情"
            class="community-dialog"
            :show-close="!busy"
            :close-on-click-modal="false"
            :close-on-press-escape="!busy"
            @update:model-value="close"
            ><PageFeedback
                :loading="loading"
                :error="detailError"
                :empty="!selected"
                @retry="open(selected)"
                ><template v-if="selected"
                    ><ElAlert
                        v-if="error"
                        :title="error"
                        type="error"
                        :closable="false"
                        role="alert"
                    />
                    <dl class="detail-fields">
                        <div>
                            <dt>用户 ID</dt>
                            <dd>{{ selected.id }}</dd>
                        </div>
                        <div>
                            <dt>用户名</dt>
                            <dd>{{ selected.username }}</dd>
                        </div>
                        <div>
                            <dt>角色</dt>
                            <dd>
                                {{
                                    selected.role === 'ADMIN'
                                        ? '管理员'
                                        : '学生'
                                }}
                            </dd>
                        </div>
                        <div>
                            <dt>账号状态</dt>
                            <dd>
                                {{
                                    selected.status === 'ACTIVE'
                                        ? '启用'
                                        : '禁用'
                                }}
                            </dd>
                        </div>
                        <div>
                            <dt>注册时间</dt>
                            <dd>{{ formatTime(selected.createdAt) }}</dd>
                        </div>
                        <div>
                            <dt>更新时间</dt>
                            <dd>{{ formatTime(selected.updatedAt) }}</dd>
                        </div>
                    </dl>
                    <template
                        v-if="
                            selected.role === 'STUDENT' &&
                            selected.id !== auth.user?.id
                        "
                        ><ElButton
                            v-if="!confirming"
                            :type="
                                selected.status === 'ACTIVE'
                                    ? 'danger'
                                    : 'success'
                            "
                            plain
                            @click="confirming = true"
                            >{{
                                selected.status === 'ACTIVE'
                                    ? '禁用账号'
                                    : '启用账号'
                            }}</ElButton
                        ><ElForm
                            v-else
                            label-position="top"
                            @submit.prevent="submit"
                            ><h3>
                                确认{{
                                    selected.status === 'ACTIVE'
                                        ? '禁用'
                                        : '启用'
                                }}“{{ selected.username }}”？
                            </h3>
                            <p v-if="selected.status === 'ACTIVE'">
                                禁用后，该学生的已有会话将在后续请求时失效。
                            </p>
                            <p v-else>启用后，该学生可重新登录。</p>
                            <ElFormItem label="操作原因（选填）"
                                ><ElInput
                                    v-model="reason"
                                    type="textarea"
                                    :rows="3"
                                    maxlength="1000"
                                    show-word-limit
                                    :disabled="busy" /></ElFormItem
                            ><ElButton
                                native-type="submit"
                                :type="
                                    selected.status === 'ACTIVE'
                                        ? 'danger'
                                        : 'primary'
                                "
                                :loading="busy"
                                :disabled="busy"
                                >确认变更</ElButton
                            ><ElButton
                                :disabled="busy"
                                @click="confirming = false"
                                >取消</ElButton
                            ></ElForm
                        ></template
                    >
                    <p v-else class="muted">管理员账号由运维管理。</p>
                </template></PageFeedback
            ></ElDialog
        >
    </section>
</template>
