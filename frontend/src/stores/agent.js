import { ref, computed, watch } from 'vue';
import { defineStore } from 'pinia';
import { useAuthStore } from './auth.js';
import * as api from '../api/agent.js';

export const useAgentStore = defineStore('agent', () => {
    const conversations = ref([]);
    const activeId = ref(null);
    const messages = ref([]);
    const loading = ref(false);
    const listing = ref(false);
    const sending = ref(false);
    const error = ref('');
    const hasMore = ref(false);
    const hasOlder = ref(false);
    const active = computed(() => conversations.value.find(item => item.id === activeId.value));
    let page = 0;
    let messagePage = 0;
    let generation = 0;
    let controller;
    let pendingStream;
    let stopping = null;
    let listGeneration = 0;
    let navigation = 0;
    let identityEpoch = 0;
    let creating = false;

    async function loadConversations(more = false) {
        if (listing.value) return;
        const ticket = ++listGeneration;
        listing.value = true;
        try {
            const result = await api.listConversations(more ? page + 1 : 0);
            if (ticket !== listGeneration) return;
            conversations.value = more ? [...conversations.value, ...result.items] : result.items;
            page = result.page;
            hasMore.value = result.hasMore;
        } catch (failure) { if (ticket === listGeneration) error.value = failure.message; }
        finally { if (ticket === listGeneration) listing.value = false; }
    }

    function stop() {
        if (stopping) return stopping;
        if (!controller) return Promise.resolve();
        const epoch = identityEpoch;
        const ticket = generation;
        const id = activeId.value;
        const abort = controller;
        const pending = pendingStream;
        abort.abort();
        const cancellation = (async () => {
            try {
                await api.cancelGeneration(id);
            } catch (failure) {
                if (epoch === identityEpoch) error.value = failure.message;
            }
            await pending;
            if (ticket !== generation || id !== activeId.value) return;
            try {
                const result = await api.listMessages(id);
                if (ticket === generation && id === activeId.value) {
                    messages.value = result.items;
                    hasOlder.value = result.hasMore;
                    messagePage = 0;
                }
            } catch (failure) {
                if (ticket === generation) error.value = failure.message;
            } finally {
                if (ticket === generation && controller === abort) {
                    controller = null;
                    sending.value = false;
                }
            }
        })();
        stopping = cancellation;
        void cancellation.finally(() => { if (stopping === cancellation) stopping = null; });
        return cancellation;
    }

    async function select(id) {
        const selected = ++navigation;
        await stop();
        if (selected !== navigation) return;
        const ticket = ++generation;
        activeId.value = id;
        messages.value = [];
        hasOlder.value = false;
        if (!id) return;
        loading.value = true;
        error.value = '';
        try {
            const result = await api.listMessages(id);
            if (ticket !== generation) return;
            messages.value = result.items;
            messagePage = 0;
            hasOlder.value = result.hasMore;
        } catch (failure) { if (ticket === generation) error.value = failure.message; }
        finally { if (ticket === generation) loading.value = false; }
    }

    async function older() {
        if (loading.value || sending.value || !activeId.value) return;
        const ticket = generation;
        loading.value = true;
        try {
            const result = await api.listMessages(activeId.value, messagePage + 1);
            if (ticket !== generation) return;
            messages.value = [...result.items, ...messages.value];
            messagePage = result.page;
            hasOlder.value = result.hasMore;
        } catch (failure) { if (ticket === generation) error.value = failure.message; }
        finally { if (ticket === generation) loading.value = false; }
    }

    async function create() {
        if (creating) return;
        creating = true;
        const epoch = identityEpoch;
        await stop();
        try {
            if (epoch !== identityEpoch) return;
            const result = await api.createConversation();
            if (epoch !== identityEpoch) return;
            conversations.value.unshift(result);
            await select(result.id);
        } catch (failure) { if (epoch === identityEpoch) error.value = failure.message; }
        finally { creating = false; }
    }

    async function rename(id, title) {
        const epoch = identityEpoch;
        try {
            const updated = await api.renameConversation(id, title);
            if (epoch !== identityEpoch) return false;
            conversations.value = conversations.value.map(item => item.id === id ? updated : item);
            return true;
        } catch (failure) { if (epoch === identityEpoch) error.value = failure.message; return false; }
    }

    async function remove(id) {
        const epoch = identityEpoch;
        if (id === activeId.value) await stop();
        if (epoch !== identityEpoch) return;
        try {
            await api.deleteConversation(id);
            if (epoch !== identityEpoch) return;
            conversations.value = conversations.value.filter(item => item.id !== id);
            if (id === activeId.value) await select(conversations.value[0]?.id || null);
            return true;
        } catch (failure) { if (epoch === identityEpoch) error.value = failure.message; return false; }
    }

    function send(text) {
        if (sending.value || loading.value || !activeId.value || !text.trim()) return;
        const ticket = generation;
        const id = activeId.value;
        const previousIds = new Set(messages.value.map(item => item.id));
        const abort = new AbortController();
        controller = abort;
        sending.value = true;
        error.value = '';
        const reply = { id: `pending-${Date.now()}`, role: 'assistant', content: '', status: 'generating', citations: [] };
        messages.value.push({ id: `user-${Date.now()}`, role: 'user', content: text, status: 'completed', citations: [] }, reply);
        // Access the reactive proxy so each delta immediately updates the view.
        const assistant = messages.value[messages.value.length - 1];
        let done = false;
        pendingStream = (async () => {
            try {
                await api.streamMessage(id, text, abort.signal, (event, data) => {
                    if (ticket !== generation || data.chatId !== id) return;
                    if (event === 'message') assistant.content += data.delta || '';
                    if (event === 'done') {
                        done = true;
                        assistant.status = 'completed';
                        assistant.citations = data.citations || [];
                    }
                    if (event === 'error') throw new Error(data.message || '生成失败，请重试');
                });
                if (!done) throw new Error('回答连接已中断，请重试');
            } catch (failure) {
                if (ticket !== generation) return;
                assistant.status = abort.signal.aborted ? 'cancelled' : 'failed';
                if (!abort.signal.aborted) error.value = failure.message;
            } finally {
                if (ticket === generation) {
                    try {
                        const result = await api.listMessages(id);
                        if (ticket === generation) {
                            messages.value = result.items;
                            const savedQuestion = result.items.some(item => item.role === 'user'
                                && item.content === text && !previousIds.has(item.id));
                            if (!done && !savedQuestion) {
                                messages.value.push({ id: `unsaved-user-${Date.now()}`, role: 'user', content: text,
                                    status: 'failed', citations: [] }, { ...assistant, unsaved: true });
                            }
                            hasOlder.value = result.hasMore;
                            messagePage = 0;
                        }
                    } catch { /* Keep partial output available when history cannot be refreshed. */ }
                    if (ticket === generation && !stopping) {
                        controller = null;
                        sending.value = false;
                    }
                }
            }
        })();
        return pendingStream;
    }

    function reset() {
        identityEpoch++;
        navigation++;
        generation++;
        listGeneration++;
        controller?.abort();
        controller = null;
        stopping = null;
        conversations.value = [];
        messages.value = [];
        activeId.value = null;
        sending.value = loading.value = listing.value = false;
        error.value = '';
    }
    watch(() => useAuthStore().user?.id, reset, { flush: 'sync' });
    return { conversations, activeId, active, messages, loading, listing, sending, error, hasMore, hasOlder,
        loadConversations, select, older, create, rename, remove, send, stop, reset };
});
