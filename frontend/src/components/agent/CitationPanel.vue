<script setup>
defineProps({ citations: { type: Array, default: () => [] } });
// Defense in depth for historical records created by older server versions.
function name(value) {
    return value?.split(/[\\/]/).pop() || '参考资料';
}
</script>
<template>
    <details v-if="citations.length" class="citations">
        <summary>参考资料（{{ citations.length }}）</summary>
        <article
            v-for="(citation, index) in citations"
            :key="index"
            class="citation"
        >
            <strong>{{ name(citation.sourceName) }}</strong>
            <small v-if="citation.chunkIndex != null"
                >片段 {{ citation.chunkIndex }}</small
            >
            <details>
                <summary>查看内容片段</summary>
                <p>{{ citation.snippet }}</p>
            </details>
        </article>
    </details>
</template>
<style scoped>
.citations {
    margin-top: 16px;
    font-size: 13px;
    color: var(--color-muted);
}
summary {
    cursor: pointer;
    padding: 11px 0;
    min-height: 44px;
}
.citation {
    padding: 12px 16px;
    border: 1px solid var(--color-border);
    border-radius: 12px;
    margin-top: 8px;
    background: var(--color-background);
    overflow-wrap: anywhere;
}
strong {
    color: var(--color-text);
}
small {
    display: block;
}
p {
    margin-bottom: 0;
    white-space: pre-wrap;
}
</style>
