"""Exercise real OS serial I/O with a fragmented/noisy pseudo-terminal peer."""
import os
from pathlib import Path
import pty
import sys
import threading
import time
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from board_cli import Board, Snapshot

def packet():
    b=bytearray([0xa5,0x5a,1,32,1,9,16,17,11,0]+[1,2,3,4,5,6,7,0,8]+[0x21,0x43,0x65,0xf7]+[1,0x8c,0,0])
    checksum=0
    for x in b[2:]: checksum^=x
    return bytes(b)+bytes([checksum])

class ProtocolTest(unittest.TestCase):
    def test_noise_bad_checksum_and_fragmented_valid_response(self):
        master,slave=pty.openpty(); errors=[]
        try:
            with Board(os.ttyname(slave)) as board:
                good=packet(); bad=bytearray(good); bad[-1]^=1
                def peer():
                    try:
                        self.assertEqual(os.read(master,1),b'?')
                        data=b'noise\xa5\x00'+bytes(bad)+good
                        for i in range(0,len(data),3):
                            os.write(master,data[i:i+3]); time.sleep(.001)
                    except BaseException as exc: errors.append(exc)
                thread=threading.Thread(target=peer,daemon=True); thread.start()
                result=board.query(); thread.join(1)
                self.assertFalse(thread.is_alive()); self.assertFalse(errors,errors)
                self.assertEqual(result.raw,good)
                self.assertEqual(result.good,16); self.assertEqual(result.bad,17)
                self.assertEqual(result.board,[1,2,3,4,5,6,7,0,8])
                self.assertIn('valid=16 invalid=17',result.render())
        finally: os.close(master); os.close(slave)
    def test_no_response_times_out(self):
        master,slave=pty.openpty()
        try:
            with Board(os.ttyname(slave)) as board:
                with self.assertRaises(TimeoutError): board.query()
        finally: os.close(master); os.close(slave)
if __name__=='__main__': unittest.main()
