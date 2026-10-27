#!/usr/bin/env python3
"""检查Java包与目录对应及职责边界，避免重新混放Controller、Service、Mapper和实体。"""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1] / 'src/main/java'
errors = []
for path in root.rglob('*.java'):
    source = path.read_text()
    package = re.search(r'^package ([\w.]+);', source, re.M)
    if not package or package[1].replace('.', '/') != str(path.parent.relative_to(root)):
        errors.append(f'{path.name}: 包名与目录不一致')
    expected = next((layer for suffix, layer in [('Controller', 'controller'), ('Mapper', 'mapper'), ('Service', 'service'), ('Dtos', 'dto'), ('Entity', 'entity')] if path.stem.endswith(suffix)), None)
    if expected and path.parent.name != expected:
        errors.append(f'{path.name}: 应在{expected}包')
    if path.parent.name == 'controller' and re.search(r'^import .*\.mapper\.', source, re.M):
        errors.append(f'{path.name}: Controller不能依赖Mapper')
    if path.parent.name in ('controller', 'service', 'mapper') and re.search(r'\bpublic record\b|^\s+record\s+', source, re.M):
        errors.append(f'{path.name}: 公共记录应移至dto或entity包')
if errors:
    raise SystemExit('\n'.join(errors))
print('Java目录与分层检查通过')
