package org.iccnet.supernotedemo

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Kotlin port of the KOReader `supernote_ink.lua` plugin — a thin JNI/binder client
 * for the Supernote firmware's stylus ink daemon.
 *
 * The firmware registers a Binder service ("service_myservice", legacy alias
 * "service.myservice") with interface token "android.demo.IMyService". The app claims
 * pen ownership, configures where the firmware may NOT paint (disable areas), sets the
 * active pen/eraser, and clears the EPDC ink overlay — all via raw Parcel transactions.
 * The firmware itself paints live stroke pixels to the e-ink overlay at sub-frame
 * latency; point data still arrives through the normal Android MotionEvent stream.
 *
 * Every method is a safe no-op when the firmware binder is absent (e.g. on an emulator,
 * an Onyx device, or a Supernote firmware that doesn't expose the service), so callers
 * can invoke these unconditionally and fall back to plain canvas rendering.
 *
 * Reference: https://github.com/plateaukao/supernote_draw (SupernoteInk.kt / HandWriteClient).
 * Pen codes are confirmed for Nomad (deviceType 3 / A5X2); Manta shares the firmware.
 */
object SupernoteInk {
    private const val TAG = "SupernoteInk"

    private const val IFACE_TOKEN = "android.demo.IMyService"
    private const val APP_NAME = "supernote-demo"
    private val SERVICE_NAMES = arrayOf("service_myservice", "service.myservice")

    // Firmware transaction codes (from the decompiled HandWriteClient).
    private const val TX_WRITE_APP_INFO = 0
    private const val TX_DISABLE_AREA = 1
    private const val TX_PEN = 2
    private const val TX_DRAW_BUFFER = 6

    /** Pen type codes for the firmware's penTypeArray. */
    object Pen {
        const val NEEDLE = 10
        const val INK = 16          // highlighter is MARK
        const val MARK = 11
        const val CALLIGRAPHY = 15
    }

    /** Firmware color codes (grayscale on e-ink). */
    object Color {
        const val BLACK = 0
        const val DARK_GRAY = -101
        const val GRAY = -102
        const val LIGHT_GRAY = 254
    }

    private var binder: IBinder? = null
    // Tri-state: null = untested, false = absent, true = present.
    private var available: Boolean? = null
    private var einkApiDumped = false

    @Synchronized
    fun isAvailable(): Boolean {
        available?.let { return it }
        binder = lookupBinder()
        val ok = binder != null
        available = ok
        if (!ok) Log.i(TAG, "service_myservice not present; firmware ink disabled (canvas fallback)")
        return ok
    }

    private fun lookupBinder(): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            for (name in SERVICE_NAMES) {
                val b = getService.invoke(null, name) as? IBinder
                if (b != null) {
                    Log.i(TAG, "found firmware binder \"$name\"")
                    return b
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "binder lookup failed: ${t.message}")
            null
        }
    }

    /**
     * Run one transaction. [writeArgs] writes the per-call int payload after the
     * "interface token + app name" preamble that every transaction shares.
     */
    @Synchronized
    private fun transact(code: Int, writeArgs: (Parcel) -> Unit) {
        if (!isAvailable()) return
        var b = binder
        if (b == null || !b.isBinderAlive) {
            // Firmware service may have restarted; re-look-up once.
            b = lookupBinder()
            binder = b
            if (b == null) {
                available = false
                Log.w(TAG, "binder gone, marking unavailable")
                return
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeString(APP_NAME)
            writeArgs(data)
            b.transact(code, data, reply, 0)
        } catch (t: Throwable) {
            // DeadObjectException etc. — drop the cached proxy so the next call re-looks up.
            Log.w(TAG, "transact($code) failed: ${t.message}")
            binder = null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** tx=0 WRITE_APP_INFO — claim pen ownership for this app. */
    fun claimPen() = transact(TX_WRITE_APP_INFO) {
        it.writeInt(0)
        it.writeInt(0)
    }

    /** tx=2 PEN — set active pen type, EMR size, and color. */
    fun setPen(type: Int, sizeEmr: Int, color: Int) = transact(TX_PEN) {
        it.writeInt(type)
        it.writeInt(sizeEmr)
        it.writeInt(color)
    }

    /** tx=2 PEN — configure the firmware eraser (type 1 = round, 3 = rectangular). */
    fun setEraser(rectangular: Boolean, sizeEmr: Int) = transact(TX_PEN) {
        it.writeInt(if (rectangular) 3 else 1)
        it.writeInt(sizeEmr)
        it.writeInt(255)
    }

    /** tx=6 DRAW_BUFFER — clear the EPDC ink overlay (after baking a stroke into our bitmap). */
    fun clearAll() = transact(TX_DRAW_BUFFER) {
        it.writeInt(255)
        it.writeInt(0)
    }

    /** tx=1 DISABLE_AREA — one full-screen rect where the firmware must not paint. */
    fun setFullScreenDisable(width: Int, height: Int) = transact(TX_DISABLE_AREA) {
        it.writeInt(1)          // rect count
        it.writeInt(0)          // x
        it.writeInt(0)          // y
        it.writeInt(width)
        it.writeInt(height)
        it.writeInt(0)          // reserved / flags
    }

    /** tx=1 DISABLE_AREA — keep firmware ink off the given rects (e.g. our toolbar). */
    fun setDisableAreas(rects: List<Rect>) = transact(TX_DISABLE_AREA) { p ->
        p.writeInt(rects.size)
        for (r in rects) {
            p.writeInt(r.left)
            p.writeInt(r.top)
            p.writeInt(r.width())
            p.writeInt(r.height())
            p.writeInt(0)
        }
    }

    /** tx=1 DISABLE_AREA — clear all disable areas (firmware may paint everywhere). */
    fun clearDisableAreas() = transact(TX_DISABLE_AREA) {
        it.writeInt(0)          // zero rects
    }

    private fun einkService(context: Context): Any? =
        try { context.getSystemService("eink") } catch (t: Throwable) { null }

    /**
     * Enable Regal (E-Ink anti-ghosting waveform). This is the standard remedy for a
     * partial-refresh "ghost" that only clears on a later refresh — it lets the firmware
     * clean residual pixels without a full-screen flash. [level] semantics are unknown;
     * 0 is a safe default and the call no-ops if the signature doesn't match.
     */
    fun enableAutoRegal(context: Context, enable: Boolean, level: Int = 0) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod(
                "enableAutoRegal",
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(eink, enable, level)
            Log.i(TAG, "enableAutoRegal($enable,$level) ok")
        } catch (t: Throwable) {
            Log.w(TAG, "enableAutoRegal failed: ${t.message}")
        }
    }

    /**
     * Refresh the e-ink screen so a freshly baked stroke repaints cleanly and any firmware
     * overlay ghost is wiped. [full] requests a full (flashy) refresh; false is a lighter
     * partial refresh suitable per-stroke. [mode] waveform is firmware-defined (0 default).
     */
    fun screenRefresh(context: Context, full: Boolean, mode: Int = 0) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod(
                "screenRefresh",
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(eink, full, mode)
            Log.i(TAG, "screenRefresh($full,$mode) ok")
        } catch (t: Throwable) {
            Log.w(TAG, "screenRefresh failed: ${t.message}")
        }
    }

    /** Full-frame clean refresh (flashes the whole screen). Good for page clear/load. */
    fun sendOneFullFrame(context: Context) {
        if (!isAvailable()) return
        val eink = einkService(context) ?: return
        try {
            eink.javaClass.getMethod("sendOneFullFrame").invoke(eink)
            Log.i(TAG, "sendOneFullFrame ok")
        } catch (t: Throwable) {
            Log.w(TAG, "sendOneFullFrame failed: ${t.message}")
        }
    }

    /**
     * Reflection on Activity.getSystemService("eink").enableFullUiAuto(boolean).
     * Required so a third-party app gets ink painted everywhere, not just inside
     * whitelisted firmware apps. Some firmwares' eink service lacks the method — we
     * degrade silently to the canvas path in that case.
     */
    fun enableFullUiAuto(context: Context, enable: Boolean) {
        if (!isAvailable()) return
        try {
            val eink = context.getSystemService("eink") ?: run {
                Log.d(TAG, "eink system service not present")
                return
            }
            if (!einkApiDumped) {
                einkApiDumped = true
                // One-time discovery: log the eink service's API so we can find a refresh
                // call to clean up the EPD after baking a stroke (kills firmware ghosting).
                val sigs = eink.javaClass.methods
                    .filter { it.declaringClass != Any::class.java }
                    .map { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
                    .distinct().sorted()
                Log.i(TAG, "eink=${eink.javaClass.name} methods:\n${sigs.joinToString("\n")}")
            }
            val m = eink.javaClass.getMethod("enableFullUiAuto", Boolean::class.javaPrimitiveType)
            m.invoke(eink, enable)
            Log.i(TAG, "enableFullUiAuto($enable) ok via ${eink.javaClass.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "enableFullUiAuto unavailable: ${t.message}")
        }
    }
}
