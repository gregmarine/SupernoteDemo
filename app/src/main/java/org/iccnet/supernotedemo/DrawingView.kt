package org.iccnet.supernotedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.iccnet.supernotedemo.io.NoteStore
import org.iccnet.supernotedemo.model.Note
import org.iccnet.supernotedemo.model.Stroke
import org.iccnet.supernotedemo.model.StrokePoint

/**
 * A minimal e-ink notebook surface.
 *
 * Committed strokes are baked into [renderBitmap]; onDraw blits that bitmap. Input always
 * comes from MotionEvent.
 *
 * On a real Supernote ([firmware] == true) the firmware overlay owns the *live* view while
 * writing: it paints stroke ink to the EPDC at sub-frame latency and keeps maintaining it.
 * We do NOT bake/redraw/clear per pen lift — that fights the hardware and flashes. Instead
 * finished strokes accumulate in [pendingStrokes] (data only, not drawn by us) while the
 * firmware overlay shows them, and the *handoff* — bake pending into the bitmap, then
 * release (clear) the firmware overlay — happens once at a natural boundary: tool change,
 * focus loss, page load/clear, detach. This mirrors the Onyx/BOOX approach in Notesprout.
 *
 * On any non-firmware device there is no hardware overlay, so we draw the active stroke and
 * bake on pen-up immediately (the canvas is the only layer).
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Tool { PEN, ERASER }

    var penColor: Int = Color.BLACK
    var penWidth: Float = 3f
    var eraserRadius: Float = 22f

    /** Notified whenever the stroke set changes (draw/erase/clear/load) so the UI can refresh. */
    var onStrokesChanged: (() -> Unit)? = null

    private var tool = Tool.PEN

    private var note = Note()
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var active: Stroke? = null
    private val activePath = Path()

    // Strokes finished this firmware session, shown live by the hardware overlay but not yet
    // baked into our bitmap. Drained at the next handoff (see releaseFirmwareOverlay).
    private val pendingStrokes = mutableListOf<Stroke>()

    /** Whether the Supernote firmware ink daemon is present. Computed once, lazily. */
    private val firmware: Boolean by lazy { SupernoteInk.isAvailable() }

    fun isFirmwareInk(): Boolean = firmware

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupFirmwareInk()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // The view stays attached across a task-switch, so onAttachedToWindow won't re-run.
        // Focus change is the reliable re-entry point: while we're away the firmware hands
        // the pen back to other apps and resets full-UI ink, so we must re-assert on return.
        if (hasWindowFocus) setupFirmwareInk() else teardownFirmwareInk()
    }

    override fun onDetachedFromWindow() {
        if (firmware) {
            releaseFirmwareOverlay()
            SupernoteInk.clearDisableAreas()
            SupernoteInk.enableFullUiAuto(context, false)
        }
        super.onDetachedFromWindow()
    }

    /** (Re-)claim the firmware pen and turn on full-UI ink. Idempotent; safe to call often. */
    private fun setupFirmwareInk() {
        if (!firmware) return
        // Order matches the KOReader plugin: claim pen ownership, enable full-UI ink (so a
        // third-party app gets painted everywhere), keep ink off the toolbar, then set the pen.
        SupernoteInk.claimPen()
        SupernoteInk.enableFullUiAuto(context, true)
        SupernoteInk.enableAutoRegal(context, true)   // anti-ghosting waveform
        applyDisableAreas()
        applyToolToFirmware()
    }

    private fun teardownFirmwareInk() {
        if (!firmware) return
        releaseFirmwareOverlay()
        SupernoteInk.enableFullUiAuto(context, false)
    }

    /**
     * The handoff: bake any strokes the firmware overlay has been showing into our bitmap,
     * then release (clear) the overlay so the app layer takes over. Called at natural
     * boundaries only — never per pen lift.
     */
    private fun releaseFirmwareOverlay() {
        if (!firmware) return
        commitPendingStrokes()
        SupernoteInk.clearAll()
    }

    /** Bake pending strokes into the visible bitmap and show them (one repaint). */
    private fun commitPendingStrokes() {
        if (pendingStrokes.isEmpty()) return
        renderCanvas?.let { c -> for (s in pendingStrokes) drawStroke(c, s) }
        pendingStrokes.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderBitmap = bmp
        renderCanvas = Canvas(bmp)
        note.width = w
        note.height = h
        rebuildBitmap()
        if (firmware) post { applyDisableAreas() }
        invalidate()
    }

    // ---------------------------------------------------------------- public API

    fun setTool(t: Tool) {
        // Tool change is a handoff boundary: bake the overlay's ink into our bitmap and
        // release it before switching the firmware pen/eraser.
        if (firmware) releaseFirmwareOverlay()
        tool = t
        if (firmware) applyToolToFirmware()
        invalidate()
    }

    fun currentTool(): Tool = tool

    fun loadNote() {
        note = NoteStore.load(context)
        pendingStrokes.clear()
        // Keep the on-screen canvas size; the loaded strokes render into it as-is.
        renderBitmap?.let { note.width = it.width; note.height = it.height }
        rebuildBitmap()
        if (firmware) SupernoteInk.clearAll()
        invalidate()
    }

    fun saveNote() {
        // note.strokes already includes pending strokes (added on pen-up), so a save is
        // valid even before the visual handoff.
        NoteStore.save(context, note)
    }

    fun clearNote() {
        note.strokes.clear()
        pendingStrokes.clear()
        active = null
        activePath.reset()
        renderCanvas?.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        invalidate()
        if (firmware) SupernoteInk.clearAll()
    }

    fun strokeCount(): Int = note.strokes.size

    // ---------------------------------------------------------------- rendering

    private fun rebuildBitmap() {
        val c = renderCanvas ?: return
        c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        for (s in note.strokes) drawStroke(c, s)
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        strokePaint.color = s.color
        strokePaint.strokeWidth = s.width
        strokePaint.alpha = if (s.tool == Stroke.TOOL_HIGHLIGHTER) 128 else 255
        if (s.points.size == 1) {
            // A dot: stroke a zero-length segment so the round cap renders a blob.
            val p = s.points[0]
            canvas.drawPoint(p.x, p.y, strokePaint)
            return
        }
        val path = Path()
        path.moveTo(s.points[0].x, s.points[0].y)
        for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
        canvas.drawPath(path, strokePaint)
    }

    override fun onDraw(canvas: Canvas) {
        renderBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // Draw the in-progress stroke ourselves only when the firmware isn't painting it.
        if (!firmware && tool == Tool.PEN) {
            active?.let {
                strokePaint.color = it.color
                strokePaint.strokeWidth = it.width
                strokePaint.alpha = 255
                canvas.drawPath(activePath, strokePaint)
            }
        }
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only the stylus draws or erases. Finger touches are ignored here (not consumed),
        // leaving them free for gestures. This prevents a resting palm/finger from inking.
        if (!isStylus(event)) return false

        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tool == Tool.ERASER) {
                    eraseAt(x, y)
                } else {
                    val s = Stroke(color = penColor, width = penWidth, tool = Stroke.TOOL_PEN)
                    s.points.add(StrokePoint(x, y, event.pressure))
                    active = s
                    activePath.reset()
                    activePath.moveTo(x, y)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tool == Tool.ERASER) {
                    for (i in 0 until event.historySize) {
                        eraseAt(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    eraseAt(x, y)
                    return true
                }
                val s = active ?: return true
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    s.points.add(StrokePoint(hx, hy, event.getHistoricalPressure(i)))
                    activePath.lineTo(hx, hy)
                }
                s.points.add(StrokePoint(x, y, event.pressure))
                activePath.lineTo(x, y)
                // Firmware paints its own live ink; only invalidate on the fallback path.
                if (!firmware) invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tool == Tool.ERASER) return true
                val s = active ?: return true
                note.strokes.add(s)
                active = null
                activePath.reset()
                if (firmware) {
                    // The firmware overlay is showing this stroke and keeps maintaining it.
                    // Don't bake/clear/refresh now — just hold the data. The visible handoff
                    // to our bitmap happens later (tool change / focus loss), avoiding the
                    // per-stroke flash and "enlargement" from a competing bitmap draw.
                    pendingStrokes.add(s)
                } else {
                    // No hardware overlay: our canvas is the only layer, so bake and show now.
                    renderCanvas?.let { drawStroke(it, s) }
                    invalidate()
                }
                onStrokesChanged?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** True only for the EMR stylus (tip) or its eraser end — never finger/palm. */
    private fun isStylus(event: MotionEvent): Boolean {
        val t = event.getToolType(0)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun eraseAt(x: Float, y: Float) {
        val before = note.strokes.size
        note.strokes.removeAll { hitTest(it, x, y, eraserRadius) }
        if (note.strokes.size != before) {
            rebuildBitmap()
            invalidate()
            onStrokesChanged?.invoke()
        }
    }

    /** True if any sampled point of [s] is within [radius] (plus half stroke width) of (x,y). */
    private fun hitTest(s: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val r = radius + s.width / 2f
        val r2 = r * r
        for (p in s.points) {
            val dx = p.x - x
            val dy = p.y - y
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    // ---------------------------------------------------------------- firmware helpers

    /** Push the current tool down to the firmware: pen inks, eraser stops the firmware inking. */
    private fun applyToolToFirmware() {
        if (!firmware) return
        if (tool == Tool.PEN) {
            SupernoteInk.claimPen()
            applyPenToFirmware()
        } else {
            // Put the firmware in eraser mode so it stops painting NEEDLE ink along the
            // stylus path while our software hit-test does the actual stroke removal.
            val emr = eraserEmr()
            SupernoteInk.setEraser(false, emr)
            android.util.Log.i("DrawingView", "firmware setEraser emr=$emr")
        }
    }

    private fun applyPenToFirmware() {
        // NEEDLE (10) = uniform-width ballpoint, so the live firmware overlay matches the
        // uniform-width stroke we bake into the bitmap. INK/CALLIGRAPHY vary with pressure.
        val emr = emrSize(penWidth)
        SupernoteInk.setPen(SupernoteInk.Pen.NEEDLE, emr, SupernoteInk.Color.BLACK)
        android.util.Log.i("DrawingView", "firmware setPen NEEDLE emr=$emr (penWidth=$penWidth)")
    }

    /** Eraser EMR size, mirroring KOReader's max(400, width*50). */
    private fun eraserEmr(): Int = (eraserRadius * 50f).toInt().coerceAtLeast(400)

    /**
     * px -> firmware EMR size. The firmware's Needle penSizeArray runs ~200..2400; KOReader
     * uses floor(width*100) hugging the thin end (w=3 -> 300). An emr near 0 paints an
     * invisible sub-pixel stroke, so we clamp to a visible floor of 200.
     */
    private fun emrSize(widthPx: Float): Int =
        (widthPx * 100f).toInt().coerceIn(200, 1200)

    /** Keep firmware ink out of the toolbar strip that sits above this view. */
    private fun applyDisableAreas() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val top = loc[1]
        if (top > 0) {
            val screenW = resources.displayMetrics.widthPixels
            SupernoteInk.setDisableAreas(listOf(Rect(0, 0, screenW, top)))
        } else {
            SupernoteInk.clearDisableAreas()
        }
    }
}
