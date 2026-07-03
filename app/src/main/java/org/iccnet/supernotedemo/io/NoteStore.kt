package org.iccnet.supernotedemo.io

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.iccnet.supernotedemo.model.Note
import java.io.File

/**
 * Dead-simple JSON persistence for a single [Note]. Writes to the app's external files
 * dir so it can be pulled off the device without root:
 *   adb pull /sdcard/Android/data/org.iccnet.supernotedemo/files/note.json
 */
object NoteStore {
    private const val TAG = "NoteStore"
    private const val FILE_NAME = "note.json"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun load(context: Context): Note {
        val f = file(context)
        if (!f.exists()) return Note()
        return try {
            gson.fromJson(f.readText(), Note::class.java) ?: Note()
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            Note()
        }
    }

    fun save(context: Context, note: Note) {
        val f = file(context)
        try {
            f.writeText(gson.toJson(note))
            Log.i(TAG, "saved ${note.strokes.size} strokes -> ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
        }
    }
}
