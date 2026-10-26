#!/usr/bin/env python3
"""静态核对新增配置/订单响应记录字段与契约；不替代HTTP值、空值、错误码验证。
使用设计目录tools/.venv/bin/python执行。只检查显式映射的无注解View记录，
复杂泛型或Jackson注解出现时必须扩展规则，不能静默忽略字段。
"""
from pathlib import Path
import re
import yaml

root = Path(__file__).resolve().parents[1]
schemas = yaml.safe_load((root / 'contract/openapi.yaml').read_text())['components']['schemas']
bindings = {
    'identity/PartnerStudentService.java': 'StudentSummary',
    'billing/AdjustmentDtos.java': 'Adjustment',
    'billing/UsageService.java': 'UsageEntry',
    'chat/AvailableModelService.java': 'AvailableModel',
    'chat/RunDtos.java': 'Run',
    'chat/MessageService.java': 'Message',
    'chat/ConversationDtos.java': 'Conversation',
    'billing/QuotaService.java': 'Quota',
    'asset/AssetDtos.java': 'Asset',
    'knowledge/ContentDtos.java': 'Content',
    'operations/JobDtos.java': 'Job',
    'project/DeliveryDtos.java': 'DeliveryVersion',
    'model/ModelDtos.java': 'Model',
    'model/PriceDtos.java': 'ModelPrice',
    'model/KeyDtos.java': 'SecretMetadata',
    'configuration/StorageDtos.java': 'Storage',
    'configuration/DomainDtos.java': 'Domain',
    'order/OrderDtos.java': 'Order',
    'project/ProjectDtos.java': 'Project',
    'project/MilestoneDtos.java': 'Milestone',
}
errors = []
for file, schema in bindings.items():
    source = (root / 'src/main/java/cn/zhixiang/platform' / file).read_text()
    match = re.search(r'public record View\(([^()]*)\)', source, re.S)
    if match is None or '@' in match[1]:
        errors.append(f'{file}: 不支持的记录形式，需人工核对并扩展检查器')
        continue
    fields = {field.strip().split()[-1] for field in match[1].split(',')}
    expected = set(schemas[schema]['properties'])
    if fields != expected:
        errors.append(f'{schema}: 多出{sorted(fields - expected)}，缺少{sorted(expected - fields)}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'{len(bindings)}种响应DTO静态字段与OpenAPI一致；未验证序列化值和所有业务分支。')
