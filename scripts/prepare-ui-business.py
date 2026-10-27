#!/usr/bin/env python3
"""通过正式管理员API创建隔离联调业务数据；不收款，不调用模型，不改业务库。"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import struct
import time
import urllib.request
import uuid
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory = Path(state['credentialFile']).parent
admin = json.loads(Path(state['credentialFile']).read_text())
# 同一步TOTP不能重用；独立验证脚本等待下一个30秒窗口。
time.sleep(31 - (time.time() % 30))
secret = admin['totpSecret']
digest = hmac.new(base64.b32decode(secret + '=' * ((8-len(secret) % 8) % 8)),
                  struct.pack('>Q', int(time.time()) // 30), hashlib.sha1).digest()
offset = digest[-1] & 15
code = str((struct.unpack('>I', digest[offset:offset+4])[0] & 0x7fffffff) % 1000000).zfill(6)
token = None


def call(path, body=None, version=None):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4()),
               'Origin': 'http://127.0.0.1:18771'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if version is not None:
        headers['If-Match'] = '"' + str(version) + '"'
    req = urllib.request.Request('http://127.0.0.1:18080/api/v1' + path,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


token = call('/auth/admin/login', dict(phone=admin['phone'], password=admin['password'], mfaCode=code))['accessToken']
school = next((r for r in call('/admin/schools?q=UI-SCHOOL-001')['items'] if r['code'] == 'UI-SCHOOL-001'), None) or call('/admin/schools', dict(name='联调验证学院', code='UI-SCHOOL-001', region='本地验证', status='ENABLED'))
major = next((r for r in call('/admin/majors?q=UI-MAJOR-001')['items'] if r['code'] == 'UI-MAJOR-001'), None) or call('/admin/majors', dict(name='软件工程', code='UI-MAJOR-001', schoolId=school['id'], level='BACHELOR', status='ENABLED'))
partners = call('/admin/users?platform=partner&size=100')['items']
partner = next(row for row in partners if row['displayName'] == '联调项目中介')
order = call('/admin/orders', dict(title='本地联调软件项目', orderNo='UI-ORDER-001', studentPhone='13900009992',
                                  partnerId=partner['id'], amount='0.00', currency='CNY', packageCode='UI-TEST',
                                  source='MANUAL', schoolId=school['id'], majorId=major['id']))
order = call('/admin/orders/' + order['id'] + '/transition', dict(action='CONFIRM', reason='隔离环境人工核实测试，不代表收款'), order['version'])
project = call('/admin/projects', dict(orderId=order['id'], title='本地联调软件项目', requirements='仅供本机验证三端归属与页面操作', deliveryDate='2026-12-31'))
board = call('/admin/forum-boards', dict(name='联调交流区', code='UI-BOARD-001', requiresReview=True, sortOrder=1, status='ENABLED'))
(directory / 'business.json').write_text(json.dumps(dict(orderId=order['id'], projectId=project['id'], boardId=board['id']), ensure_ascii=False))
print('隔离环境院校、专业、已确认未收款订单、项目和需审核板块已通过正式API创建。')

store = call('/admin/storages', dict(name='本地联调私有文件', provider='S3_COMPATIBLE', bucket='zx-project-private', region='us-east-1', endpoint='http://127.0.0.1:19000', credentialRef='local-minio', access='PRIVATE', status='ENABLED'))
(directory / 'runtime-overrides.json').write_text(json.dumps({'ZX_UPLOAD_STORAGE_ID': store['id']}))
print('已保存当前隔离库的上传配置ID，恢复API后生效。')
