#!/usr/bin/env python3
"""下载固定版本离线IPv4归属地数据库并核对摘要；不上传用户IP。"""
import argparse
import hashlib
import pathlib
import urllib.request

COMMIT = '6a276cc7c4f9f2d7ff1d8ace14b91af59752f943'
SHA256 = '46249ad1b33bcaa4cc37475b93746cec2b00acffc3316cc4f56167ac821ea2ad'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)
target = args.directory / 'ip2region_v4.xdb'
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == SHA256:
    print('固定版本数据库已存在且摘要一致')
else:
    data = urllib.request.urlopen(
        f'https://raw.githubusercontent.com/lionsoul2014/ip2region/{COMMIT}/data/ip2region_v4.xdb',
        timeout=180,
    ).read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit('数据库摘要不一致，未写入')
    temp = target.with_suffix('.tmp')
    temp.write_bytes(data)
    temp.replace(target)
    print('固定版本IPv4数据库下载及摘要校验通过')
