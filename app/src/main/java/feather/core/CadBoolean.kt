package feather.core

/** Exact axis-aligned box CSG. Results are decomposed into non-overlapping boxes. */
object CadBoolean {
    data class Bounds(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double) {
        val valid get() = x1 > x0 && y1 > y0 && z1 > z0
    }

    fun bounds(s: CadSolid): Bounds = Bounds(s.x, s.y, s.z, s.x+s.width, s.y+s.depth, s.z+s.height)

    private fun solid(id: Long, name: String, b: Bounds, feature: String) = CadSolid(
        id=id, name=name, kind=CadSolid.Kind.BOX, x=b.x0, y=b.y0, z=b.z0,
        width=b.x1-b.x0, depth=b.y1-b.y0, height=b.z1-b.z0, feature=feature
    )

    private fun intersection(a: Bounds, b: Bounds): Bounds? {
        val r=Bounds(maxOf(a.x0,b.x0),maxOf(a.y0,b.y0),maxOf(a.z0,b.z0),minOf(a.x1,b.x1),minOf(a.y1,b.y1),minOf(a.z1,b.z1))
        return r.takeIf { it.valid }
    }

    /** Subtract the overlapping cutter from a box, returning up to six disjoint box pieces. */
    private fun subtractBounds(a: Bounds, cutter: Bounds): List<Bounds> {
        val i=intersection(a,cutter) ?: return listOf(a)
        val out= mutableListOf<Bounds>()
        fun add(x0:Double,y0:Double,z0:Double,x1:Double,y1:Double,z1:Double) {
            val b=Bounds(x0,y0,z0,x1,y1,z1); if(b.valid) out += b
        }
        add(a.x0,a.y0,a.z0,i.x0,a.y1,a.z1)
        add(i.x1,a.y0,a.z0,a.x1,a.y1,a.z1)
        add(i.x0,a.y0,a.z0,i.x1,i.y0,a.z1)
        add(i.x0,i.y1,a.z0,i.x1,a.y1,a.z1)
        add(i.x0,i.y0,a.z0,i.x1,i.y1,i.z0)
        add(i.x0,i.y0,i.z1,i.x1,i.y1,a.z1)
        return out
    }

    fun cut(target: CadSolid, tool: CadSolid, firstId: Long): List<CadSolid> {
        require(target.kind==CadSolid.Kind.BOX && tool.kind==CadSolid.Kind.BOX) { "Boolean Cut currently supports Box solids only." }
        return subtractBounds(bounds(target),bounds(tool)).mapIndexed { n,b -> solid(firstId+n,"${target.name} cut ${n+1}",b,"Boolean Cut · ${target.name} − ${tool.name}") }
    }

    fun intersect(a: CadSolid, b: CadSolid, id: Long): CadSolid? {
        require(a.kind==CadSolid.Kind.BOX && b.kind==CadSolid.Kind.BOX) { "Boolean Intersection currently supports Box solids only." }
        return intersection(bounds(a),bounds(b))?.let { solid(id,"${a.name} ∩ ${b.name}",it,"Boolean Intersection") }
    }

    /** Union as one original box plus the non-overlapping remainder of the second box. */
    fun union(a: CadSolid, b: CadSolid, firstId: Long): List<CadSolid> {
        require(a.kind==CadSolid.Kind.BOX && b.kind==CadSolid.Kind.BOX) { "Boolean Union currently supports Box solids only." }
        val pieces=listOf(bounds(a)) + subtractBounds(bounds(b),bounds(a))
        return pieces.mapIndexed { n,r -> solid(firstId+n,"Union ${n+1}",r,"Boolean Union · ${a.name} + ${b.name}") }
    }
}
