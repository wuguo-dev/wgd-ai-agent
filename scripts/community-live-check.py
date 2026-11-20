"""Explicit development acceptance against a local backend and documented development PG.
Creates identifiable QA users and content, retaining records for inspection. No secrets output.
"""
import argparse
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import time
import psycopg
import yaml

spec = importlib.util.spec_from_file_location('agent_client', Path(__file__).with_name('agent-chat-live-check.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
Client = module.Client


def development_connection():
    config = yaml.safe_load(Path('src/main/resources/application-local.yml').read_text(encoding='utf8'))['spring']['datasource']
    def resolve(value):
        match = re.fullmatch(r'\$\{([^:}]+)(?::(.*))?\}', value)
        return os.environ.get(match[1], match[2]) if match else value
    connection = psycopg.connect(resolve(config['url']).removeprefix('jdbc:'),
                                 user=resolve(config['username']), password=resolve(config['password']))
    with connection.cursor() as cursor:
        cursor.execute('select current_database(),current_user')
        assert cursor.fetchone() == ('wjy_agent', 'wgd_agent'), 'Select the documented development database'
    return connection


def run(base, output):
    assert base.startswith(('http://127.0.0.1:', 'http://localhost:')), 'Local development backend required'
    checks = []
    def expect(label, actual, expected):
        assert actual == expected, f'{label}: expected {expected}, got {actual}'
        checks.append({'check': label, 'result': 'passed'})
    users = []
    accounts = []
    for suffix in ['a', 'b', 'admin']:
        client = Client(base)
        username = 'communityqa_' + str(int(time.time()*1000)) + suffix
        password = secrets.token_urlsafe(20)
        status, user = client.request('POST','/auth/register', {'username':username,'password':password,'confirmPassword':password})
        expect('register ' + suffix,status,201)
        if suffix == 'admin':
            with development_connection() as connection:
                with connection.cursor() as cursor:
                    cursor.execute("UPDATE public.community_users SET role='ADMIN',updated_at=CURRENT_TIMESTAMP WHERE id=%s AND username=%s AND role='STUDENT'",(user['id'],username))
                    assert cursor.rowcount == 1
        expect('login ' + suffix,client.request('POST','/auth/login', {'username':username,'password':password})[0],200)
        users.append(client)
        accounts.append({'id':user['id'],'username':username})
    a,b,admin=users
    anonymous = Client(base)
    expect('anonymous list',anonymous.request('GET','/community/questions')[0],401)
    expect('anonymous write',anonymous.request('POST','/community/questions',{})[0],401)
    expect('student admin boundary',a.request('GET','/admin/questions')[0],403)
    status, category = admin.request('POST','/admin/categories',{'name':'QA '+str(int(time.time()*1000)),'description':'Community acceptance','sortOrder':0})
    expect('create category',status,201)
    expect('duplicate category',admin.request('POST','/admin/categories',{'name':category['name'],'description':'duplicate','sortOrder':0})[0],409)
    expect('category edit',admin.request('PUT','/admin/categories/'+str(category['id']),{'name':category['name'],'description':'QA edited','sortOrder':1})[0],200)
    payload={'title':'QA prepared statements '+str(int(time.time()*1000)),'content':'QA <img src=x onerror=alert(1)> literal % wildcard','categoryId':category['id']}
    expect('CSRF blocks writes',a.request('POST','/community/questions',payload,csrf=False)[0],403)
    expect('reject role injection',a.request('POST','/community/questions',dict(payload,userId=accounts[1]['id']))[0],400)
    status,q=a.request('POST','/community/questions',payload)
    expect('publish',status,201)
    expect('default pending',q['status'],'PENDING')
    qp='/community/questions/'+str(q['id'])
    expect('pending author visible',a.request('GET',qp)[0],200)
    expect('pending other invisible',b.request('GET',qp)[0],404)
    expect('private search absent',b.request('GET','/community/questions?keyword='+str(q['id'])+'not-present')[1]['total'],0)
    expect('own questions',any(x['id']==q['id'] for x in a.request('GET','/community/me/questions')[1]['items']),True)
    expect('foreign personal questions absent',any(x['id']==q['id'] for x in b.request('GET','/community/me/questions')[1]['items']),False)
    with concurrent.futures.ThreadPoolExecutor() as pool:
        results=list(pool.map(lambda _: admin.request('POST','/admin/questions/'+str(q['id'])+'/approve')[0],range(2)))
    expect('concurrent review',sorted(results),[200,409])
    expect('approved public',b.request('GET',qp)[1]['status'],'APPROVED')
    status,answer=b.request('POST',qp+'/answers',{'content':'QA parameterized SQL'})
    expect('answer submitted',status,201)
    expect('answer pending',answer['status'],'PENDING')
    expect('answer private',a.request('GET',qp+'/answers')[1]['total'],0)
    expect('answer approval',admin.request('POST','/admin/answers/'+str(answer['id'])+'/approve')[0],200)
    expect('answer public',a.request('GET',qp+'/answers')[1]['total'],1)
    with concurrent.futures.ThreadPoolExecutor() as pool:
        results=list(pool.map(lambda _: a.request('POST',qp+'/favorite')[0],range(6)))
    expect('concurrent favorites',results,[204]*6)
    with development_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute('select count(*) from public.community_favorite where user_id=%s and question_id=%s',(accounts[0]['id'],q['id']))
            expect('favorite database uniqueness',cursor.fetchone()[0],1)
    expect('favorites user isolation',any(x['id']==q['id'] for x in b.request('GET','/community/me/favorites')[1]['items']),False)
    expect('favorite visible',a.request('GET',qp)[1]['favorited'],True)
    expect('unfavorite',a.request('DELETE',qp+'/favorite')[0],204)
    expect('unfavorite idempotent',a.request('DELETE',qp+'/favorite')[0],204)
    expect('pagination limit',a.request('GET','/community/questions?size=51')[0],400)
    expect('category filter',a.request('GET','/community/questions?categoryId='+str(category['id']))[1]['total'],1)
    expect('literal keyword percent',a.request('GET','/community/questions?categoryId='+str(category['id'])+'&keyword=%25')[1]['total'],1)
    status,rejected=a.request('POST','/community/questions',dict(payload,title='QA rejected'))
    expect('second pending question',status,201)
    expect('reject question',admin.request('POST','/admin/questions/'+str(rejected['id'])+'/reject',{'reason':'QA improve details'})[0],200)
    expect('rejected remains private',b.request('GET','/community/questions/'+str(rejected['id']))[0],404)
    status,rejected_answer=b.request('POST',qp+'/answers',{'content':'QA rejected private answer'})
    expect('second answer pending',status,201)
    expect('reject answer',admin.request('POST','/admin/answers/'+str(rejected_answer['id'])+'/reject',{'reason':'QA private rejection reason'})[0],200)
    own=b.request('GET','/community/me/answers')[1]['items']
    expect('own rejection reason',next(x for x in own if x['id']==rejected_answer['id'])['rejectReason'],'QA private rejection reason')
    expect('private answer absent from public',any(x['id']==rejected_answer['id'] for x in a.request('GET',qp+'/answers')[1]['items']),False)
    ids=[]
    for title in ['QA page 1 literal_underscore','QA page 2 literal_underscore']:
        status,paged=a.request('POST','/community/questions',dict(payload,title=title))
        expect('paged question create',status,201)
        ids.append(paged['id'])
        expect('paged question approve',admin.request('POST','/admin/questions/'+str(paged['id'])+'/approve')[0],200)
    page1=a.request('GET','/community/questions?categoryId='+str(category['id'])+'&size=1&page=1')[1]
    page2=a.request('GET','/community/questions?categoryId='+str(category['id'])+'&size=1&page=2')[1]
    expect('stable page one',page1['items'][0]['id'],ids[-1])
    expect('stable page two',page2['items'][0]['id'],ids[-2])
    expect('literal underscore search',a.request('GET','/community/questions?categoryId='+str(category['id'])+'&keyword=%5F')[1]['total'],2)
    for query in ['page=0','size=0','size=51']:
        expect('invalid pagination '+query,a.request('GET','/community/questions?'+query)[0],400)
    def race(action):
        if action=='disable':
            return action,admin.request('PATCH','/admin/categories/'+str(category['id'])+'/status',{'status':'DISABLED'})[0]
        return action,a.request('POST','/community/questions',dict(payload,title='QA concurrent publish'))[0]
    with concurrent.futures.ThreadPoolExecutor() as pool:
        race_results=dict(pool.map(race,['disable','publish']))
    expect('concurrent category disable',race_results['disable'],200)
    expect('publish serialized with disable',race_results['publish'] in (201,409),True)
    expect('disable category',admin.request('PATCH','/admin/categories/'+str(category['id'])+'/status',{'status':'DISABLED'})[0],200)
    expect('disabled blocks publish',a.request('POST','/community/questions',payload)[0],409)
    expect('disabled historical readable',b.request('GET',qp)[0],200)
    expect('session restore',a.request('GET','/auth/me')[0],200)
    with development_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE public.community_users SET status='DISABLED',updated_at=CURRENT_TIMESTAMP WHERE id=%s AND username=%s",(accounts[1]['id'],accounts[1]['username']))
    expect('disabled account session revoked',b.request('GET','/auth/me')[0],401)
    with development_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE public.community_users SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP WHERE id=%s AND username=%s",(accounts[1]['id'],accounts[1]['username']))
    with development_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE public.community_users SET role='STUDENT',updated_at=CURRENT_TIMESTAMP WHERE id=%s AND username=%s",(accounts[2]['id'],accounts[2]['username']))
    expect('role change session revoked',admin.request('GET','/auth/me')[0],401)
    expect('logout',a.request('POST','/auth/logout')[0],204)
    expect('logout revoked',a.request('GET','/auth/me')[0],401)
    with development_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute("select count(*),md5(string_agg(md5(row(t.*)::text),'' order by id)) from public.knowledge_base_vector_store t")
            fingerprint=cursor.fetchone()
            expect('RAG data preserved',fingerprint,(47,'40f50dac83c5829b4a9b74fe1267c34d'))
            cursor.execute('select version,script,success from public.flyway_schema_history order by installed_rank')
            history=cursor.fetchall()
            expect('V004 migration succeeds',any(row[0]=='004' and row[2] is True for row in history),True)
            cursor.execute("SELECT count(*) FROM pg_constraint WHERE conname IN ('community_question_rejected_reason_required','community_answer_rejected_reason_required')")
            expect('database rejection reason constraints',cursor.fetchone()[0],2)
    result={'base':base,'accounts':accounts,'questionId':q['id'],'categoryId':category['id'],'checks':checks,'vectorFingerprint':fingerprint,'migrationHistory':history}
    Path(output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--output',default='target/community-live-result.json')
    args=parser.parse_args()
    run(args.base_url.rstrip('/'),args.output)
