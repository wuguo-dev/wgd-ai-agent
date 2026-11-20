// Development browser acceptance. QA credentials remain in memory.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '../target/community-browser-runtime/node_modules/playwright');
const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const base = process.env.COMMUNITY_FRONTEND_URL || 'http://127.0.0.1:5176';
let browser, page, adminPage;
(async () => {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const errors = [];
    const context = await browser.newContext();
    page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    const checks = [];
    const suffix = Date.now();
    const password = crypto.randomBytes(18).toString('base64url');
    const username = `communityqa_${suffix}ui`;
    const adminName = `communityqa_${suffix}adm`;
    async function api(target, method, path, body) {
        return target.evaluate(async ({ method, path, body }) => {
            const headers = { 'Content-Type': 'application/json' };
            if (method !== 'GET') {
                const csrf = await (await fetch('/api/auth/csrf')).json();
                headers[csrf.headerName] = csrf.token;
            }
            const response = await fetch('/api' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
            return { status: response.status, body: response.status === 204 ? null : await response.json() };
        }, { method, path, body });
    }
    await page.goto(base + '/community');
    await page.waitForURL('**/login?**');
    for (const name of [username]) {
        const result = await api(page,'POST','/auth/register',{username:name,password,confirmPassword:password});
        if (result.status !== 201) throw Error('Register '+result.status);
    }
    const promote = "import sys;sys.path.insert(0,'scripts');from importlib.machinery import SourceFileLoader;m=SourceFileLoader('qa','scripts/community-live-check.py').load_module();c=m.development_connection();cur=c.cursor();cur.execute(\"INSERT INTO public.community_users(username,password_hash,role,status) SELECT %s,password_hash,'ADMIN','ACTIVE' FROM public.community_users WHERE username=%s AND role='STUDENT'\",(sys.argv[1],sys.argv[2]));assert cur.rowcount==1;c.commit();c.close()";
    execFileSync('python',['-c',promote,adminName,username]);
    await page.getByLabel('用户名',{exact:true}).fill(username);
    await page.getByLabel('密码',{exact:true}).fill(password);
    await page.locator('.login-button').click();
    await page.waitForURL('**/community');
    const adminContext = await browser.newContext();
    adminPage = await adminContext.newPage();
    adminPage.on('pageerror', e => errors.push(e.message));
    await adminPage.goto(base+'/login');
    await adminPage.getByLabel('用户名',{exact:true}).fill(adminName);
    await adminPage.getByLabel('密码',{exact:true}).fill(password);
    await adminPage.locator('.login-button').click();
    await adminPage.waitForURL('**/community');
    const category = await api(adminPage,'POST','/admin/categories',{name:`QA browser ${suffix}`,description:'Browser acceptance',sortOrder:0});
    if(category.status!==201) throw Error('category '+category.status);
    await page.goto(base+'/community/questions/new');
    await page.getByLabel('标题',{exact:true}).fill(`QA browser question ${suffix}`);
    await page.locator('.el-select').click();
    await page.getByRole('option',{name:`QA browser ${suffix}`,exact:true}).click();
    const poison = '<img src=x onerror="window.communityXss=true"> Browser literal text';
    await page.getByLabel('正文',{exact:true}).fill(poison);
    await page.getByRole('button',{name:'提交审核',exact:true}).click();
    await page.waitForURL(/\/community\/questions\/\d+/);
    await page.getByText('问题已提交，等待管理员审核。',{exact:true}).waitFor();
    const questionId = page.url().match(/questions\/(\d+)/)[1];
    checks.push('student publishes pending question through UI');
    await adminPage.goto(base+'/admin/questions');
    const row = adminPage.locator('tr').filter({hasText:`QA browser question ${suffix}`});
    await row.getByRole('button',{name:'查看与审核',exact:true}).click();
    await adminPage.getByRole('dialog').getByRole('button',{name:'确认审核'}).click();
    await row.waitFor({state:'detached'});
    checks.push('admin approves question through UI');
    await page.reload();
    await page.getByRole('button',{name:'收藏问题',exact:true}).click();
    await page.getByRole('button',{name:'取消收藏',exact:true}).waitFor();
    await page.getByLabel('回答正文',{exact:true}).fill('QA browser answer');
    await page.getByRole('button',{name:'提交回答审核',exact:true}).click();
    await page.getByText('回答已提交，等待管理员审核。可在个人中心查看审核状态。',{exact:true}).waitFor();
    await adminPage.goto(base+'/admin/answers');
    const answerRow = adminPage.locator('tr').filter({hasText:'QA browser question '+suffix});
    await answerRow.getByRole('button',{name:'查看与审核',exact:true}).click();
    await adminPage.getByRole('dialog').getByRole('button',{name:'确认审核'}).click();
    await answerRow.waitFor({state:'detached'});
    await page.reload();
    await page.getByText('QA browser answer',{exact:true}).waitFor();
    if(await page.evaluate(()=>!!window.communityXss)) throw Error('XSS executed');
    if(await page.locator('.text-content img').count()) throw Error('Unsafe HTML rendered');
    checks.push('answer approval, favorite and safe text rendering');
    await page.setViewportSize({width:1366,height:900});
    await page.screenshot({path:'target/community-desktop.png',fullPage:true});
    const viewports = [];
    for(const width of [1920,1366,768,390,375]) {
        await page.setViewportSize({width,height:900});
        for(const path of ['/community', '/community/questions/new',`/community/questions/${questionId}`,'/profile?tab=favorites']) {
            await page.goto(base+path);
            await page.locator('main').waitFor();
            await page.waitForLoadState('networkidle');
            const overflow = await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
            if(overflow) throw Error(`overflow ${width} ${path}`);
            viewports.push({width,path,overflow});
        }
        await adminPage.setViewportSize({width,height:900});
        for(const path of ['/admin/questions','/admin/answers','/admin/categories']) {
            await adminPage.goto(base+path);
            await adminPage.locator('main').waitFor();
            await adminPage.waitForLoadState('networkidle');
            const overflow = await adminPage.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
            if(overflow) throw Error(`admin overflow ${width} ${path}`);
            viewports.push({width,path,overflow});
        }
    }
    await page.goto(base+`/community/questions/${questionId}`);
    await page.getByRole('button',{name:'取消收藏',exact:true}).waitFor();
    await page.screenshot({path:'target/community-mobile.png',fullPage:true});
    await page.reload();
    await page.getByRole('button',{name:'取消收藏',exact:true}).waitFor();
    checks.push('refresh restores server identity and favorite');
    await page.goto(base+'/admin/questions');
    await page.getByRole('heading',{name:'当前账号无权访问该页面',exact:true}).waitFor();
    const identity=await api(page,'GET','/auth/me');
    if(identity.status!==200) throw Error('403 clears identity');
    checks.push('forbidden route preserves identity');
    await page.route('**/api/community/questions?*', route => route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'SERVICE_UNAVAILABLE',message:'QA 网络异常反馈'})}));
    await page.goto(base+'/community');
    await page.getByText('QA 网络异常反馈',{exact:true}).waitFor();
    await page.unroute('**/api/community/questions?*');
    await page.getByRole('button',{name:'重试',exact:true}).click();
    await page.getByText('QA 网络异常反馈',{exact:true}).waitFor({state:'detached'});
    checks.push('simulated 503 presents failure and retry recovers');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();
    await page.waitForURL('**/login**');
    checks.push('logout returns to login');
    if(errors.length) throw Error(JSON.stringify(errors));
    const result={checks,viewports,pageErrors:errors,questionId,accounts:[username,adminName]};
    await fs.writeFile('target/community-browser-result.json',JSON.stringify(result,null,2));
    console.log(JSON.stringify(result,null,2));
})().catch(async error=>{await page?.screenshot({path:'target/community-student-failure.png',fullPage:true});await adminPage?.screenshot({path:'target/community-admin-failure.png',fullPage:true});console.error(error);process.exitCode=1;}).finally(async()=>{await browser?.close();});
