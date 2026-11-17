#!/usr/bin/env python3
"""Dependency-free Linux 115200 8N1 client; channel B only, never JTAG UART0."""
import argparse
import fcntl
import glob
import json
import os
import select
import termios
import time
import tty
from dataclasses import dataclass

def find_port():
    candidates=glob.glob('/dev/serial/by-id/*-if01-port0')
    if len(candidates)!=1:
        raise RuntimeError('Specify --port: expected exactly one FTDI channel-B serial port')
    return candidates[0]

@dataclass
class Snapshot:
    raw: bytes
    @property
    def flags(self): return self.raw[3]
    @property
    def board(self): return list(self.raw[10:19])
    @property
    def state(self): return self.raw[4]
    @property
    def entered(self): return self.raw[5]
    @property
    def good(self): return self.raw[6]
    @property
    def bad(self): return self.raw[7]
    @property
    def hints(self): return self.raw[8]
    @property
    def phase(self): return self.raw[9]
    @property
    def digits(self):
        n=int.from_bytes(self.raw[19:23],'little')
        return [(n>>(4*i))&15 for i in range(8)]
    @property
    def leds(self): return int.from_bytes(self.raw[23:25],'little')
    def as_dict(self):
        return {k:getattr(self,k) for k in ('board','state','entered','good','bad','hints','phase','flags','digits','leds')}
    def render(self):
        cells=[' ' if v==0 else '-' if v==15 else str(v) for v in self.board]
        grid='\n'.join(' | '.join(cells[i:i+3]) for i in (0,3,6))
        movable=[str(i) for i in range(1,9) if self.hints & (1<<(8-i))]
        return (grid+f"\nstate={('INIT','PLAY','WON')[self.state]}  valid={self.good:02} invalid={self.bad:02}"
                f'\nmovable={",".join(movable)}  demo={bool(self.flags&1)} phase={self.phase}'
                f'\nLED={self.leds:016b}  advanced={bool(self.flags&32)} high_page={bool(self.flags&64)}')

class Board:
    def __init__(self,port=None):
        self.port=port or find_port()
        self.fd=os.open(self.port,os.O_RDWR|os.O_NOCTTY|os.O_NONBLOCK)
        try:
            fcntl.flock(self.fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.saved=termios.tcgetattr(self.fd)
            tty.setraw(self.fd)
            attrs=termios.tcgetattr(self.fd)
            attrs[2]=(attrs[2]&~(termios.PARENB|termios.CSTOPB|termios.CSIZE|termios.CRTSCTS))|termios.CS8|termios.CLOCAL|termios.CREAD
            attrs[4]=attrs[5]=termios.B115200
            termios.tcsetattr(self.fd,termios.TCSANOW,attrs)
            termios.tcflush(self.fd,termios.TCIOFLUSH)
        except BaseException:
            os.close(self.fd); raise
    def __enter__(self): return self
    def __exit__(self,*args):
        termios.tcsetattr(self.fd,termios.TCSANOW,self.saved)
        os.close(self.fd)
    def command(self,c,query=True):
        if len(c)!=1 or c not in '0123456789ABCDEFcruvdnazh?': raise ValueError('Unknown command')
        os.write(self.fd,c.encode('ascii'))
        time.sleep(.004)
        return self.query() if query else None
    def query(self):
        termios.tcflush(self.fd,termios.TCIFLUSH)
        os.write(self.fd,b'?')
        data=bytearray(); deadline=time.monotonic()+1.0
        while time.monotonic()<deadline:
            ready,_,_=select.select([self.fd],[],[],.1)
            if not ready: continue
            data.extend(os.read(self.fd,4096))
            while len(data)>=28:
                pos=data.find(b'\xa5\x5a')
                if pos<0:
                    data[:]=data[-1:]; break
                if pos: del data[:pos]
                if len(data)<28: break
                packet=bytes(data[:28]); checksum=0
                for x in packet[2:27]: checksum^=x
                if packet[2]==1 and checksum==packet[27]: return Snapshot(packet)
                del data[0]
        raise TimeoutError(f'No valid FPGA response from {self.port}; received {data.hex()}')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port'); p.add_argument('--json',action='store_true')
    p.add_argument('commands',nargs='?',default='?',help='Sequence, e.g. ar123456708v; no commands means status only')
    p.add_argument('--interactive',action='store_true')
    args=p.parse_args()
    with Board(args.port) as board:
        snapshot=board.query()
        if args.commands!='?':
            for c in args.commands: snapshot=board.command(c)
        print(json.dumps(snapshot.as_dict()) if args.json else snapshot.render())
        if args.interactive:
            print('a=enable UART; z=disable; r=reset; 0..F=confirm; c=confirm/statistics;'
                  ' u=undo; v=view; d=demo mode; n=demo next; h=count page; ?=status; q=quit')
            while True:
                try: command=input('> ').strip()
                except EOFError: break
                if command=='q': break
                for c in command:
                    snapshot=board.query() if c=='?' else board.command(c)
                print(snapshot.render())
if __name__=='__main__': main()
