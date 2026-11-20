import { onUnmounted, ref } from 'vue';

export function useCommunityPage(loader) {
    const items = ref([]);
    const total = ref(0);
    const page = ref(1);
    const loading = ref(false);
    const error = ref('');
    let generation = 0;
    async function load() {
        const current = ++generation;
        loading.value = true;
        error.value = '';
        items.value = [];
        try {
            const result = await loader({ page: page.value, size: 10 });
            if (current !== generation) return;
            const lastPage = Math.max(1, Math.ceil(result.total / 10));
            if (page.value > lastPage) {
                page.value = lastPage;
                await load();
                return;
            }
            items.value = result.items;
            total.value = result.total;
        } catch (failure) {
            if (current === generation) error.value = failure.message;
        } finally {
            if (current === generation) loading.value = false;
        }
    }
    onUnmounted(() => {
        generation++;
    });
    return { items, total, page, loading, error, load };
}

export function formatTime(value) {
    return value
        ? new Date(value).toLocaleString('zh-CN', { hour12: false })
        : '';
}
export const statusLabel = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
};
