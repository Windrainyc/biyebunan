#!/usr/bin/env python3
"""在指定UI隔离库添加独立探针事件，验证运行中outbox→RabbitMQ→inbox→站内通知；不修改既有业务记录。"""
import base64, hashlib, hmac, json, re, shlex, subprocess, time, urllib.request, uuid
from pathlib import Path
root = Path(__file__).resolve().parents[1]
state = json.loads((root/'docs/ui-verification-state.json').read_text())
database = state['database']
assert state['isolatedDatabase'] and re.fullmatch(r'zx_ui_[a-f0-9]+', database)
account = json.loads(Path(state['credentialFile']).read_text())
assert re.fullmatch(r'[0-9]+', account['phone'])
base = ['docker','--context','colima-zhixiang','exec','-i','zhixiang-local-postgres-1','psql','-X','-U','postgres','-d',database,'-v','ON_ERROR_STOP=1','-At']
def sql(text):
    result = subprocess.run(base, input='SET search_path=app;\n'+text, text=True,capture_output=True)
    if result.returncode: raise RuntimeError('隔离探针SQL失败；不输出内部数据')
    return result.stdout.strip().removeprefix('SET\n')
env = {}
for line in (root.parent/'知项平台本地环境/.env.local').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        k,v=line.removeprefix('export ').split('=',1);env[k]=shlex.split(v)[0] if v else ''
credential=base64.b64encode((env['ZX_RABBIT_USER']+':'+env['ZX_RABBIT_PASSWORD']).encode()).decode()
def queue(name):
    req=urllib.request.Request('http://127.0.0.1:25673/api/queues/'+database+'/'+name,headers={'Authorization':'Basic '+credential})
    with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
q=queue('zx.notifications'); assert q['consumers']>=1 and q['durable']
digest=hmac.new((Path(state["credentialFile"]).parent/"auth-hmac").read_text().strip().encode(), ("phone:"+account["phone"]).encode(),hashlib.sha256).hexdigest()
recipient=sql("SELECT id FROM user_account WHERE phone_digest='"+digest+"' AND platform='admin'")
uuid.UUID(recipient)
event=str(uuid.uuid4());aggregate=str(uuid.uuid4())
sql(f"BEGIN; INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES('{event}','{aggregate}','test.NOTIFICATION_BROKER','{{}}'); INSERT INTO notification_delivery_task(event_id,recipient_id,title,body) VALUES('{event}','{recipient}','消息队列接入验收','本机隔离环境消息已通过RabbitMQ到达站内收件箱。'); COMMIT;")
for _ in range(30):
    status=sql(f"SELECT (SELECT status FROM outbox_event WHERE id='{event}')||'|'||(SELECT count(*) FROM inbox_event WHERE event_id='{event}' AND consumer='notification-v1')||'|'||(SELECT count(*) FROM in_app_notification WHERE event_id='{event}')")
    if status=='SENT|1|1':break
    time.sleep(1)
assert status=='SENT|1|1',status
report={'database':database,'eventId':event,'publisherConfirmed':True,'notificationInboxCount':1,'notificationCount':1,'liveConsumers':q['consumers'],'fixtureKind':'独立数据库探针，业务阶段与绑定另由集成测试覆盖','verifiedAt':time.strftime('%Y-%m-%d %H:%M:%S')}
(root/'docs/notification-broker-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
