#!/usr/bin/env python3
"""用已构建制品的锁定依赖运行JavaDoc严格校验，不额外下载构建插件。
先执行run-local.py test；输出位于target/apidocs，警告视为失败。
"""
from pathlib import Path
import subprocess
import zipfile
root = Path(__file__).resolve().parents[1]
lib = root/'target/javadoc-lib'
lib.mkdir(exist_ok=True)
with zipfile.ZipFile(root/'target/platform-0.1.0-SNAPSHOT.jar') as archive:
    dependencies = []
    for name in archive.namelist():
        if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
            dependency = lib/Path(name).name
            dependency.write_bytes(archive.read(name))
            dependencies.append(str(dependency))
import os
result = subprocess.run(['javadoc','-quiet','-encoding','UTF-8','-Xdoclint:all','-Werror','-classpath',os.pathsep.join(dependencies),'-sourcepath',str(root/'src/main/java'),'-subpackages','cn.zhixiang.platform','-d',str(root/'target/apidocs')])
raise SystemExit(result.returncode)
