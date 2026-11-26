package io.personal.filemanager.data.links

/** Bounded ICO directory reader; PNG and common uncompressed 32/24-bit Windows icon frames. */
internal object IcoIcon {
    data class Frame(val width: Int, val height: Int, val png: ByteArray? = null, val pixels: IntArray? = null)
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, p: Int): Long = u16(b,p).toLong() or (u16(b,p+2).toLong() shl 16)
    fun decode(b: ByteArray): Frame? {
        if (b.size < 22 || u16(b,0)!=0 || u16(b,2)!=1) return null
        val count = u16(b,4)
        if (count !in 1..128 || 6+16*count>b.size) return null
        val entries = (0 until count).map { 6+it*16 }.sortedByDescending { p ->
            val w=(b[p].toInt() and 255).let { if(it==0)256 else it }
            val h=(b[p+1].toInt() and 255).let { if(it==0)256 else it }
            w*h
        }
        for(p in entries) {
            val length=u32(b,p+8);val offset=u32(b,p+12)
            if(length<8 || offset<6+16*count || offset+length>b.size)continue
            val start=offset.toInt(); val end=(offset+length).toInt()
            if(b[start]==0x89.toByte() && b[start+1]==80.toByte() && b[start+2]==78.toByte() && b[start+3]==71.toByte())
                return Frame(0,0,png=b.copyOfRange(start,end))
            if(length<40)continue
            val header=u32(b,start); val w=u32(b,start+4); val totalH=u32(b,start+8);val bits=u16(b,start+14)
            if(header !in 40..length || w !in 1..256 || totalH !in 2..512 || totalH%2!=0L ||
                u16(b,start+12)!=1 || bits !in listOf(24,32) || u32(b,start+16)!=0L)continue
            val width=w.toInt(); val height=(totalH/2).toInt()
            val stride=((width*bits+31)/32)*4
            val maskStride=((width+31)/32)*4
            val data=start+header.toInt();val mask=data+stride*height
            if(mask>end)continue
            val hasMask=mask+maskStride*height<=end
            val pixels=IntArray(width*height)
            val alphaPresent=bits==32 && (0 until height).any { y -> (0 until width).any { x -> b[data+y*stride+x*4+3].toInt()!=0 } }
            for(y in 0 until height)for(x in 0 until width) {
                val k=data+(height-1-y)*stride+x*(bits/8)
                val blue=b[k].toInt() and 255; val green=b[k+1].toInt() and 255; val red=b[k+2].toInt() and 255
                val transparent=hasMask && ((b[mask+(height-1-y)*maskStride+x/8].toInt() and (128 shr (x%8)))!=0)
                val alpha=if(transparent)0 else if(alphaPresent)b[k+3].toInt() and 255 else 255
                pixels[y*width+x]=(alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            return Frame(width,height,pixels=pixels)
        }
        return null
    }
}
