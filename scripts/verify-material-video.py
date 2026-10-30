#!/usr/bin/env python3
"""只对现有隔离UI数据库执行素材上传和知识复用验收；凭据、签名URL不写报告。"""
import base64, hashlib, hmac, json, struct, time, urllib.request, urllib.error, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
state=json.loads((ROOT/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
account=json.loads(Path(state['credentialFile']).read_text())
secret=base64.b32decode(account['totpSecret']+'='*((8-len(account['totpSecret'])%8)%8))
d=hmac.new(secret,struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();o=d[-1]&15
code=str((struct.unpack('>I',d[o:o+4])[0]&0x7fffffff)%1000000).zfill(6)
token=None

def call(path,body=None,method=None,version=None,expected=200):
    headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:18781','Idempotency-Key':str(uuid.uuid4())}
    if token: headers['Authorization']='Bearer '+token
    if version: headers['If-Match']='"'+str(version)+'"'
    req=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as res:
            assert res.status==expected,(path,res.status,expected)
            data=res.read();return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        if e.code==expected: return {'status':e.code}
        raise RuntimeError(f'{path}: HTTP {e.code}') from None

token=call('/auth/admin/login',dict(phone=account['phone'],password=account['password'],mfaCode=code))['accessToken']
cats=call('/admin/material-categories?q=MATERIAL_VERIFY&size=100')['items']
category=next((x for x in cats if x['code']=='MATERIAL_VERIFY'),None) or call('/admin/material-categories',{'name':'素材功能验收','code':'MATERIAL_VERIFY','status':'ENABLED'},expected=201)
assets=[]
import subprocess, tempfile
videoDirectory=tempfile.TemporaryDirectory(prefix='zx-material-video-')
videoPath=Path(videoDirectory.name)/'material-test.mp4'
subprocess.run(['/opt/homebrew/bin/ffmpeg','-v','error','-f','lavfi','-i','color=c=blue:s=160x90:d=1','-c:v','libx264','-pix_fmt','yuv420p',str(videoPath)],check=True)
for name,mime,data in [('素材视频验收.mp4','video/mp4',videoPath.read_bytes())]:
    grant=call('/admin/uploads',{'ownerType':'MATERIAL','ownerId':category['id'],'filename':name,'mimeType':mime,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()},expected=201)
    req=urllib.request.Request(grant['url'],data=data,headers=grant['headers'],method=grant['method'])
    with urllib.request.urlopen(req,timeout=30) as res: assert 200<=res.status<300
    a=call('/admin/uploads/'+grant['uploadId']+'/complete',{},expected=202)
    for attempt in range(40):
        a=call('/admin/assets/'+a['id'])
        if a['state'] in ('READY','REJECTED'):break
        time.sleep(1)
    assert a['state']=='READY',a['state']
    download=call('/admin/assets/'+a['id']+'/download',{},method='POST')
    with urllib.request.urlopen(download['url'],timeout=30) as res: assert hashlib.sha256(res.read()).hexdigest()==hashlib.sha256(data).hexdigest()
    assets.append(a)
videoDirectory.cleanup()
assert call('/admin/materials?kind=VIDEO&state=READY')['total']>=1
report={'database':state['database'],'assetId':assets[0]['id'],'state':assets[0]['state'],'checks':['真实MP4签名上传','worker扫描READY','原件私有下载摘要一致','视频类型筛选可见']}
(ROOT/'docs/material-video-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
