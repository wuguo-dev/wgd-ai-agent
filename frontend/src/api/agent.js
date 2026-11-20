import request, { csrfToken, httpFailure } from './request.js';
import { consumeAgentStream } from '../composables/useAgentStream.js';

const base = '/agent/conversations';
const path = id => `${base}/${encodeURIComponent(id)}`;
export const listConversations = (page = 0) => request.get(base, { params: { page, size: 20 } });
export const createConversation = () => request.post(base, {});
export const listMessages = (id, page = 0) => request.get(`${path(id)}/messages`, { params: { page, size: 20 } });
export const renameConversation = (id, title) => request.patch(path(id), { title });
export const deleteConversation = id => request.delete(path(id));
export const cancelGeneration = id => request.post(`${path(id)}/cancel`, {});

export async function streamMessage(id, message, signal, onEvent) {
    const csrf = await csrfToken();
    const response = await fetch(`/api${path(id)}/messages/stream`, {
        method: 'POST', credentials: 'same-origin', signal,
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ message }),
    });
    if (!response.ok) throw httpFailure(response.status, await response.json().catch(() => ({})));
    if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
        throw new Error('服务未返回流式回答，请重试');
    }
    await consumeAgentStream(response.body, onEvent, signal);
}
