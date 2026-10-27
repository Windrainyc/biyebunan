#!/usr/bin/env python3
"""统一Java源码格式。默认检查；传--write原地修复。使用锁定版本，缺失依赖时提示安装。"""
from pathlib import Path
import subprocess
import sys
import os
root = Path(__file__).resolve().parents[1]
repository = Path.home() / '.m2/repository'
artifacts = [
    ('com.google.googlejavaformat', 'google-java-format', '1.22.0'),
    ('com.google.guava', 'guava', '33.3.1-jre'),
    ('com.google.guava', 'failureaccess', '1.0.2'),
]
jars = []
for group, artifact, version in artifacts:
    jar = repository / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.jar'
    if not jar.exists():
        raise SystemExit(f'缺少格式工具依赖，请执行 mvn dependency:get -Dartifact={group}:{artifact}:{version}')
    jars.append(str(jar))
exports = ['api', 'file', 'parser', 'tree', 'util', 'code']
command = ['java'] + [f'--add-exports=jdk.compiler/com.sun.tools.javac.{part}=ALL-UNNAMED' for part in exports]
command += ['-cp', os.pathsep.join(jars), 'com.google.googlejavaformat.java.Main', '--aosp']
command += ['--replace'] if '--write' in sys.argv else ['--dry-run', '--set-exit-if-changed']
command += [str(path) for path in sorted((root / 'src').rglob('*.java'))]
raise SystemExit(subprocess.run(command, cwd=root).returncode)
