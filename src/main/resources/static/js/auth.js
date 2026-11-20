// Authentication stays in an HttpOnly cookie. Only user information and CSRF live in memory.
let currentUser = null;
let csrf = null;

export async function request(path, options = {}) {
    const headers = { ...options.headers };
    if (options.method === 'POST') {
        if (!csrf) csrf = await request('auth/csrf');
        headers[csrf.headerName] = csrf.token;
        headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(new URL(path, new URL('../', import.meta.url)), {
        ...options, headers, credentials: 'same-origin', cache: 'no-store'
    });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        if (response.status === 401) {
            currentUser = null;
            csrf = null;
            if (!path.endsWith('/login') && !location.pathname.endsWith('/login.html')) {
                location.replace(new URL('../login.html', import.meta.url));
            }
        }
        if (response.status === 403) csrf = null;
        throw new Error(body.message || '请求失败，请稍后重试');
    }
    return response.status === 204 ? null : response.json();
}

export async function login(username, password) {
    currentUser = await request('auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
    csrf = null;
    return currentUser;
}

export async function restore() {
    currentUser = await request('auth/me');
    return currentUser;
}

export async function logout() {
    await request('auth/logout', { method: 'POST', body: '{}' });
    currentUser = null;
    csrf = null;
    location.replace(new URL('../login.html', import.meta.url));
}
