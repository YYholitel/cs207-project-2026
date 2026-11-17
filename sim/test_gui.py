"""Bridge/API behavior, independent of a browser and of a physical board."""
import concurrent.futures
import json
from pathlib import Path
import random
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from gui_server import Bridge, Handler, scramble, GOAL
from board_cli import Snapshot
from model import Game
from verify_board import expected_digits
PORT='/dev/serial/by-id/test-if01-port0'

class ModelBoard:
    def __init__(self,port):
        self.game=Game(); self.advanced=False; self.high=False; self.closed=False
        self.commands=[]; self.broken=False
    def command(self,c):
        self.commands.append(c)
        if c=='a': self.advanced=True
        elif c=='z': self.advanced=False; self.high=False
        elif self.advanced:
            if c.isdigit(): self.game.step(confirm=True,value=int(c))
            elif c=='c': self.game.step(confirm=True)
            elif c=='r': self.game.step(clear=True)
            elif c=='u': self.game.step(undo=True)
            elif c=='v': self.game.step(view=True)
            elif c=='d': self.game.step(demo=True)
            elif c=='n': self.game.step(next=True)
            elif c=='h': self.high=not self.high
        return self.query()
    def query(self):
        if self.broken: raise OSError('USB unplugged')
        g=self.game
        flags=int(g.demo)|int(g.error)<<1|int(g.stats)<<2|int(g.view)<<3|int(g.previous is not None)<<4|int(self.advanced)<<5|int(self.high)<<6
        digits=sum(v<<(4*i) for i,v in enumerate(expected_digits(g)))
        b=bytearray([0xa5,0x5a,1,flags,g.state,g.entered,g.good,g.bad,g.hints,g.phase]+g.board+list(digits.to_bytes(4,'little'))+[0,0,0,0])
        check=0
        for x in b[2:]: check^=x
        return Snapshot(bytes(b)+bytes([check]))
    def __exit__(self,*args): self.closed=True

def create_bridge(): return Bridge(factory=ModelBoard,ports=lambda:[PORT])
def init(bridge,board): return bridge.action({'action':'initialize','board':board})['snapshot']
def solvable(board):
    return sum(a>b for i,a in enumerate(board) for b in board[i+1:] if a and b)%2==0

class BridgeTest(unittest.TestCase):
    def setUp(self): self.bridge=create_bridge(); self.bridge.connect()
    def tearDown(self): self.bridge.close()
    def test_generated_games_are_unique_solvable_and_not_already_won(self):
        rng=random.Random(207)
        for steps in (8,24,60):
            for _ in range(100):
                board=scramble(steps,rng)
                self.assertEqual(sorted(board),list(range(9)))
                self.assertTrue(solvable(board)); self.assertNotEqual(board,GOAL)
            snapshot=self.bridge.action({'action':'new_game','steps':steps})['snapshot']
            self.assertTrue(solvable(snapshot['board'])); self.assertEqual(snapshot['state'],1)
            self.assertTrue(snapshot['flags']&8)
    def test_invalid_initialization_does_not_reset_existing_game(self):
        init(self.bridge,[1,2,3,4,5,6,7,0,8]); before=self.bridge.snapshot.raw
        for board in ([0]*9,list(range(8)),['0']+list(range(1,9)),[False]+list(range(1,9))):
            with self.assertRaises(ValueError): init(self.bridge,board)
            self.assertEqual(self.bridge.snapshot.raw,before)
    def test_moves_are_executed_by_board_and_undo_restores_counters(self):
        init(self.bridge,[1,2,3,4,5,6,7,0,8])
        s=self.bridge.action({'action':'command','command':'1'})['snapshot']; self.assertEqual(s['bad'],1)
        s=self.bridge.action({'action':'direction','direction':'right'})['snapshot']
        self.assertEqual(s['board'],GOAL); self.assertEqual((s['good'],s['bad'],s['state']),(1,1,2))
        s=self.bridge.action({'action':'command','command':'u'})['snapshot']
        self.assertEqual((s['good'],s['bad'],s['state']),(0,1,1))
    def test_direction_uses_fresh_board_and_rejects_boundary(self):
        init(self.bridge,[0,1,2,3,4,5,6,7,8])
        before=len(self.bridge.board.commands)
        with self.assertRaises(ValueError): self.bridge.action({'action':'direction','direction':'left'})
        self.assertEqual(len(self.bridge.board.commands),before)
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            list(pool.map(lambda _:self.bridge.action({'action':'direction','direction':'right'}),range(2)))
        self.assertEqual(self.bridge.snapshot.board[:3],[1,2,0]); self.assertEqual(self.bridge.snapshot.good,2)
    def test_physical_reset_is_read_only_until_explicit_gui_action(self):
        model=self.bridge.board; model.game.step(clear=True); model.advanced=False
        before=len(model.commands); self.bridge._refresh()
        self.assertEqual(len(model.commands),before); self.assertFalse(self.bridge.snapshot.flags&32)
        self.bridge.action({'action':'command','command':'1'})
        self.assertEqual(model.commands[-2:],['a','1']); self.assertEqual(self.bridge.snapshot.entered,1)
    def test_demo_and_disconnect(self):
        s=self.bridge.action({'action':'demo'})['snapshot']; self.assertEqual(s['phase'],1)
        for phase in (2,3,4,5,1):
            s=self.bridge.action({'action':'command','command':'n'})['snapshot']; self.assertEqual(s['phase'],phase)
        self.bridge.action({'action':'demo'}); self.assertEqual(self.bridge.snapshot.state,0)
        model=self.bridge.board; self.bridge.disconnect(); self.assertTrue(model.closed)
        self.assertEqual(model.commands[-1],'z'); self.assertFalse(self.bridge.payload()['connected'])
    def test_unplugged_board_disconnects_with_explicit_error(self):
        self.bridge.board.broken=True; self.bridge._refresh()
        self.assertFalse(self.bridge.payload()['connected']); self.assertIn('USB unplugged',self.bridge.payload()['error'])
    def test_no_arbitrary_serial_path_or_arbitrary_command(self):
        with self.assertRaises(ValueError): self.bridge.connect('/etc/passwd')
        for command in ('aa','z','?','!','\n',None):
            with self.assertRaises(ValueError): self.bridge.action({'action':'command','command':command})

class HTTPTest(unittest.TestCase):
    def setUp(self):
        self.bridge=create_bridge(); self.token='test-session'
        cls=type('TestHandler',(Handler,),{'bridge':self.bridge,'token':self.token})
        self.server=ThreadingHTTPServer(('127.0.0.1',0),cls)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.base=f'http://127.0.0.1:{self.server.server_port}'
    def tearDown(self): self.server.shutdown(); self.server.server_close(); self.thread.join(1); self.bridge.close()
    def request(self,path,body=None,headers=None):
        h={'X-Session-Token':self.token,'Content-Type':'application/json'};h.update(headers or {})
        data=None if body is None else json.dumps(body).encode()
        return urllib.request.urlopen(urllib.request.Request(self.base+path,data=data,headers=h),timeout=2)
    def test_real_http_flow(self):
        s=json.load(self.request('/api/status')); self.assertEqual(s['token'],self.token);self.assertFalse(s['connected'])
        s=json.load(self.request('/api/connect',{}));self.assertTrue(s['connected'])
        json.load(self.request('/api/action',{'action':'initialize','board':[1,2,3,4,5,6,7,0,8]}))
        s=json.load(self.request('/api/action',{'action':'direction','direction':'right'}))
        self.assertEqual(s['snapshot']['board'],GOAL)
        self.assertFalse(json.load(self.request('/api/disconnect',{}))['connected'])
    def test_origin_token_and_host_guards(self):
        for headers in ({'X-Session-Token':'wrong'},{'Origin':'https://other.example'},{'Host':'other.example'}):
            with self.assertRaises(urllib.error.HTTPError) as error: self.request('/api/connect',{},headers)
            self.assertEqual(error.exception.code,403)
        self.assertFalse(self.bridge.payload()['connected'])
    def test_static_assets_and_no_traversal(self):
        for path,kind in (('/','text/html'),('/app.js','text/javascript'),('/style.css','text/css'),('/favicon.svg','image/svg+xml')):
            response=self.request(path); self.assertIn(kind,response.headers['Content-Type'])
            self.assertIn("frame-ancestors 'none'",response.headers['Content-Security-Policy'])
            self.assertGreater(len(response.read()),50)
        with self.assertRaises(urllib.error.HTTPError) as error:self.request('/../tools/gui_server.py')
        self.assertEqual(error.exception.code,404)
    def test_malformed_input_is_reported(self):
        req=urllib.request.Request(self.base+'/api/action',data=b'not-json',headers={'X-Session-Token':self.token})
        with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(req)
        self.assertEqual(error.exception.code,400)
if __name__=='__main__': unittest.main()
