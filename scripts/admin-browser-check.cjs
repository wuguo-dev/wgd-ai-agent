// Real admin/community browser acceptance, credentials exist only in process memory.
const { chromium } = require(
  process.env.PLAYWRIGHT_MODULE ||
    "../target/community-browser-runtime/node_modules/playwright",
);
const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs/promises");
let browser, page, adminPage;
const base = process.env.ADMIN_FRONTEND_URL || "http://127.0.0.1:5178";
(async () => {
  browser = await chromium.launch({ channel: "chrome", headless: true });
  const errors = [];
  const checks = [];
  const layouts = [];
  const ctx = await browser.newContext();
  page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  const actx = await browser.newContext();
  adminPage = await actx.newPage();
  adminPage.on("pageerror", (e) => errors.push(e.message));
  const suffix = Date.now();
  const username = "adminui_" + suffix;
  const adminName = username + "adm";
  const password = crypto.randomBytes(18).toString("base64url");
  async function api(p, method, path, body) {
    return p.evaluate(
      async ({ method, path, body }) => {
        const headers = { "Content-Type": "application/json" };
        if (method !== "GET") {
          const t = await (await fetch("/api/auth/csrf")).json();
          headers[t.headerName] = t.token;
        }
        const r = await fetch("/api" + path, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
        });
        return {
          status: r.status,
          body: r.status === 204 ? null : await r.json(),
        };
      },
      { method, path, body },
    );
  }
  await page.goto(base + "/login");
  const registered = await api(page, "POST", "/auth/register", {
    username,
    password,
    confirmPassword: password,
  });
  if (registered.status !== 201) throw Error("register " + registered.status);
  const python =
    "import sys;from importlib.machinery import SourceFileLoader;m=SourceFileLoader('qa','scripts/community-live-check.py').load_module();c=m.development_connection();q=c.cursor();q.execute(\"INSERT INTO community_users(username,password_hash,role,status) SELECT %s,password_hash,'ADMIN','ACTIVE' FROM community_users WHERE username=%s AND role='STUDENT'\",(sys.argv[1],sys.argv[2]));assert q.rowcount==1;c.commit();c.close()";
  execFileSync("python", ["-c", python, adminName, username]);
  async function login(p, name) {
    await p.goto(base + "/login");
    await p.getByLabel("用户名", { exact: true }).fill(name);
    await p.getByLabel("密码", { exact: true }).fill(password);
    await p.locator(".login-button").click();
    await p.waitForURL(name === adminName ? "**/admin/dashboard" : "**/community");
  }
  await login(page, username);
  await login(adminPage, adminName);
  const category = await api(adminPage, "POST", "/admin/categories", {
    name: "QA browser " + suffix,
    description: "browser admin",
    sortOrder: 0,
  });
  if (category.status !== 201) throw Error("category");
  let questionId;
  async function manage(kind, title, action) {
    await adminPage.goto(
      base + "/admin/" + kind + "?keyword=" + encodeURIComponent(title),
    );
    const row = adminPage.locator("tr").filter({ hasText: title });
    await row.getByRole("button", { name: "详情与管理", exact: true }).click();
    const dialog = adminPage.getByRole("dialog");
    await dialog
      .getByRole("button", {
        name:
          action === "删除" || action === "恢复"
            ? action + (kind === "questions" ? "问题" : "回答")
            : action,
        exact: true,
      })
      .click();
    if (action === "删除")
      await dialog.locator("textarea").fill("QA browser removal");
    await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
  }
  for (const width of [1366, 375]) {
    await page.setViewportSize({ width, height: 900 });
    await adminPage.setViewportSize({ width, height: 900 });
    const title = "QA ui " + suffix + " " + width;
    await page.goto(base + "/community/questions/new");
    await page.waitForLoadState("networkidle");
    await page.getByLabel("标题", { exact: true }).fill(title);
    await page.locator(".el-select").click();
    await page
      .getByRole("option", { name: category.body.name, exact: true })
      .click();
    await page
      .getByLabel("正文", { exact: true })
      .fill('<img onerror="window.qaUnsafe=true"> QA safe content');
    await page.getByRole("button", { name: "提交审核", exact: true }).click();
    await page.waitForURL(/\/community\/questions\/\d+/);
    questionId = page.url().match(/questions\/(\d+)/)[1];
    await manage("questions", title, "审核通过");
    await page.reload();
    await page.getByRole("button", { name: "收藏问题", exact: true }).click();
    await page.getByRole("button", { name: "取消收藏", exact: true }).waitFor();
    await page
      .getByLabel("回答正文", { exact: true })
      .fill("QA browser answer " + width);
    await page
      .getByRole("button", { name: "提交回答审核", exact: true })
      .click();
    await page
      .getByText("回答已提交，等待管理员审核。可在个人中心查看审核状态。", {
        exact: true,
      })
      .waitFor();
    await manage("answers", title, "审核通过");
    await page.reload();
    await page
      .getByText("QA browser answer " + width, { exact: true })
      .waitFor();
    await manage("answers", title, "删除");
    await manage("answers", title, "恢复");
    await manage("questions", title, "删除");
    await page.reload();
    await page
      .getByRole("button", { name: "取消收藏", exact: true })
      .waitFor({ state: "hidden" });
    await manage("questions", title, "恢复");
    await page.reload();
    await page.getByRole("button", { name: "取消收藏", exact: true }).waitFor();
    if (
      (await page.evaluate(() => !!window.qaUnsafe)) ||
      (await page.locator(".text-content img").count())
    )
      throw Error("unsafe HTML");
    await adminPage.goto(base + "/admin/users?keyword=" + username);
    await adminPage
      .locator("tr")
      .filter({ hasText: username })
      .filter({ hasNotText: adminName })
      .getByRole("button", { name: "查看详情", exact: true })
      .click();
    let dialog = adminPage.getByRole("dialog");
    await dialog.getByRole("button", { name: "禁用账号", exact: true }).click();
    await dialog.getByRole("button", { name: "确认变更", exact: true }).click();
    await dialog
      .getByRole("button", { name: "启用账号", exact: true })
      .waitFor();
    const revoked = await api(page, "GET", "/auth/me");
    if (revoked.status !== 401) throw Error("disabled session");
    await dialog.getByRole("button", { name: "启用账号", exact: true }).click();
    await dialog.getByRole("button", { name: "确认变更", exact: true }).click();
    await dialog
      .getByRole("button", { name: "禁用账号", exact: true })
      .waitFor();
    await dialog.locator(".el-dialog__headerbtn").click();
    await login(page, username);
    checks.push({
      width,
      flow: "publish/review/answer/favorite/delete/restore/disable/enable/relogin",
      passed: true,
    });
  }
  const routes = [
    "/admin/dashboard",
    "/admin/questions",
    "/admin/answers",
    "/admin/users",
    "/admin/categories",
    "/admin/operation-logs",
  ];
  for (const width of [1920, 1366, 1024, 768, 390, 375]) {
    await page.setViewportSize({ width, height: 900 });
    await adminPage.setViewportSize({ width, height: 900 });
    for (const path of routes) {
      await adminPage.goto(base + path);
      await adminPage.locator("h1").waitFor();
      await adminPage.waitForLoadState("networkidle");
      const overflow = await adminPage.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      );
      if (overflow) throw Error("overflow " + width + " " + path);
      layouts.push({ width, path, overflow });
    }
    for (const path of [
      "/community",
      "/community/questions/new",
      "/community/questions/" + questionId,
      "/profile",
      "/agent/chat",
    ]) {
      await page.goto(base + path);
      await page.locator("h1").waitFor();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      );
      if (overflow) throw Error("overflow " + width + " " + path);
      layouts.push({ width, path, overflow });
    }
    if (width === 1366 || width === 375) {
      await adminPage.goto(base + "/admin/dashboard");
      await adminPage.waitForLoadState("networkidle");
      await adminPage.screenshot({
        path: "target/admin-dashboard-" + width + ".png",
        fullPage: true,
      });
      await page.goto(base + "/community");
      await page.waitForLoadState("networkidle");
      await page.screenshot({
        path: "target/admin-community-" + width + ".png",
        fullPage: true,
      });
      await page.goto(base + "/agent/chat");
      await page.waitForLoadState("networkidle");
      await page.screenshot({
        path: "target/admin-agent-" + width + ".png",
        fullPage: true,
      });
    }
  }
  await adminPage.goto(base + "/admin/dashboard");
  const menu = adminPage.getByRole("button", {
    name: "打开管理菜单",
    exact: true,
  });
  await menu.click();
  await adminPage
    .getByRole("dialog")
    .getByRole("link", { name: "用户管理", exact: true })
    .click();
  await adminPage.getByRole("dialog").waitFor({ state: "hidden" });
  await menu.focus();
  await menu.press("Enter");
  await adminPage.keyboard.press("Escape");
  await adminPage.getByRole("dialog").waitFor({ state: "hidden" });
  await adminPage.waitForTimeout(250);
  if (!(await menu.evaluate((el) => el === document.activeElement)))
    throw Error("drawer focus not restored");
  if (await adminPage.locator(".el-overlay:visible").count())
    throw Error("overlay remains");
  checks.push({
    flow: "mobile drawer navigation, Escape focus and overlay cleanup",
    passed: true,
  });
  const guest = await browser.newContext();
  const gp = await guest.newPage();
  for (const width of [1920, 1366, 1024, 768, 390, 375]) {
    await gp.setViewportSize({ width, height: 900 });
    for (const path of ["/login", "/register"]) {
      await gp.goto(base + path);
      await gp.waitForLoadState("networkidle");
      const overflow = await gp.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      );
      if (overflow) throw Error("auth overflow " + width + path);
      layouts.push({ width, path, overflow });
    }
  }
  await gp.screenshot({
    path: "target/admin-register-375.png",
    fullPage: true,
  });
  if (errors.length) throw Error(JSON.stringify(errors));
  const result = {
    checks,
    layouts,
    pageErrors: errors,
    accounts: [username, adminName],
    questionId,
  };
  await fs.writeFile(
    "target/admin-browser-result.json",
    JSON.stringify(result, null, 2),
  );
  console.log(JSON.stringify(result, null, 2));
})()
  .catch(async (e) => {
    await page?.screenshot({
      path: "target/admin-student-failure.png",
      fullPage: true,
    });
    await adminPage?.screenshot({
      path: "target/admin-ui-failure.png",
      fullPage: true,
    });
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    await browser?.close();
  });
