#!/usr/bin/env python3
"""Compare actual FPGA telemetry against independent game model, not host emulation."""
import argparse
import datetime
import json
import random
from pathlib import Path
from board_cli import Board
from model import Game, DEMO, GOAL

def expected_digits(g):
    d=[v if v in range(1,9) else 15 for v in g.board[:8]]
    if not g.state and g.error and g.entered<8: d[g.entered]=14
    if g.stats and g.state==2: d=[g.good//10,g.good%10,15,15,15,15,g.bad//10,g.bad%10]
    return [15]*8 if g.demo and g.phase==5 else d

def run(port=None):
    rng=random.Random(2072026); g=Game(); results=[]
    with Board(port) as hw:
        hw.command('a'); hw.command('r')
        def verify(c=None,**kwargs):
            if c is None: actual=hw.query()
            else:
                g.step(**kwargs); actual=hw.command(c)
            expected={k:getattr(g,k) for k in ('board','state','entered','good','bad','hints','phase')}
            expected_flags=(int(g.demo)|int(g.error)<<1|int(g.stats)<<2|int(g.view)<<3|int(g.previous is not None)<<4)
            observed=actual.as_dict()
            for key,v in expected.items():
                assert observed[key]==v, f'{len(results)} cmd={c}: {key} expected={v}, got={observed[key]}'
            assert actual.flags&31==expected_flags, f'flags mismatch: {observed}'
            assert actual.digits==expected_digits(g), f'digit format mismatch: {observed}'
            leds=actual.leds
            if g.demo and g.phase==5: assert leds==0
            else:
                last=g.board[8] if g.board[8]<=8 else 0
                if not g.state and g.error and g.entered==8: last=15
                assert leds>>12==last
                byte=g.hints if g.view else (((g.good>>4)<<4 | g.bad>>4) if actual.flags&64 else ((g.good&15)<<4 | g.bad&15))
                assert leds&255==byte
                assert bool(leds&0x400)==(not g.demo) and bool(leds&0x200)==g.demo
                assert bool(leds&0x800)==bool(actual.flags&32)
                if g.state==2: assert leds&0x100
                elif not g.error: assert not leds&0x100
            results.append({'command':c,'snapshot':observed})
        def reset(): verify('r',clear=True)
        def init(board,errors=False):
            reset()
            for i,v in enumerate(board):
                if errors:
                    verify('F',confirm=True,value=15)
                    if i: verify(str(board[i-1]),confirm=True,value=board[i-1])
                verify(str(v),confirm=True,value=v)
        verify()
        init(DEMO,True)
        verify('v',view=True)
        verify('1',confirm=True,value=1); verify('u',undo=True)
        verify('1',confirm=True,value=1); verify('8',confirm=True,value=8)
        verify('c',confirm=True); verify('u',undo=True); verify('8',confirm=True,value=8)
        init(GOAL); verify('c',confirm=True)
        # Neighbors of every cell, plus prohibited row-wraps and all invalid codes.
        for blank in range(9):
            b=list(range(1,9)); b.insert(blank,0)
            if b==GOAL: b[0],b[1]=b[1],b[0]
            init(b)
            for value in range(16):
                verify(f'{value:X}',confirm=True,value=value); verify('u',undo=True)
        init([0,1,2,3,4,5,6,7,8])
        for _ in range(102): verify('1',confirm=True,value=1)
        for _ in range(102): verify('0',confirm=True,value=0)
        verify('u',undo=True)
        hw.command('h'); verify(); hw.command('h'); verify()
        # Decimal and LED values agree for completed games with multi-digit counts.
        init(DEMO)
        for _ in range(16): verify('7',confirm=True,value=7)
        for _ in range(18): verify('0',confirm=True,value=0)
        verify('8',confirm=True,value=8); verify('c',confirm=True)
        assert g.good==17 and g.bad==18
        for _ in range(8):
            b=list(range(9)); rng.shuffle(b); init(b,True)
            for _ in range(40):
                if rng.random()<.2: verify('u',undo=True)
                else:
                    v=rng.randrange(16); verify(f'{v:X}',confirm=True,value=v)
        reset(); verify('d',demo=True)
        for _ in range(12):
            verify('n',next=True); verify('v',view=True)
        # Leave the real board at demo scene 1, hints visible, UART control disabled.
        reset(); verify('d',demo=True); verify('n',next=True); verify('v',view=True)
        final=hw.command('z')
        assert not final.flags&32
        report={'result':'PASS','checks':len(results),'seed':2072026,
                'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'port':hw.port,'final':final.as_dict(),'trace':results}
    return report

def write_report(report,target):
    target=Path(target); target.parent.mkdir(exist_ok=True,parents=True)
    trace=target.with_name(target.stem+'_trace.jsonl')
    trace.write_text(''.join(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n'
                             for row in report['trace']))
    summary={k:v for k,v in report.items() if k!='trace'}
    summary['trace_file']=trace.name
    target.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--port')
    p.add_argument('--output',default='reports/hardware_acceptance.json'); args=p.parse_args()
    report=run(args.port)
    target=Path(args.output); write_report(report,target)
    print(f'HARDWARE_PASS checks={report["checks"]} port={report["port"]} report={target}')
