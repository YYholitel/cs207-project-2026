package edu.sustech.cs207.egopuzzle;

import java.io.Closeable;
import java.io.IOException;

interface SerialTransport extends Closeable {
    void write(byte[] bytes) throws IOException;
    /** Returns zero when this read times out. */
    int read(byte[] bytes, int timeoutMs) throws IOException;
}
