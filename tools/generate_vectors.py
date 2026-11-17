from pathlib import Path
import random
from model import Game, GOAL, DEMO
root=Path(__file__).resolve().parents[1]
(root/'build').mkdir(exist_ok=True)
rng=random.Random(2072026)
g=Game(); rows=[]
def emit(**kw):
    g.step(**kw)
    c=(int(kw.get('clear',False))<<10 | int(kw.get('confirm',False))<<9 |
       kw.get('value',0)<<5 | int(kw.get('undo',False))<<4 |
       int(kw.get('view',False))<<3 | int(kw.get('demo',False))<<2 |
       int(kw.get('next',False))<<1)
    rows.append(f'{c:03x} {g.packed():019x}\n')
def initialize(board, errors=False):
    emit(clear=True)
    for i,v in enumerate(board):
        if errors:
            emit(confirm=True,value=9+i%7)
            if i: emit(confirm=True,value=board[i-1])
        emit(confirm=True,value=v)
# All 9 empty positions, every possible move value (including 0 and 9..15).
for blank in range(9):
    b=list(range(1,9)); b.insert(blank,0)
    # Avoid a goal board so every candidate is exercised in PLAY.
    if b==GOAL: b[0],b[1]=b[1],b[0]
    for v in range(16):
        initialize(b,errors=True)
        emit(confirm=True,value=v); emit(view=True); emit(undo=True); emit(undo=True)
initialize(GOAL,True)
emit(confirm=True); emit(confirm=True); emit(undo=True)
initialize(DEMO)
emit(confirm=True,value=1); emit(undo=True)
emit(confirm=True,value=1); emit(confirm=True,value=8)
emit(confirm=True); emit(undo=True); emit(confirm=True,value=8)
# Saturation and restoration at/around 15, 16, 98, 99, 100.
initialize(DEMO)
for _ in range(110): emit(confirm=True,value=0)
emit(undo=True)
initialize([0,1,2,3,4,5,6,7,8])
for _ in range(110): emit(confirm=True,value=1)
emit(undo=True)
# Repeated demonstration cycles, display toggles, ignored ordinary inputs.
emit(clear=True); emit(demo=True)
for _ in range(24):
    emit(next=True); emit(view=True); emit(confirm=True,value=15); emit(undo=True)
emit(demo=True)
# Random permutations include unsolvable boards: spec accepts every unique 0..8 input.
for _ in range(120):
    b=list(range(9)); rng.shuffle(b); initialize(b, errors=True)
    for _ in range(100):
        p=rng.random()
        if p<.12: emit(undo=True)
        elif p<.22: emit(view=True)
        else: emit(confirm=True,value=rng.randrange(16))
# Simultaneous controls verify documented priority.
initialize(DEMO)
emit(confirm=True,value=1,view=True)
emit(confirm=True,value=8,undo=True)
emit(demo=True,confirm=True,value=5,view=True)
emit(clear=True,demo=True,next=True,confirm=True,undo=True,view=True)
(root/'build/core_vectors.txt').write_text(''.join(rows))
print(f'Generated {len(rows)} independently predicted cycles (seed 2072026)')
