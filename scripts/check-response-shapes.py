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
    'operations/dto/StatisticsResponse.java': 'Statistics',
    'operations/dto/AuditResponse.java': 'Audit',
    'operations/dto/ProbeResponse.java': 'Health',
    'forum/dto/CommentDtos.java': 'Comment',
    'forum/dto/ReportDtos.java': 'Report',
    'forum/dto/PostDtos.java': 'Post',
    'notification/dto/NotificationResponse.java': 'Notification',
    'notification/dto/TemplateDtos.java': 'NotificationTemplate',
    'forum/dto/BoardDtos.java': 'ForumBoard',
    'identity/dto/PartnerStudentResponse.java': 'StudentSummary',
    'billing/dto/AdjustmentDtos.java': 'Adjustment',
    'billing/dto/UsageResponse.java': 'UsageEntry',
    'chat/dto/AvailableModelResponse.java': 'AvailableModel',
    'chat/dto/RunDtos.java': 'Run',
    'chat/dto/MessageResponse.java': 'Message',
    'chat/dto/ConversationDtos.java': 'Conversation',
    'billing/dto/QuotaResponse.java': 'Quota',
    'asset/dto/AssetDtos.java': 'Asset',
    'knowledge/dto/ContentDtos.java': 'Content',
    'operations/dto/JobDtos.java': 'Job',
    'project/dto/DeliveryDtos.java': 'DeliveryVersion',
    'model/dto/ModelDtos.java': 'Model',
    'model/dto/PriceDtos.java': 'ModelPrice',
    'model/dto/KeyDtos.java': 'SecretMetadata',
    'configuration/dto/StorageDtos.java': 'Storage',
    'configuration/dto/DomainDtos.java': 'Domain',
    'order/dto/OrderDtos.java': 'Order',
    'project/dto/ProjectDtos.java': 'Project',
    'project/dto/MilestoneDtos.java': 'Milestone',
}
errors = []
for file, schema in bindings.items():
    source = (root / 'src/main/java/cn/zhixiang/platform' / file).read_text()
    record_name = Path(file).stem if Path(file).stem.endswith('Response') else 'View'
    match = re.search(r'public record ' + record_name + r'\(([^()]*)\)', source, re.S)
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
