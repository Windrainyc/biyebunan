#!/usr/bin/env python3
"""在既有隔离UI库通过正式API创建仅有billing:adjudicate裁决权限的测试管理员。
凭据写入0600文件，不打印、不发送真实短信，不修改生产账户。
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import struct
import time
import urllib.parse
import urllib.request
import uuid

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
if not state.get('isolatedDatabase') or not state['database'].startswith('zx_ui_'):
    raise SystemExit('只允许隔离验证库')
directory = Path(state['credentialFile']).parent
output = directory / 'reviewer.json'
if output.exists():
    print('隔离复核账户凭据已存在，保留原账户')
    raise SystemExit(0)
admin = json.loads(Path(state['credentialFile']).read_text())

def otp(secret):
    digest = hmac.new(base64.b32decode(secret + '=' * ((8-len(secret) % 8) % 8)), struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return str((struct.unpack('>I', digest[offset:offset+4])[0] & 0x7fffffff) % 1000000).zfill(6)

def call(path, body=None, token=None):
    headers = {'Content-Type':'application/json', 'Idempotency-Key':str(uuid.uuid4()), 'Origin':'http://127.0.0.1:18781'}
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request('http://127.0.0.1:18080/api/v1'+path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)

# 使用新TOTP时间窗，避免与管理员上一登录共用已消费的验证码。
time.sleep(31-time.time()%30)
session = call('/auth/admin/login', {'phone':admin['phone'],'password':admin['password'],'mfaCode':otp(admin['totpSecret'])})
token = session['accessToken']
roles = call('/admin/roles?q=UI_REVIEWER&size=100',token=token)['items']
role = next((r for r in roles if r['code']=='UI_REVIEWER'), None)
if role is None:
    role = call('/admin/roles', {'name':'隔离账务复核员','code':'UI_REVIEWER','platform':'admin','dataScope':'ALL','permissionCodes':['billing:adjudicate'],'status':'ENABLED'}, token)
users = call('/admin/users?q=13900009995&size=100',token=token)['items']
user = next((u for u in users if u['displayName']=='隔离账务复核员'), None)
if user is None:
    user = call('/admin/users',{'phone':'13900009995','displayName':'隔离账务复核员','platform':'admin','roleIds':[role['id']]},token)
invite = call('/admin/users/'+user['id']+'/invitations',{'expiresInHours':1},token)
invitation = urllib.parse.parse_qs(urllib.parse.urlparse(invite['activationUrl']).fragment)['token'][0]
enrollment = call('/auth/admin/mfa-enrollments',{'invitationToken':invitation})
secret = urllib.parse.parse_qs(urllib.parse.urlparse(enrollment['otpAuthUri']).query)['secret'][0]
password = secrets.token_urlsafe(24)
call('/auth/admin/activate', {'invitationToken':invitation,'password':password,'mfaEnrollmentId':enrollment['enrollmentId'],'mfaCode':otp(secret)})
output.write_text(json.dumps({'phone':'13900009995','password':password,'totpSecret':secret,'id':user['id'],'database':state['database']}))
output.chmod(0o600)
print('隔离复核管理员已通过邀请/MFA激活，仅具备billing:adjudicate裁决权限')
