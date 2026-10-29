#!/usr/bin/env python3
"""通过正式API幂等创建本机体验基础数据，验证既有账户，并生成权限600的本地账号交付文件。仅隔离UI库可用，不发送真实短信或调用模型。"""
import base64,hashlib,hmac,json,struct,time,urllib.request,urllib.parse,uuid,os
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=json.loads((root/'docs/ui-verification-state.json').read_text());assert s['isolatedDatabase'] and s['database'].startswith('zx_ui_')
p=Path(s['credentialFile']);a=json.loads(p.read_text());student=json.loads((p.parent/'student.json').read_text())
time.sleep(31-time.time()%30)
d=hmac.new(base64.b32decode(a['totpSecret']+'='*((8-len(a['totpSecret'])%8)%8)),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();o=d[-1]&15;otp=str((struct.unpack('>I',d[o:o+4])[0]&0x7fffffff)%1000000).zfill(6)
token=None
def call(path,body=None,platform='admin'):
 headers={'Content-Type':'application/json','Origin':f'http://127.0.0.1:{18781 if platform=="admin" else 18780}','Idempotency-Key':str(uuid.uuid4())}
 if token and platform=='admin':headers['Authorization']='Bearer '+token
 req=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
 with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)
token=call('/auth/admin/login',dict(phone=a['phone'],password=a['password'],mfaCode=otp))['accessToken']
call('/auth/student/login',dict(phone=student['phone'],password=student['password']),'student')
created=[]
def ensure(resource,code,body):
 rows=call('/admin/'+resource+'?q='+urllib.parse.quote(code))['items']
 old=next((r for r in rows if r.get('code')==code),None)
 if old:return old
 obj=call('/admin/'+resource,dict(code=code,**body));created.append(resource);return obj
for n,(name,major) in enumerate([('体验数据·信息工程学院','软件工程'),('体验数据·财经学院','电子商务'),('体验数据·艺术学院','数字媒体')],1):
 school=ensure('schools',f'DEMO-SCHOOL-{n:03}',dict(name=name,region='本地测试',status='ENABLED'))
 ensure('majors',f'DEMO-MAJOR-{n:03}',dict(name=major,schoolId=school['id'],level='BACHELOR',status='ENABLED'))
ensure('forum-boards','DEMO-BOARD-001',dict(name='体验数据·项目交流',requiresReview=True,sortOrder=20,status='ENABLED'))
report={'createdCount':len(created),'createdResources':created,'adminLogin':'PASS','studentLogin':'PASS'}
for resource in ['orders','projects','knowledge','forum-boards','schools','majors']:
 try:report[resource]=call('/admin/'+resource+'?size=1')['total']
 except Exception:report[resource]='未计数'
(root/'docs/demo-seed-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
# 用户请求的本机账户交付文件，仅当前用户可读，不纳入Git。
f=p.parent/'测试访问账号.md'
f.write_text(f'''# 知项本地测试访问账号\n\n管理员：http://127.0.0.1:18781/admin/login\n账号：{a['phone']}\n密码：{a['password']}\n\n管理员启用TOTP二次验证，请在验证器中添加以下密钥，使用每30秒变化的6位验证码。不要关闭MFA。\nTOTP密钥：{a['totpSecret']}\n算法SHA1，6位，周期30秒。\n\n学生：http://127.0.0.1:18780/student/login\n账号：{student['phone']}\n密码：{student['password']}\n\n以上仅为本机隔离测试账户。模型回复和短信来自本机测试服务，无真实付费调用。\n''');f.chmod(0o600)
print(json.dumps(report,ensure_ascii=False));print('accountFile='+str(f))
