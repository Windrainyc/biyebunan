#!/usr/bin/env python3
"""创建独立UI验证数据库并通过正式初始化/激活流程准备管理员。仅限本机验证。
凭据输出到0600文件；不写业务库，不发送短信，默认不调用模型，显式--test-model仅连接本机隔离协议服务。Ctrl+C关闭自己启动的API。
验证库保留用于复核，清理由记录中的确切数据库名执行，禁止匹配删除其他库。
"""
import os,shlex,subprocess,json,uuid,time,urllib.request,urllib.parse,base64,hmac,hashlib,struct,secrets,socket,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
os.umask(0o077)
if set(sys.argv[1:]) - {'--test-sms','--resume','--test-model','--container-frontends','--office'}:raise SystemExit('仅支持--test-sms、--test-model、--container-frontends、--office和--resume')
env=os.environ.copy()
if '--office' in sys.argv:
 token_path=Path(__file__).resolve().parents[1]/'secrets/office-preview-token'
 if not token_path.is_file(): raise SystemExit('请先启动独立Office转换服务')
 env.update(ZX_DOCUMENT_PREVIEW_ENDPOINT='http://127.0.0.1:18790',ZX_DOCUMENT_PREVIEW_TOKEN_FILE=str(token_path))

# 已有API占用端口时拒绝初始化，避免把请求送到其他运行环境。
with socket.socket() as probe:
 if probe.connect_ex(('127.0.0.1',18080)) == 0:
  raise SystemExit('18080已被占用，请先停止本任务拥有的验证API，不接管其他进程')
for line in Path('/Users/chuanchuan/Documents/知项平台本地环境/.env.local').read_text().splitlines():
 if line.strip() and not line.lstrip().startswith('#'):
  key,value=line.removeprefix('export ').split('=',1);env[key]=shlex.split(value)[0] if value else ''
resume='--resume' in sys.argv
db=json.loads((root/'docs/ui-verification-state.json').read_text())['database'] if resume else 'zx_ui_'+uuid.uuid4().hex[:12]
if not db.startswith('zx_ui_') or not db.removeprefix('zx_ui_').isalnum():raise SystemExit('拒绝非隔离数据库')
directory=root/'secrets'/db;directory.mkdir(mode=0o700,exist_ok=resume)
docker=['docker','--context','colima-zhixiang','exec','zhixiang-local-postgres-1']
if not resume:
 subprocess.run(docker+['createdb','-U','postgres','-O',env['ZX_DB_USER'],db],check=True)
 subprocess.run(docker+['psql','-U','postgres','-d',db,'-c','CREATE EXTENSION vector'],check=True,stdout=subprocess.DEVNULL)
hmac_file=directory/'auth-hmac'
if resume and not hmac_file.exists():raise SystemExit('旧验证库缺少原始HMAC密钥，拒绝生成替代密钥；须新建隔离验证库')
if not hmac_file.exists():hmac_file.write_text(secrets.token_hex(32))
hmac_file.chmod(0o600)
env.update(ZX_DB_URL=f'jdbc:postgresql://127.0.0.1:15432/{db}?currentSchema=app',ZX_AUTH_HMAC_KEY=hmac_file.read_text().strip(),ZX_STUDENT_ORIGIN='http://127.0.0.1:18770',ZX_ADMIN_ORIGIN='http://127.0.0.1:18771',ZX_PARTNER_ORIGIN='http://127.0.0.1:18772',ZX_SMS_ENABLED='false',ZX_BOOTSTRAP_PHONE='13900009991',ZX_BOOTSTRAP_NAME='本地验证管理员',ZX_BOOTSTRAP_OUTPUT=str(directory/'invitation.json'))
# 容器与开发入口择一允许，不使用通配Origin或信任代理伪造来源。
if '--container-frontends' in sys.argv:
 env.update(ZX_STUDENT_ORIGIN='http://127.0.0.1:18780',ZX_ADMIN_ORIGIN='http://127.0.0.1:18781',ZX_PARTNER_ORIGIN='http://127.0.0.1:18782')
overrides=directory/'runtime-overrides.json'
if overrides.exists():
 values=json.loads(overrides.read_text())
 if set(values)-{'ZX_UPLOAD_STORAGE_ID'}:raise SystemExit('不支持的运行覆盖配置')
 env.update(values)
env.update(ZX_MODEL_CALLS_ENABLED='false',ZX_MODEL_ALLOWED_HOSTS='')
sms_fixture=None
java_options=[]
if '--test-sms' in sys.argv:
 from ui_sms_fixture import SmsFixture
 sms_fixture=SmsFixture(directory)
 env.update(ZX_SMS_ENABLED='true',ZX_SMS_ENDPOINT='https://localhost:18443/messages',ZX_SMS_KEY=sms_fixture.key,ZX_TERMS_VERSION='ui-test-nonbinding')
 java_options=[f'-Djavax.net.ssl.trustStore={sms_fixture.truststore}','-Djavax.net.ssl.trustStorePassword=changeit']
if '--test-model' in sys.argv:
 if not sms_fixture:raise SystemExit('--test-model必须同时指定--test-sms以加载隔离TLS信任库')
 env.update(ZX_MODEL_CALLS_ENABLED='true',ZX_MODEL_ALLOWED_HOSTS='localhost')
# 每个验证进程使用独立构建快照，避免后续Maven打包破坏正在运行的懒加载类。
import shutil
runtime_jar=directory/('api-runtime-'+uuid.uuid4().hex+'.jar')
shutil.copyfile(root/'target/platform-0.1.0-SNAPSHOT.jar',runtime_jar)
command=['java',*java_options,'-jar',str(runtime_jar)]
log=open(directory/'runtime.log','w');os.chmod(directory/'runtime.log',0o600)
# 恢复已有环境也必须先迁移；只跳过一次性管理员初始化。迁移失败禁止启动新版本。
for profile in (['migrate'] if resume else ['migrate','bootstrap']):
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
 credentials=directory/'credentials.json'
 if not resume:
  invitation=json.loads((directory/'invitation.json').read_text())
  link=next(v for v in invitation.values()if isinstance(v,str)and '#token='in v)
  token=urllib.parse.parse_qs(urllib.parse.urlparse(link).fragment)['token'][0]
  enrollment=post('/auth/admin/mfa-enrollments',{'invitationToken':token})
  secret=urllib.parse.parse_qs(urllib.parse.urlparse(enrollment['otpAuthUri']).query)['secret'][0]
  raw=base64.b32decode(secret+'='*((8-len(secret)%8)%8));digest=hmac.new(raw,struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();offset=digest[-1]&15;code=str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
  password=secrets.token_urlsafe(24)
  post('/auth/admin/activate',{'invitationToken':token,'password':password,'mfaEnrollmentId':enrollment['enrollmentId'],'mfaCode':code})
  credentials=directory/'credentials.json';credentials.write_text(json.dumps({'phone':env['ZX_BOOTSTRAP_PHONE'],'password':password,'totpSecret':secret,'database':db,'apiPid':process.pid}));credentials.chmod(0o600)
 (root/'docs/ui-verification-state.json').write_text(json.dumps({'database':db,'credentialFile':str(credentials),'apiPid':process.pid,'isolatedDatabase':True,'paidCalls':False,'smsFixture':bool(sms_fixture),'hmacPersisted':True,'modelFixture':'--test-model' in sys.argv,'containerFrontends':'--container-frontends' in sys.argv},ensure_ascii=False,indent=2))
 print('隔离UI验证API已恢复，保留既有账户与持久密钥。' if resume else '隔离UI验证API已就绪，管理员通过正式邀请与MFA激活。',flush=True)
 process.wait()
finally:
 process.terminate()
 try:process.wait(timeout=30)
 except subprocess.TimeoutExpired:
  process.kill()
  process.wait()
 runtime_jar.unlink(missing_ok=True)
 log.close()
 if sms_fixture:sms_fixture.close()
