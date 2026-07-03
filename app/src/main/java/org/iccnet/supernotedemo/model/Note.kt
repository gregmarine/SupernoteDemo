package org.iccnet.supernotedemo.model

/** One sampled stylus point. [p] is pressure (0..1), kept for future width modulation. */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val p: Float = 1f,
)

/** A single ink stroke: an ordered polyline plus its paint attributes. */
data class Stroke(
    val points: MutableList<StrokePoint> = mutableListOf(),
    val color: Int = android.graphics.Color.BLACK,
    val width: Float = 3f,
    val tool: String = TOOL_PEN,
) {
    companion object {
        const val TOOL_PEN = "pen"
        const val TOOL_HIGHLIGHTER = "highlighter"
    }
}

/** The whole page. [width]/[height] record the canvas the strokes were drawn on. */
data class Note(
    var version: Int = 1,
    var width: Int = 0,
    var height: Int = 0,
    val strokes: MutableList<Stroke> = mutableListOf(),
)
