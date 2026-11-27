package feather.core

import org.junit.Assert.*
import org.junit.Test

class CadBooleanTest {
    private fun box(id:Long,x:Double,y:Double,z:Double,w:Double,d:Double,h:Double)=
        CadSolid(id,"B$id",CadSolid.Kind.BOX,x,y,z,w,d,h)

    private fun volume(parts:List<CadSolid>) = parts.sumOf { it.width*it.depth*it.height }

    @Test fun cutMakesCenteredPocketAndPreservesExpectedVolume() {
        val base=box(1,0.0,0.0,0.0,40.0,30.0,10.0)
        val cutter=box(2,15.0,10.0,5.0,10.0,10.0,5.0)
        val result=CadBoolean.cut(base,cutter,10)
        assertEquals(40.0*30.0*10.0-10.0*10.0*5.0,volume(result),1e-8)
        assertTrue(result.all { it.kind==CadSolid.Kind.BOX })
    }

    @Test fun intersectionReturnsOnlyOverlap() {
        val a=box(1,0.0,0.0,0.0,20.0,20.0,20.0)
        val b=box(2,10.0,5.0,2.0,20.0,10.0,8.0)
        val result=CadBoolean.intersect(a,b,3)
        assertNotNull(result)
        assertEquals(10.0*10.0*8.0,result!!.width*result.depth*result.height,1e-8)
    }

    @Test fun unionDecomposesOverlappingBoxesWithoutVolumeOverlap() {
        val a=box(1,0.0,0.0,0.0,20.0,20.0,20.0)
        val b=box(2,10.0,0.0,0.0,20.0,20.0,20.0)
        val result=CadBoolean.union(a,b,10)
        assertEquals(20.0*20.0*20.0+10.0*20.0*20.0,volume(result),1e-8)
    }
}
