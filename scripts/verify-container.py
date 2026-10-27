#!/usr/bin/env python3
"""在既有本地中间件网络验证API镜像，结束后仅删除本脚本创建的容器。
预先执行run-local.py migrate与docker build。秘密仅通过0600临时env文件传递，不打印。
不测试真实云供应商、生产集群或故障转移。
"""
import json
import http.client
import os
from pathlib import Path
import shlex
import socket
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
import uuid
root = Path(__file__).resolve().parents[1]
source = Path('/Users/chuanchuan/Documents/知项平台本地环境/.env.local')
loaded = {}
for line in source.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.removeprefix('export ').split('=', 1)
        loaded[key] = shlex.split(value)[0] if value else ''
names = ['ZX_DB_USER', 'ZX_DB_PASSWORD', 'ZX_REDIS_USER', 'ZX_REDIS_PASSWORD', 'ZX_RABBIT_USER', 'ZX_RABBIT_PASSWORD', 'ZX_RABBIT_VHOST', 'ZX_S3_ACCESS_KEY', 'ZX_S3_SECRET_KEY', 'ZX_S3_REGION']
env = {key: loaded[key] for key in names}
env.update(ZX_S3_ENDPOINT='http://minio:9000', ZX_DB_URL='jdbc:postgresql://postgres:5432/zhixiang?currentSchema=app', ZX_REDIS_HOST='redis', ZX_REDIS_PORT='6379', ZX_RABBIT_HOST='rabbitmq', ZX_RABBIT_PORT='5672', ZX_AUTH_HMAC_KEY=(root/'secrets/auth-hmac').read_text().strip(), ZX_SMS_ENABLED='false')
name = 'zx-api-verify-' + uuid.uuid4().hex[:12]
image = 'zhixiang/platform:0.14.0-dev'
command = ['docker', '--context', 'colima-zhixiang']
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
with tempfile.TemporaryDirectory(prefix='zx-container-') as directory:
    config = Path(directory)/'api.env'
    fd = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as writer:
        for key, value in env.items():
            if '\n' in value or '\r' in value: raise ValueError('环境变量不可含换行')
            writer.write(f'{key}={value}\n')
    started = False
    try:
        subprocess.run(command + ['run', '-d', '--name', name, '--network', 'zhixiang-local-middleware', '--env-file', str(config), '--read-only', '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '-p', f'127.0.0.1:{port}:18080', image], check=True, stdout=subprocess.DEVNULL)
        started = True
        base = f'http://127.0.0.1:{port}'
        for _ in range(45):
            try:
                with urllib.request.urlopen(base+'/actuator/health/readiness', timeout=2) as response:
                    if json.load(response)['status'] == 'UP': break
            except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.RemoteDisconnected): pass
            time.sleep(1)
        else: raise RuntimeError('容器未在45秒内就绪；请检查中间件连通性')
        results = {'readiness': 'UP'}
        for endpoint, expected in [('/actuator/health/liveness',200),('/api/v1/auth/me',401)]:
            try:
                with urllib.request.urlopen(base+endpoint, timeout=3) as response: status = response.status
            except urllib.error.HTTPError as error: status = error.code
            if status != expected: raise RuntimeError(f'{endpoint} 状态不符: {status}')
            results[endpoint] = status
        user = subprocess.check_output(command+['exec',name,'id','-u'],text=True).strip()
        if user != '10001': raise RuntimeError('容器未使用预期非root用户')
        results.update(image=image,user=user,readOnlyRoot=True,multiNodeVerified=False)
        (root/'docs/container-verification.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(results,ensure_ascii=False))
    finally:
        if started:
            subprocess.run(command+['stop','--time','35',name],check=False,stdout=subprocess.DEVNULL)
            subprocess.run(command+['rm',name],check=False,stdout=subprocess.DEVNULL)
