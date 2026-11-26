package io.personal.filemanager.data.transfer;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class LocalHttpsRangeTest {
    @Test public void validRanges() throws Exception {
        LocalHttpsServer.ByteRange r=LocalHttpsServer.parseRange("bytes=8-",10);
        assertEquals(8,r.start); assertEquals(9,r.end); assertEquals(2,r.length());
        r=LocalHttpsServer.parseRange("bytes=-3",10);
        assertEquals(7,r.start); assertEquals(3,r.length());
        r=LocalHttpsServer.parseRange("bytes=3-100",10);
        assertEquals(3,r.start); assertEquals(9,r.end);
        assertEquals(0,LocalHttpsServer.parseRange(null,0).length());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        LocalHttpsServer.downloadHeaders(out,r,10,true);
        String header=out.toString("US-ASCII");
        assertTrue(header.contains("206 Response"));
        assertTrue(header.contains("Content-Range: bytes 3-9/10"));
        assertTrue(header.contains("Content-Length: 7"));
    }
    @Test public void rejectsInvalidRanges() throws Exception {
        for(String value:new String[]{"bytes=10-","bytes=-0","bytes=9-2","bytes=1-,3-","bytes=9999999999999999999-"}) {
            try { LocalHttpsServer.parseRange(value,10); fail(value); }
            catch(IOException expected) {}
        }
    }
}
