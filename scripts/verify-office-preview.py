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
        with urllib.request.urlopen(req,timeout=120) as res:
            assert res.status==expected,(path,res.status,expected)
            data=res.read();return json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        if e.code==expected: return {'status':e.code}
        raise RuntimeError(f'{path}: HTTP {e.code}') from None

token=call('/auth/admin/login',dict(phone=account['phone'],password=account['password'],mfaCode=code))['accessToken']
cats=call('/admin/material-categories?q=MATERIAL_VERIFY&size=100')['items']
category=next((x for x in cats if x['code']=='MATERIAL_VERIFY'),None) or call('/admin/material-categories',{'name':'素材功能验收','code':'MATERIAL_VERIFY','status':'ENABLED'},expected=201)

import io, zipfile
buffer=io.BytesIO()
with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED) as archive:
    archive.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    archive.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    archive.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>知项 Office 私有文档预览验收</w:t></w:r></w:p><w:p><w:r><w:t>This is an isolated test document.</w:t></w:r></w:p></w:body></w:document>')
data=buffer.getvalue()
grant=call('/admin/uploads',{'ownerType':'MATERIAL','ownerId':category['id'],'filename':'Office私有预览验收.docx','mimeType':'application/vnd.openxmlformats-officedocument.wordprocessingml.document','size':len(data),'sha256':hashlib.sha256(data).hexdigest()},expected=201)
with urllib.request.urlopen(urllib.request.Request(grant['url'],data=data,headers=grant['headers'],method=grant['method']),timeout=30) as response: assert 200<=response.status<300
asset=call('/admin/uploads/'+grant['uploadId']+'/complete',{},expected=202)
for attempt in range(45):
    asset=call('/admin/assets/'+asset['id'])
    if asset['state'] in ('READY','REJECTED'): break
    time.sleep(1)
assert asset['state']=='READY',asset['state']
result=call('/admin/assets/'+asset['id']+'/preview',{},method='POST')
pdf=base64.b64decode(result['pdfBase64'],validate=True)
assert pdf.startswith(b'%PDF-') and len(pdf)>100
Path('/tmp/zx-office-preview-verified.pdf').write_bytes(pdf)
call('/student/assets/'+asset['id']+'/preview',{},method='POST',expected=403)
report={'database':state['database'],'assetId':asset['id'],'sourceState':asset['state'],'pdfBytes':len(pdf),'checks':['真实DOCX签名上传和扫描','Office转换为PDF','跨端管理员令牌访问学生预览403'],'verifiedAt':time.strftime('%Y-%m-%d %H:%M:%S')}
(ROOT/'docs/office-preview-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
