package feather.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sin

/**
 * The Rounga signature: the word written in one continuous pen stroke, with a quill feather
 * that draws it. The stroke is hand-built from Bezier curves (see [SEGMENTS]) so it needs no font
 * file and stays crisp at any size. [progress] 0..1 is how much of the word is written.
 */
private object RoungaInk {
    // Logical drawing space: the word sits inside x 82..616, y 45..240; the rest leaves room for the quill.
    const val VIEW_X = 50f
    const val VIEW_Y = -70f
    const val VIEW_W = 700f
    const val VIEW_H = 330f

    /** First point (x, y), then 83 cubic segments of (c1x, c1y, c2x, c2y, x, y). */
    val SEGMENTS = floatArrayOf(
        110.0f, 50.0f, 107.5f, 58.0f, 103.3f, 70.0f, 100.0f, 82.0f,
        96.7f, 94.0f, 93.0f, 107.3f, 90.0f, 122.0f, 87.0f, 136.7f,
        84.0f, 158.0f, 82.0f, 170.0f, 83.5f, 160.5f, 85.0f, 145.0f,
        88.0f, 132.0f, 91.0f, 119.0f, 95.3f, 103.3f, 100.0f, 92.0f,
        104.7f, 80.7f, 109.3f, 71.7f, 116.0f, 64.0f, 122.7f, 56.3f,
        130.7f, 48.0f, 140.0f, 46.0f, 149.3f, 44.0f, 165.7f, 46.3f,
        172.0f, 52.0f, 178.3f, 57.7f, 180.0f, 71.7f, 178.0f, 80.0f,
        176.0f, 88.3f, 167.7f, 97.0f, 160.0f, 102.0f, 152.3f, 107.0f,
        140.0f, 108.7f, 132.0f, 110.0f, 124.0f, 111.3f, 117.0f, 110.0f,
        112.0f, 110.0f, 117.5f, 111.5f, 127.7f, 111.0f, 134.0f, 116.0f,
        140.3f, 121.0f, 145.3f, 131.7f, 150.0f, 140.0f, 154.7f, 148.3f,
        158.0f, 160.0f, 162.0f, 166.0f, 166.0f, 172.0f, 169.3f, 177.0f,
        174.0f, 176.0f, 178.7f, 175.0f, 184.7f, 166.3f, 190.0f, 160.0f,
        195.3f, 153.7f, 200.0f, 144.7f, 206.0f, 138.0f, 212.0f, 131.3f,
        219.3f, 124.2f, 226.0f, 120.0f, 232.7f, 115.8f, 245.0f, 115.0f,
        246.0f, 113.0f, 247.0f, 111.0f, 237.3f, 106.5f, 232.0f, 108.0f,
        226.7f, 109.5f, 217.7f, 115.7f, 214.0f, 122.0f, 210.3f, 128.3f,
        208.3f, 138.7f, 210.0f, 146.0f, 211.7f, 153.3f, 218.0f, 162.7f,
        224.0f, 166.0f, 230.0f, 169.3f, 240.0f, 169.3f, 246.0f, 166.0f,
        252.0f, 162.7f, 258.3f, 153.3f, 260.0f, 146.0f, 261.7f, 138.7f,
        259.0f, 128.0f, 256.0f, 122.0f, 253.0f, 116.0f, 242.7f, 111.7f,
        242.0f, 110.0f, 241.3f, 108.3f, 248.0f, 110.7f, 252.0f, 112.0f,
        256.0f, 113.3f, 262.3f, 117.7f, 266.0f, 118.0f, 269.7f, 118.3f,
        271.8f, 113.3f, 274.0f, 114.0f, 276.2f, 114.7f, 277.8f, 116.3f,
        279.0f, 122.0f, 280.2f, 127.7f, 278.7f, 140.3f, 281.0f, 148.0f,
        283.3f, 155.7f, 288.3f, 166.0f, 293.0f, 168.0f, 297.7f, 170.0f,
        305.0f, 166.0f, 309.0f, 160.0f, 313.0f, 154.0f, 315.0f, 139.7f,
        317.0f, 132.0f, 319.0f, 124.3f, 320.0f, 118.5f, 321.0f, 114.0f,
        321.5f, 122.0f, 321.3f, 137.3f, 323.0f, 146.0f, 324.7f, 154.7f,
        326.8f, 163.7f, 331.0f, 166.0f, 335.2f, 168.3f, 343.5f, 165.7f,
        348.0f, 160.0f, 352.5f, 154.3f, 355.0f, 139.7f, 358.0f, 132.0f,
        361.0f, 124.3f, 364.0f, 118.5f, 366.0f, 114.0f, 365.5f, 121.0f,
        365.0f, 132.7f, 364.0f, 142.0f, 363.0f, 151.3f, 361.0f, 163.0f,
        360.0f, 170.0f, 361.0f, 162.5f, 362.0f, 148.0f, 364.0f, 140.0f,
        366.0f, 132.0f, 367.7f, 127.0f, 372.0f, 122.0f, 376.3f, 117.0f,
        384.3f, 110.3f, 390.0f, 110.0f, 395.7f, 109.7f, 402.7f, 114.0f,
        406.0f, 120.0f, 409.3f, 126.0f, 409.7f, 138.3f, 410.0f, 146.0f,
        410.3f, 153.7f, 406.3f, 161.7f, 408.0f, 166.0f, 409.7f, 170.3f,
        415.7f, 174.3f, 420.0f, 172.0f, 424.3f, 169.7f, 428.7f, 160.7f,
        434.0f, 152.0f, 439.3f, 143.3f, 451.0f, 127.0f, 452.0f, 120.0f,
        453.0f, 113.0f, 444.7f, 109.3f, 440.0f, 110.0f, 435.3f, 110.7f,
        427.3f, 117.7f, 424.0f, 124.0f, 420.7f, 130.3f, 418.3f, 141.3f,
        420.0f, 148.0f, 421.7f, 154.7f, 428.7f, 163.3f, 434.0f, 164.0f,
        439.3f, 164.7f, 448.0f, 158.7f, 452.0f, 152.0f, 456.0f, 145.3f,
        456.8f, 130.7f, 458.0f, 124.0f, 459.2f, 117.3f, 458.8f, 115.0f,
        459.0f, 112.0f, 459.5f, 121.5f, 461.0f, 136.7f, 461.0f, 150.0f,
        461.0f, 163.3f, 461.2f, 179.3f, 459.0f, 192.0f, 456.8f, 204.7f,
        453.2f, 218.0f, 448.0f, 226.0f, 442.8f, 234.0f, 434.3f, 239.7f,
        428.0f, 240.0f, 421.7f, 240.3f, 412.3f, 232.7f, 410.0f, 228.0f,
        407.7f, 223.3f, 409.3f, 216.0f, 414.0f, 212.0f, 418.7f, 208.0f,
        428.0f, 206.3f, 438.0f, 204.0f, 448.0f, 201.7f, 462.7f, 202.3f,
        474.0f, 198.0f, 485.3f, 193.7f, 497.3f, 186.3f, 506.0f, 178.0f,
        514.7f, 169.7f, 520.7f, 157.7f, 526.0f, 148.0f, 531.3f, 138.3f,
        538.3f, 126.3f, 538.0f, 120.0f, 537.7f, 113.7f, 529.0f, 109.3f,
        524.0f, 110.0f, 519.0f, 110.7f, 511.3f, 117.7f, 508.0f, 124.0f,
        504.7f, 130.3f, 502.3f, 141.0f, 504.0f, 148.0f, 505.7f, 155.0f,
        512.0f, 164.3f, 518.0f, 166.0f, 524.0f, 167.7f, 535.0f, 163.3f,
        540.0f, 158.0f, 545.0f, 152.7f, 546.2f, 141.7f, 548.0f, 134.0f,
        549.8f, 126.3f, 550.2f, 117.5f, 551.0f, 112.0f, 551.0f, 120.5f,
        550.7f, 137.0f, 551.0f, 146.0f, 551.3f, 155.0f, 550.2f, 161.7f,
        553.0f, 166.0f, 555.8f, 170.3f, 561.8f, 174.3f, 568.0f, 172.0f,
        574.2f, 169.7f, 582.0f, 159.7f, 590.0f, 152.0f, 598.0f, 144.3f,
        609.5f, 132.5f, 616.0f, 126.0f,
    )

    val path: Path by lazy {
        val p = Path()
        val s = SEGMENTS
        p.moveTo(s[0], s[1])
        var i = 2
        while (i + 5 < s.size) {
            p.cubicTo(s[i], s[i + 1], s[i + 2], s[i + 3], s[i + 4], s[i + 5])
            i += 6
        }
        p
    }

    /** Quill in local units with the nib at (0, 0), leaning up and to the right. */
    val vane: Path by lazy {
        Path().apply {
            moveTo(18f, -34f)
            cubicTo(52f, -52f, 112f, -100f, 104f, -158f)
            cubicTo(70f, -160f, 14f, -104f, 18f, -34f)
            close()
        }
    }
    val shaft: Path by lazy {
        Path().apply {
            moveTo(18f, -34f)
            cubicTo(40f, -70f, 80f, -115f, 104f, -158f)
        }
    }
}

@Composable
fun RoungaWordmark(
    progress: Float,
    modifier: Modifier = Modifier,
    showQuill: Boolean = true,
    ink: Color = MaterialTheme.colorScheme.primary,
    feather: Color = MaterialTheme.colorScheme.tertiary,
    strokeWidth: Float = 7f,
) {
    val measure = remember { PathMeasure().also { it.setPath(RoungaInk.path, false) } }
    val total = remember { measure.length }
    val shown = progress.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val k = min(size.width / RoungaInk.VIEW_W, size.height / RoungaInk.VIEW_H)
        val dx = (size.width - RoungaInk.VIEW_W * k) / 2f
        val dy = (size.height - RoungaInk.VIEW_H * k) / 2f
        withTransform({
            translate(dx, dy)
            scale(k, k, Offset.Zero)
            translate(-RoungaInk.VIEW_X, -RoungaInk.VIEW_Y)
        }) {
            val written = Path()
            measure.getSegment(0f, total * shown, written, true)
            drawPath(written, ink, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

            if (showQuill && shown > 0f) {
                val tip = measure.getPosition(total * shown)
                if (tip != Offset.Unspecified) {
                    val bob = sin(shown * 90f) * 3f // the hand is never perfectly still
                    withTransform({ translate(tip.x, tip.y); rotate(bob, Offset.Zero) }) {
                        drawPath(RoungaInk.vane, feather.copy(alpha = 0.92f))
                        drawPath(RoungaInk.vane, feather, style = Stroke(width = 2f, join = StrokeJoin.Round))
                        drawPath(RoungaInk.shaft, feather.copy(alpha = 0.55f), style = Stroke(width = 3f, cap = StrokeCap.Round))
                        drawLine(feather, Offset.Zero, Offset(18f, -34f), strokeWidth = 5f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** Full-screen opening: the quill writes "Rounga". Tap to skip. */
@Composable
internal fun RoungaSplash(onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 2800, easing = LinearEasing))
        delay(700)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(0.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RoungaWordmark(
                    progress = progress.value,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(220.dp),
                )
                Text(
                    "draw  -  design  -  make",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
