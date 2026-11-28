package com.pentax.k1sync.viewmodel

import com.pentax.k1sync.image.CropStraightenConfig
import com.pentax.k1sync.image.PostToneConfig
import com.pentax.k1sync.spektra.SpektraDarkroomParams
import com.pentax.k1sync.watermark.WatermarkConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StudioEditSnapshot(
    val colorGradeMode: ColorGradeMode,
    val selectedLutId: String,
    val lutIntensity: Float,
    val spektraParams: SpektraDarkroomParams,
    val postTone: PostToneConfig,
    val cropConfig: CropStraightenConfig,
    val config: WatermarkConfig
)

/**
 * Self-contained 25-step Studio Undo / Redo manager with 480ms continuous-slider coalescing.
 */
class StudioHistoryManager(private val maxSteps: Int = 25) {

    private val undoStack = ArrayDeque<StudioEditSnapshot>()
    private val redoStack = ArrayDeque<StudioEditSnapshot>()
    private var lastCoalesceTag: String = ""
    private var lastCoalesceMs: Long = 0L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun record(snapshot: StudioEditSnapshot, coalesceTag: String = "") {
        val now = System.currentTimeMillis()
        if (coalesceTag.isNotEmpty() && coalesceTag == lastCoalesceTag && (now - lastCoalesceMs) < 480L && undoStack.isNotEmpty()) {
            lastCoalesceMs = now
            return
        }
        if (undoStack.lastOrNull() == snapshot) return
        undoStack.addLast(snapshot)
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        redoStack.clear()
        lastCoalesceTag = coalesceTag
        lastCoalesceMs = now
        syncFlags()
    }

    fun undo(current: StudioEditSnapshot): StudioEditSnapshot? {
        if (undoStack.isEmpty()) return null
        val prev = undoStack.removeLast()
        redoStack.addLast(current)
        while (redoStack.size > maxSteps) redoStack.removeFirst()
        lastCoalesceTag = ""
        syncFlags()
        return prev
    }

    fun redo(current: StudioEditSnapshot): StudioEditSnapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        lastCoalesceTag = ""
        syncFlags()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastCoalesceTag = ""
        syncFlags()
    }

    private fun syncFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
