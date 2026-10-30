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
for name,mime,data in [('素材封面验收.png','image/png',base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j9uoAAAAASUVORK5CYII=')),('素材文档验收.txt','text/plain','这是隔离环境的素材库上传与知识关联验收文件。'.encode())]:
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
contentCategory=call('/admin/categories?status=ENABLED&size=1')['items'][0]
body={'title':'素材库关联验收文档','categoryId':contentCategory['id'],'tagIds':[],'type':'DOCUMENT','assetId':assets[1]['id'],'coverAssetId':assets[0]['id'],'audience':'PUBLIC'}
content=call('/admin/knowledge',body,expected=201)
assert content['assetId']==assets[1]['id'] and content['coverAssetId']==assets[0]['id']
for a in assets:call('/admin/assets/'+a['id'],method='DELETE',version=a['version'],expected=409)
for action in ['submit-review','publish']:
    content=call('/admin/knowledge/'+content['id']+'/'+action,{'reason':'隔离素材关联验收'},version=content['version'])
assert content['state']=='PUBLISHED'
report={'database':state['database'],'categoryId':category['id'],'assets':[{'id':a['id'],'filename':a['filename'],'state':a['state']} for a in assets],'contentId':content['id'],'checks':['真实签名PUT上传图片和文档','worker扫描READY','私有下载摘要一致','新增知识关联共享正文和封面','两个引用资产删除409','审核发布成功']}
(ROOT/'docs/material-library-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
