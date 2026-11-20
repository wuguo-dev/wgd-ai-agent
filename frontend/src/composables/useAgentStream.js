// Decode UTF-8 incrementally and dispatch only complete SSE frames.
export async function consumeAgentStream(body, onEvent, signal) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let eventName = 'message';
    let data = [];
    function line(value) {
        if (value === '') {
            if (data.length) onEvent(eventName, JSON.parse(data.join('\n')));
            eventName = 'message';
            data = [];
        } else if (value.startsWith('event:')) {
            eventName = value.slice(6).trim();
        } else if (value.startsWith('data:')) {
            data.push(value.slice(5).replace(/^ /, ''));
        }
    }
    function drain(final = false) {
        let index;
        while ((index = buffer.search(/[\r\n]/)) >= 0) {
            if (!final && buffer[index] === '\r' && index === buffer.length - 1) break;
            const width = buffer[index] === '\r' && buffer[index + 1] === '\n' ? 2 : 1;
            line(buffer.slice(0, index));
            buffer = buffer.slice(index + width);
        }
    }
    const abort = () => { void reader.cancel(); };
    signal?.addEventListener('abort', abort, { once: true });
    try {
        while (true) {
            if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            drain();
        }
        buffer += decoder.decode();
        drain(true);
        if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
    } finally {
        signal?.removeEventListener('abort', abort);
        await reader.cancel().catch(() => {});
        reader.releaseLock();
    }
}
