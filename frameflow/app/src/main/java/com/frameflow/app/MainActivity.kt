package com.frameflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FrameflowApp() } }
    }
}

enum class Tool { Brush, Eraser, SmartSelect, ColorRepeat }
enum class Part(val label: String) {
    None("None"), Body("Body"), Face("Face"), LeftArm("Left arm"), RightArm("Right arm"),
    LeftLeg("Left leg"), RightLeg("Right leg"), Background("Background"), Accessory("Accessory")
}

data class BrushPreset(val name: String, val family: String, val width: Float, val alpha: Float)
data class StrokeData(
    val id: String = UUID.randomUUID().toString(),
    val points: List<Offset>,
    val color: Color,
    val width: Float,
    val alpha: Float,
    val erase: Boolean
)

class LayerState(name: String, part: Part, strokes: List<StrokeData> = emptyList()) {
    var name by mutableStateOf(name)
    var part by mutableStateOf(part)
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    val strokes = mutableStateListOf<StrokeData>().apply { addAll(strokes) }
    fun cloneLayer() = LayerState(name, part, strokes.map { it.copy(points = it.points.toList()) }).also {
        it.visible = visible
        it.locked = locked
    }
}

class FrameState(duration: Int = 1000, layers: List<LayerState> = defaultLayers()) {
    var durationMs by mutableIntStateOf(duration)
    val layers = mutableStateListOf<LayerState>().apply { addAll(layers) }
    fun cloneFrame() = FrameState(durationMs, layers.map { it.cloneLayer() })
}

private fun defaultLayers() = listOf(
    LayerState("Body", Part.Body),
    LayerState("Face", Part.Face),
    LayerState("Left arm", Part.LeftArm),
    LayerState("Right arm", Part.RightArm),
    LayerState("Left leg", Part.LeftLeg),
    LayerState("Right leg", Part.RightLeg),
    LayerState("Background", Part.Background)
)

private val brushFamilies = listOf("Ink", "Pencil", "Marker", "Paint", "Pixel", "Spray", "Chalk", "Calligraphy", "Highlighter", "Texture", "Crayon", "Airbrush")
private val brushes = buildList {
    var i = 1
    brushFamilies.forEachIndexed { familyIndex, family ->
        repeat(20) { variant ->
            add(BrushPreset("$family ${variant + 1}", family, 3f + (variant * 2.1f) + familyIndex, 0.55f + (variant % 6) * 0.075f))
            i++
        }
    }
}
private val erasers = List(60) { BrushPreset("Eraser ${it + 1}", "Eraser", 8f + it * 2.2f, 1f) }

class EditorState {
    val frames = mutableStateListOf(FrameState())
    var frameIndex by mutableIntStateOf(0)
    var layerIndex by mutableIntStateOf(0)
    var tool by mutableStateOf(Tool.Brush)
    var brush by mutableStateOf(brushes[8])
    var eraser by mutableStateOf(erasers[12])
    var hue by mutableFloatStateOf(220f)
    var saturation by mutableFloatStateOf(.72f)
    var value by mutableFloatStateOf(.88f)
    var alpha by mutableFloatStateOf(1f)
    var onion by mutableStateOf(true)
    var onionAlpha by mutableFloatStateOf(.18f)
    var playing by mutableStateOf(false)
    var showBrushes by mutableStateOf(false)
    var showErasers by mutableStateOf(false)
    var showColors by mutableStateOf(false)
    var showLayers by mutableStateOf(false)
    var showSmart by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    val frame get() = frames[frameIndex]
    val layer get() = frame.layers[layerIndex.coerceIn(frame.layers.indices)]
    val color get() = Color.hsv(hue, saturation, value, alpha)

    fun cloneFrame() {
        frames.add(frameIndex + 1, frame.cloneFrame())
        frameIndex++
        selectedIds.clear()
    }

    fun selectWholePartAt(point: Offset) {
        val hit = frame.layers.asReversed().firstNotNullOfOrNull { layer ->
            if (!layer.visible) null else layer.strokes.asReversed().firstOrNull { hitStroke(it, point) }?.let { layer to it }
        } ?: run { selectedIds.clear(); return }
        val targetPart = hit.first.part
        selectedIds.clear()
        if (targetPart != Part.None && targetPart != Part.Background) {
            frame.layers.filter { it.part == targetPart }.flatMap { it.strokes }.forEach { selectedIds.add(it.id) }
        } else {
            selectedIds.add(hit.second.id)
        }
    }

    fun selectColorAt(point: Offset) {
        val hit = frame.layers.asReversed().firstNotNullOfOrNull { layer ->
            layer.strokes.asReversed().firstOrNull { !it.erase && hitStroke(it, point) }
        } ?: run { selectedIds.clear(); return }
        selectedIds.clear()
        frame.layers.flatMap { it.strokes }.filter { !it.erase && colorDistance(it.color, hit.color) < .08f }.forEach { selectedIds.add(it.id) }
    }

    fun moveSelection(delta: Offset) {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        frame.layers.forEach { layer ->
            for (i in layer.strokes.indices) {
                val s = layer.strokes[i]
                if (s.id in ids) layer.strokes[i] = s.copy(points = s.points.map { it + delta })
            }
        }
    }

    fun deleteSelection() {
        val ids = selectedIds.toSet()
        frame.layers.forEach { layer -> layer.strokes.removeAll { it.id in ids } }
        selectedIds.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameflowApp() {
    val state = remember { EditorState() }
    var livePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(state.playing) {
        while (state.playing) {
            delay(state.frame.durationMs.toLong())
            state.frameIndex = (state.frameIndex + 1) % state.frames.size
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Frameflow", fontWeight = FontWeight.SemiBold); Text("Static animation", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    TextButton(onClick = { state.onion = !state.onion }) { Text(if (state.onion) "Onion ON" else "Onion") }
                    TextButton(onClick = { state.playing = !state.playing }) { Text(if (state.playing) "Stop" else "Play") }
                }
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = state::cloneFrame, text = { Text("+ Clone frame") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            ) {
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .pointerInput(state.tool, state.frameIndex, state.layerIndex, state.brush, state.eraser, state.color) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    when (state.tool) {
                                        Tool.Brush, Tool.Eraser -> livePoints = listOf(p)
                                        Tool.SmartSelect -> state.selectWholePartAt(p)
                                        Tool.ColorRepeat -> state.selectColorAt(p)
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    when (state.tool) {
                                        Tool.Brush, Tool.Eraser -> livePoints = livePoints + change.position
                                        Tool.SmartSelect, Tool.ColorRepeat -> state.moveSelection(drag)
                                    }
                                },
                                onDragEnd = {
                                    if ((state.tool == Tool.Brush || state.tool == Tool.Eraser) && livePoints.size > 1 && !state.layer.locked) {
                                        val preset = if (state.tool == Tool.Eraser) state.eraser else state.brush
                                        state.layer.strokes.add(StrokeData(points = livePoints, color = state.color, width = preset.width, alpha = preset.alpha, erase = state.tool == Tool.Eraser))
                                    }
                                    livePoints = emptyList()
                                }
                            )
                        }
                ) {
                    drawRect(Color.White)
                    if (state.onion && !state.playing && state.frameIndex > 0) drawFrame(state.frames[state.frameIndex - 1], state.onionAlpha)
                    drawFrame(state.frame, 1f, state.selectedIds.toSet())
                    if (livePoints.size > 1) {
                        val preset = if (state.tool == Tool.Eraser) state.eraser else state.brush
                        drawOneStroke(StrokeData(points = livePoints, color = state.color, width = preset.width, alpha = preset.alpha, erase = state.tool == Tool.Eraser), 1f, false)
                    }
                }

                if (state.selectedIds.isNotEmpty()) {
                    Row(Modifier.align(Alignment.TopCenter).padding(10.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Whole part selected · ${state.selectedIds.size} strokes", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = state::deleteSelection) { Text("Delete") }
                    }
                }
            }

            Timeline(state)
            ToolBar(state)
        }
    }

    if (state.showBrushes) PresetSheet("Brushes · 240", brushes, state.brush, { state.showBrushes = false }) { state.brush = it; state.tool = Tool.Brush }
    if (state.showErasers) PresetSheet("Erasers · 60", erasers, state.eraser, { state.showErasers = false }) { state.eraser = it; state.tool = Tool.Eraser }
    if (state.showColors) ColorSheet(state)
    if (state.showLayers) LayersSheet(state)
    if (state.showSmart) SmartSheet(state)
}

@Composable
private fun Timeline(state: EditorState) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.frames.forEachIndexed { index, frame ->
                val width = (76 + frame.durationMs / 18).coerceIn(82, 230).dp
                Box(
                    Modifier.width(width).height(64.dp)
                        .background(if (index == state.frameIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                        .clickable { state.frameIndex = index; state.selectedIds.clear() }
                ) {
                    Column(Modifier.padding(9.dp)) {
                        Text("Frame ${index + 1}", fontWeight = FontWeight.Medium)
                        Text("${frame.durationMs / 1000f}s", style = MaterialTheme.typography.labelSmall)
                    }
                    Box(
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(14.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .22f), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                            .pointerInput(frame.durationMs) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val ms = with(density) { drag.x.toDp().value * 18f }.toInt()
                                    frame.durationMs = (frame.durationMs + ms).coerceIn(100, 10000)
                                }
                            }
                    )
                }
            }
            Spacer(Modifier.width(80.dp))
        }
        Text("Drag the right edge of a frame to change how long it holds.", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolBar(state: EditorState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ToolButton("Brush", state.tool == Tool.Brush) { state.showBrushes = true }
        ToolButton("Eraser", state.tool == Tool.Eraser) { state.showErasers = true }
        ToolButton("Colour", false) { state.showColors = true }
        ToolButton("Layers", false) { state.showLayers = true }
        ToolButton("Smart", state.tool == Tool.SmartSelect || state.tool == Tool.ColorRepeat) { state.showSmart = true }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(title: String, list: List<BrushPreset>, selected: BrushPreset, dismiss: () -> Unit, choose: (BrushPreset) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("Searchable categories can come next; every preset here is already usable.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(list) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.family} · ${p.width.toInt()} px") },
                        trailingContent = { if (p == selected) Text("Selected") },
                        modifier = Modifier.clickable { choose(p); dismiss() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSheet(state: EditorState) {
    ModalBottomSheet(onDismissRequest = { state.showColors = false }) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Colour", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(state.color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text("Infinite HSV colour + opacity")
            }
            SliderRow("Hue", state.hue, 0f..360f) { state.hue = it }
            SliderRow("Saturation", state.saturation, 0f..1f) { state.saturation = it }
            SliderRow("Value", state.value, 0f..1f) { state.value = it }
            SliderRow("Opacity", state.alpha, 0f..1f) { state.alpha = it }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(86.dp))
        Slider(value, set, Modifier.weight(1f), valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(state: EditorState) {
    ModalBottomSheet(onDismissRequest = { state.showLayers = false }) {
        Column(Modifier.fillMaxWidth().padding(14.dp).padding(bottom = 24.dp)) {
            Text("Layers", style = MaterialTheme.typography.titleLarge)
            state.frame.layers.forEachIndexed { index, layer ->
                ListItem(
                    headlineContent = { Text(layer.name) },
                    supportingContent = { Text("Part: ${layer.part.label} · ${layer.strokes.size} strokes") },
                    leadingContent = { Checkbox(layer.visible, { layer.visible = it }) },
                    trailingContent = {
                        TextButton(onClick = {
                            val parts = Part.entries
                            layer.part = parts[(parts.indexOf(layer.part) + 1) % parts.size]
                        }) { Text("Tag") }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (index == state.layerIndex) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f) else Color.Transparent),
                    modifier = Modifier.clickable { state.layerIndex = index }
                )
            }
            TextButton(onClick = { state.frame.layers.add(0, LayerState("Layer ${state.frame.layers.size + 1}", Part.None)); state.layerIndex = 0 }) { Text("+ Add layer") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartSheet(state: EditorState) {
    ModalBottomSheet(onDismissRequest = { state.showSmart = false }) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Smart", style = MaterialTheme.typography.titleLarge)
            ListItem(
                headlineContent = { Text("Whole-limb select") },
                supportingContent = { Text("Tap a limb. Every stroke, fill, shade, colour and brush mark belonging to that tagged limb is selected together. Drag to move it; Delete removes it all.") },
                modifier = Modifier.clickable { state.tool = Tool.SmartSelect; state.showSmart = false }
            )
            ListItem(
                headlineContent = { Text("Colour Repeat") },
                supportingContent = { Text("Separate tool: tap one colour to select matching strokes across the frame.") },
                modifier = Modifier.clickable { state.tool = Tool.ColorRepeat; state.showSmart = false }
            )
            SliderRow("Onion", state.onionAlpha, .05f..45f) { state.onionAlpha = it }
            Text("Local vision-model limb detection can feed the same whole-part selection system later; selection semantics already work without pretending AI is running.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrame(frame: FrameState, alpha: Float, selected: Set<String> = emptySet()) {
    frame.layers.filter { it.visible }.asReversed().forEach { layer ->
        layer.strokes.forEach { stroke -> drawOneStroke(stroke, alpha, stroke.id in selected) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOneStroke(stroke: StrokeData, alpha: Float, selected: Boolean) {
    if (stroke.points.size < 2) return
    val path = Path().apply {
        moveTo(stroke.points.first().x, stroke.points.first().y)
        stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    if (selected) drawPath(path, Color(0xFF6750A4).copy(alpha = .35f), style = Stroke(stroke.width + 14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
        path,
        stroke.color.copy(alpha = stroke.color.alpha * stroke.alpha * alpha),
        style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = if (stroke.erase) BlendMode.Clear else BlendMode.SrcOver
    )
}

private fun hitStroke(stroke: StrokeData, point: Offset): Boolean {
    val threshold = (stroke.width * .8f).coerceAtLeast(18f)
    return stroke.points.any { (it - point).getDistance() <= threshold }
}

private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return sqrt(dr * dr + dg * dg + db * db)
}
