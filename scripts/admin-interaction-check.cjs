// Real interaction regression for the 2026-11-19 maintenance changes.
const { chromium } = require(
  process.env.PLAYWRIGHT_MODULE ||
    "../target/community-browser-runtime/node_modules/playwright",
);
const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs/promises");
let browser, page, admin;
(async () => {
  const base = process.env.ADMIN_FRONTEND_URL || "http://127.0.0.1:5180";
  browser = await chromium.launch({ channel: "chrome", headless: true });
  page = await (await browser.newContext()).newPage();
  admin = await (await browser.newContext()).newPage();
  const errors = [];
  for (const p of [page, admin])
    p.on("pageerror", (e) => errors.push(e.message));
  const checks = [];
  const suffix = Date.now();
  const username = "interqa_" + suffix;
  const adminName = username + "adm";
  const password = crypto.randomBytes(18).toString("base64url");
  function expect(label, value) {
    if (!value) throw Error(label);
    checks.push(label);
  }
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
        const data = r.status === 204 ? null : await r.json();
        if (r.status >= 400) throw Error(path + " " + r.status);
        return data;
      },
      { method, path, body },
    );
  }
  await page.goto(base + "/login");
  await api(page, "POST", "/auth/register", {
    username,
    password,
    confirmPassword: password,
  });
  const promote =
    "import sys;from importlib.machinery import SourceFileLoader;m=SourceFileLoader('qa','scripts/community-live-check.py').load_module();c=m.development_connection();q=c.cursor();q.execute(\"INSERT INTO community_users(username,password_hash,role,status) SELECT %s,password_hash,'ADMIN','ACTIVE' FROM community_users WHERE username=%s\",(sys.argv[1],sys.argv[2]));assert q.rowcount==1;c.commit();c.close()";
  execFileSync("python", ["-c", promote, adminName, username]);
  async function login(p, name) {
    await p.goto(base + "/login");
    await p.getByLabel("用户名", { exact: true }).fill(name);
    await p.getByLabel("密码", { exact: true }).fill(password);
    await p.locator(".login-button").click();
    await p.waitForURL(name === adminName ? "**/admin/dashboard" : "**/community");
  }
  await login(page, username);
  await login(admin, adminName);
  const categories = [];
  for (let i = 0; i < 11; i++)
    categories.push(
      await api(admin, "POST", "/admin/categories", {
        name: "IQ " + suffix + " " + String(i).padStart(2, "0"),
        description: "Interaction QA",
        sortOrder: i,
      }),
    );
  const prefix = "IQ questions " + suffix;
  for (let i = 0; i < 11; i++)
    await api(page, "POST", "/community/questions", {
      title: prefix + " " + i,
      content: "QA maintenance",
      categoryId: categories[0].id,
    });
  async function sameQuery(p, path, button, apiPath) {
    await p.goto(base + path);
    await p.waitForLoadState("networkidle");
    for (let i = 0; i < 2; i++) {
      const response = p.waitForResponse(
        (r) => r.request().method() === "GET" && r.url().includes(apiPath),
      );
      await p.getByRole("button", { name: button, exact: true }).click();
      const r = await response;
      expect(
        "same filter fetch " + path + " " + i,
        new URL(r.url()).searchParams.get("page") === "1",
      );
      await p.waitForLoadState("networkidle");
    }
  }
  await sameQuery(
    admin,
    "/admin/questions?keyword=" +
      encodeURIComponent(prefix) +
      "&deleted=ACTIVE",
    "查询",
    "/api/admin/questions?",
  );
  const before = await admin
    .locator(".page-feedback")
    .first()
    .evaluate((e) => e.offsetHeight);
  await admin.route("**/api/admin/questions?*", async (route) => {
    await new Promise((r) => setTimeout(r, 800));
    await route.continue();
  });
  const loaded = admin.waitForResponse((r) =>
    r.url().includes("/api/admin/questions?"),
  );
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin.locator('.page-feedback[aria-busy="true"]').waitFor();
  const during = await admin
    .locator(".page-feedback")
    .first()
    .evaluate((e) => e.offsetHeight);
  expect("loading preserves previous list height", during >= before);
  await loaded;
  await admin.unroute("**/api/admin/questions?*");
  await admin.waitForLoadState("networkidle");
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  await admin
    .locator(".el-pagination .number")
    .filter({ hasText: /^2$/ })
    .click();
  await admin.waitForLoadState("networkidle");
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  expect(
    "last page has one question",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 1,
  );
  await admin.getByRole("button", { name: "详情与管理", exact: true }).click();
  let dialog = admin.getByRole("dialog");
  await dialog.getByRole("button", { name: "删除问题", exact: true }).click();
  await dialog.locator("textarea").fill("QA last page deletion");
  await dialog.getByRole("button", { name: "确认操作", exact: true }).click();
  await dialog.waitFor({ state: "hidden" });
  await admin.waitForLoadState("networkidle");
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  expect(
    "delete last item falls back to page one",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 10,
  );
  await sameQuery(
    admin,
    "/admin/users?keyword=" + username,
    "查询",
    "/api/admin/users?",
  );
  const own = await api(page, "GET", "/auth/me");
  await admin.route("**/api/admin/users/" + own.id, (r) =>
    r.fulfill({
      status: 503,
      json: { code: "SERVICE_UNAVAILABLE", message: "QA detail unavailable" },
    }),
  );
  await admin
    .locator("tr")
    .filter({ hasText: username })
    .filter({ hasNotText: adminName })
    .getByRole("button", { name: "查看详情", exact: true })
    .click();
  dialog = admin.getByRole("dialog");
  await dialog.getByText("QA detail unavailable", { exact: true }).waitFor();
  expect(
    "failed user detail has no state action",
    (await dialog
      .getByRole("button", { name: /禁用账号|启用账号/ })
      .count()) === 0,
  );
  await admin.unroute("**/api/admin/users/" + own.id);
  await dialog.getByRole("button", { name: "重试", exact: true }).click();
  await dialog.getByRole("button", { name: "禁用账号", exact: true }).waitFor();
  expect("detail retry restores state action", true);
  await dialog.locator(".el-dialog__headerbtn").click();
  await admin.goto(base + "/admin/dashboard");
  await admin.getByRole("button", { name: "折叠导航", exact: true }).click();
  for (const label of [
    "控制台",
    "问题管理",
    "回答管理",
    "用户管理",
    "分类管理",
    "操作日志",
  ])
    expect(
      "collapsed nav label " + label,
      (await admin
        .getByRole("navigation", { name: "后台导航", exact: true })
        .getByRole("link", { name: label, exact: true })
        .count()) === 1,
    );
  expect(
    "collapsed back link label",
    (await admin
      .getByRole("link", { name: "返回学生社区", exact: true })
      .count()) === 1,
  );
  expect(
    "admin avatar label",
    (await admin
      .getByRole("button", { name: new RegExp("管理员 " + adminName) })
      .count()) === 1,
  );
  expect(
    "student avatar label",
    (await page.getByRole("button", { name: new RegExp(username) }).count()) ===
      1,
  );
  await admin.goto(base + "/admin/categories");
  await admin.getByPlaceholder("搜索分类名称").fill("IQ " + suffix);
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin.waitForLoadState("networkidle");
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  expect(
    "categories first page ten rows",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 10,
  );
  await admin
    .locator(".el-pagination .number")
    .filter({ hasText: /^2$/ })
    .click();
  expect(
    "categories second page one row",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 1,
  );
  await admin.locator(".el-select").click();
  await admin.getByRole("option", { name: "启用", exact: true }).click();
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  await admin
    .locator(".el-pagination .number")
    .filter({ hasText: /^2$/ })
    .click();
  await admin.getByRole("button", { name: "停用", exact: true }).click();
  await admin
    .getByRole("dialog")
    .getByRole("button", { name: "确认变更", exact: true })
    .click();
  await admin.getByRole("dialog").waitFor({ state: "hidden" });
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  expect(
    "category disable last record falls back",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 10,
  );
  await admin.getByPlaceholder("搜索分类名称").fill(categories[0].name);
  await admin.getByRole("button", { name: "查询", exact: true }).click();
  expect(
    "category filter resets page",
    (await admin.locator(".el-table__body-wrapper tbody tr").count()) === 1 &&
      (await admin.locator(".el-pagination .number.is-active").count()) === 0,
  );
  await sameQuery(page, "/community", "搜索", "/api/community/questions?");
  await admin.setViewportSize({ width: 375, height: 900 });
  await admin.goto(base + "/admin/categories");
  await admin.waitForLoadState("networkidle");
  await admin.locator('.page-feedback[aria-busy="false"]').first().waitFor();
  expect(
    "mobile no horizontal overflow",
    await admin.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  );
  await admin.screenshot({
    path: "target/admin-1119-interactions-375.png",
    fullPage: true,
  });
  expect("no page errors", errors.length === 0);
  const result = {
    checks,
    height: { before, during },
    errors,
    accounts: [username, adminName],
    mocked: ["user detail 503 only"],
  };
  await fs.writeFile(
    "target/admin-1119-interaction-result.json",
    JSON.stringify(result, null, 2),
  );
  console.log(JSON.stringify(result, null, 2));
})()
  .catch(async (e) => {
    await admin?.screenshot({
      path: "target/admin-1119-interaction-failure.png",
      fullPage: true,
    });
    console.error(e);
    process.exitCode = 1;
  })
  .finally(async () => {
    await browser?.close();
  });
