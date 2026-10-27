#!/usr/bin/env python3
"""创建独立UI验证数据库并通过正式初始化/激活流程准备管理员。仅限本机验证。
凭据输出到0600文件；不写业务库，不发送短信，不调用模型。Ctrl+C关闭自己启动的API。
验证库保留用于复核，清理由记录中的确切数据库名执行，禁止匹配删除其他库。
"""
import os,shlex,subprocess,json,uuid,time,urllib.request,urllib.parse,base64,hmac,hashlib,struct,secrets,socket
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=os.environ.copy()
# 已有API占用端口时拒绝初始化，避免把请求送到其他运行环境。
with socket.socket() as probe:
 if probe.connect_ex(('127.0.0.1',18080)) == 0:
  raise SystemExit('18080已被占用，请先停止本任务拥有的验证API，不接管其他进程')
for line in Path('/Users/chuanchuan/Documents/知项平台本地环境/.env.local').read_text().splitlines():
 if line.strip() and not line.lstrip().startswith('#'):
  key,value=line.removeprefix('export ').split('=',1);env[key]=shlex.split(value)[0] if value else ''
db='zx_ui_'+uuid.uuid4().hex[:12]
directory=root/'secrets'/db;directory.mkdir(mode=0o700)
docker=['docker','--context','colima-zhixiang','exec','zhixiang-local-postgres-1']
subprocess.run(docker+['createdb','-U','postgres','-O',env['ZX_DB_USER'],db],check=True)
subprocess.run(docker+['psql','-U','postgres','-d',db,'-c','CREATE EXTENSION vector'],check=True,stdout=subprocess.DEVNULL)
env.update(ZX_DB_URL=f'jdbc:postgresql://127.0.0.1:15432/{db}?currentSchema=app',ZX_AUTH_HMAC_KEY=secrets.token_hex(32),ZX_STUDENT_ORIGIN='http://127.0.0.1:18770',ZX_ADMIN_ORIGIN='http://127.0.0.1:18771',ZX_PARTNER_ORIGIN='http://127.0.0.1:18772',ZX_SMS_ENABLED='false',ZX_BOOTSTRAP_PHONE='13900009991',ZX_BOOTSTRAP_NAME='本地验证管理员',ZX_BOOTSTRAP_OUTPUT=str(directory/'invitation.json'))
command=['java','-jar','target/platform-0.1.0-SNAPSHOT.jar']
log=open(directory/'runtime.log','w');os.chmod(directory/'runtime.log',0o600)
for profile in ['migrate','bootstrap']:
 subprocess.run(command+['--spring.profiles.active='+profile],cwd=root,env=env,stdout=log,stderr=log,check=True)
process=subprocess.Popen(command+['--spring.profiles.active=api'],cwd=root,env=env,stdout=log,stderr=log)
def post(path,body):
 request=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Origin':env['ZX_ADMIN_ORIGIN'],'Idempotency-Key':str(uuid.uuid4())})
 with urllib.request.urlopen(request,timeout=20)as response:return json.load(response)
try:
 for _ in range(60):
  if process.poll() is not None:raise RuntimeError('验证API提前退出，请检查受保护日志')
  try:
   with urllib.request.urlopen('http://127.0.0.1:18080/actuator/health/readiness',timeout=1)as response:
    if json.load(response)['status']=='UP':break
  except Exception:time.sleep(1)
 else:raise RuntimeError('API启动失败')
 invitation=json.loads((directory/'invitation.json').read_text())
 link=next(v for v in invitation.values()if isinstance(v,str)and '#token='in v)
 token=urllib.parse.parse_qs(urllib.parse.urlparse(link).fragment)['token'][0]
 enrollment=post('/auth/admin/mfa-enrollments',{'invitationToken':token})
 secret=urllib.parse.parse_qs(urllib.parse.urlparse(enrollment['otpAuthUri']).query)['secret'][0]
 raw=base64.b32decode(secret+'='*((8-len(secret)%8)%8));digest=hmac.new(raw,struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();offset=digest[-1]&15;code=str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
 password=secrets.token_urlsafe(24)
 post('/auth/admin/activate',{'invitationToken':token,'password':password,'mfaEnrollmentId':enrollment['enrollmentId'],'mfaCode':code})
 credentials=directory/'credentials.json';credentials.write_text(json.dumps({'phone':env['ZX_BOOTSTRAP_PHONE'],'password':password,'totpSecret':secret,'database':db,'apiPid':process.pid}));credentials.chmod(0o600)
 (root/'docs/ui-verification-state.json').write_text(json.dumps({'database':db,'credentialFile':str(credentials),'apiPid':process.pid,'isolatedDatabase':True,'paidCalls':False},ensure_ascii=False,indent=2))
 print('隔离UI验证API已就绪，管理员通过正式邀请与MFA激活。',flush=True)
 process.wait()
finally:
 process.terminate()
 try:process.wait(timeout=30)
 except subprocess.TimeoutExpired:process.kill()
 log.close()
