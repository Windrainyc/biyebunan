#!/usr/bin/env python3
"""通过隔离HTTPS测试短信及正式API准备三端联调账户，禁止指向业务库。"""
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
import urllib.error
import uuid

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
if not state.get('isolatedDatabase') or not state['database'].startswith('zx_ui_'):
    raise SystemExit('仅允许隔离UI验证数据库')
directory = Path(state['credentialFile']).parent
if not (directory / 'sms-fixture/truststore.p12').exists():
    raise SystemExit('请使用--test-sms启动隔离验证环境')
admin = json.loads(Path(state['credentialFile']).read_text())


def call(path, body=None, platform='admin', token=None):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4()),
               'Origin': f'http://127.0.0.1:{dict(student=18770,admin=18771,partner=18772)[platform]}'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request('http://127.0.0.1:18080/api/v1' + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def proof(phone, platform, purpose):
    challenge = call('/auth/challenges', dict(phone=phone, platform=platform, purpose=purpose), platform)
    evidence = json.loads((directory / 'sms-fixture' / (challenge['challengeId'] + '.json')).read_text())
    return dict(challengeId=challenge['challengeId'], code=evidence['code'])


# 避免重复使用管理员激活/其他脚本刚使用的TOTP时间窗口。
time.sleep(31 - (time.time() % 30))
secret = admin['totpSecret']
digest = hmac.new(base64.b32decode(secret + '=' * ((8-len(secret) % 8) % 8)),
                  struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
offset = digest[-1] & 15
code = str((struct.unpack('>I', digest[offset:offset+4])[0] & 0x7fffffff) % 1000000).zfill(6)
session = call('/auth/admin/login', dict(phone=admin['phone'], password=admin['password'], mfaCode=code))
token = session['accessToken']
student_path = directory / 'student.json'
student = json.loads(student_path.read_text()) if student_path.exists() else dict(phone='13900009992', password=secrets.token_urlsafe(24), displayName='联调学生')
student_path.write_text(json.dumps(student, ensure_ascii=False))
existing = call('/admin/users?platform=student&size=100&q=' + urllib.parse.quote(student['displayName']) + '', token=token)['items']
if existing:
    call('/auth/password-reset', {**proof(student['phone'], 'student', 'RESET_PASSWORD'), 'newPassword': student['password']}, 'student')
else:
    try:
        call('/auth/student/register', {**student, **proof(student['phone'], 'student', 'REGISTER'), 'termsVersion': 'ui-test-nonbinding'}, 'student')
    except urllib.error.HTTPError as error:
        if error.code != 409: raise
        call('/auth/password-reset', {**proof(student['phone'], 'student', 'RESET_PASSWORD'), 'newPassword': student['password']}, 'student')
roles = call('/admin/roles?platform=partner&size=100', token=token)['items']
role = next(row for row in roles if row['platform'] == 'partner' and row['status'] == 'ENABLED')
partner_path = directory / 'partner.json'
partner = json.loads(partner_path.read_text()) if partner_path.exists() else dict(phone='13900009993', password=secrets.token_urlsafe(24), displayName='联调项目中介')
partner_path.write_text(json.dumps(partner, ensure_ascii=False))
user = call('/admin/users', dict(phone=partner['phone'], displayName=partner['displayName'],
                                platform='partner', roleIds=[role['id']]), token=token)
invitation = call('/admin/users/' + user['id'] + '/invitations', dict(expiresInHours=1), token=token)
invite = urllib.parse.parse_qs(urllib.parse.urlparse(invitation['activationUrl']).fragment)['token'][0]
call('/auth/partner/activate', {**partner, **proof(partner['phone'], 'partner', 'ACTIVATE'),
                               'termsVersion': 'ui-test-nonbinding', 'invitationToken': invite}, 'partner')
for name, value in [('student', student), ('partner', partner)]:
    target = directory / (name + '.json')
    target.write_text(json.dumps(value, ensure_ascii=False))
    target.chmod(0o600)
print('学生注册与中介邀请激活通过正式API完成；验证码仅来自本机HTTPS测试服务，无真实短信。')
