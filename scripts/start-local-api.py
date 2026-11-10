#!/usr/bin/env python3
"""在此Mac恢复原隔离库的Vite开发API。默认只检查；不创建数据库、账户或worker。"""
import argparse
import errno
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['check', 'start'], nargs='?', default='check')
parser.add_argument('--backend', type=Path, default=Path('/Users/chuanchuan/Documents/知项平台后端'))
parser.add_argument('--environment', type=Path, default=Path('/Users/chuanchuan/Documents/知项平台本地环境'))
args = parser.parse_args()
root = args.backend.resolve()
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
db = state['database']
if not state.get('isolatedDatabase') or not re.fullmatch(r'zx_ui_[A-Za-z0-9]+', db):
    raise SystemExit('拒绝使用非隔离数据库')
secret_dir = root / 'secrets' / db
jar = root / 'target/platform-0.1.0-SNAPSHOT.jar'
for p in [jar, secret_dir / 'auth-hmac', args.environment / '.env.local']:
    if not p.is_file():
        raise SystemExit('缺少所需本机文件：' + str(p))
with socket.socket() as probe:
    probe.settimeout(2)
    status = probe.connect_ex(('127.0.0.1', 18080))
    if status not in (0, errno.ECONNREFUSED):
        raise SystemExit('无法确认18080端口状态，请允许本机端口检查；不会继续启动')
    occupied = status == 0
if args.action == 'check':
    print(json.dumps({'isolatedDatabase': db, 'port18080Occupied': occupied,
                      'frontendPorts': [18770, 18771, 18772], 'rabbitVhost': db,
                      'paidCalls': False, 'realSms': False}, ensure_ascii=False))
    raise SystemExit(0)
if occupied:
    raise SystemExit('18080已有服务，拒绝重复启动；先核实并停止自己拥有的API进程')
env = os.environ.copy()
for line in (args.environment / '.env.local').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.removeprefix('export ').split('=', 1)
        env[key] = shlex.split(value)[0] if value else ''
# 必须在读取旧.env.local之后覆盖，防止API落回公共消息vhost。
env.update(ZX_DB_URL=f'jdbc:postgresql://127.0.0.1:15432/{db}?currentSchema=app',
           ZX_AUTH_HMAC_KEY=(secret_dir / 'auth-hmac').read_text().strip(),
           ZX_RABBIT_VHOST=db, ZX_PORT='18080', ZX_STUDENT_ORIGIN='http://127.0.0.1:18770',
           ZX_ADMIN_ORIGIN='http://127.0.0.1:18771', ZX_PARTNER_ORIGIN='http://127.0.0.1:18772',
           ZX_SMS_ENABLED='false', ZX_MODEL_CALLS_ENABLED='false',
           ZX_MODEL_ALLOWED_HOSTS='', ZX_OTLP_ENABLED='false')
os.umask(0o077)
runtime = secret_dir / 'local-dev-handoff'
runtime.mkdir(mode=0o700, exist_ok=True)
snapshot = runtime / ('api-' + uuid.uuid4().hex + '.jar')
shutil.copyfile(jar, snapshot)
command = ['java', '-jar', str(snapshot)]
process = None
try:
    with (runtime / 'api.log').open('w') as log:
        subprocess.run(command + ['--spring.profiles.active=migrate'], cwd=root, env=env,
                       stdout=log, stderr=log, check=True)
        process = subprocess.Popen(command + ['--spring.profiles.active=api'], cwd=root,
                                   env=env, stdout=log, stderr=log)
        (runtime / 'api-state.json').write_text(json.dumps({'pid': process.pid, 'database': db,
                                                         'vhost': db, 'jar': str(snapshot)}))
        print('已创建隔离API进程，PID=' + str(process.pid) + '；以readiness接口确认就绪。', flush=True)
        result = process.wait()
        if result:
            raise SystemExit(result)
except KeyboardInterrupt:
    pass
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    snapshot.unlink(missing_ok=True)
