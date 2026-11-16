package nl.markmaaktmedia.keeper.convert

import java.io.ByteArrayInputStream

/**
 * JPEG lezen met het JDK van de testmachine. javax.imageio staat niet op het compile-pad van een Android-module (dat is
 * android.jar), maar wel op de JVM waarop de unit tests draaien, dus via reflectie.
 */
object JdkJpeg {
    class Decoded(val width: Int, val height: Int, val argb: IntArray)

    fun decode(bytes: ByteArray): Decoded {
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java)
            .invoke(null, ByteArrayInputStream(bytes)) ?: error("geen leesbare afbeelding")
        val cls = Class.forName("java.awt.image.BufferedImage")
        val w = cls.getMethod("getWidth").invoke(img) as Int
        val h = cls.getMethod("getHeight").invoke(img) as Int
        val out = IntArray(w * h)
        cls.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(img, 0, 0, w, h, out, 0, w)
        return Decoded(w, h, out)
    }
}
