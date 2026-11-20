// Usage: PLAYWRIGHT_MODULE=/path/to/playwright node scripts/agent-chat-browser-check.cjs
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
let browser;

(async () => {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const context = await browser.newContext();
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const base = process.env.AGENT_FRONTEND_URL || 'http://127.0.0.1:5175';
    const username = `agentbrowser_${Date.now()}`;
    const password = crypto.randomBytes(18).toString('base64url');
    await page.goto(base + '/agent/chat');
    await page.waitForURL('**/login?**');
    await page.evaluate(async ({ username, password }) => {
        const token = await (await fetch('/api/auth/csrf')).json();
        const response = await fetch('/api/auth/register', {
            method: 'POST', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
            body: JSON.stringify({ username, password, confirmPassword: password }),
        });
        if (response.status !== 201) throw new Error('Registration failed ' + response.status);
    }, { username, password });
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.locator('.login-button').click();
    await page.waitForURL('**/agent/chat');
    await page.getByRole('button', { name: '新建对话', exact: true }).last().click();
    await page.locator('#agent-question').fill('Spring Boot 的 Controller、Service、Repository 应该如何分层？请简短回答。');
    await page.getByRole('button', { name: /发送问题/ }).click();
    await page.getByRole('button', { name: '停止生成', exact: true }).waitFor();
    await page.getByRole('button', { name: /发送问题/ }).waitFor({ timeout: 180000 });
    const beforeRefresh = await page.locator('.chat-message').count();
    await page.reload();
    await page.locator('.chat-message').first().waitFor();
    const afterRefresh = await page.locator('.chat-message').count();
    const errorsHandled = [];
    for (const status of [403, 429]) {
        await page.route('**/messages/stream', route => route.fulfill({
            status, contentType: 'application/json', body: JSON.stringify({ code: 'QA_ERROR', message: `QA ${status}` }),
        }));
        await page.locator('#agent-question').fill(`QA ${status}`);
        await page.getByRole('button', { name: /发送问题/ }).click();
        await page.getByRole('alert').filter({ hasText: `QA ${status}` }).waitFor();
        await page.getByRole('button', { name: '重试', exact: true }).last().waitFor();
        errorsHandled.push(status);
        await page.unroute('**/messages/stream');
    }
    await page.reload();
    await page.locator('.chat-message').first().waitFor();
    await page.locator('#agent-question').fill('请详细解释Spring Boot的Controller、Service、Repository分层，各列出五个示例。');
    const streamStarted = page.waitForResponse(response => response.url().endsWith('/messages/stream'));
    await page.getByRole('button', { name: /发送问题/ }).click();
    await streamStarted;
    await page.getByRole('button', { name: '停止生成', exact: true }).click();
    await page.getByRole('button', { name: /发送问题/ }).waitFor();
    await page.waitForFunction(() => document.querySelector('.chat-message:last-child')?.textContent.includes('已停止'));
    const cancelled = true;
    const poison = '<img src=x onerror="window.agentXss=true"><script>window.agentXss=true</script> [bad](javascript:alert(1))\n```js\nconst safe = 1;\n```';
    await page.route('**/messages?page=0&size=20', async route => {
        const response = await route.fetch();
        const body = await response.json();
        body.items.push({ id: 'qa-xss', role: 'assistant', content: poison, status: 'completed', citations: [] });
        await route.fulfill({ response, json: body });
    });
    await page.reload();
    await page.getByRole('button', { name: '复制代码', exact: true }).waitFor();
    const markdownSafety = await page.evaluate(() => ({
        executed: !!window.agentXss,
        images: document.querySelectorAll('.markdown img').length,
        scripts: document.querySelectorAll('.markdown script').length,
        javascriptLinks: document.querySelectorAll('.markdown a[href^="javascript:"]').length,
        highlightedCode: !!document.querySelector('.markdown code .hljs-keyword'),
    }));
    if (markdownSafety.executed || markdownSafety.images || markdownSafety.scripts || markdownSafety.javascriptLinks) throw new Error('Markdown unsafe');
    await page.getByRole('button', { name: '复制代码', exact: true }).click();
    const copiedCode = await page.evaluate(() => navigator.clipboard.readText());
    if (!copiedCode.includes('const safe = 1;')) throw new Error('Code clipboard mismatch');
    await page.getByRole('button', { name: '复制回答', exact: true }).last().click();
    const copiedAnswer = await page.evaluate(() => navigator.clipboard.readText());
    if (!copiedAnswer.includes('const safe = 1;')) throw new Error('Answer clipboard mismatch');
    await page.unroute('**/messages?page=0&size=20');
    await page.reload();
    await page.locator('.chat-message').first().waitFor();
    const layouts = [];
    for (const width of [1920, 1366, 768, 390, 375]) {
        await page.setViewportSize({ width, height: 900 });
        const result = await page.evaluate(() => ({
            scrollWidth: document.documentElement.scrollWidth,
            viewport: innerWidth,
            composerVisible: !!document.querySelector('.composer'),
        }));
        layouts.push({ width, ...result });
        if (result.scrollWidth > width) throw new Error(`Overflow at ${width}`);
        if (width === 1920 || width === 375) await page.screenshot({ path: `target/agent-chat-${width}.png`, fullPage: true });
    }
    const storage = await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }));
    await page.setViewportSize({ width: 1366, height: 900 });
    await page.getByRole('button', { name: '重命名 新对话', exact: true }).click();
    await page.getByRole('textbox', { name: '会话标题' }).fill('浏览器重命名');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('heading', { name: '浏览器重命名', exact: true }).waitFor();
    await page.getByRole('button', { name: '新建对话', exact: true }).last().click();
    await page.getByRole('heading', { name: '新对话', exact: true }).waitFor();
    await page.getByRole('button', { name: '删除 新对话', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click();
    await page.getByRole('heading', { name: '浏览器重命名', exact: true }).waitFor();
    const conversationCrud = true;
    await page.getByRole('button', { name: /退出/ }).click();
    await page.waitForURL('**/login**');
    await page.goto(base + '/agent/chat');
    await page.waitForURL('**/login?**');
    const result = { beforeRefresh, afterRefresh, errorsHandled, cancelled, markdownSafety, copiedCode: true, copiedAnswer: true, conversationCrud, layouts, storage, errors, logoutProtected: true };
    await fs.writeFile('target/agent-browser-result.json', JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));
    await browser.close();
})().catch(async error => { console.error(error.message); await browser?.close(); process.exitCode = 1; });
