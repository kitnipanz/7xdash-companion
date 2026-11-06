package com.kooo.evcam.obd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The wire to an ELM327 adapter. {@link #close} may come from another thread, to break a blocked {@link #open}. */
interface ObdLink {

    void open() throws IOException;

    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    void close();
}
