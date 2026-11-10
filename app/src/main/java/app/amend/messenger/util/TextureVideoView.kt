package app.amend.messenger.util

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws a WebRTC video track into an ordinary view (a TextureView), filling it edge to edge.
 *
 * Used for our own small camera window. A SurfaceView — what WebRTC normally draws into —
 * is a separate layer behind or above the whole app: it ignores rounded corners and borders,
 * and two of them on screen can end up stacked the wrong way round, so that our camera hides
 * the friend's video. This view is part of the normal screen, so it is clipped, rounded and
 * layered like anything else.
 */
class TextureVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("amend-local-video")
    private var released = false

    init {
        renderer.init(Rtc.egl.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    override fun onFrame(frame: VideoFrame) {
        if (!released) renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // crop the picture to the window's shape, so it fills it with no bars
        if (height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // the GL thread must let go of the surface before it is destroyed
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        runCatching { done.await(1, TimeUnit.SECONDS) }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() {
        if (released) return
        released = true
        renderer.release()
    }
}
