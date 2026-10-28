#!/usr/bin/env python3
"""隔离管理端写接口契约回归。只创建/删除本次随机前缀资源，不清空库，不发送短信。
使用架构设计tools/.venv/bin/python运行；报告仅保留状态与约束名，不保留凭据或正文。
"""
import base64
import hashlib
import hmac
import json
from pathlib import Path
import struct
import time
import urllib.error
import urllib.request
import uuid
import yaml
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
state = json.loads((ROOT/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_'), '仅隔离库可执行'
contract = yaml.safe_load((ROOT/'contract/openapi.yaml').read_text())
credentials = json.loads(Path(state['credentialFile']).read_text())
base = 'http://127.0.0.1:18080'
token = None
results = []
owned = []
prefix = 'WRITE_' + uuid.uuid4().hex[:12].upper()


def call(method, path, body=None, version=None, key=None):
    headers = {'Content-Type':'application/json', 'Origin':'http://127.0.0.1:18781'}
    if token: headers['Authorization'] = 'Bearer ' + token
    if method != 'GET': headers['Idempotency-Key'] = key or str(uuid.uuid4())
    if version is not None: headers['If-Match'] = '"' + str(version) + '"'
    req = urllib.request.Request(base+path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None, response.headers.get('ETag')


def check(label, method, path, pattern, expected, body=None, version=None, key=None):
    status, data, etag = call(method, path, body, version, key)
    response = contract['paths'][pattern][method.lower()]['responses'].get(str(status), {})
    if '$ref' in response:
        response = contract['components']['responses'][response['$ref'].split('/')[-1]]
    content = response.get('content', {})
    schema = content.get('application/json', content.get('application/problem+json', {})).get('schema')
    failures = []
    if status != expected: failures.append('httpStatus')
    if data is not None:
        if schema is None: failures.append('responseSchemaMissing')
        else:
            validator = Draft202012Validator({**schema, 'components':contract['components']}, format_checker=FormatChecker())
            failures.extend(str(e.validator) for e in validator.iter_errors(data))
    results.append({'case':label, 'method':method, 'path':pattern, 'status':status, 'expected':expected, 'failures':failures})
    assert not failures, f'{label}: HTTP {status}, constraints={failures}'
    return data, etag


# 新OTP窗口避免复用浏览器已经消费的代码，任何秘密都不进入报告。
time.sleep(31-time.time()%30)
s = credentials['totpSecret']
digest = hmac.new(base64.b32decode(s+'='*((8-len(s)%8)%8)), struct.pack('>Q',int(time.time())//30), hashlib.sha1).digest()
offset = digest[-1]&15
otp = str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
status, login, _ = call('POST','/api/v1/auth/admin/login', {'phone':credentials['phone'],'password':credentials['password'],'mfaCode':otp})
assert status == 200, f'隔离登录HTTP{status}'
token = login['accessToken']
fixtures = {
    'schools': {'region':'隔离写回归'},
    'tags': {},
    'categories': {'parentId':None,'sortOrder':1},
    'forum-boards': {'requiresReview':True,'sortOrder':1},
    'notification-templates': {'channel':'IN_APP','providerTemplateId':'isolated-unused-template'},
    'roles': {'platform':'student','dataScope':'SELF','permissionCodes':[]},
}
try:
    for resource, extra in fixtures.items():
        path = '/api/v1/admin/'+resource
        body = {'name':'隔离写回归-'+resource,'code':prefix+'_'+resource.replace('-', '_').upper(),'status':'DISABLED',**extra}
        key = str(uuid.uuid4())
        row, etag = check(resource+' create','POST',path,path,201,body,key=key)
        owned.append((path,row['id']))
        assert etag, '可更新资源必须返回ETag'
        replay, _ = check(resource+' replay','POST',path,path,201,body,key=key)
        assert replay['id']==row['id'] and replay['version']==row['version'], '幂等重放产生新资源'
        check(resource+' key conflict','POST',path,path,409,{**body,'name':'不同请求'},key=key)
        check(resource+' unique','POST',path,path,409,body)
        target=path+'/'+row['id']; pattern=path+'/{id}'
        row,_=check(resource+' detail','GET',target,pattern,200)
        old=row['version']
        updated={**body,'name':'隔离已修改-'+resource}
        row,_=check(resource+' update','PUT',target,pattern,200,updated,old)
        assert row['name']==updated['name'] and row['version']>old
        check(resource+' stale update','PUT',target,pattern,412,body,old)
        row,_=check(resource+' enable','POST',target+'/status',pattern+'/status',200,{'status':'ENABLED'},row['version'])
        assert row['status']=='ENABLED'
        row,_=check(resource+' disable','POST',target+'/status',pattern+'/status',200,{'status':'DISABLED'},row['version'])
        assert row['status']=='DISABLED'
        check(resource+' stale delete','DELETE',target,pattern,412,version=old)
        check(resource+' delete','DELETE',target,pattern,204,version=row['version'])
        owned.remove((path,row['id']))
        check(resource+' absent','GET',target,pattern,404)
finally:
    # 失败时仅清理本次API返回的资源；失败清理单独记录，不掩盖原错误。
    for path, identifier in owned:
        try:
            status, row, _ = call('GET',path+'/'+identifier)
            if status==200 and row.get('code','').startswith(prefix):
                code, _, _ = call('DELETE',path+'/'+identifier,version=row['version'])
                results.append({'case':'cleanup','path':path+'/{id}','status':code,'failures':[] if code==204 else ['cleanupFailed']})
        except Exception:
            results.append({'case':'cleanup','path':path+'/{id}','failures':['cleanupFailed']})
    report={'database':state['database'],'prefix':prefix,'checks':len(results),'passed':sum(not r['failures'] for r in results),'results':results}
    (ROOT/'docs/ui-write-contract-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(f'写入契约回归通过：{len(results)}项，六类隔离资源已清理。')
