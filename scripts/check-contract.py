#!/usr/bin/env python3
"""校验OpenAPI结构及实测Spring路由/权限映射；不将路由存在误认为业务全部完成。
先运行run-local.py test生成target/implemented-routes.json。
依赖PyYAML和openapi-spec-validator，可使用设计目录tools/.venv。
"""
from pathlib import Path
import json
import re
import yaml
from openapi_spec_validator import validate
root = Path(__file__).resolve().parents[1]
doc = yaml.safe_load((root / 'contract/openapi.yaml').read_text())
validate(doc)
actual = json.loads((root / 'target/implemented-routes.json').read_text())
implemented = {(r['method'].lower(), r['path']): r for r in actual}
failures = []
for (method, path), route in implemented.items():
    spec = doc['paths'].get(path, {}).get(method)
    if spec is None:
        failures.append(f'{method.upper()} {path}: 未定义契约')
        continue
    if path in ('/api/v1/health/live', '/api/v1/health/ready'):
        if spec.get('security') != [] or route['permission']:
            failures.append(f'{method.upper()} {path}: 健康接口必须显式公开且不配置方法权限')
    elif path == '/api/v1/callbacks/oss':
        if spec.get('security') != [] or route['permission']:
            failures.append('OSS回调必须使用独立供应商签名而非用户权限')
    elif not path.startswith('/api/v1/auth/'):
        if spec.get('x-authenticated') and path in ('/api/v1/admin/activity','/api/v1/student/activity','/api/v1/partner/activity') and method == 'post' and route['permission'] == 'isAuthenticated()':
            continue
        if spec.get('x-permissions-all') and route['permission'] == ' and '.join("hasAuthority('%s')" % permission for permission in spec['x-permissions-all']):
            continue
        match = re.fullmatch(r"hasAuthority\('([^']+)'\)", route['permission'])
        if not match or match[1] != spec.get('x-permission'):
            failures.append(f'{method.upper()} {path}: 权限表达式与契约不一致')
lines = ['# 接口实现覆盖矩阵', '', '由真实测试上下文导出路由并与OpenAPI比较。已映射表示已存在业务处理代码，不能替代每个状态、字段和失败流程的验收；未实现接口保持显式列出。', '', '| 方法 | 路径 | 当前状态 |', '|---|---|---|']
count = 0
for path, methods in doc['paths'].items():
    for method in methods:
        if method not in ('get', 'post', 'put', 'patch', 'delete'):
            continue
        count += 1
        state = '已映射，验证范围见开发进度' if (method, path) in implemented else '未实现'
        lines.append(f'| {method.upper()} | `{path}` | {state} |')
lines[2] += f' 当前已映射 {len(implemented)} / {count} 个操作。'
(root / 'docs/接口实现覆盖矩阵.md').write_text('\n'.join(lines) + '\n')
if failures:
    raise SystemExit('\n'.join(failures))
print(f'OpenAPI结构有效；{len(implemented)}/{count} 路由已映射，所有已映射管理端权限与契约一致。')
