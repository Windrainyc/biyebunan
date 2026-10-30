#!/usr/bin/env python3
"""向现有本机隔离API导入官方教育字典；代码去重、保留既有值、逐条持久化报告，不输出凭据。"""
import base64, hashlib, hmac, json, struct, time, urllib.request, urllib.parse, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
state=json.loads((ROOT/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
account=json.loads(Path(state['credentialFile']).read_text())
secret=base64.b32decode(account['totpSecret']+'='*((8-len(account['totpSecret'])%8)%8))
digest=hmac.new(secret,struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();offset=digest[-1]&15
code=str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
token=None
def call(path,body=None):
 headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:18781','Idempotency-Key':str(uuid.uuid4())}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
 with urllib.request.urlopen(req,timeout=30) as res:return json.load(res)
token=call('/auth/admin/login',dict(phone=account['phone'],password=account['password'],mfaCode=code))['accessToken']
report={'database':state['database'],'sources':'data/education/README.md','resources':{}}
for resource,filename in [('schools','schools-2026.json'),('majors','computer-majors-2026.json')]:
 existing={};page=1
 while True:
  result=call(f'/admin/{resource}?page={page}&size=100')
  for row in result['items']:
   if resource=='schools' or row.get('schoolId') is None:existing[row['code']]=row
  if page*100>=result['total']:break
  page+=1
 counts={'created':0,'existing':0,'conflicts':[]};report['resources'][resource]=counts
 for row in json.loads((ROOT/'data/education'/filename).read_text()):
  if row['code'] in existing:
   old=existing[row['code']]
   if old['name']!=row['name']:counts['conflicts'].append({'code':row['code'],'existing':old['name'],'source':row['name']})
   counts['existing']+=1;continue
  body={k:v for k,v in row.items() if k!='educationLevel'}
  call('/admin/'+resource,body);counts['created']+=1
  (ROOT/'docs/national-education-import.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 report['resources'][resource]['totalAfter']=call('/admin/'+resource+'?size=1')['total']
(ROOT/'docs/national-education-import.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
