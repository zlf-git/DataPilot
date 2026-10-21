# -*- coding: utf-8 -*-
"""
假 OpenAI 兼容端点（QA 用，Ticket 08b）。

用途：在没有真 Key 的情况下验证「真实 provider 链路」——请求路径、鉴权头、
以及（本票重点）**usage 是否正确落进 token 口径**。QA-08 P2-5 报的正是
「端点回报 111/222/333，应用侧 promptTokens 仍是 0」：说明 usage 被丢了。

用法：python fake_openai.py [port] [--usage 111,222,333] [--ir path/to/ir.json]
默认端口 18100，默认回放 stub_ir/q1.json 的 ir，默认 usage 111/222/333。

响应形状就是 OpenAI Chat Completions 的最小可用集：
  { "id","object","created","model","choices":[{"index","message":{"role","content"},"finish_reason"}],
    "usage":{"prompt_tokens","completion_tokens","total_tokens"} }
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
PORT = 18100
USAGE = (111, 222, 333)
IR_FILE = os.path.join(BASE, 'src', 'main', 'resources', 'stub_ir', 'q1.json')

args = sys.argv[1:]
if args and args[0].isdigit():
    PORT = int(args.pop(0))
if '--usage' in args:
    USAGE = tuple(int(x) for x in args[args.index('--usage') + 1].split(','))
if '--ir' in args:
    IR_FILE = args[args.index('--ir') + 1]

with open(IR_FILE, encoding='utf-8') as fp:
    IR = json.load(fp)['ir']

SEEN = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_POST(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length).decode('utf-8') if length else ''
        SEEN.append({
            'path': self.path,
            'auth': self.headers.get('Authorization'),
            'model': json.loads(body).get('model') if body else None,
        })
        payload = {
            'id': 'chatcmpl-fake',
            'object': 'chat.completion',
            'created': 0,
            'model': (json.loads(body).get('model') if body else 'fake-model'),
            'choices': [{
                'index': 0,
                'message': {'role': 'assistant', 'content': json.dumps(IR, ensure_ascii=False)},
                'finish_reason': 'stop',
            }],
            'usage': {
                'prompt_tokens': USAGE[0],
                'completion_tokens': USAGE[1],
                'total_tokens': USAGE[2],
            },
        }
        raw = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        """排障用：GET /__seen 回放收到的请求，验证路径与鉴权头"""
        raw = json.dumps(SEEN, ensure_ascii=False).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    print('fake openai on :%d  usage=%s  ir=%s' % (PORT, USAGE, os.path.basename(IR_FILE)))
    HTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
