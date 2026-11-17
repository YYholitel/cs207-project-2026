"""Independent executable specification, also used by real-board acceptance."""
from dataclasses import dataclass, field
GOAL = list(range(1, 9)) + [0]
DEMO = [1, 2, 3, 4, 5, 6, 7, 0, 8]
@dataclass
class Game:
    board: list = field(default_factory=lambda: [15] * 9)
    state: int = 0
    entered: int = 0
    good: int = 0
    bad: int = 0
    error: bool = False
    stats: bool = False
    view: bool = False
    demo: bool = False
    phase: int = 0
    previous: tuple | None = None
    def reset_game(self):
        mode, phase = self.demo, self.phase
        self.__dict__.update(Game().__dict__)
        self.demo, self.phase = mode, phase
    @property
    def hints(self):
        if not self.state: return 0
        r, c = divmod(self.board.index(0), 3)
        return sum(1 << (8-v) for j, v in enumerate(self.board)
                   if v and abs(j//3-r) + abs(j%3-c) == 1)
    def step(self, *, clear=False, confirm=False, value=0, undo=False,
             view=False, demo=False, next=False):
        if clear:
            self.reset_game(); self.demo=False; self.phase=0; return
        if demo:
            mode = not self.demo
            self.reset_game(); self.demo=mode; self.phase=0; return
        if view: self.view = not self.view
        if self.demo:
            if not next: return
            if self.phase in (0, 5):
                self.board=DEMO.copy(); self.entered=9; self.state=1
                self.good=self.bad=0; self.error=self.stats=False; self.previous=None
                self.phase=1
            elif self.phase==1: self.bad=1; self.error=True; self.phase=2
            elif self.phase==2:
                self.board=GOAL.copy(); self.state=2; self.good=1
                self.error=False; self.phase=3
            elif self.phase==3: self.stats=True; self.phase=4
            elif self.phase==4: self.reset_game(); self.phase=5
            return
        if undo and self.previous is not None:
            b,g,bad,e,s = self.previous
            self.board=b.copy(); self.good=g; self.bad=bad; self.error=e; self.state=s
            self.stats=False; self.previous=None; return
        if not confirm: return
        if self.state==0:
            if value>8 or value in self.board[:self.entered]: self.error=True; return
            self.board[self.entered]=value; self.entered+=1; self.error=False
            if self.entered==9: self.state=2 if self.board==GOAL else 1
        elif self.state==1:
            self.previous=(self.board.copy(), self.good, self.bad, self.error, self.state)
            blank=self.board.index(0)
            legal=False
            if value in range(1,9):
                dest=self.board.index(value)
                legal=abs(blank//3-dest//3)+abs(blank%3-dest%3)==1
            if legal:
                self.board[blank],self.board[dest]=value,0
                self.good=min(99,self.good+1); self.error=False
                if self.board==GOAL: self.state=2
            else: self.bad=min(99,self.bad+1); self.error=True
        else: self.stats=not self.stats
    def packed(self):
        b=sum(v<<(4*i) for i,v in enumerate(self.board))
        return (b | self.state<<36 | self.entered<<38 | self.good<<42 | self.bad<<50 |
                int(self.error)<<58 | int(self.stats)<<59 | int(self.view)<<60 |
                int(self.demo)<<61 | self.phase<<62 | int(self.previous is not None)<<65 |
                self.hints<<66)
