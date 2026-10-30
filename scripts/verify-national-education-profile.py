#!/usr/bin/env python3
"""在本机隔离UI库验证全国专业可用于个人资料，随后恢复原资料；不得用于生产。"""
import json,urllib.request,urllib.parse,uuid
from pathlib import Path
state=json.loads(Path('docs/ui-verification-state.json').read_text());assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_');c=json.loads((Path(state['credentialFile']).parent/'student.json').read_text());token=None
def call(path,body=None,version=None,method=None):
 headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:18780','Idempotency-Key':str(uuid.uuid4())}
 if token:headers['Authorization']='Bearer '+token
 if version is not None:headers['If-Match']='"'+str(version)+'"'
 req=urllib.request.Request('http://127.0.0.1:18080/api/v1'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 with urllib.request.urlopen(req) as r:return json.load(r)
token=call('/auth/student/login',dict(phone=c['phone'],password=c['password']))['accessToken']
old=call('/student/me');a=call('/student/dictionaries/schools?q='+urllib.parse.quote('北京大学'))['items'];school=next(x for x in a if x['name']=='北京大学');m=call('/student/dictionaries/majors?q=080902&schoolId='+school['id'])['items'];major=next(x for x in m if x['code']=='080902' and x.get('schoolId') is None)
changed=call('/student/me',dict(displayName=old['displayName'],schoolId=school['id'],majorId=major['id']),old['version'],'PUT')
assert changed['majorId']==major['id']
restored=call('/student/me',{k:old[k] for k in ['displayName','schoolId','majorId']},changed['version'],'PUT')
assert restored['schoolId']==old['schoolId'] and restored['majorId']==old['majorId']
Path('docs/national-education-profile-verification.json').write_text(json.dumps({'nationalMajorVisibleWithSchoolFilter':True,'studentProfileIndependentSelection':'PASS','originalProfileRestored':True},indent=2)+'\n')
print('student independent selection and restoration PASS')
