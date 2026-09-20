package app.seb3thehacker.gearslip

import android.view.Surface

/**
 * Lets [GearslipRunner] drive on-screen content without owning any Android UI itself.
 *
 * The protocol side decides *when* a surface is needed and *where* touches landed; the
 * activity decides *what* to render and how to inject events, because both need a UI thread
 * and a Context.
 */
interface Projection {

    /** Called once the encoder's input Surface exists and content should start rendering. */
    fun onSurfaceReady(surface: Surface, width: Int, height: Int, densityDpi: Int)

    /**
     * A touch from the head unit, already scaled into projected-video coordinates.
     * [action] is an Android MotionEvent action - Android Auto's PointerAction enum uses
     * identical values (DOWN=0, UP=1, MOVED=2, POINTER_DOWN=5, POINTER_UP=6).
     */
    fun onTouch(action: Int, x: Float, y: Float)

    fun onProjectionStopped()
}
