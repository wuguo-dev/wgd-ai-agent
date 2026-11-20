<script setup>
import { reactive, ref } from 'vue';
import {
    ElButton,
    ElDatePicker,
    ElDialog,
    ElForm,
    ElFormItem,
    ElInput,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
} from 'element-plus';
import { actionLabels, getOperationLogs } from '../../api/admin.js';
import {
    formatTime,
    useCommunityPage,
} from '../../composables/useCommunityPage.js';
import PageFeedback from '../../components/community/PageFeedback.vue';
const filters = reactive({
    operatorId: '',
    action: '',
    startAt: '',
    endAt: '',
});
const applied = ref({});
const selected = ref(null);
const filterError = ref('');
const list = useCommunityPage((params) =>
    getOperationLogs({ ...params, ...applied.value })
);
function search() {
    filterError.value = '';
    if (
        filters.startAt &&
        filters.endAt &&
        new Date(filters.startAt) > new Date(filters.endAt)
    ) {
        filterError.value = '开始时间不能晚于结束时间';
        return;
    }
    applied.value = Object.fromEntries(
        Object.entries(filters)
            .filter(([, value]) => value !== '' && value != null)
            .map(([key, value]) => [
                key,
                key.endsWith('At') ? new Date(value).toISOString() : value,
            ])
    );
    list.page.value = 1;
    list.load();
}
function reset() {
    Object.assign(filters, {
        operatorId: '',
        action: '',
        startAt: '',
        endAt: '',
    });
    search();
}
function state(value) {
    try {
        return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
        return value || '—';
    }
}
list.load();
</script>
<template>
    <section class="community">
        <div class="page-heading">
            <div>
                <h1>操作日志</h1>
                <p class="admin-description">查看管理员关键操作与变更记录。</p>
            </div>
        </div>
        <div class="panel">
            <ElForm
                :model="filters"
                label-position="top"
                class="admin-filters"
                @submit.prevent="search"
                ><ElFormItem label="管理员 ID"
                    ><ElInput
                        v-model="filters.operatorId"
                        inputmode="numeric"
                        placeholder="输入管理员 ID"
                        clearable /></ElFormItem
                ><ElFormItem label="操作类型"
                    ><ElSelect
                        v-model="filters.action"
                        clearable
                        placeholder="全部操作"
                        ><ElOption
                            v-for="(label, value) in actionLabels"
                            :key="value"
                            :label="label"
                            :value="value" /></ElSelect></ElFormItem
                ><ElFormItem label="开始时间"
                    ><ElDatePicker
                        v-model="filters.startAt"
                        type="datetime"
                        placeholder="请选择开始时间" /></ElFormItem
                ><ElFormItem label="结束时间" :error="filterError"
                    ><ElDatePicker
                        v-model="filters.endAt"
                        type="datetime"
                        placeholder="请选择结束时间"
                /></ElFormItem>
                <div class="admin-filter-actions">
                    <ElButton type="primary" native-type="submit">查询</ElButton
                    ><ElButton @click="reset">重置</ElButton>
                </div></ElForm
            ><PageFeedback
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
                        /><ElTableColumn label="管理员" min-width="150"
                            ><template #default="{ row }"
                                >{{ row.operator.username
                                }}<small class="operator-id"
                                    >ID {{ row.operator.id }}</small
                                ></template
                            ></ElTableColumn
                        ><ElTableColumn label="操作" min-width="140"
                            ><template #default="{ row }">{{
                                actionLabels[row.action] || row.action
                            }}</template></ElTableColumn
                        ><ElTableColumn label="目标" min-width="130"
                            ><template #default="{ row }"
                                >{{ row.targetType }} #{{
                                    row.targetId
                                }}</template
                            ></ElTableColumn
                        ><ElTableColumn
                            prop="reason"
                            label="原因"
                            min-width="180"
                        /><ElTableColumn label="操作时间" min-width="180"
                            ><template #default="{ row }">{{
                                formatTime(row.createdAt)
                            }}</template></ElTableColumn
                        ><ElTableColumn label="详情" width="100"
                            ><template #default="{ row }"
                                ><ElButton @click="selected = row"
                                    >查看</ElButton
                                ></template
                            ></ElTableColumn
                        ></ElTable
                    >
                </div></PageFeedback
            >
        </div>
        <ElDialog
            :model-value="!!selected"
            title="操作详情"
            class="community-dialog"
            @update:model-value="selected = null"
            ><template v-if="selected"
                ><dl class="detail-fields">
                    <div>
                        <dt>操作人</dt>
                        <dd>
                            {{ selected.operator.username }}（{{
                                selected.operator.id
                            }}）
                        </dd>
                    </div>
                    <div>
                        <dt>操作类型</dt>
                        <dd>
                            {{
                                actionLabels[selected.action] || selected.action
                            }}
                        </dd>
                    </div>
                    <div>
                        <dt>目标记录</dt>
                        <dd>
                            {{ selected.targetType }} #{{ selected.targetId }}
                        </dd>
                    </div>
                    <div>
                        <dt>时间</dt>
                        <dd>{{ formatTime(selected.createdAt) }}</dd>
                    </div>
                </dl>
                <h3>操作原因</h3>
                <p class="text-content">{{ selected.reason || '—' }}</p>
                <h3>变更前</h3>
                <pre class="audit-state">{{ state(selected.beforeState) }}</pre>
                <h3>变更后</h3>
                <pre class="audit-state">{{ state(selected.afterState) }}</pre>
            </template></ElDialog
        >
    </section>
</template>
<style scoped>
.operator-id {
    display: block;
    color: var(--color-muted);
}
</style>
