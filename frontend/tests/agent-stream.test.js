import test from 'node:test';
import assert from 'node:assert/strict';
import { consumeAgentStream } from '../src/composables/useAgentStream.js';

test('SSE parses split UTF-8, CRLF, multiline data and terminal events', async () => {
    const bytes = new TextEncoder().encode(': ping\r\nevent: message\r\ndata: {"delta":\r\ndata: "中文"}\r\n\r\nevent: done\ndata: {"citations":[]}\n\n');
    const events = [];
    const body = new ReadableStream({ start(controller) {
        for (const byte of bytes) controller.enqueue(new Uint8Array([byte]));
        controller.close();
    } });
    await consumeAgentStream(body, (...event) => events.push(event));
    assert.deepEqual(events, [['message', { delta: '中文' }], ['done', { citations: [] }]]);
});

test('abort cancels the stream reader', async () => {
    const abort = new AbortController();
    let cancelled = false;
    const body = new ReadableStream({ cancel() { cancelled = true; } });
    const pending = consumeAgentStream(body, () => {}, abort.signal);
    abort.abort();
    await assert.rejects(pending, { name: 'AbortError' });
    assert.equal(cancelled, true);
});
