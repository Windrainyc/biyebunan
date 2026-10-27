#!/usr/bin/env python3
"""仅在当前隔离UI库准备或撤销动态菜单浏览器验证夹具，使用正式管理员API。"""
import base64, hashlib, hmac, json, os, struct, sys, time, urllib.request, uuid
from pathlib import Path
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = json.loads((root/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory = Path(state['credentialFile']).parent
record = directory/'navigation-fixture.json'
action = sys.argv[1] if len(sys.argv)>1 else 'create'
assert action in ('create','disable','remove')
admin = json.loads(Path(state['credentialFile']).read_text())
time.sleep(31-time.time()%30)
secret=admin['totpSecret']
digest=hmac.new(base64.b32decode(secret+'='*((8-len(secret)%8)%8)),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest()
code=str((struct.unpack('>I',digest[digest[-1]&15:(digest[-1]&15)+4])[0]&0x7fffffff)%1000000).zfill(6)
token=None

def call(path,body=None,method=None,version=None):
    headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:18771','Idempotency-Key':str(uuid.uuid4())}
    if token: headers['Authorization']='Bearer '+token
    if version is not None: headers['If-Match']='"'+str(version)+'"'
    req=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers=headers)
    with urllib.request.urlopen(req,timeout=15) as response:
        raw=response.read();return json.loads(raw) if raw else None

token=call('/auth/admin/login',dict(phone=admin['phone'],password=admin['password'],mfaCode=code))['accessToken']
if action=='create':
    if record.exists(): raise SystemExit('已有夹具记录，请先核对，禁止重复创建')
    # 仅新增自己的菜单，不覆盖既有配置。
    if call('/admin/menus?platform=student')['total'] != 0: raise SystemExit('当前学生平台已有配置，拒绝改变其导航范围')
    menu=call('/admin/menus',dict(name='联调项目入口',code='UI-NAV-001',platform='student',parentId=None,path='/student/projects',permissionCode='get_student_projects',sortOrder=0,status='ENABLED'))
    record.write_text(json.dumps(menu))
elif action=='disable':
    menu=json.loads(record.read_text())
    menu=call('/admin/menus/'+menu['id']+'/status',dict(status='DISABLED'),version=menu['version'])
    record.write_text(json.dumps(menu))
else:
    menu=json.loads(record.read_text())
    call('/admin/menus/'+menu['id'],method='DELETE',version=menu['version'])
    record.rename(directory/'navigation-fixture-removed.json')
print('隔离导航夹具操作完成：'+action)
