<script setup>
import { ref, watch } from 'vue';
import {
    ElAlert,
    ElButton,
    ElEmpty,
    ElPagination,
    ElSkeleton,
} from 'element-plus';
const props = defineProps({
    reserveSpace: Boolean,
    loading: Boolean,
    error: String,
    empty: Boolean,
    total: Number,
    page: Number,
});
defineEmits(['retry', 'page']);
const region = ref();
const reservedHeight = ref(320);
watch(
    () => props.loading,
    (loading) => {
        if (loading && props.reserveSpace && region.value) {
            reservedHeight.value = Math.max(320, region.value.offsetHeight);
        }
    },
    { flush: 'sync' }
);
</script>
<template>
    <div
        ref="region"
        class="page-feedback"
        :aria-busy="loading"
        :style="
            reserveSpace && loading
                ? { minHeight: `${reservedHeight}px` }
                : undefined
        "
    >
        <ElSkeleton v-if="loading" :rows="4" animated aria-label="正在加载" />
        <ElAlert
            v-else-if="error"
            :title="error"
            type="error"
            :closable="false"
            show-icon
            role="alert"
        >
            <ElButton text @click="$emit('retry')">重试</ElButton>
        </ElAlert>
        <template v-else>
            <ElEmpty v-if="empty" description="暂无内容" :image-size="72" />
            <slot v-else />
            <ElPagination
                v-if="total > 10"
                class="community-pagination"
                :current-page="page"
                :page-size="10"
                :total="total"
                layout="prev, pager, next"
                :pager-count="5"
                @current-change="$emit('page', $event)"
            />
        </template>
    </div>
</template>
