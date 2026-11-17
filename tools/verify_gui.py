#!/usr/bin/env python3
"""Accept the real local GUI bridge against FPGA hardware and independent rules."""
import argparse
import datetime
import json
import random
from pathlib import Path
import urllib.error
import urllib.request
from model import Game, DEMO
from verify_board import expected_digits, write_report

class Client:
    def __init__(self,url):
        self.url=url.rstrip('/'); self.token=self.get()['token']
    def get(self): return json.load(urllib.request.urlopen(self.url+'/api/status',timeout=3))
    def post(self,path,body):
        req=urllib.request.Request(self.url+path,data=json.dumps(body).encode(),
             headers={'Content-Type':'application/json','X-Session-Token':self.token})
        return json.load(urllib.request.urlopen(req,timeout=5))

def run(url):
    client=Client(url)
    if not client.get()['connected']:client.post('/api/connect',{})
    rng=random.Random(2072026); game=Game(); records=[]
    def compare(data,action):
        s=data['snapshot']
        for key in ('board','state','entered','good','bad','hints','phase'):
            assert s[key]==getattr(game,key),f'{action}: {key} expected={getattr(game,key)} actual={s[key]}'
        flags=int(game.demo)|int(game.error)<<1|int(game.stats)<<2|int(game.view)<<3|int(game.previous is not None)<<4
        assert s['flags']&31==flags,(action,s)
        assert s['digits']==expected_digits(game),(action,s['digits'])
        assert s['flags']&32,'GUI must have real FPGA UART control enabled'
        records.append({'action':action,'snapshot':s})
        return s
    def initialize(board):
        game.step(clear=True)
        for value in board: game.step(confirm=True,value=value)
        game.step(view=True)
        return compare(client.post('/api/action',{'action':'initialize','board':board}),{'initialize':board})
    def command(c,**kw):
        game.step(**kw)
        return compare(client.post('/api/action',{'action':'command','command':c}),{'command':c})
    def direction(name):
        dr,dc={'up':(-1,0),'down':(1,0),'left':(0,-1),'right':(0,1)}[name]
        blank=game.board.index(0); r,c=divmod(blank,3)
        nr,nc=r+dr,c+dc
        action={'action':'direction','direction':name}
        if 0<=nr<3 and 0<=nc<3:
            game.step(confirm=True,value=game.board[nr*3+nc]); compare(client.post('/api/action',action),action)
        else:
            try: client.post('/api/action',action)
            except urllib.error.HTTPError as exc: assert exc.code==400
            else: raise AssertionError('Boundary direction accepted')
            compare(client.get(),{'rejected':name})
    initialize(DEMO)
    command('1',confirm=True,value=1); direction('right'); command('c',confirm=True)
    command('u',undo=True); command('v',view=True); direction('left'); command('u',undo=True)
    # Invalid custom input must preserve the live board and statistics.
    try:client.post('/api/action',{'action':'initialize','board':[0]*9})
    except urllib.error.HTTPError as exc:assert exc.code==400
    else:raise AssertionError('Duplicate layout accepted')
    compare(client.get(),{'rejected':'duplicate initialization'})
    for steps in (8,24,60):
        data=client.post('/api/action',{'action':'new_game','steps':steps})
        game.step(clear=True)
        for value in data['snapshot']['board']:game.step(confirm=True,value=value)
        game.step(view=True);compare(data,{'new_game':steps})
        for _ in range(30):
            if game.state==2:command('u',undo=True)
            elif rng.random()<.5:direction(rng.choice(['up','down','left','right']))
            else:
                value=rng.randrange(9);command(str(value),confirm=True,value=value)
    game.step(demo=True);game.step(next=True)
    if not game.view:game.step(view=True)
    compare(client.post('/api/action',{'action':'demo'}),{'demo':'start'})
    for _ in range(10):command('n',next=True)
    # Leave a fresh solvable warmup on the user's board, ready for GUI input.
    data=client.post('/api/action',{'action':'new_game','steps':8})
    game.step(clear=True)
    for value in data['snapshot']['board']:game.step(confirm=True,value=value)
    game.step(view=True);compare(data,{'new_game':'ready to play'})
    return {'result':'PASS','checks':len(records),'url':url,'seed':2072026,
            'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'final':client.get()['snapshot'],'trace':records}
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://127.0.0.1:8765')
    p.add_argument('--output',default='reports/gui_hardware_acceptance.json');args=p.parse_args()
    report=run(args.url);write_report(report,Path(args.output))
    print(f'GUI_HARDWARE_PASS checks={report["checks"]} url={args.url}')
