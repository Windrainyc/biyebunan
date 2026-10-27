#!/usr/bin/env python3
"""本地受控备份恢复演练：只读app，恢复至本脚本新建的隔离数据库，最后删除该数据库。
使用既有colima-zhixiang PostgreSQL容器的本地管理连接，不读取或打印口令。
备份只在0700临时目录短暂存在，不提交、不上传；本演练不代替长期异地备份。
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

root = Path(__file__).resolve().parents[1]
database = 'zx_restore_' + uuid.uuid4().hex
assert re.fullmatch(r'zx_restore_[0-9a-f]{32}', database)
base = ['docker', '--context', 'colima-zhixiang', 'exec', '-i', 'zhixiang-local-postgres-1']

def sql(db, statement):
    result = subprocess.run(base + ['psql', '-X', '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1', '-At'], input=statement, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('恢复演练SQL失败；未输出数据库内部内容')
    return result.stdout.strip()

created = False
start = time.monotonic()
try:
    with tempfile.TemporaryDirectory(prefix='zx-backup-') as temporary:
        dump = Path(temporary) / 'app.dump'
        with open(dump, 'xb') as output:
            os.chmod(dump, 0o600)
            result = subprocess.run(base + ['pg_dump', '-U', 'postgres', '-d', 'zhixiang', '--schema=app', '--format=custom', '--no-owner', '--no-acl'], stdout=output, stderr=subprocess.PIPE)
        if result.returncode: raise RuntimeError('备份失败；未打印原始日志')
        sql('postgres', f'CREATE DATABASE {database}')
        created = True
        sql(database, 'CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public')
        with open(dump, 'rb') as source:
            result = subprocess.run(base + ['pg_restore', '-U', 'postgres', '-d', database, '--no-owner', '--no-acl', '--exit-on-error'], stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if result.returncode: raise RuntimeError('恢复失败；未打印可能含数据的原始日志')
        tables = sql('zhixiang', "SELECT tablename FROM pg_tables WHERE schemaname='app' ORDER BY tablename").splitlines()
        for table in tables:
            if not re.fullmatch(r'[a-z0-9_]+', table): raise RuntimeError('未知表名格式')
            query = f'SELECT count(*) FROM app.{table}'
            if sql('zhixiang', query) != sql(database, query):
                raise RuntimeError('恢复行数与当前源库不一致，源库可能在演练期间变化')
        invalid = sql(database, "SELECT count(*) FROM pg_constraint WHERE connamespace='app'::regnamespace AND NOT convalidated")
        if invalid != '0': raise RuntimeError('恢复后存在未验证约束')
        report = {'source': 'local app schema', 'isolatedRestore': True, 'tablesChecked': len(tables), 'rowCountsMatch': True, 'constraintsValidated': True, 'dumpBytes': dump.stat().st_size, 'elapsedSeconds': round(time.monotonic()-start, 2), 'productionRpoRtoVerified': False, 'objectStorageBackupVerified': False}
        (root/'docs/backup-restore-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report,ensure_ascii=False))
finally:
    if created:
        sql('postgres', f'DROP DATABASE {database}')
