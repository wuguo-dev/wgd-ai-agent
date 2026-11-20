import { restore, logout } from './auth.js';

const identity = document.querySelector('#identity');
const status = document.querySelector('#status');
const button = document.querySelector('#logout');
async function refresh() {
    try {
        const user = await restore();
        identity.textContent = `欢迎，${user.username} · 学生`;
        button.disabled = false;
        status.textContent = '';
    } catch (error) {
        status.textContent = error.message;
    }
}
button.addEventListener('click', async () => {
    button.disabled = true;
    try { await logout(); }
    catch (error) { status.textContent = error.message; button.disabled = false; }
});
refresh();
window.addEventListener('pageshow', event => { if (event.persisted) refresh(); });
window.addEventListener('focus', refresh);
// Detect expiry and logout in another tab while the page stays open.
setInterval(refresh, 60000);
