"""Explicit local development admin acceptance, preserves identifiable QA records.
Run serially with other write suites so stats and direct SQL use the same dataset.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from importlib.machinery import SourceFileLoader
import json
from pathlib import Path
import secrets
import time

qa = SourceFileLoader("community_qa", "scripts/community-live-check.py").load_module()
Client = qa.Client


def run(base, output):
    assert base.startswith(("http://127.0.0.1:", "http://localhost:"))
    checks = []

    def check(label, actual, expected):
        assert actual == expected, f"{label}: {actual} != {expected}"
        checks.append(label)

    def req(client, method, path, body=None, expected=200):
        status, result = client.request(method, path, body)
        check(method + " " + path, status, expected)
        return result

    accounts = []
    clients = []
    password = secrets.token_urlsafe(20)
    for suffix in ("a", "b", "adm", "adm2"):
        client = Client(base)
        name = "adminqa_" + str(int(time.time() * 1000)) + suffix
        user = req(
            client,
            "POST",
            "/auth/register",
            dict(username=name, password=password, confirmPassword=password),
            201,
        )
        if suffix.startswith("adm"):
            with qa.development_connection() as c:
                with c.cursor() as cur:
                    cur.execute(
                        "UPDATE public.community_users SET role='ADMIN' WHERE id=%s AND username=%s AND role='STUDENT'",
                        (user["id"], name),
                    )
                    assert cur.rowcount == 1
        req(client, "POST", "/auth/login", dict(username=name, password=password))
        accounts.append(dict(id=user["id"], username=name))
        clients.append(client)
    a, b, admin, admin2 = clients
    req(Client(base), "GET", "/admin/dashboard/stats", expected=401)
    for path in (
        "/admin/users",
        "/admin/operation-logs",
        "/admin/questions",
        "/admin/dashboard/stats",
    ):
        req(a, "GET", path, expected=403)
    category = req(
        admin,
        "POST",
        "/admin/categories",
        dict(
            name="QA admin " + str(int(time.time() * 1000)),
            description="QA audit",
            sortOrder=0,
        ),
        201,
    )
    cat = "/admin/categories/" + str(category["id"])
    req(
        admin,
        "PUT",
        cat,
        dict(name=category["name"], description="QA updated", sortOrder=1),
    )
    req(admin, "PATCH", cat + "/status", dict(status="DISABLED"))
    req(admin, "PATCH", cat + "/status", dict(status="ENABLED"))
    question = lambda title: req(
        a,
        "POST",
        "/community/questions",
        dict(
            title=title, content="QA admin searchable body", categoryId=category["id"]
        ),
        201,
    )
    q = question("QA manage question")
    qid = q["id"]
    ap = "/admin/questions/" + str(qid)
    qp = "/community/questions/" + str(qid)
    req(admin, "POST", ap + "/approve")
    answer = req(b, "POST", qp + "/answers", dict(content="QA approved answer"), 201)
    answerp = "/admin/answers/" + str(answer["id"])
    req(admin, "POST", answerp + "/approve")
    rejected = question("QA rejected original state")
    rp = "/admin/questions/" + str(rejected["id"])
    req(admin, "POST", rp + "/reject", dict(reason="QA question rejection"))
    rejecta = req(b, "POST", qp + "/answers", dict(content="QA rejected answer"), 201)
    req(
        admin,
        "POST",
        "/admin/answers/" + str(rejecta["id"]) + "/reject",
        dict(reason="QA answer rejection"),
    )
    req(a, "POST", qp + "/favorite", expected=204)
    status, _ = admin.request("DELETE", ap, dict(reason="bad csrf"), csrf=False)
    check("delete csrf", status, 403)
    req(a, "DELETE", ap, dict(reason="student"), 403)
    before = req(admin, "GET", "/admin/operation-logs")["total"]
    req(admin, "DELETE", answerp, dict(reason="QA hide answer"))
    check("deleted answer hidden", req(a, "GET", qp + "/answers")["total"], 0)
    req(admin, "DELETE", ap, dict(reason="QA hide question"))
    for client in (a, b, admin):
        req(client, "GET", qp, expected=404)
    for path in (
        "/community/questions?categoryId=" + str(category["id"]),
        "/community/questions?keyword=manage",
        "/community/me/favorites",
    ):
        check(
            "hidden entry " + path,
            any(x["id"] == qid for x in req(a, "GET", path)["items"]),
            False,
        )
    check(
        "hidden own questions",
        any(x["id"] == qid for x in req(a, "GET", "/community/me/questions")["items"]),
        False,
    )
    check(
        "hidden own answers",
        any(
            x["id"] == answer["id"]
            for x in req(b, "GET", "/community/me/answers")["items"]
        ),
        False,
    )
    req(b, "POST", qp + "/answers", dict(content="must fail"), 404)
    req(b, "POST", qp + "/favorite", expected=404)
    req(admin, "POST", answerp + "/restore")
    req(a, "GET", qp + "/answers", expected=404)
    req(admin, "POST", ap + "/approve", expected=409)
    req(admin, "POST", ap + "/restore")
    check("restored approved status", req(admin, "GET", ap)["status"], "APPROVED")
    check("restored answer public", req(a, "GET", qp + "/answers")["total"], 1)
    check("favorite preserved", req(a, "GET", qp)["favorited"], True)
    req(admin, "DELETE", rp, dict(reason="QA rejected delete"))
    check(
        "rejected preserved on restore",
        req(admin, "POST", rp + "/restore")["status"],
        "REJECTED",
    )
    req(b, "GET", "/community/questions/" + str(rejected["id"]), expected=404)
    # Distinct authenticated admins race; only successful state transitions are audited.
    with ThreadPoolExecutor() as pool:
        codes = list(
            pool.map(
                lambda c: c.request("DELETE", ap, dict(reason="QA concurrent delete"))[
                    0
                ],
                (admin, admin2),
            )
        )
    check("concurrent delete", sorted(codes), [200, 409])
    with ThreadPoolExecutor() as pool:
        codes = list(
            pool.map(lambda c: c.request("POST", ap + "/restore")[0], (admin, admin2))
        )
    check("concurrent restore", sorted(codes), [200, 409])
    pending = question("QA delete review race")
    pendingp = "/admin/questions/" + str(pending["id"])
    with ThreadPoolExecutor() as pool:
        tasks = [
            pool.submit(
                admin.request, "DELETE", pendingp, dict(reason="QA delete review race")
            ),
            pool.submit(admin2.request, "POST", pendingp + "/approve"),
        ]
        results = [f.result()[0] for f in tasks]
    check("delete succeeds during review race", results[0], 200)
    check("review serialized with delete", results[1] in (200, 409), True)
    current = req(admin, "GET", pendingp)
    check("race final deleted", current["deletedAt"] is not None, True)
    userp = "/admin/users/" + str(accounts[0]["id"])
    user = req(admin, "GET", userp)
    check(
        "safe user fields",
        set(user),
        {"id", "username", "role", "status", "createdAt", "updatedAt"},
    )
    req(admin, "PATCH", userp + "/status", dict(status="DISABLED", reason="QA disable"))
    req(a, "GET", "/auth/me", expected=401)
    req(
        a,
        "POST",
        "/auth/login",
        dict(username=accounts[0]["username"], password=password),
        401,
    )
    req(admin, "PATCH", userp + "/status", dict(status="ACTIVE", reason="QA enable"))
    req(a, "GET", "/auth/me", expected=401)
    req(
        a,
        "POST",
        "/auth/login",
        dict(username=accounts[0]["username"], password=password),
    )
    req(
        admin,
        "PATCH",
        "/admin/users/" + str(accounts[2]["id"]) + "/status",
        dict(status="DISABLED"),
        403,
    )
    req(
        admin,
        "PATCH",
        "/admin/users/" + str(accounts[3]["id"]) + "/status",
        dict(status="DISABLED"),
        403,
    )
    stats = req(admin, "GET", "/admin/dashboard/stats")
    sqls = {
        "studentTotal": "SELECT count(*) FROM community_users WHERE role='STUDENT'",
        "activeStudents": "SELECT count(*) FROM community_users WHERE role='STUDENT' AND status='ACTIVE'",
        "disabledStudents": "SELECT count(*) FROM community_users WHERE role='STUDENT' AND status='DISABLED'",
        "questionTotal": "SELECT count(*) FROM community_question",
        "answerTotal": "SELECT count(*) FROM community_answer",
        "pendingQuestions": "SELECT count(*) FROM community_question WHERE status='PENDING' AND deleted_at IS NULL",
        "pendingAnswers": "SELECT count(*) FROM community_answer a JOIN community_question q ON q.id=a.question_id WHERE a.status='PENDING' AND a.deleted_at IS NULL AND q.deleted_at IS NULL AND q.status='APPROVED'",
        "deletedQuestions": "SELECT count(*) FROM community_question WHERE deleted_at IS NOT NULL",
        "deletedAnswers": "SELECT count(*) FROM community_answer WHERE deleted_at IS NOT NULL",
    }
    with qa.development_connection() as c:
        with c.cursor() as cur:
            for key, sql in sqls.items():
                cur.execute(sql)
                check("stats " + key, stats[key], cur.fetchone()[0])
            cur.execute(
                "SELECT action,before_state,after_state,reason,target_id FROM admin_operation_log WHERE operator_id IN (%s,%s)",
                (accounts[2]["id"], accounts[3]["id"]),
            )
            logs = cur.fetchall()
            check(
                "only successful delete audited",
                sum(r[0] == "QUESTION_DELETE" and r[4] == qid for r in logs),
                2,
            )
            check(
                "only successful restore audited",
                sum(r[0] == "QUESTION_RESTORE" and r[4] == qid for r in logs),
                2,
            )
            check(
                "review race audit matches outcome",
                sum(r[0] == "QUESTION_APPROVE" and r[4] == pending["id"] for r in logs),
                1 if results[1] == 200 else 0,
            )
            required = {
                "QUESTION_APPROVE",
                "QUESTION_REJECT",
                "ANSWER_APPROVE",
                "ANSWER_REJECT",
                "QUESTION_DELETE",
                "QUESTION_RESTORE",
                "ANSWER_DELETE",
                "ANSWER_RESTORE",
                "USER_DISABLE",
                "USER_ENABLE",
                "CATEGORY_CREATE",
                "CATEGORY_UPDATE",
                "CATEGORY_DISABLE",
                "CATEGORY_ENABLE",
            }
            check("all actions audited", required.issubset({r[0] for r in logs}), True)
            check(
                "audit excludes credentials",
                any(
                    x in json.dumps(logs, default=str).lower()
                    for x in (
                        password.lower(),
                        "password_hash",
                        "passwordhash",
                        "sessionid",
                        "api-key",
                    )
                ),
                False,
            )
            cur.execute(
                "SELECT count(*) FROM community_favorite WHERE user_id=%s AND question_id=%s",
                (accounts[0]["id"], qid),
            )
            check("favorite physically retained", cur.fetchone()[0], 1)
            cur.execute(
                "select count(*),md5(string_agg(md5(row(t.*)::text),'' order by id)) from public.knowledge_base_vector_store t"
            )
            check(
                "vector unchanged",
                cur.fetchone(),
                (47, "40f50dac83c5829b4a9b74fe1267c34d"),
            )
            cur.execute(
                "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank"
            )
            history = cur.fetchall()
            check(
                "V005 migration success",
                any(row == ("005", True) for row in history),
                True,
            )
    req(
        admin,
        "GET",
        "/admin/operation-logs?operatorId="
        + str(accounts[2]["id"])
        + "&action=USER_DISABLE",
    )
    req(admin, "GET", "/admin/questions?size=51", expected=400)
    req(
        admin,
        "GET",
        "/admin/operation-logs?startAt=2026-11-19T00:00:00Z&endAt=2026-11-18T00:00:00Z",
        expected=400,
    )
    result = dict(
        checks=checks,
        accounts=accounts,
        questionId=qid,
        migrations=history,
        stats=stats,
        auditActions=sorted({r[0] for r in logs}),
    )
    Path(output).write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf8"
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", required=True)
    p.add_argument("--output", default="target/admin-live-result.json")
    args = p.parse_args()
    run(args.base_url.rstrip("/"), args.output)
