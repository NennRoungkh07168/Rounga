package feather.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import feather.core.CadSolid
import feather.core.CadBoolean
import feather.core.CadStlExporter
import kotlin.math.*

@Composable
fun CadScreen(onExport: (String) -> Unit, modifier: Modifier = Modifier) {
    val solids = remember { mutableStateListOf<CadSolid>() }
    var selected by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<CadSolid?>(null) }
    var showPadDialog by remember { mutableStateOf(false) }
    var showRevolveDialog by remember { mutableStateOf(false) }
    var showPocketDialog by remember { mutableStateOf(false) }
    var showBooleanDialog by remember { mutableStateOf(false) }
    var nextId by remember { mutableLongStateOf(1L) }
    val active = solids.firstOrNull { it.id == selected }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ROUNGA 3D CAD", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                Text("Mechanical solid workspace · millimetres", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick={onExport(CadStlExporter.export(solids.toList()))}, enabled=solids.isNotEmpty()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(5.dp)); Text("STL")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal=10.dp)) {
            CadViewport(solids, selected, Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape=RoundedCornerShape(8.dp), color=MaterialTheme.colorScheme.surface.copy(alpha=.92f)) {
                Text("ISO VIEW  ·  ${solids.size} solid(s)", Modifier.padding(horizontal=10.dp, vertical=6.dp), style=MaterialTheme.typography.labelSmall)
            }
            if (solids.isEmpty()) Text("Add a primitive to begin modelling", Modifier.align(Alignment.Center), color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp, vertical=6.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick={ val id=nextId++; val s=CadSolid(id,"Box $id",CadSolid.Kind.BOX, feature="Box primitive"); solids.add(s); selected=s.id }, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Add,null); Text("Box") }
            FilledTonalButton(onClick={ val id=nextId++; val s=CadSolid(id,"Cylinder $id",CadSolid.Kind.CYLINDER,width=20.0,depth=20.0,height=30.0,feature="Cylinder primitive"); solids.add(s); selected=s.id }, modifier=Modifier.weight(1f)) { Icon(Icons.Default.Add,null); Text("Cylinder") }
            Button(onClick={showPadDialog=true}, modifier=Modifier.weight(1.25f)) { Icon(Icons.Default.Add,null); Text("Sketch + Pad") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp, vertical=2.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={showRevolveDialog=true}, modifier=Modifier.weight(1f)) { Text("Revolve") }
            OutlinedButton(onClick={showPocketDialog=true}, enabled=active?.kind==CadSolid.Kind.BOX, modifier=Modifier.weight(1f)) { Text("Pocket") }
            OutlinedButton(onClick={showBooleanDialog=true}, enabled=solids.count { it.kind==CadSolid.Kind.BOX }>=2, modifier=Modifier.weight(1f)) { Text("Boolean") }
        }
        Row(Modifier.fillMaxWidth().heightIn(min=145.dp).padding(horizontal=10.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.weight(1f), shape=RoundedCornerShape(12.dp), tonalElevation=1.dp) {
                Column(Modifier.padding(10.dp)) {
                    Text("MODEL TREE", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                    LazyColumn(Modifier.heightIn(max=130.dp)) {
                        items(solids, key={it.id}) { s ->
                            Row(Modifier.fillMaxWidth().clickable { selected=s.id }.background(if(selected==s.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(7.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text(when(s.kind) { CadSolid.Kind.BOX -> "▰"; CadSolid.Kind.CYLINDER -> "◉"; CadSolid.Kind.TUBE -> "◎" }, color=MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style=MaterialTheme.typography.bodyMedium)
                                    Text(s.feature, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${s.width.toInt()}×${s.depth.toInt()}×${s.height.toInt()}", style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Surface(Modifier.weight(1f), shape=RoundedCornerShape(12.dp), tonalElevation=1.dp) {
                Column(Modifier.padding(10.dp), verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("PROPERTIES", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                    if(active==null) Text("Select a solid", style=MaterialTheme.typography.bodySmall)
                    else {
                        Text(active.name, style=MaterialTheme.typography.bodyMedium, fontWeight=FontWeight.SemiBold)
                        Text("${active.width} × ${active.depth} × ${active.height} mm", style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            IconButton(onClick={editing=active}) { Icon(Icons.Default.Edit,"Edit dimensions") }
                            IconButton(onClick={ val copy=active.copy(id=nextId++,name="${active.name} copy",x=active.x+active.width+5); solids.add(copy); selected=copy.id }) { Icon(Icons.Default.ContentCopy,"Duplicate") }
                            IconButton(onClick={solids.removeAll { it.id==active.id }; selected=null}) { Icon(Icons.Default.Delete,"Delete") }
                        }
                    }
                }
            }
        }
        Text("Box / Cylinder, Sketch Pad, Revolve, rectangular Pocket and box Boolean operations are available. Boolean tools currently require axis-aligned Box solids; advanced curved-surface CSG, constraints and STEP/IGES are not yet implemented.", Modifier.fillMaxWidth().padding(10.dp), style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { solid -> DimensionDialog(solid, onDismiss={editing=null}, onApply={ updated -> val i=solids.indexOfFirst{it.id==updated.id}; if(i>=0) solids[i]=updated; editing=null }) }
    if(showPadDialog) InteractiveSketcherDialog(
        onDismiss={showPadDialog=false},
        onCreatePad={ kind, width, depth, height ->
            val id=nextId++
            val label=if(kind==CadSolid.Kind.BOX) "Sketch Pad $id" else "Circle Pad $id"
            val solid=CadSolid(id,label,kind,width=width,depth=depth,height=height,feature="Interactive 2D sketch → Pad (${height} mm)")
            solids.add(solid); selected=id; showPadDialog=false
        }
    )
    if(showPocketDialog && active!=null) PocketDialog(active, onDismiss={showPocketDialog=false}, onCreate={w,d,depth ->
        val cutter=CadSolid(-1,"Pocket tool",CadSolid.Kind.BOX,
            x=active.x+(active.width-w)/2, y=active.y+(active.depth-d)/2,
            z=active.z+active.height-depth, width=w, depth=d, height=depth, feature="Pocket cutter")
        val result=CadBoolean.cut(active,cutter,nextId)
        nextId += result.size
        solids.removeAll { it.id==active.id }; solids.addAll(result); selected=result.firstOrNull()?.id
        showPocketDialog=false
    })
    if(showBooleanDialog) BooleanDialog(solids.filter { it.kind==CadSolid.Kind.BOX }, onDismiss={showBooleanDialog=false}, onApply={a,b,op ->
        val first=solids.firstOrNull { it.id==a }; val second=solids.firstOrNull { it.id==b }
        if(first!=null && second!=null && first.id!=second.id) {
            val result=when(op) {
                "Cut" -> CadBoolean.cut(first,second,nextId)
                "Union" -> CadBoolean.union(first,second,nextId)
                else -> listOfNotNull(CadBoolean.intersect(first,second,nextId))
            }
            nextId += result.size
            solids.removeAll { it.id==first.id || it.id==second.id }
            solids.addAll(result); selected=result.firstOrNull()?.id
        }
        showBooleanDialog=false
    })
    if(showRevolveDialog) RevolveDialog(
        onDismiss={showRevolveDialog=false},
        onCreate={ outer, inner, height ->
            val id=nextId++
            val solid=CadSolid(id,"Revolve $id",CadSolid.Kind.TUBE,width=outer,depth=outer,height=height,
                innerDiameter=inner,feature="Revolve annular profile · 360°")
            solids.add(solid); selected=id; showRevolveDialog=false
        }
    )
}

@Composable
private fun PocketDialog(target: CadSolid, onDismiss:()->Unit, onCreate:(Double,Double,Double)->Unit) {
    var width by remember(target.id) { mutableStateOf((target.width/2).toString()) }
    var depth by remember(target.id) { mutableStateOf((target.depth/2).toString()) }
    var cutDepth by remember(target.id) { mutableStateOf((target.height/2).toString()) }
    val w=width.toDoubleOrNull(); val d=depth.toDoubleOrNull(); val h=cutDepth.toDoubleOrNull()
    val valid=w!=null && d!=null && h!=null && w>0 && d>0 && h>0 && w<=target.width && d<=target.depth && h<=target.height
    AlertDialog(onDismissRequest=onDismiss, title={Text("Pocket · rectangular cut")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Cuts a centered rectangular pocket downward from the top face of ${target.name}.", style=MaterialTheme.typography.bodySmall)
            DimField("Pocket width (mm)",width,{width=it})
            DimField("Pocket depth (mm)",depth,{depth=it})
            DimField("Cut depth (mm)",cutDepth,{cutDepth=it})
            Text("Target: ${target.width} × ${target.depth} × ${target.height} mm", style=MaterialTheme.typography.labelSmall)
        }
    }, confirmButton={TextButton(enabled=valid,onClick={onCreate(w!!,d!!,h!!)}){Text("Apply Pocket")}}, dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun BooleanDialog(boxes:List<CadSolid>, onDismiss:()->Unit, onApply:(Long,Long,String)->Unit) {
    var target by remember { mutableLongStateOf(boxes.firstOrNull()?.id ?: -1L) }
    var tool by remember { mutableLongStateOf(boxes.getOrNull(1)?.id ?: -1L) }
    var operation by remember { mutableStateOf("Cut") }
    var targetExpanded by remember { mutableStateOf(false) }
    var toolExpanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss, title={Text("Boolean operation")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Axis-aligned Box solids only. Cut = target minus tool; Union = combine; Intersection = overlap.", style=MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick={targetExpanded=true}, modifier=Modifier.fillMaxWidth()) { Text("Target: ${boxes.firstOrNull{it.id==target}?.name ?: "Select"}") }
                DropdownMenu(expanded=targetExpanded,onDismissRequest={targetExpanded=false}) { boxes.forEach { b -> DropdownMenuItem(text={Text(b.name)},onClick={target=b.id;targetExpanded=false}) } }
            }
            Box {
                OutlinedButton(onClick={toolExpanded=true}, modifier=Modifier.fillMaxWidth()) { Text("Tool: ${boxes.firstOrNull{it.id==tool}?.name ?: "Select"}") }
                DropdownMenu(expanded=toolExpanded,onDismissRequest={toolExpanded=false}) { boxes.forEach { b -> DropdownMenuItem(text={Text(b.name)},onClick={tool=b.id;toolExpanded=false}) } }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("Cut","Union","Intersection").forEach { op -> FilterChip(selected=operation==op,onClick={operation=op},label={Text(op)}) }
            }
        }
    }, confirmButton={TextButton(enabled=target>=0 && tool>=0 && target!=tool,onClick={onApply(target,tool,operation)}){Text("Apply")}}, dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun SketchPadDialog(onDismiss:()->Unit, onCreate:(CadSolid.Kind,Double,Double,Double)->Unit) {
    var profile by remember { mutableStateOf(CadSolid.Kind.BOX) }
    var width by remember { mutableStateOf("40") }
    var depth by remember { mutableStateOf("30") }
    var height by remember { mutableStateOf("10") }
    val w=width.toDoubleOrNull()?.takeIf { it>0 }
    val d=depth.toDoubleOrNull()?.takeIf { it>0 }
    val h=height.toDoubleOrNull()?.takeIf { it>0 }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={ Text("2D Sketch → Pad") },
        text={
            Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("Choose a closed 2D profile", style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=profile==CadSolid.Kind.BOX,onClick={profile=CadSolid.Kind.BOX},label={Text("Rectangle")})
                    FilterChip(selected=profile==CadSolid.Kind.CYLINDER,onClick={profile=CadSolid.Kind.CYLINDER},label={Text("Circle")})
                }
                Canvas(Modifier.fillMaxWidth().height(94.dp).background(Color(0xFF101923),RoundedCornerShape(8.dp))) {
                    val cx=size.width/2; val cy=size.height/2
                    if(profile==CadSolid.Kind.BOX) {
                        val rw=size.width*.42f; val rh=size.height*.32f
                        drawRect(Color(0xFF65B7D6),Offset(cx-rw/2,cy-rh/2),androidx.compose.ui.geometry.Size(rw,rh),style=androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    } else {
                        drawCircle(Color(0xFF65B7D6),radius=min(size.width,size.height)*.28f,center=Offset(cx,cy),style=androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    }
                }
                Text(if(profile==CadSolid.Kind.BOX) "Rectangle dimensions (mm)" else "Circle diameter (mm)",fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    DimField(if(profile==CadSolid.Kind.BOX) "Width" else "Diameter",width,{width=it},Modifier.weight(1f))
                    if(profile==CadSolid.Kind.BOX) DimField("Depth",depth,{depth=it},Modifier.weight(1f))
                }
                DimField("Pad height (mm)",height,{height=it})
                Text("Creates a solid by extruding the selected profile upward from Z = 0.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton={ TextButton(enabled=w!=null && d!=null && h!=null,onClick={onCreate(profile,w!!,if(profile==CadSolid.Kind.CYLINDER) w else d!!,h!!)}) { Text("Create Pad") } },
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}}
    )
}

@Composable
private fun RevolveDialog(onDismiss:()->Unit, onCreate:(Double,Double,Double)->Unit) {
    var outer by remember { mutableStateOf("30") }
    var inner by remember { mutableStateOf("10") }
    var height by remember { mutableStateOf("25") }
    val od=outer.toDoubleOrNull()?.takeIf { it>0 }
    val id=inner.toDoubleOrNull()?.takeIf { it>0 }
    val h=height.toDoubleOrNull()?.takeIf { it>0 }
    val valid=od!=null && id!=null && h!=null && id<od
    AlertDialog(onDismissRequest=onDismiss, title={Text("Revolve · 360°")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Create a hollow rotational solid from a rectangular annular profile around the Z axis.", style=MaterialTheme.typography.bodySmall)
            DimField("Outer diameter (mm)",outer,{outer=it})
            DimField("Inner diameter (mm)",inner,{inner=it})
            DimField("Axial height (mm)",height,{height=it})
            if(id!=null && od!=null && id>=od) Text("Inner diameter must be smaller than outer diameter.", color=MaterialTheme.colorScheme.error, style=MaterialTheme.typography.labelSmall)
        }
    }, confirmButton={TextButton(enabled=valid,onClick={onCreate(od!!,id!!,h!!)}){Text("Create Revolve")}}, dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun DimensionDialog(s: CadSolid, onDismiss:()->Unit, onApply:(CadSolid)->Unit) {
    var w by remember(s.id) { mutableStateOf(s.width.toString()) }; var d by remember(s.id) { mutableStateOf(s.depth.toString()) }; var h by remember(s.id) { mutableStateOf(s.height.toString()) }
    var x by remember(s.id) { mutableStateOf(s.x.toString()) }; var y by remember(s.id) { mutableStateOf(s.y.toString()) }; var z by remember(s.id) { mutableStateOf(s.z.toString()) }
    AlertDialog(onDismissRequest=onDismiss, title={Text("Edit ${s.name}")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Size (mm)", fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { DimField("Width",w,{w=it},Modifier.weight(1f)); DimField("Depth",d,{d=it},Modifier.weight(1f)); DimField("Height",h,{h=it},Modifier.weight(1f)) }
            Text("Position (mm)", fontWeight=FontWeight.SemiBold)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { DimField("X",x,{x=it},Modifier.weight(1f)); DimField("Y",y,{y=it},Modifier.weight(1f)); DimField("Z",z,{z=it},Modifier.weight(1f)) }
        }
    }, confirmButton={TextButton(onClick={ val values=listOf(w,d,h,x,y,z).map{it.toDoubleOrNull()}; if(values.all{it!=null} && values.take(3).all{it!!>0}) onApply(s.copy(width=values[0]!!,depth=values[1]!!,height=values[2]!!,x=values[3]!!,y=values[4]!!,z=values[5]!!)) }) { Text("Apply") }}, dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun DimField(label:String,value:String,onValue:(String)->Unit,modifier:Modifier=Modifier) {
    OutlinedTextField(value,onValue,label={Text(label)},modifier=modifier, singleLine=true, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
}

@Composable
private fun CadViewport(solids:List<CadSolid>, selected:Long?, modifier:Modifier=Modifier) {
    Canvas(modifier.background(Color(0xFF101923), RoundedCornerShape(14.dp))) {
        val scale=min(size.width,size.height)/170f
        val ox=size.width*.5f; val oy=size.height*.64f
        fun project(x:Double,y:Double,z:Double)=Offset(ox+(x-y).toFloat()*scale*.72f, oy+((x+y)*.36-z).toFloat()*scale)
        // Engineering grid on the XY plane.
        for(i in -8..8) {
            val a=project(i*10.0,-80.0,0.0); val b=project(i*10.0,80.0,0.0)
            drawLine(Color(0xFF263747),a,b,1f)
            val c=project(-80.0,i*10.0,0.0); val d=project(80.0,i*10.0,0.0)
            drawLine(Color(0xFF263747),c,d,1f)
        }
        solids.forEach { s ->
            val x=s.x; val y=s.y; val z=s.z; val w=s.width; val d=s.depth; val h=s.height
            val p=listOf(project(x,y,z),project(x+w,y,z),project(x+w,y+d,z),project(x,y+d,z),project(x,y,z+h),project(x+w,y,z+h),project(x+w,y+d,z+h),project(x,y+d,z+h))
            val chosen=s.id==selected
            fun face(ids:List<Int>, color:Color) { val path=Path().apply { moveTo(p[ids[0]].x,p[ids[0]].y); ids.drop(1).forEach{lineTo(p[it].x,p[it].y)}; close() }; drawPath(path,color); for(i in ids.indices) drawLine(if(chosen) Color(0xFFFFD166) else Color(0xFFB6C9D8),p[ids[i]],p[ids[(i+1)%ids.size]],if(chosen)2.5f else 1.5f) }
            if(s.kind==CadSolid.Kind.BOX) { face(listOf(0,1,2,3),Color(0xFF386B91)); face(listOf(0,1,5,4),Color(0xFF4E91B8)); face(listOf(1,2,6,5),Color(0xFF285475)); face(listOf(4,5,6,7),Color(0xFF6EA6C7)) }
            else {
                val top=Path().apply { for(i in 0..64){val a=2*PI*i/64; val q=project(x+w/2+w/2*cos(a),y+d/2+d/2*sin(a),z+h); if(i==0)moveTo(q.x,q.y) else lineTo(q.x,q.y)};close() }
                val bottom=Path().apply { for(i in 0..64){val a=2*PI*i/64; val q=project(x+w/2+w/2*cos(a),y+d/2+d/2*sin(a),z); if(i==0)moveTo(q.x,q.y) else lineTo(q.x,q.y)};close() }
                drawPath(bottom,Color(0xFF285475)); drawLine(Color.LightGray,project(x,y+d/2,z),project(x,y+d/2,z+h),2f)
                if(s.kind==CadSolid.Kind.TUBE) {
                    val r=s.innerDiameter/2
                    val ring=Path().apply { fillType=PathFillType.EvenOdd; addPath(top); for(i in 64 downTo 0){val a=2*PI*i/64; val q=project(x+w/2+r*cos(a),y+d/2+r*sin(a),z+h); if(i==64) moveTo(q.x,q.y) else lineTo(q.x,q.y)}; close() }
                    drawPath(ring,Color(0xFF6EA6C7));
                    val innerA=project(x+w/2+r,y+d/2,z+h); val innerB=project(x+w/2+r,y+d/2,z)
                    drawLine(Color(0xFFB6C9D8),innerA,innerB,1.5f)
                } else drawPath(top,Color(0xFF6EA6C7))
                drawPath(top,Color(0xFFB6C9D8),style=androidx.compose.ui.graphics.drawscope.Stroke(if(chosen)3f else 1.5f))
            }
        }
        val origin=project(0.0,0.0,0.0); drawLine(Color(0xFFE76F51),origin,project(35.0,0.0,0.0),3f); drawLine(Color(0xFF2A9D8F),origin,project(0.0,35.0,0.0),3f); drawLine(Color(0xFFE9C46A),origin,project(0.0,0.0,35.0),3f)
    }
}
