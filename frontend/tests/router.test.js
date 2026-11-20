import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouter, createMemoryHistory } from 'vue-router';
import { routes, safeReturnPath, defaultHomePath } from '../src/router/index.js';

const router = createRouter({ history: createMemoryHistory(), routes });
test('returns only registered protected local destinations', () => {
    assert.equal(safeReturnPath(router, '/community?from=learning#home'), '/community?from=learning#home');
    for (const target of [undefined, ['/community'], 'https://example.org', '//example.org',
        '/\\example.org', '/login', '/missing', '/community\n', 'javascript:alert(1)']) {
        assert.equal(safeReturnPath(router, target), '/community');
    }
});

test('login defaults to the home page of the authenticated role', () => {
    assert.equal(defaultHomePath('ADMIN'), '/admin/dashboard');
    assert.equal(defaultHomePath('STUDENT'), '/community');
    for (const target of [undefined, '/missing', '/login', '//example.org']) {
        assert.equal(safeReturnPath(router, target, 'ADMIN'), '/admin/dashboard');
        assert.equal(safeReturnPath(router, target, 'STUDENT'), '/community');
    }
});

test('login returns only to routes allowed for the authenticated role', () => {
    assert.equal(safeReturnPath(router, '/admin/users?status=ACTIVE', 'ADMIN'), '/admin/users?status=ACTIVE');
    assert.equal(safeReturnPath(router, '/admin/users', 'STUDENT'), '/community');
    assert.equal(safeReturnPath(router, '/agent/chat', 'ADMIN'), '/agent/chat');
    assert.equal(safeReturnPath(router, '/agent/chat', 'STUDENT'), '/agent/chat');
    assert.equal(safeReturnPath(router, '/community/questions/42', 'ADMIN'), '/community/questions/42');
    assert.equal(routes.find(route => route.path === '/').redirect, '/login');
});
