#!/usr/bin/env python3
"""Loopback-only GUI bridge to the real EGO1 FPGA. Python standard library only."""
import argparse
import atexit
import glob
import json
from pathlib import Path
import random
import secrets
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
from board_cli import Board

ROOT=Path(__file__).resolve().parents[1]
WEB=ROOT/'gui'
GOAL=list(range(1,9))+[0]

def scramble(steps, rng=None):
    """Generate a solvable board by walking from the goal; never immediately undo."""
    rng=rng or random.SystemRandom()
    board=GOAL.copy(); blank=8; previous=None
    for _ in range(steps):
        r,c=divmod(blank,3)
        neighbors=[i for i in range(9) if abs(i//3-r)+abs(i%3-c)==1 and i!=previous]
        dest=rng.choice(neighbors); board[blank],board[dest]=board[dest],0
        previous,blank=blank,dest
    if board==GOAL:
        board[7],board[8]=board[8],board[7]
    return board

class Bridge:
    def __init__(self, factory=Board, ports=None):
        self.factory=factory
        self.port_provider=ports or (lambda: sorted(glob.glob('/dev/serial/by-id/*-if01-port0')))
        self.lock=threading.RLock(); self.board=None; self.snapshot=None
        self.revision=0; self.error=None; self.port=None; self.updated_at=None
        self.stop=threading.Event()
    def _store(self,snapshot):
        if self.snapshot is None or snapshot.raw!=self.snapshot.raw: self.revision+=1
        self.snapshot=snapshot; self.updated_at=time.time(); self.error=None
    def _close(self,disable=True):
        if self.board:
            try:
                if disable: self._store(self.board.command('z'))
            except (OSError,TimeoutError): pass
            try: self.board.__exit__(None,None,None)
            except OSError: pass
        self.board=None; self.port=None; self.revision+=1
    def _refresh(self):
        if self.board:
            try: self._store(self.board.query())
            except (OSError,TimeoutError) as exc:
                self._close(disable=False); self.error=f'串口连接已中断：{exc}'
    def payload(self):
        with self.lock:
            return {'connected':self.board is not None,'port':self.port,
                    'ports':self.port_provider(),'revision':self.revision,
                    'updated_at':self.updated_at,'error':self.error,
                    'snapshot':self.snapshot.as_dict() if self.snapshot else None}
    def poll(self):
        while not self.stop.wait(.12):
            with self.lock: self._refresh()
    def connect(self,port=None):
        with self.lock:
            choices=self.port_provider()
            if port is None:
                if len(choices)!=1: raise ValueError('请选择 EGO1 的串口 B 通道')
                port=choices[0]
            if port not in choices: raise ValueError('串口必须是列表中的 EGO1 B 通道')
            self._close()
            try:
                self.board=self.factory(port); self.port=port
                self._store(self.board.command('a'))
                if not self.snapshot.flags&32: raise RuntimeError('FPGA 未启用串口控制，请检查固件')
                self.revision+=1
            except Exception:
                self._close(disable=False); raise
            return self.payload()
    def disconnect(self):
        with self.lock:
            self._close(); return self.payload()
    def _command(self,c):
        if not self.board: raise ValueError('请先连接开发板')
        self._store(self.board.command(c))
    def action(self,body):
        with self.lock:
            if not self.board: raise ValueError('请先连接开发板')
            self._refresh()
            if not self.board: raise ValueError('开发板连接已中断，请重新连接')
            # Physical RESET intentionally disables UART control. Re-enable for
            # the explicit GUI action, while read-only polling never changes it.
            if not self.snapshot.flags&32: self._command('a')
            kind=body.get('action')
            if kind=='command':
                c=body.get('command')
                if not isinstance(c,str) or len(c)!=1 or c not in '0123456789cruvdnh':
                    raise ValueError('无效控制指令')
                self._command(c)
            elif kind in ('new_game','initialize'):
                if kind=='new_game':
                    steps=body.get('steps',24)
                    if type(steps) is not int or steps not in (8,24,60): raise ValueError('无效难度')
                    values=scramble(steps)
                else:
                    values=body.get('board')
                    if not isinstance(values,list) or len(values)!=9 or any(type(v) is not int for v in values) or sorted(values)!=list(range(9)):
                        raise ValueError('棋盘必须恰好包含 0–8，每个数字一次')
                self._command('r')
                for value in values: self._command(str(value))
                if not self.snapshot.flags&8: self._command('v')
                if self.snapshot.board!=values or self.snapshot.entered!=9:
                    raise RuntimeError('实板初始化结果不一致，请重试')
            elif kind=='demo':
                self._command('d')
                if self.snapshot.flags&1:
                    self._command('n')
                    if not self.snapshot.flags&8: self._command('v')
            elif kind=='direction':
                if self.snapshot.state!=1 or self.snapshot.flags&1: raise ValueError('请在手工游戏中使用方向键')
                moves={'up':(-1,0),'down':(1,0),'left':(0,-1),'right':(0,1)}
                if body.get('direction') not in moves: raise ValueError('无效方向')
                blank=self.snapshot.board.index(0); r,c=divmod(blank,3)
                dr,dc=moves[body['direction']]; nr,nc=r+dr,c+dc
                if not (0<=nr<3 and 0<=nc<3): raise ValueError('这一侧没有棋子可移动')
                self._command(str(self.snapshot.board[nr*3+nc]))
            else: raise ValueError('未知操作')
            return self.payload()
    def close(self):
        self.stop.set()
        with self.lock: self._close()

class Handler(BaseHTTPRequestHandler):
    bridge=None
    token=None
    def log_message(self,fmt,*args): pass
    def _json(self,data,status=200):
        b=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def _host_ok(self):
        return self.headers.get('Host') in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}')
    def do_GET(self):
        if not self._host_ok(): return self._json({'error':'Invalid host'},403)
        path=urlparse(self.path).path
        if path=='/api/status':
            return self._json({**self.bridge.payload(),'token':self.token})
        files={'/':'index.html','/app.js':'app.js','/style.css':'style.css','/favicon.svg':'favicon.svg'}
        if path not in files: return self._json({'error':'Not found'},404)
        data=(WEB/files[path]).read_bytes()
        mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml'}
        self.send_response(200); self.send_header('Content-Type',mime[Path(files[path]).suffix])
        self.send_header('Cache-Control','no-cache'); self.send_header('Content-Length',str(len(data)))
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        if not self._host_ok() or self.headers.get('X-Session-Token')!=self.token:
            return self._json({'error':'Invalid session'},403)
        origin=self.headers.get('Origin')
        if origin and origin not in (f'http://127.0.0.1:{self.server.server_port}',f'http://localhost:{self.server.server_port}'):
            return self._json({'error':'Invalid origin'},403)
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=2048: raise ValueError('无效请求长度')
            body=json.loads(self.rfile.read(length))
            if not isinstance(body,dict): raise ValueError('无效请求')
            if self.path=='/api/connect': result=self.bridge.connect(body.get('port'))
            elif self.path=='/api/disconnect': result=self.bridge.disconnect()
            elif self.path=='/api/action': result=self.bridge.action(body)
            else: return self._json({'error':'Not found'},404)
            self._json(result)
        except (ValueError,json.JSONDecodeError) as exc: self._json({'error':str(exc)},400)
        except Exception as exc: self._json({'error':f'实板通信失败：{exc}'},503)

def serve(port=8765,bridge=None):
    bridge=bridge or Bridge()
    handler=type('SessionHandler',(Handler,),{'bridge':bridge,'token':secrets.token_urlsafe(32)})
    server=ThreadingHTTPServer(('127.0.0.1',port),handler)
    thread=threading.Thread(target=bridge.poll,daemon=True); thread.start()
    atexit.register(bridge.close)
    def stop_server(*_):
        threading.Thread(target=server.shutdown,daemon=True).start()
    if threading.current_thread() is threading.main_thread():
        signal.signal(signal.SIGTERM,stop_server)
        signal.signal(signal.SIGINT,stop_server)
    print(f'SLIDE / 09 · EGO1 GUI: http://127.0.0.1:{server.server_port}',flush=True)
    try: server.serve_forever()
    finally: server.server_close(); bridge.close()
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--port',type=int,default=8765)
    args=parser.parse_args(); serve(args.port)
