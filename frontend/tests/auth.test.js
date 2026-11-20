import test from 'node:test';
import assert from 'node:assert/strict';
import { createPinia, setActivePinia } from 'pinia';
import request, { onUnauthorized } from '../src/api/request.js';
import { useAuthStore } from '../src/stores/auth.js';

const student = { id: 1, username: 'student', role: 'STUDENT' };
function setup() {
    setActivePinia(createPinia());
    const auth = useAuthStore();
    onUnauthorized(() => auth.clear());
    return auth;
}
function response(config, data) {
    return { config, data, status: 200, statusText: 'OK', headers: {} };
}

test('deduplicates identity restoration and caches initialized state', async () => {
    const auth = setup();
    let requests = 0;
    request.defaults.adapter = async config => { requests++; return response(config, student); };
    await Promise.all([auth.fetchCurrentUser(), auth.fetchCurrentUser(), auth.fetchCurrentUser()]);
    await auth.fetchCurrentUser();
    assert.equal(requests, 1);
    assert.equal(auth.initialized, true);
    assert.equal(auth.isAuthenticated, true);
    assert.equal(auth.user.username, student.username);
});

test('ignores late identity response after session state was cleared', async () => {
    const auth = setup();
    let complete;
    request.defaults.adapter = config => new Promise(resolve => { complete = () => resolve(response(config, student)); });
    const pending = auth.fetchCurrentUser();
    await new Promise(resolve => setImmediate(resolve));
    auth.clear();
    complete();
    await pending;
    assert.equal(auth.user, null);
});

test('401 clears identity while 403 preserves identity and reports permission error', async () => {
    const auth = setup();
    request.defaults.adapter = async config => response(config, student);
    await auth.fetchCurrentUser();
    for (const status of [403, 401]) {
        request.defaults.adapter = async config => {
            throw { config, response: { status, data: { code: status === 403 ? 'FORBIDDEN' : 'UNAUTHORIZED', message: 'test-error' } } };
        };
        if (status === 403) {
            await assert.rejects(auth.fetchCurrentUser(true), error => error.status === 403);
            assert.equal(auth.isAuthenticated, true);
        } else {
            assert.equal(await auth.fetchCurrentUser(true), null);
            assert.equal(auth.isAuthenticated, false);
        }
    }
});
