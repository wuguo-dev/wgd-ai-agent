<script setup>
import { ElTag } from 'element-plus';
import { formatTime, statusLabel } from '../../composables/useCommunityPage.js';
defineProps({ items: Array, showStatus: Boolean });
</script>
<template>
    <div class="question-list">
        <article v-for="item in items" :key="item.id" class="question-row">
            <div class="question-meta">
                <span>{{ item.category.name }}</span
                ><span>{{ item.author.username }}</span
                ><time>{{ formatTime(item.createdAt) }}</time>
            </div>
            <h2>
                <RouterLink :to="`/community/questions/${item.id}`">{{
                    item.title
                }}</RouterLink>
            </h2>
            <p class="question-excerpt">{{ item.content.slice(0, 180) }}</p>
            <ElTag
                v-if="showStatus"
                :type="
                    item.status === 'REJECTED'
                        ? 'danger'
                        : item.status === 'APPROVED'
                          ? 'success'
                          : 'warning'
                "
                >{{ statusLabel[item.status] }}</ElTag
            >
            <p v-if="showStatus && item.rejectReason" class="reject-reason">
                审核原因 · {{ item.rejectReason }}
            </p>
        </article>
    </div>
</template>
