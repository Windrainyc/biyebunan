"""仅供隔离UI联调的HTTPS短信协议服务；不连接任何短信供应商。"""
import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import secrets
import ssl
import subprocess
import threading
import uuid


class SmsFixture:
    """严格限制测试号码，将验证码保存在受保护目录供测试驱动读取。"""

    def __init__(self, directory: Path, port=18443):
        self.directory = directory / 'sms-fixture'
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.key = secrets.token_urlsafe(32)
        self.port = port
        cert = self.directory / 'certificate.pem'
        private = self.directory / 'private.pem'
        config = self.directory / 'openssl.cnf'
        config.write_text('[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n'
                          '[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n'
                          'basicConstraints=critical,CA:TRUE\nkeyUsage=critical,digitalSignature,keyCertSign\n')
        self.truststore = self.directory / 'truststore.p12'
        if not (cert.exists() and private.exists() and self.truststore.exists()):
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                            '-keyout', str(private), '-out', str(cert), '-days', '2',
                            '-config', str(config)], check=True, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
            private.chmod(0o600)
            self.truststore = self.directory / 'truststore.p12'
            # 此store仅含本次本地公钥证书；密码不是业务凭据，不导入系统信任库。
            subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'local-ui-fixture',
                            '-file', str(cert), '-keystore', str(self.truststore), '-storetype',
                            'PKCS12', '-storepass', 'changeit'], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        fixture = self
        self.lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                if self.path != '/messages' or not hmac.compare_digest(
                    self.headers.get('Authorization', ''), 'Bearer ' + fixture.key
                ):
                    self.send_error(403)
                    return
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 4096:
                        raise ValueError()
                    body = json.loads(self.rfile.read(size))
                    challenge = str(uuid.UUID(body['challengeId']))
                    phone = str(body['phone'])
                    if not (len(phone) == 11 and phone.startswith('1390000999') and phone.isdigit()):
                        raise ValueError()
                    if body['purpose'] not in ('REGISTER', 'RESET_PASSWORD', 'ACTIVATE', 'BIND_ORDER'):
                        raise ValueError()
                    if not (str(body['code']).isdigit() and len(str(body['code'])) == 6):
                        raise ValueError()
                    if self.headers.get('Idempotency-Key') != challenge:
                        raise ValueError()
                    target = fixture.directory / (challenge + '.json')
                    with fixture.lock:
                        if target.exists() and json.loads(target.read_text()) != body:
                            self.send_error(409)
                            return
                        target.write_text(json.dumps(body))
                        target.chmod(0o600)
                    payload = b'{"accepted":true}'
                    self.send_response(202)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except (ValueError, KeyError, TypeError):
                    self.send_error(400)

        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, private)
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
