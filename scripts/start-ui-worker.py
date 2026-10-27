#!/usr/bin/env python3
"""启动隔离验证库worker，专用RabbitMQ vhost避免消费业务消息。"""
import base64
import json
import os
from pathlib import Path
import shlex
import subprocess
import urllib.parse
import urllib.request
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
env = os.environ.copy()
for line in Path('/Users/chuanchuan/Documents/知项平台本地环境/.env.local').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.removeprefix('export ').split('=', 1)
        env[key] = shlex.split(value)[0] if value else ''
vhost = state['database']
credential = base64.b64encode((env['ZX_RABBIT_USER'] + ':' + env['ZX_RABBIT_PASSWORD']).encode()).decode()


def put(path, body):
    req = urllib.request.Request('http://127.0.0.1:25673/api/' + path, method='PUT',
                                 data=json.dumps(body).encode(), headers={
                                     'Authorization': 'Basic ' + credential,
                                     'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10):
        pass


put('vhosts/' + vhost, {})
put('permissions/' + vhost + '/' + urllib.parse.quote(env['ZX_RABBIT_USER'], safe=''),
    dict(configure='^zx\\..*', write='^zx\\..*', read='^zx\\..*'))
put('exchanges/' + vhost + '/zx.events', dict(type='direct', durable=True, auto_delete=False, internal=False, arguments={}))
directory = Path(state['credentialFile']).parent
hmac_key = directory / 'auth-hmac'
if not hmac_key.is_file():
    raise SystemExit('缺少与API一致的持久认证密钥，拒绝启动worker')
env.update(ZX_OTLP_ENABLED="false", ZX_RABBIT_VHOST=vhost,
           ZX_DB_URL=f'jdbc:postgresql://127.0.0.1:15432/{vhost}?currentSchema=app',
           ZX_AUTH_HMAC_KEY=hmac_key.read_text().strip())
directory = Path(state['credentialFile']).parent
os.umask(0o077)
with (directory / 'worker.log').open('w') as log:
    process = subprocess.Popen(['java', '-jar', 'target/platform-0.1.0-SNAPSHOT.jar',
                                '--spring.profiles.active=worker'], cwd=root, env=env,
                               stdout=log, stderr=log)
    (directory / 'worker-state.json').write_text(json.dumps(dict(pid=process.pid, database=vhost, vhost=vhost)))
    print('隔离worker已创建，专用数据库与RabbitMQ vhost；启动结果须检查受保护日志。', flush=True)
    try:
        process.wait()
    finally:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
