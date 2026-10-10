package dev.ligustah.lsnav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun layer(id: Int): Bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also {
        context.getDrawable(id)!!.apply { setBounds(0, 0, 108, 108); draw(Canvas(it)) }
    }

    @Test fun themedIconPreservesTheWebsiteLogoSilhouette() {
        val color = layer(R.drawable.launcher_foreground)
        val mono = layer(R.drawable.launcher_monochrome)
        assertEquals(0, Color.alpha(mono.getPixel(54, 50)))
        assertEquals(255, Color.alpha(color.getPixel(54, 54)))
        assertTrue(Color.red(color.getPixel(54, 70)) > 240)
        assertTrue(Color.green(color.getPixel(54, 70)) > 200)
        for (x in 0 until 108) for (y in 0 until 108) {
            if ((x - 54) * (x - 54) + (y - 54) * (y - 54) > 22 * 22) assertEquals(Color.alpha(color.getPixel(x, y)), Color.alpha(mono.getPixel(x, y)))
            if (Color.alpha(mono.getPixel(x, y)) > 0) assertTrue("Artwork must fit the adaptive safe circle", (x - 54) * (x - 54) + (y - 54) * (y - 54) <= 33 * 33)
        }
        assertTrue(context.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
    }

    @Test fun renderLauncherSizesAndMasks() {
        val image = Bitmap.createBitmap(840, 460, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(243, 244, 246))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 41, 55); textSize = 20f }
        canvas.drawText("Sunshine Navigator", 28f, 34f, label)
        val names = listOf("Circle", "Rounded square", "Themed light", "Themed dark")
        for (column in 0..3) {
            val x = 28 + column * 205
            canvas.drawText(names[column], x.toFloat(), 72f, label)
            for ((row, size) in listOf(144, 64, 48).withIndex()) {
                val y = listOf(92, 270, 370)[row]
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                val mask = Path().apply {
                    if (column == 1) addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * .24f, size * .24f, Path.Direction.CW)
                    else addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                }
                canvas.clipPath(mask)
                if (column < 2) {
                    context.getDrawable(R.drawable.launcher_background)!!.apply { setBounds(0, 0, size, size); draw(canvas) }
                } else canvas.drawColor(if (column == 2) Color.rgb(220, 226, 255) else Color.rgb(38, 43, 67))
                context.getDrawable(if (column < 2) R.drawable.launcher_foreground else R.drawable.launcher_monochrome)!!.mutate().apply {
                    if (column >= 2) setTint(if (column == 2) Color.rgb(46, 49, 87) else Color.rgb(220, 226, 255))
                    setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
                    draw(canvas)
                }
                canvas.restore()
            }
        }
        val output = File("build/outputs/roborazzi/launcher-icon.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
