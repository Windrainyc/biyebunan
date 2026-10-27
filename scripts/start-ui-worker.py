#!/usr/bin/env python3
"""启动隔离验证库worker，专用RabbitMQ vhost避免消费业务消息。"""
import base64
import json
import os
from pathlib import Path
import shutil
import uuid
import shlex
import subprocess
import sys
import urllib.parse
import urllib.request
root = Path(__file__).resolve().parents[1]
state = json.loads((root / 'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory = Path(state['credentialFile']).parent
existing_state = directory / 'worker-state.json'
if existing_state.exists():
    existing = json.loads(existing_state.read_text())
    current = subprocess.run(['ps', '-p', str(existing['pid']), '-o', 'command='], capture_output=True, text=True)
    if current.returncode == 0 and '--spring.profiles.active=worker' in current.stdout:
        raise SystemExit('当前验证worker仍在运行，请先按记录核对并停止，拒绝重复启动')
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
env.update(ZX_MODEL_CALLS_ENABLED='false', ZX_MODEL_ALLOWED_HOSTS='')
java_options = []
if '--test-model' in sys.argv:
    truststore = directory / 'sms-fixture/truststore.p12'
    if not truststore.exists(): raise SystemExit('缺少隔离TLS信任库')
    env.update(ZX_MODEL_CALLS_ENABLED='true', ZX_MODEL_ALLOWED_HOSTS='localhost')
    java_options = [f'-Djavax.net.ssl.trustStore={truststore}', '-Djavax.net.ssl.trustStorePassword=changeit']
runtime_jar = directory / ('worker-runtime-' + uuid.uuid4().hex + '.jar')
shutil.copyfile(root / 'target/platform-0.1.0-SNAPSHOT.jar', runtime_jar)
with (directory / 'worker.log').open('w') as log:
    process = subprocess.Popen(['java', *java_options, '-jar', str(runtime_jar),
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
            process.wait()
        runtime_jar.unlink(missing_ok=True)
