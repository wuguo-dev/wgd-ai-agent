import test from 'node:test';
import assert from 'node:assert/strict';
import { createPinia, setActivePinia } from 'pinia';
import request from '../src/api/request.js';
import { useAgentStore } from '../src/stores/agent.js';
import { useAuthStore } from '../src/stores/auth.js';

function setup() {
    setActivePinia(createPinia());
    return useAgentStore();
}
function deferredAdapter() {
    const pending = [];
    request.defaults.adapter = config => new Promise(resolve => {
        pending.push(data => resolve({ config, data, status: 200, statusText: 'OK', headers: {} }));
    });
    return pending;
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('late history response cannot overwrite the most recent selected conversation', async () => {
    const store = setup();
    const pending = deferredAdapter();
    const first = store.select('first');
    await tick();
    const second = store.select('second');
    await tick();
    pending[1]({ items: [{ id: 'second-message' }], hasMore: false });
    await second;
    pending[0]({ items: [{ id: 'first-message' }], hasMore: false });
    await first;
    assert.equal(store.activeId, 'second');
    assert.equal(store.messages[0].id, 'second-message');
});

test('identity removal clears chat state and rejects late list/history results', async () => {
    const store = setup();
    const auth = useAuthStore();
    auth.user = { id: 1 };
    const pending = deferredAdapter();
    const history = store.select('private');
    const list = store.loadConversations();
    await tick();
    auth.clear();
    for (const complete of pending) complete({ items: [{ id: 'private' }], hasMore: false });
    await Promise.all([history, list]);
    assert.equal(store.activeId, null);
    assert.deepEqual(store.messages, []);
    assert.deepEqual(store.conversations, []);
});

test('request failure before persistence keeps the question and a retryable failed reply', async () => {
    const store = setup();
    store.activeId = 'private';
    request.defaults.adapter = async config => ({ config, data: { items: [], hasMore: false },
        status: 200, statusText: 'OK', headers: {} });
    // The relative CSRF URL has no server in Node, exercising a real pre-stream network failure.
    await store.send('保留这个问题');
    assert.equal(store.sending, false);
    assert.equal(store.messages.length, 2);
    assert.equal(store.messages[0].content, '保留这个问题');
    assert.equal(store.messages[1].status, 'failed');
    assert.equal(store.messages[1].unsaved, true);
    assert.ok(store.error);
});

test('stop refreshes terminal history and keeps sending locked until that refresh finishes', async () => {
    const store = setup();
    store.activeId = 'private';
    const pending = deferredAdapter();
    const stream = store.send('停止这个问题');
    await tick();
    assert.equal(pending.length, 1);
    const cancellation = store.stop();
    const duplicate = store.stop();
    await tick();
    pending[0]({ items: [{ id: 'user', role: 'user', content: '停止这个问题' },
        { id: 'reply', role: 'assistant', content: '部分回答', status: 'generating' }], hasMore: false });
    await stream;
    await tick();
    assert.equal(store.sending, true);
    assert.equal(store.send('不应发出第二个问题'), undefined);
    assert.equal(pending.length, 2);
    pending[1]({ items: [{ id: 'reply', role: 'assistant', content: '部分回答', status: 'cancelled' }], hasMore: false });
    await Promise.all([cancellation, duplicate]);
    assert.equal(store.messages.at(-1).status, 'cancelled');
    assert.equal(store.sending, false);
});
