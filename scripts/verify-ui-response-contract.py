#!/usr/bin/env python3
"""对隔离三端实际只读JSON响应执行OpenAPI 3.1校验，不保存正文、令牌或密钥。
用设计工程tools/.venv/bin/python运行；不会发送短信或调用模型。
"""
import base64
import datetime
import hashlib
import hmac
import json
from pathlib import Path
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import yaml
from jsonschema import Draft202012Validator, FormatChecker

root = Path(__file__).resolve().parents[1]
state = json.loads((root/'docs/ui-verification-state.json').read_text())
assert state.get('isolatedDatabase') and state['database'].startswith('zx_ui_'), '仅允许隔离验证库'
directory = Path(state['credentialFile']).parent
contract = yaml.safe_load((root/'contract/openapi.yaml').read_text())
ports = {'admin':18781,'student':18780,'partner':18782} if state.get('containerFrontends') else {'admin':18771,'student':18770,'partner':18772}
base = 'http://127.0.0.1:18080'

def call(path, platform, body=None, token=None, query=None):
    headers = {'Origin':f'http://127.0.0.1:{ports[platform]}','Content-Type':'application/json'}
    if token: headers['Authorization'] = 'Bearer '+token
    url = base + path + ('?'+urllib.parse.urlencode(query) if query else '')
    req = urllib.request.Request(url,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=20) as response:
            return response.status,json.load(response)
    except urllib.error.HTTPError as error:
        return error.code,None

def login(platform):
    credentials = json.loads((Path(state['credentialFile']) if platform=='admin' else directory/f'{platform}.json').read_text())
    body = {k:credentials[k] for k in ['phone','password']}
    if platform=='admin':
        # 等待新窗口，避免与交互登录重复消费同一OTP。
        time.sleep(31-time.time()%30)
        secret=credentials['totpSecret']
        digest=hmac.new(base64.b32decode(secret+'='*((8-len(secret)%8)%8)),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest()
        offset=digest[-1]&15
        body['mfaCode']=str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
    status,data=call('/api/v1/auth/'+platform+'/login',platform,body)
    assert status==200,f'{platform}隔离登录失败HTTP{status}'
    return data['accessToken']

tokens={p:login(p) for p in ports}
results=[]
today=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date().isoformat()
for path, methods in contract['paths'].items():
    parts=path.split('/')
    platform=parts[3] if len(parts)>3 else ''
    if platform not in tokens or '{' in path or 'get' not in methods: continue
    op=methods['get']
    response=op.get('responses',{}).get('200',op.get('responses',{}).get(200,{}))
    schema=response.get('content',{}).get('application/json',{}).get('schema')
    if not schema: continue
    query={}
    for parameter in op.get('parameters',[]):
        if parameter.get('in')!='query': continue
        name=parameter['name']
        if name=='reason': query[name]='隔离响应契约验证，仅核对结构，不输出私聊正文'
        elif name in ('from','to'): query[name]=today
        elif name=='q' and parameter.get('required'): query[name]='项目'
    status,data=call(path,platform,token=tokens[platform],query=query)
    failures=[]
    if status==200:
        validator=Draft202012Validator({**schema,'components':contract['components']},format_checker=FormatChecker())
        for error in validator.iter_errors(data):
            # 不保存error.message/instance，避免失败报告泄露业务正文或凭据。
            failures.append({'path':'/'.join(map(str,error.absolute_path)),'constraint':error.validator})
    else: failures.append({'httpStatus':status})
    results.append({'method':'GET','path':path,'status':status,'failures':failures})
negative_status,_=call('/api/v1/student/knowledge-search','student',token=tokens['student'])
assert negative_status==400, '缺失检索词应返回400'
report={'negativeChecks':{'missingSearchQuery':negative_status},'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'contractVersion':contract['info']['version'],'isolatedDatabase':True,'checks':results,'passed':sum(not r['failures'] for r in results),'total':len(results),'scope':'无路径参数的三端只读JSON响应；不代表所有写操作或浏览器验收'}
(root/'docs/ui-response-contract-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(f"实际响应契约：{report['passed']}/{report['total']}通过")
for r in results:
    if r['failures']: print(r['path'],json.dumps(r['failures'],ensure_ascii=False))
raise SystemExit(0 if report['passed']==report['total'] else 1)
