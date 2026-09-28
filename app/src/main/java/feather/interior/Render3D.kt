package feather.interior

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Orbit camera around the middle of the room. */
data class Camera3D(
    val yawDeg: Double = 28.0,
    val pitchDeg: Double = 36.0,
    val distanceCm: Double = 0.0, // 0 = pick a distance that frames the room
) {
    fun orbit(dxDeg: Double, dyDeg: Double) = copy(
        yawDeg = yawDeg + dxDeg,
        pitchDeg = (pitchDeg + dyDeg).coerceIn(8.0, 85.0),
    )
}

/** A filled polygon in screen pixels, already in painter's order (draw the list first to last). */
class Poly(val xs: FloatArray, val ys: FloatArray, val fill: Int, val edge: Int, val edgeWidth: Float)

/**
 * A small software 3D renderer: perspective projection, back-face culling and depth-sorted quads.
 * It draws boxes and flat floor tiles only, which is all furniture layouts need, and it has no
 * dependencies, so it runs anywhere Compose draws.
 */
object Render3D {

    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = V(x * k, y * k, z * k)
        infix fun dot(o: V) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun unit(): V { val l = sqrt(this dot this); return if (l == 0.0) this else V(x / l, y / l, z / l) }
    }

    private const val FOV_DEG = 50.0
    private val LIGHT = V(-0.35, 0.85, 0.45).unit()

    /** Distance that fits the whole room comfortably in view. */
    fun defaultDistance(room: Room): Double {
        val diag = sqrt(room.widthCm * room.widthCm + room.depthCm * room.depthCm + room.heightCm * room.heightCm)
        return diag * 1.25
    }

    fun render(scene: Scene, cam: Camera3D, widthPx: Float, heightPx: Float, selectedId: String?): List<Poly> {
        val room = scene.room
        val target = V(room.widthCm / 2, room.heightCm * 0.3, room.depthCm / 2)
        val dist = if (cam.distanceCm > 0.0) cam.distanceCm else defaultDistance(room)
        val yaw = Math.toRadians(cam.yawDeg)
        val pitch = Math.toRadians(cam.pitchDeg)
        val eye = target + V(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * dist
        val forward = (target - eye).unit()
        val right = (forward cross V(0.0, 1.0, 0.0)).unit()
        val up = right cross forward
        val focal = (minOf(widthPx, heightPx) / 2.0) / tan(Math.toRadians(FOV_DEG / 2))
        val cx = widthPx / 2.0
        val cy = heightPx / 2.0

        fun project(p: V): DoubleArray? {
            val d = p - eye
            val z = d dot forward
            if (z < 5.0) return null
            return doubleArrayOf(cx + focal * (d dot right) / z, cy - focal * (d dot up) / z)
        }

        val out = ArrayList<Poly>()

        fun shadeColor(argb: Int, factor: Double): Int {
            val f = factor.coerceIn(0.0, 1.3)
            val r = (((argb shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
            val g = (((argb shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
            val b = ((argb and 0xFF) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun lit(normal: V): Double = 0.55 + 0.45 * maxOf(0.0, normal dot LIGHT)

        /** Adds a quad if it faces the camera. Vertices in world space; [normal] points out of the visible face. */
        fun quad(a: V, b: V, c: V, d: V, normal: V, color: Int, tint: Double, edge: Int, edgeWidth: Float, cullBack: Boolean = true) {
            val centre = (a + b + c + d) * 0.25
            if (cullBack && (normal dot (eye - centre)) <= 0.0) return
            val pa = project(a) ?: return
            val pb = project(b) ?: return
            val pc = project(c) ?: return
            val pd = project(d) ?: return
            val shaded = shadeColor(color, lit(normal) * tint)
            out += Poly(
                floatArrayOf(pa[0].toFloat(), pb[0].toFloat(), pc[0].toFloat(), pd[0].toFloat()),
                floatArrayOf(pa[1].toFloat(), pb[1].toFloat(), pc[1].toFloat(), pd[1].toFloat()),
                shaded, edge, edgeWidth,
            )
        }

        // ---- Floor: 50 cm tiles with a faint checker so the perspective reads. -------------------
        val floorNormal = V(0.0, 1.0, 0.0)
        val tile = 50.0
        var tx = 0
        var x0 = 0.0
        while (x0 < room.widthCm) {
            val x1 = minOf(x0 + tile, room.widthCm)
            var tz = 0
            var z0 = 0.0
            while (z0 < room.depthCm) {
                val z1 = minOf(z0 + tile, room.depthCm)
                val tint = if ((tx + tz) % 2 == 0) 1.0 else 0.94
                quad(V(x0, 0.0, z0), V(x1, 0.0, z0), V(x1, 0.0, z1), V(x0, 0.0, z1), floorNormal, room.floorColor, tint, shadeColor(room.floorColor, 0.8), 0.6f)
                z0 += tile; tz++
            }
            x0 += tile; tx++
        }

        // ---- Walls: only the ones whose inner face points at the camera, so the room is always cut away. ----
        val h = room.heightCm
        val w = room.widthCm
        val dp = room.depthCm
        val wallEdge = shadeColor(room.wallColor, 0.7)
        quad(V(0.0, 0.0, 0.0), V(w, 0.0, 0.0), V(w, h, 0.0), V(0.0, h, 0.0), V(0.0, 0.0, 1.0), room.wallColor, 1.0, wallEdge, 1.5f)     // back
        quad(V(w, 0.0, dp), V(0.0, 0.0, dp), V(0.0, h, dp), V(w, h, dp), V(0.0, 0.0, -1.0), room.wallColor, 1.0, wallEdge, 1.5f)          // front
        quad(V(0.0, 0.0, dp), V(0.0, 0.0, 0.0), V(0.0, h, 0.0), V(0.0, h, dp), V(1.0, 0.0, 0.0), room.wallColor, 0.96, wallEdge, 1.5f)    // left
        quad(V(w, 0.0, 0.0), V(w, 0.0, dp), V(w, h, dp), V(w, h, 0.0), V(-1.0, 0.0, 0.0), room.wallColor, 0.96, wallEdge, 1.5f)          // right

        // ---- Furniture: rugs first, then far to near. ---------------------------------------------
        fun centreOf(item: RoomItem) = V(item.xCm, item.elevationCm + item.heightCm / 2, item.yCm)
        fun distTo(p: V) = ((p - eye) dot (p - eye))
        val ordered = scene.items.sortedWith(
            compareBy<RoomItem> { if (it.type == ItemType.RUG) 0 else 1 }.thenByDescending { distTo(centreOf(it)) },
        )
        for (item in ordered) {
            val selected = item.id == selectedId
            val edge = if (selected) 0xFF3D5AFE.toInt() else 0x55000000
            val edgeWidth = if (selected) 3f else 0.8f
            val parts = Furniture.partsOf(item).sortedByDescending { p ->
                val (wx, wz) = PlanGeometry.toWorld(item, p.lx, p.lz)
                distTo(V(wx, item.elevationCm + p.y0 + p.h / 2, wz))
            }
            for (p in parts) box(item, p) { a, b, c, d, n, col, tint -> quad(a, b, c, d, n, col, tint, edge, edgeWidth) }
        }
        return out
    }

    /** Emits the five visible faces (top and four sides) of one part through [emit]. */
    private fun box(item: RoomItem, p: Part, emit: (V, V, V, V, V, Int, Double) -> Unit) {
        val color = p.colorOverride ?: item.color
        val y0 = item.elevationCm + p.y0
        val y1 = y0 + p.h
        val hw = p.w / 2
        val hd = p.d / 2
        fun world(lx: Double, lz: Double, y: Double): V {
            val (x, z) = PlanGeometry.toWorld(item, p.lx + lx, p.lz + lz)
            return V(x, y, z)
        }
        val r = Math.toRadians(item.rotationDeg)
        val c = cos(r)
        val s = sin(r)
        // local normal (nx, nz) -> world
        fun n(nx: Double, nz: Double) = V(nx * c - nz * s, 0.0, nx * s + nz * c)

        val b0 = world(-hw, -hd, y0); val b1 = world(hw, -hd, y0); val b2 = world(hw, hd, y0); val b3 = world(-hw, hd, y0)
        val t0 = world(-hw, -hd, y1); val t1 = world(hw, -hd, y1); val t2 = world(hw, hd, y1); val t3 = world(-hw, hd, y1)
        val k = p.shade
        emit(t0, t1, t2, t3, V(0.0, 1.0, 0.0), color, k)          // top
        emit(b3, b2, t2, t3, n(0.0, 1.0), color, k * 0.97)        // front (+z)
        emit(b2, b1, t1, t2, n(1.0, 0.0), color, k * 0.93)        // right (+x)
        emit(b1, b0, t0, t1, n(0.0, -1.0), color, k * 0.9)        // back (-z)
        emit(b0, b3, t3, t0, n(-1.0, 0.0), color, k * 0.93)       // left (-x)
    }
}
