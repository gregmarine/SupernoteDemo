package org.iccnet.supernotedemo

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.iccnet.supernotedemo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the status line in sync with draw/erase/clear gestures.
        binding.drawing.onStrokesChanged = { updateStatus() }

        // Load any previously saved note once the view has been sized.
        binding.drawing.post {
            binding.drawing.loadNote()
            updateStatus()
        }

        binding.penBtn.setOnClickListener {
            binding.drawing.setTool(DrawingView.Tool.PEN)
            updateStatus()
        }
        binding.eraserBtn.setOnClickListener {
            binding.drawing.setTool(DrawingView.Tool.ERASER)
            updateStatus()
        }
        binding.clearBtn.setOnClickListener {
            binding.drawing.clearNote()
            updateStatus()
        }
        binding.saveBtn.setOnClickListener {
            binding.drawing.saveNote()
            Toast.makeText(this, "Saved ${binding.drawing.strokeCount()} strokes", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Autosave so nothing is lost on background/close.
        binding.drawing.saveNote()
    }

    private fun updateStatus() {
        val ink = if (binding.drawing.isFirmwareInk()) "firmware ink" else "canvas fallback"
        val toolName = if (binding.drawing.currentTool() == DrawingView.Tool.PEN) "Pen" else "Eraser"
        binding.status.text = "$toolName · $ink · ${binding.drawing.strokeCount()} strokes"
    }
}
