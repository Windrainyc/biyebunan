#!/usr/bin/env python3
"""通过正式API配置隔离模型、价格、会员，仅供本机协议及账务联调。"""
import base64, hashlib, hmac, json, os, struct, sys, time, urllib.request, uuid
from pathlib import Path
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = json.loads((root/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory = Path(state['credentialFile']).parent
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

from datetime import datetime,timezone,timedelta
fixture=directory/'model-business.json'
if fixture.exists():raise SystemExit('模型夹具已存在，请核对后接续，禁止重复授予')
model=call('/admin/models',dict(name='本机协议验证模型（非真实AI）',code='ui-isolated-model',provider='OPENAI_COMPATIBLE',endpoint='https://localhost:18444/chat/completions',contextLimit=65536,maxOutputTokens=1024,status='ENABLED'))
# 价格须未来生效，不修改已生效快照。
price=call('/admin/model-prices',dict(modelId=model['id'],currency='CNY',inputPerMillion='1',outputPerMillion='1',cachedInputPerMillion='1',effectiveFrom=(datetime.now(timezone.utc)+timedelta(seconds=10)).isoformat(),status='ENABLED'))
keyrow=call('/admin/model-keys',dict(name='仅本机协议测试',modelId=model['id'],secretValue=(directory/'model-fixture-key').read_text().strip()))
call('/admin/model-keys/'+keyrow['id']+'/status',dict(status='ENABLED'),version=keyrow['version'])
plan=call('/admin/membership-plans',dict(name='本机联调权益',code='UI-PLAN-001',durationDays=30,monthlyTokenQuota=100000,monthlyAmountLimit='10',status='ENABLED'))
student=next(row for row in call('/admin/users?platform=student&size=100')['items'] if row['displayName']=='联调学生')
membership=call('/admin/users/'+student['id']+'/memberships',dict(planId=plan['id'],startsAt=datetime.now(timezone.utc).isoformat(),reason='本机隔离协议测试，不收款不购买'))
fixture.write_text(json.dumps(dict(modelId=model['id'],planId=plan['id'],studentId=student['id'],membershipId=membership['id'])))
print('隔离模型、未来生效价格、只写密钥和学生权益已通过正式API准备。')
