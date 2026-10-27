#!/usr/bin/env python3
"""加载受保护本地变量并启动应用；凭证不输出、不写入命令行参数。"""
import os
import pathlib
import shlex
import sys
root = pathlib.Path(__file__).resolve().parents[1]
env = os.environ.copy()
source = pathlib.Path('/Users/chuanchuan/Documents/知项平台本地环境/.env.local')
for line in source.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        name, value = line.removeprefix('export ').split('=', 1)
        env[name] = shlex.split(value)[0] if value else ''
secret = root / 'secrets' / 'auth-hmac'
if not secret.exists():
    import secrets
    secret.parent.mkdir(mode=0o700, exist_ok=True)
    fd = os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f: f.write(secrets.token_hex(32))
env['ZX_AUTH_HMAC_KEY'] = secret.read_text().strip()
profile = sys.argv[1] if len(sys.argv) > 1 else 'api'
if profile == 'test':
    import subprocess
    subprocess.run([sys.executable, str(root / 'scripts/format-java.py')], check=True)
    subprocess.run([sys.executable, str(root / 'scripts/check-java-layout.py')], check=True)
    env['ZX_INTEGRATION'] = 'true'
    env['ZX_TEST_ADMIN_PASSWORD'] = (source.parent / 'secrets/postgres_admin_password').read_text().strip()
    os.chdir(root)
    os.execvpe('mvn', ['mvn', '-s', 'scripts/maven-settings.xml', '-B', '-ntp', 'verify'], env)
if profile not in ('api', 'worker', 'migrate', 'bootstrap', 'replay-dead'):
    raise SystemExit('仅允许 api、worker、migrate、bootstrap、replay-dead')
os.chdir(root)
os.execvpe('java', ['java', '-jar', 'target/platform-0.1.0-SNAPSHOT.jar', '--spring.profiles.active='+profile], env)
