package io.personal.filemanager.data.links

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IcoIconTest {
    @Test fun decodesCommon32BitFrameAndRejectsTruncation() {
        val b=ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0).putShort(1).putShort(1)
        b.put(1.toByte()).put(1.toByte()).put(0.toByte()).put(0.toByte()).putShort(1).putShort(32)
        b.putInt(48).putInt(22)
        b.putInt(40).putInt(1).putInt(2).putShort(1).putShort(32).putInt(0)
        b.position(62);b.put(byteArrayOf(0x11,0x22,0x33,0xff.toByte()))
        val frame=IcoIcon.decode(b.array())!!
        assertEquals(1,frame.width);assertEquals(1,frame.height)
        assertEquals(0xff332211.toInt(),frame.pixels!![0])
        assertNull(IcoIcon.decode(b.array().copyOf(65)))
    }
    @Test fun fallbackUsesDomainInitial() {
        assertEquals("B",SiteIcons.monogram("https://bill.1cent.host"))
        assertEquals("E",SiteIcons.monogram("https://www.example.com/"))
    }
}
