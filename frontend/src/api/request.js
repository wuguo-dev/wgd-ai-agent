import axios from 'axios';

const settings = { baseURL: '/api', withCredentials: true, timeout: 15000 };
const csrfClient = axios.create(settings);
const request = axios.create(settings);
let csrfRequest = null;
let unauthorizedHandler = () => {};

export function onUnauthorized(handler) {
    unauthorizedHandler = handler;
}

export async function csrfToken() {
    // Share concurrent fetches, fetch afresh for the next write after session rotation.
    if (!csrfRequest) {
        csrfRequest = csrfClient.get('/auth/csrf').then(response => response.data)
            .finally(() => { csrfRequest = null; });
    }
    return csrfRequest;
}

export function httpFailure(status, data = {}) {
    if (status === 401) unauthorizedHandler();
    const message = status === 403 ? '权限不足或安全校验失败，请刷新后重试'
        : status === 429 ? '请求过于频繁，请稍后重试' : '暂时无法连接服务，请稍后重试';
    return Object.assign(new Error(data.message || message), { status, code: data.code || 'NETWORK_ERROR' });
}

request.interceptors.request.use(async config => {
    if (!['get', 'head', 'options'].includes(config.method)) {
        const csrf = await csrfToken();
        config.headers.set(csrf.headerName, csrf.token);
    }
    return config;
});

request.interceptors.response.use(response => response.data, error => {
    const status = error.response?.status;
    if (status === 401 && error.config?.url !== '/auth/login') unauthorizedHandler();
    const fallback = status === 403 ? '权限不足或安全校验失败，请刷新后重试'
        : status === 429 ? '登录尝试过于频繁，请稍后重试'
        : error.code === 'ECONNABORTED' ? '请求超时，请稍后重试'
        : '暂时无法连接服务，请稍后重试';
    // Return a minimal error, keeping Axios config (including credentials) out of UI/logs.
    const failure = new Error(error.response?.data?.message || fallback);
    failure.status = status;
    failure.code = error.response?.data?.code || 'NETWORK_ERROR';
    return Promise.reject(failure);
});

export default request;
