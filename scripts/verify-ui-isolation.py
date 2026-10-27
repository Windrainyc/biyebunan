#!/usr/bin/env python3
"""使用正式登录接口验证隔离库三端边界；只读业务数据，不输出凭据或聊天正文。"""
import json
from pathlib import Path
import urllib.request
import urllib.error

root = Path(__file__).resolve().parents[1]
state = json.loads((root/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory = Path(state['credentialFile']).parent
base = 'http://127.0.0.1:18080/api/v1'
ports = {'student': 18780, 'partner': 18782} if state.get('containerFrontends') else {'student': 18770, 'partner': 18772}


def call(path, token=None, platform='student', body=None):
    headers = {'Origin': f'http://127.0.0.1:{ports[platform]}', 'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base+path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None


def login(platform):
    credentials = json.loads((directory/f'{platform}.json').read_text())
    status, session = call('/auth/'+platform+'/login', platform=platform, body={key:credentials[key] for key in ['phone','password']})
    assert status == 200, '隔离账户登录失败'
    return session['accessToken']


student = login('student')
partner = login('partner')
status, conversations = call('/student/conversations', student)
assert status == 200 and conversations['items'], '需要已完成的隔离问答夹具'
conversation = conversations['items'][0]['id']
business = json.loads((directory/'business.json').read_text())
checks = {}
for name,path,token,platform,expected in [
    ('student_own_messages','/student/conversations/'+conversation+'/messages',student,'student',200),
    ('partner_rejected_student_messages','/student/conversations/'+conversation+'/messages',partner,'partner',403),
    ('partner_rejected_admin_audit','/admin/conversations?reason=isolated-verification',partner,'partner',403),
    ('student_rejected_admin_users','/admin/users',student,'student',403),
    ('anonymous_rejected_messages','/student/conversations/'+conversation+'/messages',None,'student',401),
    ('partner_assigned_project','/partner/projects/'+business['projectId']+'/progress',partner,'partner',200),
]:
    status, _ = call(path,token,platform)
    assert status == expected, f'{name}: expected {expected}, got {status}'
    checks[name] = status
# 中介不能通过学生下载授权路径取得交付文件。
status, deliveries = call('/student/projects/'+business['projectId']+'/deliveries',student)
assert status == 200 and deliveries['items'], '需要已发布交付夹具'
status, _ = call('/student/assets/'+str(deliveries['items'][0]['assetId'])+'/download',partner,'partner',{})
assert status == 403, f'中介下载隔离不符: {status}'
checks['partner_rejected_student_asset_download'] = status
(root/'docs/ui-isolation-verification.json').write_text(json.dumps({'checks':checks,'isolatedDatabase':True,'realSms':False,'paidModelCalls':False},ensure_ascii=False,indent=2)+'\n')
print(json.dumps(checks,ensure_ascii=False))
