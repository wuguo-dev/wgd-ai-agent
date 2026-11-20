<script setup>
import { computed, reactive, ref } from 'vue';
import {
    ElAlert,
    ElButton,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElInputNumber,
    ElSelect,
    ElOption,
    ElTable,
    ElTableColumn,
    ElTag,
} from 'element-plus';
import {
    getAdminCategories,
    saveCategory,
    setCategoryStatus,
} from '../../api/community.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
const categories = ref([]);
const filters = reactive({ keyword: '', status: '' });
const applied = ref({ keyword: '', status: '' });
const filteredCategories = computed(() =>
    categories.value.filter(
        (item) =>
            item.name.includes(applied.value.keyword) &&
            (!applied.value.status || item.status === applied.value.status)
    )
);
const page = ref(1);
const pagedCategories = computed(() =>
    filteredCategories.value.slice((page.value - 1) * 10, page.value * 10)
);
const confirmingCategory = ref(null);
const statusError = ref('');
function searchCategories() {
    applied.value = { ...filters };
    page.value = 1;
}
function resetCategories() {
    Object.assign(filters, { keyword: '', status: '' });
    searchCategories();
}
function confirmStatus(item) {
    confirmingCategory.value = item;
    statusError.value = '';
}
async function submitStatus() {
    const item = confirmingCategory.value;
    if (!item || statusBusy.value) return;
    statusBusy.value = item.id;
    statusError.value = '';
    try {
        await setCategoryStatus(
            item.id,
            item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
        );
        confirmingCategory.value = null;
        await load();
    } catch (failure) {
        statusError.value = failure.message;
    } finally {
        statusBusy.value = null;
    }
}

const loading = ref(false);
const error = ref('');
const dialogError = ref('');
const visible = ref(false);
const editingId = ref(null);
const busy = ref(false);
const statusBusy = ref(null);
const form = ref();
const input = reactive({ name: '', description: '', sortOrder: 0 });
const rules = {
    name: [
        {
            required: true,
            whitespace: true,
            message: '请输入分类名称',
            trigger: ['blur', 'change'],
        },
        { max: 80, message: '最多80字', trigger: 'change' },
    ],
    description: [{ max: 500, message: '最多500字', trigger: 'change' }],
    sortOrder: [
        {
            required: true,
            type: 'number',
            min: 0,
            max: 10000,
            message: '请输入0至10000的整数',
            trigger: 'change',
        },
    ],
};
async function load() {
    loading.value = true;
    error.value = '';
    try {
        categories.value = await getAdminCategories();
        page.value = Math.min(
            page.value,
            Math.max(1, Math.ceil(filteredCategories.value.length / 10))
        );
    } catch (failure) {
        error.value = failure.message;
    } finally {
        loading.value = false;
    }
}
function open(item) {
    editingId.value = item?.id || null;
    Object.assign(input, {
        name: item?.name || '',
        description: item?.description || '',
        sortOrder: item?.sortOrder || 0,
    });
    dialogError.value = '';
    visible.value = true;
}
async function submit() {
    if (busy.value) return;
    busy.value = true;
    dialogError.value = '';
    try {
        if (!(await form.value.validate().catch(() => false))) return;
        await saveCategory(editingId.value, {
            name: input.name.trim(),
            description: input.description.trim(),
            sortOrder: input.sortOrder,
        });
        visible.value = false;
        await load();
    } catch (failure) {
        dialogError.value = failure.message;
    } finally {
        busy.value = false;
    }
}

load();
</script>
<template>
    <section class="community">
        <div class="page-heading">
            <div>
                <h1>分类管理</h1>
                <p class="admin-description">
                    维护问题分类、展示顺序和发布范围。
                </p>
            </div>
            <ElButton type="primary" @click="open()">新增分类</ElButton>
        </div>
        <div class="panel">
            <ElForm
                class="admin-filters"
                label-position="top"
                @submit.prevent="searchCategories"
                ><ElFormItem label="分类名称"
                    ><ElInput
                        v-model="filters.keyword"
                        clearable
                        maxlength="80"
                        placeholder="搜索分类名称" /></ElFormItem
                ><ElFormItem label="分类状态"
                    ><ElSelect
                        v-model="filters.status"
                        clearable
                        placeholder="全部状态"
                        ><ElOption label="启用" value="ENABLED" /><ElOption
                            label="停用"
                            value="DISABLED" /></ElSelect
                ></ElFormItem>
                <div class="admin-filter-actions">
                    <ElButton type="primary" native-type="submit">查询</ElButton
                    ><ElButton @click="resetCategories">重置</ElButton>
                </div></ElForm
            >
            <PageFeedback
                reserve-space
                :loading="loading"
                :error="error"
                :empty="!filteredCategories.length"
                :total="filteredCategories.length"
                :page="page"
                @page="page = $event"
                @retry="load"
                ><div class="table-scroll">
                    <ElTable :data="pagedCategories"
                        ><ElTableColumn
                            prop="name"
                            label="名称"
                            min-width="130"
                        /><ElTableColumn
                            prop="description"
                            label="说明"
                            min-width="200"
                        /><ElTableColumn
                            prop="sortOrder"
                            label="排序"
                            width="75"
                        /><ElTableColumn label="状态" width="90"
                            ><template #default="{ row }"
                                ><ElTag
                                    :type="
                                        row.status === 'ENABLED'
                                            ? 'success'
                                            : 'info'
                                    "
                                    >{{
                                        row.status === 'ENABLED'
                                            ? '启用'
                                            : '停用'
                                    }}</ElTag
                                ></template
                            ></ElTableColumn
                        ><ElTableColumn label="操作" width="180"
                            ><template #default="{ row }"
                                ><ElButton
                                    :disabled="!!statusBusy"
                                    @click="open(row)"
                                    >编辑</ElButton
                                ><ElButton
                                    :loading="statusBusy === row.id"
                                    :disabled="!!statusBusy"
                                    @click="confirmStatus(row)"
                                    >{{
                                        row.status === 'ENABLED'
                                            ? '停用'
                                            : '启用'
                                    }}</ElButton
                                ></template
                            ></ElTableColumn
                        ></ElTable
                    >
                </div></PageFeedback
            >
        </div>
        <ElDialog
            v-model="visible"
            class="community-dialog"
            :title="editingId ? '编辑分类' : '新增分类'"
            :close-on-click-modal="false"
            :close-on-press-escape="!busy"
            :show-close="!busy"
            ><ElAlert
                v-if="dialogError"
                :title="dialogError"
                type="error"
                :closable="false"
                role="alert"
            /><ElForm
                v-if="visible"
                ref="form"
                :model="input"
                :rules="rules"
                label-position="top"
                @submit.prevent="submit"
                ><ElFormItem label="名称" prop="name" for="category-name"
                    ><ElInput
                        id="category-name"
                        v-model="input.name"
                        maxlength="80"
                        :disabled="busy" /></ElFormItem
                ><ElFormItem
                    label="说明"
                    prop="description"
                    for="category-description"
                    ><ElInput
                        id="category-description"
                        v-model="input.description"
                        type="textarea"
                        :rows="4"
                        maxlength="500"
                        show-word-limit
                        :disabled="busy" /></ElFormItem
                ><ElFormItem
                    label="排序（数值越小越靠前）"
                    prop="sortOrder"
                    for="category-order"
                    ><ElInputNumber
                        id="category-order"
                        v-model="input.sortOrder"
                        :min="0"
                        :max="10000"
                        :precision="0"
                        :disabled="busy" /></ElFormItem
                ><ElButton
                    type="primary"
                    native-type="submit"
                    :loading="busy"
                    :disabled="busy"
                    >保存分类</ElButton
                ></ElForm
            ></ElDialog
        >
        <ElDialog
            :model-value="!!confirmingCategory"
            class="community-dialog"
            title="确认分类状态变更"
            :close-on-click-modal="false"
            :show-close="!statusBusy"
            :close-on-press-escape="!statusBusy"
            @update:model-value="!statusBusy && (confirmingCategory = null)"
            ><template v-if="confirmingCategory"
                ><ElAlert
                    v-if="statusError"
                    :title="statusError"
                    type="error"
                    :closable="false"
                    role="alert"
                />
                <h3>
                    确认{{
                        confirmingCategory.status === 'ENABLED'
                            ? '停用'
                            : '启用'
                    }}“{{ confirmingCategory.name }}”？
                </h3>
                <p>
                    {{
                        confirmingCategory.status === 'ENABLED'
                            ? '停用后不能用于新问题发布，历史问题仍保留分类信息。'
                            : '启用后，学生可在发布新问题时选择此分类。'
                    }}
                </p>
                <ElButton
                    type="primary"
                    :loading="!!statusBusy"
                    :disabled="!!statusBusy"
                    @click="submitStatus"
                    >确认变更</ElButton
                ><ElButton
                    :disabled="!!statusBusy"
                    @click="confirmingCategory = null"
                    >取消</ElButton
                ></template
            ></ElDialog
        >
    </section>
</template>
