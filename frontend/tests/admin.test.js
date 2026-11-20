import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouter, createMemoryHistory } from 'vue-router';
import request from '../src/api/request.js';
import { routes, safeReturnPath } from '../src/router/index.js';
import {
    actionLabels,
    getStats,
    getManagedContent,
    getManagedDetail,
    getUsers,
    getUser,
    getOperationLogs,
} from '../src/api/admin.js';

function captureRequests() {
    const calls = [];
    request.defaults.adapter = async (config) => {
        calls.push(config);
        return { config, data: {}, status: 200, statusText: 'OK', headers: {} };
    };
    return calls;
}

test('admin content API forwards all filters and reads dedicated admin detail endpoints', async () => {
    const calls = captureRequests();
    const filters = {
        page: 2,
        size: 10,
        keyword: "%_'",
        author: 'student',
        categoryId: 3,
        status: 'PENDING',
        deleted: 'ACTIVE',
        sort: 'OLDEST',
    };
    await getManagedContent('questions', filters);
    await getManagedDetail('questions', 17);
    await getManagedContent('answers', {
        page: 1,
        questionId: 17,
        deleted: 'DELETED',
    });
    await getManagedDetail('answers', 21);
    assert.deepEqual(
        calls.map((call) => call.url),
        [
            '/admin/questions',
            '/admin/questions/17',
            '/admin/answers',
            '/admin/answers/21',
        ]
    );
    assert.deepEqual(calls[0].params, filters);
    assert.deepEqual(calls[2].params, {
        page: 1,
        questionId: 17,
        deleted: 'DELETED',
    });
});

test('admin users statistics and audit API forwards role status operator and UTC ranges', async () => {
    const calls = captureRequests();
    await getStats();
    await getUsers({
        page: 1,
        size: 10,
        role: 'STUDENT',
        status: 'DISABLED',
        keyword: 'student',
    });
    await getUser(3);
    const filters = {
        page: 1,
        operatorId: 4,
        action: 'QUESTION_DELETE',
        startAt: '2026-11-18T00:00:00.000Z',
        endAt: '2026-11-18T23:59:59.000Z',
    };
    await getOperationLogs(filters);
    assert.deepEqual(
        calls.map((call) => call.url),
        [
            '/admin/dashboard/stats',
            '/admin/users',
            '/admin/users/3',
            '/admin/operation-logs',
        ]
    );
    assert.deepEqual(calls[3].params, filters);
});

test('all six admin destinations use the independent ADMIN layout and safe return validation', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    for (const suffix of [
        'dashboard',
        'questions',
        'answers',
        'users',
        'categories',
        'operation-logs',
    ]) {
        const path = `/admin/${suffix}`;
        const resolved = router.resolve(path);
        assert.deepEqual(resolved.meta.roles, ['ADMIN']);
        assert.equal(resolved.meta.requiresAuth, true);
        assert.equal(resolved.matched[0].path, '/admin');
        assert.match(
            String(routes.find((route) => route.path === '/admin').component),
            /AdminLayout/
        );
        assert.equal(safeReturnPath(router, path, 'ADMIN'), path);
    }
    assert.deepEqual(router.resolve('/agent/chat').meta.roles, ['STUDENT', 'ADMIN']);
    assert.equal(
        routes
            .find((route) => route.path === '/admin')
            .children.find((route) => route.path === '').redirect,
        '/admin/dashboard'
    );
});

test('audit action labels cover every contract operation without synthetic metrics', () => {
    assert.equal(Object.keys(actionLabels).length, 14);
    for (const action of [
        'QUESTION_APPROVE',
        'ANSWER_REJECT',
        'QUESTION_DELETE',
        'ANSWER_RESTORE',
        'USER_DISABLE',
        'USER_ENABLE',
        'CATEGORY_CREATE',
        'CATEGORY_UPDATE',
        'CATEGORY_DISABLE',
        'CATEGORY_ENABLE',
    ]) {
        assert.equal(typeof actionLabels[action], 'string');
    }
});
