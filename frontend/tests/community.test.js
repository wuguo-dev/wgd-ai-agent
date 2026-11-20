import test from 'node:test';
import assert from 'node:assert/strict';
import { createRenderer } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';
import { useCommunityPage } from '../src/composables/useCommunityPage.js';
import { routes, safeReturnPath } from '../src/router/index.js';
import request from '../src/api/request.js';
import { getQuestions, getAnswers, getMyRecords, getPending } from '../src/api/community.js';

function setupPage(loader) {
    let page;
    const renderer = createRenderer({
        createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {},
    });
    const app = renderer.createApp({ setup() { page = useCommunityPage(loader); return () => null; } });
    app.mount({});
    return { page, unmount: () => app.unmount() };
}

test('community pagination isolates late filters and preserves 1-based page contract', async () => {
    const pending = [];
    const { page, unmount } = setupPage(params => new Promise(resolve => pending.push({ params, resolve })));
    const first = page.load();
    page.page.value = 2;
    const second = page.load();
    assert.deepEqual(pending[1].params, { page: 2, size: 10 });
    pending[1].resolve({ items: [{ id: 'latest' }], total: 11 });
    await second;
    pending[0].resolve({ items: [{ id: 'stale-private' }], total: 99 });
    await first;
    assert.equal(page.items.value[0].id, 'latest');
    assert.equal(page.total.value, 11);
    unmount();
});

test('community page clears old records on reload and discards responses after unmount', async () => {
    let resolve;
    const { page, unmount } = setupPage(() => new Promise(done => { resolve = done; }));
    page.items.value = [{ id: 'old-private' }];
    const loading = page.load();
    assert.deepEqual(page.items.value, []);
    unmount();
    resolve({ items: [{ id: 'late-private' }], total: 1 });
    await loading;
    assert.deepEqual(page.items.value, []);
});

test('community API uses nested resource routes and forwards bounded pagination parameters', async () => {
    const calls = [];
    request.defaults.adapter = async config => {
        calls.push(config);
        return { config, data: { items: [], page: 1, size: 10, total: 0 }, status: 200, statusText: 'OK', headers: {} };
    };
    const params = { page: 1, size: 10, keyword: "%'_", categoryId: 3 };
    await getQuestions(params);
    await getAnswers(7, { page: 2, size: 10 });
    await getMyRecords('favorites', { page: 1, size: 10 });
    await getPending('answers', { page: 1, size: 10 });
    assert.deepEqual(calls.map(call => call.url), ['/community/questions', '/community/questions/7/answers', '/community/me/favorites', '/admin/answers']);
    assert.deepEqual(calls[0].params, params);
});

test('community routes declare server-aligned roles and safe registered return paths', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    for (const path of ['/community', '/community/questions/new', '/community/questions/123', '/profile?tab=answers']) {
        assert.deepEqual(router.resolve(path).meta.roles, ['STUDENT', 'ADMIN']);
        assert.equal(safeReturnPath(router, path), path);
    }
    for (const path of ['/admin/questions', '/admin/answers', '/admin/categories']) {
        assert.deepEqual(router.resolve(path).meta.roles, ['ADMIN']);
    }
    assert.deepEqual(router.resolve('/agent/chat').meta.roles, ['STUDENT', 'ADMIN']);
});

test('pagination moves to the last available page after the final row disappears', async () => {
    const requestedPages = [];
    const { page, unmount } = setupPage(async params => {
        requestedPages.push(params.page);
        return params.page > 2
            ? { items: [], total: 11 }
            : { items: [{ id: 'remaining-row' }], total: 11 };
    });
    page.page.value = 3;
    await page.load();
    assert.deepEqual(requestedPages, [3, 2]);
    assert.equal(page.page.value, 2);
    assert.equal(page.items.value[0].id, 'remaining-row');
    assert.equal(page.loading.value, false);
    unmount();
});

test('pagination returns to page one when filtered records become empty', async () => {
    const requestedPages = [];
    const { page, unmount } = setupPage(async params => {
        requestedPages.push(params.page);
        return { items: [], total: 0 };
    });
    page.page.value = 2;
    await page.load();
    assert.deepEqual(requestedPages, [2, 1]);
    assert.equal(page.page.value, 1);
    assert.deepEqual(page.items.value, []);
    assert.equal(page.error.value, '');
    unmount();
});
