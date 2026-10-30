#!/usr/bin/env python3
"""构建并启动独立Office转换器；凭据只保存在secrets，固定本机端口，不触碰其他服务。"""
from pathlib import Path
import os
import secrets
import subprocess
import time
import urllib.request
import urllib.error
root = Path(__file__).resolve().parents[1]
os.umask(0o077)
directory = root / 'secrets'
directory.mkdir(exist_ok=True, mode=0o700)
token_file = directory / 'office-preview-token'
if not token_file.exists():
    with token_file.open('x') as file:
        file.write(secrets.token_hex(32))
token = token_file.read_text().strip()
if len(token) < 32 or not all(c in '0123456789abcdef' for c in token):
    raise SystemExit('转换服务凭据格式无效')
env_file = directory / 'office-preview.env'
env_file.write_text('ZX_CONVERSION_TOKEN=' + token + '\n')
os.chmod(env_file, 0o600)
subprocess.run(['mvn','-s',str(root/'scripts/maven-settings.xml'),'-f',str(root/'deploy/kkfileview/pom.xml'),'-B','-ntp','package'],check=True)
subprocess.run(['docker','--context','colima-zhixiang','compose','-p','zhixiang-office','-f',str(root/'deploy/kkfileview/compose.yaml'),'up','-d','--build'],check=True)
# Office容器更新后重新解析其内部地址，避免网关保留旧IP。
subprocess.run(['docker','--context','colima-zhixiang','compose','-p','zhixiang-office','-f',str(root/'deploy/kkfileview/compose.yaml'),'restart','gateway'],check=True)
# 以鉴权拒绝验证真实应用入口就绪，不能只用容器运行状态冒充可用。
for _ in range(30):
    try:
        req = urllib.request.Request('http://127.0.0.1:18790/internal/convert', data=b'probe', method='POST')
        urllib.request.urlopen(req, timeout=2).close()
    except urllib.error.HTTPError as error:
        if error.code == 403:
            break
    except OSError:
        pass
    time.sleep(1)
else:
    raise SystemExit('Office入口未就绪，请检查转换器日志')
print('Office转换器已启动，需继续验证真实文档转换；仅本机18790访问。')
