package feather.core

import kotlin.math.sqrt

/** Lightweight, unit-aware starter solid model. Dimensions and positions are millimetres. */
data class CadSolid(
    val id: Long,
    val name: String,
    val kind: Kind,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val width: Double = 20.0,
    val depth: Double = 20.0,
    val height: Double = 20.0,
    val feature: String = "Primitive",
    /** Inner diameter in mm; used by revolved annular profiles (TUBE). */
    val innerDiameter: Double = 0.0,
) {
    enum class Kind { BOX, CYLINDER, TUBE }
    init {
        require(width > 0 && depth > 0 && height > 0) { "Solid dimensions must be greater than zero" }
        if (kind == Kind.TUBE) require(innerDiameter > 0 && innerDiameter < minOf(width, depth)) {
            "Tube inner diameter must be positive and smaller than its outer diameter"
        }
    }
}

/** ASCII STL export for the supported starter primitives. STL stores a tessellated mesh, not editable features. */
object CadStlExporter {
    private data class V(val x: Double, val y: Double, val z: Double) {
        operator fun minus(b: V) = V(x-b.x, y-b.y, z-b.z)
        fun cross(b: V) = V(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x)
        fun normalized(): V { val n = sqrt(x*x+y*y+z*z).coerceAtLeast(1e-12); return V(x/n,y/n,z/n) }
        override fun toString() = "$x $y $z"
    }
    private fun facet(a: V,b: V,c: V): String {
        val n = (b-a).cross(c-a).normalized()
        return " facet normal $n\n  outer loop\n   vertex $a\n   vertex $b\n   vertex $c\n  endloop\n endfacet\n"
    }
    fun export(solids: List<CadSolid>): String = buildString {
        append("solid rounga_cad\n")
        solids.forEach { s ->
            val x=s.x; val y=s.y; val z=s.z; val w=s.width; val d=s.depth; val h=s.height
            if (s.kind == CadSolid.Kind.BOX) {
                val p = listOf(V(x,y,z),V(x+w,y,z),V(x+w,y+d,z),V(x,y+d,z),V(x,y,z+h),V(x+w,y,z+h),V(x+w,y+d,z+h),V(x,y+d,z+h))
                val tris = listOf(0 to 2 to 1,0 to 3 to 2,4 to 5 to 6,4 to 6 to 7,0 to 1 to 5,0 to 5 to 4,1 to 2 to 6,1 to 6 to 5,2 to 3 to 7,2 to 7 to 6,3 to 0 to 4,3 to 4 to 7)
                tris.forEach { t -> append(facet(p[t.first.first],p[t.first.second],p[t.second])) }
            } else {
                val n=64; val cx=x+w/2; val cy=y+d/2; val outer=minOf(w,d)/2
                val inner=if(s.kind==CadSolid.Kind.TUBE) s.innerDiameter/2 else 0.0
                for (i in 0 until n) {
                    val a=2*Math.PI*i/n; val b=2*Math.PI*(i+1)/n
                    val p1=V(cx+outer*kotlin.math.cos(a),cy+outer*kotlin.math.sin(a),z)
                    val p2=V(cx+outer*kotlin.math.cos(b),cy+outer*kotlin.math.sin(b),z)
                    val q1=V(p1.x,p1.y,z+h); val q2=V(p2.x,p2.y,z+h)
                    if (s.kind==CadSolid.Kind.CYLINDER) {
                        append(facet(V(cx,cy,z),p2,p1)); append(facet(V(cx,cy,z+h),q1,q2))
                    } else {
                        val i1=V(cx+inner*kotlin.math.cos(a),cy+inner*kotlin.math.sin(a),z)
                        val i2=V(cx+inner*kotlin.math.cos(b),cy+inner*kotlin.math.sin(b),z)
                        val j1=V(i1.x,i1.y,z+h); val j2=V(i2.x,i2.y,z+h)
                        // Outer wall, inner wall, and the two annular end faces.
                        append(facet(p1,p2,q2)); append(facet(p1,q2,q1))
                        append(facet(i1,j2,i2)); append(facet(i1,j1,j2))
                        append(facet(q1,q2,j2)); append(facet(q1,j2,j1))
                        append(facet(p1,i2,p2)); append(facet(p1,i1,i2))
                    }
                    if (s.kind==CadSolid.Kind.CYLINDER) { append(facet(p1,p2,q2)); append(facet(p1,q2,q1)) }
                }
            }
        }
        append("endsolid rounga_cad\n")
    }
}
