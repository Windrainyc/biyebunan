#!/usr/bin/env python3
"""隔离HTTPS模型协议服务：固定输出与用量仅供UI账务联调，绝不请求外部模型。"""
import hmac,json,os,secrets,ssl,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
os.umask(0o077)
root=Path(__file__).resolve().parents[1]
state=json.loads((root/'docs/ui-verification-state.json').read_text())
assert state['isolatedDatabase'] and state['database'].startswith('zx_ui_')
directory=Path(state['credentialFile']).parent
key_file=directory/'model-fixture-key'
if not key_file.exists():key_file.write_text(secrets.token_urlsafe(32))
key=key_file.read_text().strip()
# 延迟仅供断线回放验证，限制在0至5秒；不影响生产网关。
delay=float(os.environ.get('ZX_UI_MODEL_DELAY_SECONDS','0.15'))
assert 0<=delay<=5
lock=threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        if self.path!='/chat/completions' or not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+key):
            self.send_error(403);return
        try:
            size=int(self.headers.get('Content-Length','0'))
            if not 0<size<=1048576:raise ValueError()
            body=json.loads(self.rfile.read(size))
            run=str(uuid.UUID(self.headers.get('X-Client-Request-Id','')))
            if body.get('model')!='ui-isolated-model' or body.get('stream') is not True:raise ValueError()
            # 仅记录调用次数及run ID；不保存用户文本、上下文、密钥。
            with lock:
                with (directory/'model-fixture-calls.jsonl').open('a') as out:out.write(json.dumps({'runId':run,'time':time.time()})+'\n')
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('x-request-id','ui-'+run);self.end_headers()
            for part in ['【本机协议测试，非真实模型回答】','项目交付需要先通过安全扫描，','再发布不可覆盖的交付版本。','绑定订单的学生可以下载已发布文件。']:
                self.wfile.write(('data: '+json.dumps({'choices':[{'delta':{'content':part}}]},ensure_ascii=False)+'\n\n').encode());self.wfile.flush();time.sleep(delay)
            self.wfile.write(b'data: {"choices":[],"usage":{"prompt_tokens":8,"completion_tokens":12,"total_tokens":20}}\n\ndata: [DONE]\n\n');self.wfile.flush()
        except (ValueError,KeyError,TypeError):self.send_error(400)
        except (BrokenPipeError,ConnectionResetError):pass
server=ThreadingHTTPServer(('127.0.0.1',18444),Handler)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(directory/'sms-fixture/certificate.pem',directory/'sms-fixture/private.pem')
server.socket=context.wrap_socket(server.socket,server_side=True)
(directory/'model-fixture-state.json').write_text(json.dumps({'pid':os.getpid(),'database':state['database'],'port':18444}))
print('本机HTTPS模型协议服务已启动，仅允许隔离测试模型，无外部调用。',flush=True)
try:server.serve_forever()
finally:server.server_close()
