import { login, restore } from './auth.js';

const form = document.querySelector('#login-form');
const status = document.querySelector('#status');
const submit = document.querySelector('#submit');
const password = document.querySelector('#password');
const toggle = document.querySelector('#toggle-password');
let pending = false;
toggle.addEventListener('click', () => {
    const visible = password.type === 'password';
    password.type = visible ? 'text' : 'password';
    toggle.textContent = visible ? '隐藏' : '显示';
    toggle.setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
    toggle.setAttribute('aria-pressed', String(visible));
});
form.addEventListener('submit', async event => {
    event.preventDefault();
    if (pending || !form.reportValidity()) return;
    const username = form.elements.username.value.trim();
    if (!username || !password.value.trim()) {
        status.textContent = '请输入用户名和密码';
        return;
    }
    pending = true;
    submit.disabled = true;
    status.textContent = '正在登录…';
    try {
        await login(username, password.value);
        password.value = '';
        location.replace(new URL('../community.html', import.meta.url));
    } catch (error) {
        status.textContent = error.message;
    } finally {
        pending = false;
        submit.disabled = false;
    }
});
restore().then(() => location.replace(new URL('../community.html', import.meta.url)))
    .catch(error => { if (!error.message.includes('请先登录')) status.textContent = error.message; });
