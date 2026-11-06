package com.minamikune.billiards.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.minamikune.billiards.game.GameMode
import com.minamikune.billiards.game.MatchEngine
import com.minamikune.billiards.game.SessionState
import com.minamikune.billiards.physics.BallState
import com.minamikune.billiards.physics.TableSpace
import com.minamikune.billiards.physics.TableSpec
import com.minamikune.billiards.physics.Vec2
import com.minamikune.billiards.physics.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 3.0 table renderer.
 *
 * Reads the physics state under the match lock, copies it into primitive arrays, and
 * never mutates the simulation. All geometry is procedural and all materials are
 * generated at startup.
 */
class TableRenderer(
    private val context: Context,
    @Volatile var engine: MatchEngine,
    private val highQuality: Boolean,
) : GLSurfaceView.Renderer {

    fun interface OnContextReady { fun ready() }
    var onContextReady: OnContextReady? = null

    private val lock = Any()
    private var proj = Mat4.identity()
    private var view = Mat4.identity()
    private var viewProj = Mat4.identity()
    private var invViewProj = Mat4.identity()
    private var root = Mat4.identity()
    private var portrait = false
    private var surfaceW = 1
    private var surfaceH = 1
    private var eye = Vec3(0.0, 5.0, 2.0)
    private var elapsed = 0f
    private var lastNanos = 0L

    private var ballProgram = 0
    private var tableProgram = 0
    private var shadowProgram = 0
    private var lineProgram = 0
    private val uniforms = HashMap<String, Int>(64)
    private var sphereMesh: Mesh? = null
    private var lowSphereMesh: Mesh? = null
    private var boxMesh: Mesh? = null
    private var planeMesh: Mesh? = null
    private var discMesh: Mesh? = null
    private var ringMesh: Mesh? = null
    private var cueMesh: Mesh? = null
    private var quadMesh: Mesh? = null
    private var lineVao = 0
    private var lineVbo = 0
    private val lineVerts = FloatArray(4096)
    private val lineBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(lineVerts.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var ballTextures = IntArray(16)
    private var clothTexture = 0
    private var woodTexture = 0
    private var shadowTexture = 0
    private var floorTexture = 0
    private var whiteTexture = 0
    private var ready = false

    private val ballRender = Array(16) { BallRender() }
    private val fall = FloatArray(16)
    private val wasActive = BooleanArray(16) { true }
    private var lastEngine: MatchEngine? = null
    private var frameState: SessionState = SessionState.AIMING
    private var frameMode: GameMode = GameMode.VS_AI
    private var framePlayer = 0
    private var frameAim = Vec2.UNIT_X
    private var framePower = 0.5f
    private var frameCueActive = false
    private var frameCue = Vec2.ZERO
    private var ballInHand = false

    private class BallRender {
        var x = 0.0
        var z = 0.0
        var qx = 0.0
        var qy = 0.0
        var qz = 0.0
        var qw = 1.0
        var active = true
        var pocket = -1
        var visible = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates cached program/uniform ids from the old one.
        ready = false
        uniforms.clear()
        GLES30.glClearColor(0.020f, 0.024f, 0.028f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        ballProgram = GlUtil.program(BALL_VERT, BALL_FRAG)
        tableProgram = GlUtil.program(TABLE_VERT, TABLE_FRAG)
        shadowProgram = GlUtil.program(SIMPLE_VERT, SHADOW_FRAG)
        lineProgram = GlUtil.program(LINE_VERT, LINE_FRAG)

        sphereMesh = Meshes.sphere(if (highQuality) 32 else 20, if (highQuality) 48 else 30)
        lowSphereMesh = Meshes.sphere(12, 18)
        boxMesh = Meshes.box()
        planeMesh = Meshes.plane()
        discMesh = Meshes.disc()
        ringMesh = Meshes.ring(0.86f)
        cueMesh = Meshes.cueCylinder(20)
        quadMesh = Meshes.quad()

        ballTextures = IntArray(16) { GlUtil.texture(BallArt.ball(it), repeat = false) }
        clothTexture = GlUtil.texture(BallArt.cloth(), repeat = true)
        woodTexture = GlUtil.texture(BallArt.wood(), repeat = true)
        shadowTexture = GlUtil.texture(BallArt.shadow(), repeat = false)
        floorTexture = GlUtil.texture(BallArt.floor(), repeat = true)
        val white = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        white.eraseColor(android.graphics.Color.WHITE)
        whiteTexture = GlUtil.texture(white, repeat = false)

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        lineVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        lineVbo = ids[0]
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            lineVerts.size * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)

        ready = true
        GlUtil.checkError("onSurfaceCreated")
        onContextReady?.ready()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = max(1, width)
        surfaceH = max(1, height)
        GLES30.glViewport(0, 0, width, height)
        synchronized(lock) {
            portrait = height > width
            root = if (portrait) Mat4.rotateY((Math.PI / 2.0).toFloat()) else Mat4.identity()
            val aspect = width.toFloat() / height.toFloat()
            proj = Mat4.perspective(36f, aspect, 0.05f, 40f)
            fitCamera(aspect)
            viewProj = Mat4.multiply(proj, view)
            invViewProj = Mat4.inverse(viewProj)
        }
    }

    private fun fitCamera(aspect: Float) {
        val hx = if (portrait) (TableSpec.HALF_WIDTH + 0.20) else (TableSpec.HALF_LENGTH + 0.20)
        val hz = if (portrait) (TableSpec.HALF_LENGTH + 0.20) else (TableSpec.HALF_WIDTH + 0.20)
        val dir = Vec3(0.0, 0.86, 0.51).normalized()
        var lo = 1.5f
        var hi = 14f
        repeat(34) {
            val mid = (lo + hi) * 0.5f
            val e = Vec3(dir.x * mid, dir.y * mid, dir.z * mid)
            val v = Mat4.lookAt(e, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
            val vp = Mat4.multiply(proj, v)
            var ok = true
            for (sx in -1..1 step 2) for (sz in -1..1 step 2) for (y in floatArrayOf(0f, 0.12f)) {
                val p = Mat4.transformPoint(vp, (sx * hx).toFloat(), y, (sz * hz).toFloat())
                if (abs(p.x) > 0.90 || abs(p.y) > 0.80) ok = false
            }
            if (ok) hi = mid else lo = mid
        }
        val d = hi * 1.015f
        eye = Vec3(dir.x * d, dir.y * d, dir.z * d)
        view = Mat4.lookAt(eye, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else min(0.05f, (now - lastNanos) / 1_000_000_000f)
        lastNanos = now
        elapsed += dt

        copyState(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        drawFloor()
        drawTable()
        drawShadows()
        drawPockets()
        drawAimGuide()
        drawBalls()
        drawCue()
        GlUtil.checkError("frame")
    }

    private fun copyState(dt: Float) {
        val e = engine
        if (e !== lastEngine) {
            lastEngine = e
            for (i in fall.indices) {
                fall[i] = 0f
                wasActive[i] = true
            }
        }
        synchronized(e) {
            frameState = e.state
            frameMode = e.mode
            framePlayer = e.currentPlayer
            frameAim = e.aimDirection
            framePower = e.power.toFloat()
            ballInHand = e.ballInHand
            val cue = e.world.balls[0]
            frameCueActive = cue.active
            frameCue = cue.position
            for (i in 0 until 16) {
                val b = e.world.balls[i]
                val r = ballRender[i]
                r.x = b.position.x
                r.z = b.position.y
                val q = TableSpace.orientationToWorld(b.orientation)
                r.qx = q.x
                r.qy = q.y
                r.qz = q.z
                r.qw = q.w
                r.active = b.active
                r.pocket = b.pocketIndex
                r.visible = true
                if (b.active) {
                    fall[i] = 0f
                    wasActive[i] = true
                } else {
                    if (wasActive[i]) {
                        wasActive[i] = false
                        fall[i] = 0f
                    }
                    fall[i] = min(1f, fall[i] + dt / 0.55f)
                    r.visible = fall[i] < 1f
                }
            }
        }
    }

    private fun drawFloor() {
        GLES30.glUseProgram(tableProgram)
        setViewProj(tableProgram)
        val model = Mat4.multiply(
            Mat4.translate(0f, -0.31f, 0f),
            Mat4.scale(4.2f, 1f, 4.2f),
        )
        setMat4(tableProgram, "uModel", model)
        setVec3(tableProgram, "uCam", eye)
        setVec3(tableProgram, "uLight", lightPos())
        setVec3(tableProgram, "uTint", 0.36f, 0.32f, 0.27f)
        setVec2(tableProgram, "uUvScale", 5f, 5f)
        setFloat(tableProgram, "uAlpha", 1f)
        bindTex(tableProgram, floorTexture)
        quadMesh?.draw()
    }

    private fun drawTable() {
        GLES30.glUseProgram(tableProgram)
        setViewProj(tableProgram)
        setVec3(tableProgram, "uCam", eye)
        setVec3(tableProgram, "uLight", lightPos())
        setFloat(tableProgram, "uAlpha", 1f)

        // bed slab
        part(
            boxMesh!!,
            Mat4.multiply(root, Mat4.multiply(Mat4.translate(0f, -0.075f, 0f), Mat4.scale(2.96f, 0.15f, 1.69f))),
            woodTexture, 0.85f, 0.72f, 0.56f, 2.2f, 1.4f,
        )

        // playing cloth
        GLES30.glUseProgram(tableProgram)
        setViewProj(tableProgram)
        setVec3(tableProgram, "uCam", eye)
        setVec3(tableProgram, "uLight", lightPos())
        setMat4(tableProgram, "uModel",
            Mat4.multiply(root, Mat4.multiply(Mat4.translate(0f, 0.0008f, 0f), Mat4.scale(TableSpec.HALF_LENGTH.toFloat(), 1f, TableSpec.HALF_WIDTH.toFloat()))))
        setVec3(tableProgram, "uTint", 1f, 1f, 1f)
        setVec2(tableProgram, "uUvScale", 3.2f, 1.6f)
        setFloat(tableProgram, "uAlpha", 1f)
        bindTex(tableProgram, clothTexture)
        planeMesh?.draw()

        // outer wood frame
        val frame = 0.16f
        val halfL = TableSpec.HALF_LENGTH.toFloat()
        val halfW = TableSpec.HALF_WIDTH.toFloat()
        val lip = 0.05f
        val longLen = halfL * 2f + 0.42f
        val zRail = halfW + lip + frame / 2f
        part(boxMesh!!, Mat4.multiply(root, Mat4.multiply(Mat4.translate(0f, 0.028f, zRail), Mat4.scale(longLen, 0.10f, frame))),
            woodTexture, 0.90f, 0.75f, 0.58f, 4.0f, 0.7f)
        part(boxMesh!!, Mat4.multiply(root, Mat4.multiply(Mat4.translate(0f, 0.028f, -zRail), Mat4.scale(longLen, 0.10f, frame))),
            woodTexture, 0.90f, 0.75f, 0.58f, 4.0f, 0.7f)
        val xRail = halfL + lip + frame / 2f
        part(boxMesh!!, Mat4.multiply(root, Mat4.multiply(Mat4.translate(xRail, 0.028f, 0f), Mat4.scale(frame, 0.10f, halfW * 2f + lip * 2f))),
            woodTexture, 0.90f, 0.75f, 0.58f, 0.7f, 2.2f)
        part(boxMesh!!, Mat4.multiply(root, Mat4.multiply(Mat4.translate(-xRail, 0.028f, 0f), Mat4.scale(frame, 0.10f, halfW * 2f + lip * 2f))),
            woodTexture, 0.90f, 0.75f, 0.58f, 0.7f, 2.2f)

        // cushions, split around the six pockets
        val cMouth = TableSpec.CORNER_MOUTH.toFloat()
        val sMouth = TableSpec.SIDE_MOUTH_HALF.toFloat()
        val cushionW = 0.05f
        val cushionH = 0.042f
        val yC = cushionH / 2f
        val cushionTint = floatArrayOf(0.62f, 0.88f, 0.72f)
        for (side in intArrayOf(-1, 1)) {
            val zc = side * (halfW + cushionW / 2f)
            val segs = listOf(
                (-halfL + cMouth) to (-sMouth),
                sMouth to (halfL - cMouth),
            )
            for ((a, b) in segs) {
                val len = b - a
                if (len <= 0f) continue
                val model = Mat4.multiply(
                    root,
                    Mat4.multiply(Mat4.translate((a + b) / 2f, yC, zc), Mat4.scale(len, cushionH, cushionW)),
                )
                part(boxMesh!!, model, clothTexture, cushionTint[0], cushionTint[1], cushionTint[2], len * 2.2f, 1.2f)
            }
            val xc = side * (halfL + cushionW / 2f)
            val endLen = (halfW - cMouth) * 2f
            val model = Mat4.multiply(
                root,
                Mat4.multiply(Mat4.translate(xc, yC, 0f), Mat4.scale(cushionW, cushionH, endLen)),
            )
            part(boxMesh!!, model, clothTexture, cushionTint[0], cushionTint[1], cushionTint[2], 1.1f, 1.6f)
        }

        // gold diamond sights inlaid in the rail
        val diamond = boxMesh!!
        for (k in intArrayOf(-3, -2, -1, 1, 2, 3)) {
            val x = k * TableSpec.LENGTH.toFloat() / 8f
            for (side in intArrayOf(-1, 1)) {
                val model = Mat4.multiply(root, Mat4.multiply(
                    Mat4.translate(x, 0.0805f, side * zRail),
                    Mat4.multiply(Mat4.rotateY(0.785f), Mat4.scale(0.018f, 0.008f, 0.018f)),
                ))
                part(diamond, model, whiteTexture, 0.92f, 0.78f, 0.46f, 1f, 1f)
            }
        }
        for (k in intArrayOf(-1, 0, 1)) {
            val z = k * TableSpec.WIDTH.toFloat() / 8f
            for (side in intArrayOf(-1, 1)) {
                val model = Mat4.multiply(root, Mat4.multiply(
                    Mat4.translate(side * xRail, 0.0805f, z),
                    Mat4.multiply(Mat4.rotateY(0.785f), Mat4.scale(0.018f, 0.008f, 0.018f)),
                ))
                part(diamond, model, whiteTexture, 0.92f, 0.78f, 0.46f, 1f, 1f)
            }
        }

        // table legs hint at the floor
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val model = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(sx * (halfL + 0.12f), -0.20f, sz * (halfW + 0.12f)),
                Mat4.scale(0.12f, 0.22f, 0.12f),
            ))
            part(boxMesh!!, model, woodTexture, 0.46f, 0.33f, 0.23f, 1f, 1f)
        }
    }

    private fun drawPockets() {
        GLES30.glUseProgram(tableProgram)
        setViewProj(tableProgram)
        setVec3(tableProgram, "uCam", eye)
        setVec3(tableProgram, "uLight", lightPos())
        setVec2(tableProgram, "uUvScale", 1f, 1f)
        setFloat(tableProgram, "uAlpha", 1f)
        for (p in TableSpec.pockets) {
            val r = (p.captureRadius + 0.012).toFloat()
            val model = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(p.center.x.toFloat(), 0.0042f, p.center.y.toFloat()),
                Mat4.scale(r, 1f, r),
            ))
            setMat4(tableProgram, "uModel", model)
            setVec3(tableProgram, "uTint", 0.025f, 0.05f, 0.035f)
            bindTex(tableProgram, whiteTexture)
            discMesh?.draw()

            val ringModel = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(p.center.x.toFloat(), 0.0055f, p.center.y.toFloat()),
                Mat4.scale(r * 1.16f, 1f, r * 1.16f),
            ))
            setMat4(tableProgram, "uModel", ringModel)
            setVec3(tableProgram, "uTint", 0.38f, 0.30f, 0.17f)
            bindTex(tableProgram, whiteTexture)
            ringMesh?.draw()
        }
    }

    private fun drawShadows() {
        GLES30.glUseProgram(shadowProgram)
        setViewProj(shadowProgram)
        GLES30.glDepthMask(false)
        for (i in 0 until 16) {
            val b = ballRender[i]
            if (!b.active || !b.visible) continue
            val s = TableSpec.BALL_RADIUS.toFloat() * 2.9f
            val model = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(b.x.toFloat(), 0.0035f, b.z.toFloat()),
                Mat4.scale(s, 1f, s),
            ))
            setMat4(shadowProgram, "uModel", model)
            setFloat(shadowProgram, "uAlpha", 0.72f)
            bindTex(shadowProgram, shadowTexture)
            quadMesh?.draw()
        }
        GLES30.glDepthMask(true)
    }

    private fun drawBalls() {
        GLES30.glUseProgram(ballProgram)
        setViewProj(ballProgram)
        setVec3(ballProgram, "uCam", eye)
        setVec3(ballProgram, "uLight", lightPos())
        GLES30.glDepthMask(true)
        for (i in 0 until 16) {
            val b = ballRender[i]
            if (!b.visible) continue
            if (!b.active && fall[i] >= 1f) continue
            val pocket = if (b.pocket in 0..5) TableSpec.pockets[b.pocket] else null
            val radius = TableSpec.BALL_RADIUS.toFloat()
            var x = b.x.toFloat()
            var z = b.z.toFloat()
            var y = radius
            var scale = radius
            var alpha = 1f
            if (!b.active) {
                val f = fall[i]
                if (pocket != null) {
                    x = pocket.center.x.toFloat()
                    z = pocket.center.y.toFloat()
                }
                y = radius - f * (radius + 0.10f)
                scale = radius * (1f - 0.28f * f)
                alpha = 1f - f * f
            }
            val model = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(x, y, z),
                Mat4.multiply(Mat4.fromQuaternion(b.qx, b.qy, b.qz, b.qw), Mat4.scale(scale)),
            ))
            setMat4(ballProgram, "uModel", model)
            val tex = if (i == 0) ballTextures[0] else ballTextures[i]
            bindTex(ballProgram, tex)
            setFloat(ballProgram, "uAlpha", alpha)
            (if (highQuality) sphereMesh else lowSphereMesh)?.draw()
        }

        // ghost ball at the first contact point
        if (showAim()) {
            val target = aimTarget()
            val ghostModel = Mat4.multiply(root, Mat4.multiply(
                Mat4.translate(target.x.toFloat(), TableSpec.BALL_RADIUS.toFloat(), target.y.toFloat()),
                Mat4.scale(TableSpec.BALL_RADIUS.toFloat() * 0.96f),
            ))
            GLES30.glDepthMask(false)
            setMat4(ballProgram, "uModel", ghostModel)
            bindTex(ballProgram, ballTextures[0])
            setFloat(ballProgram, "uAlpha", 0.20f)
            lowSphereMesh?.draw()
            GLES30.glDepthMask(true)
        }
    }

    private fun showAim(): Boolean {
        if (frameState != SessionState.AIMING || !frameCueActive) return false
        if (frameMode == GameMode.VS_AI && framePlayer != 0) return false
        return true
    }

    private fun aimTarget(): Vec2 {
        val d = frameAim.normalized()
        val c = frameCue
        var best = Double.MAX_VALUE
        for (i in 1 until 16) {
            val b = ballRender[i]
            if (!b.active) continue
            val ox = b.x - c.x
            val oz = b.z - c.y
            val proj = ox * d.x + oz * d.y
            if (proj <= 0.0) continue
            val perp2 = ox * ox + oz * oz - proj * proj
            val rr = (TableSpec.BALL_RADIUS * 2.0) * (TableSpec.BALL_RADIUS * 2.0)
            if (perp2 > rr) continue
            val t = proj - sqrt(rr - perp2)
            if (t > 0.0 && t < best) best = t
        }
        val maxX = TableSpec.HALF_LENGTH - TableSpec.BALL_RADIUS
        val maxZ = TableSpec.HALF_WIDTH - TableSpec.BALL_RADIUS
        val wall = min(
            when {
                d.x > 1e-6 -> (maxX - c.x) / d.x
                d.x < -1e-6 -> (-maxX - c.x) / d.x
                else -> Double.MAX_VALUE
            },
            when {
                d.y > 1e-6 -> (maxZ - c.y) / d.y
                d.y < -1e-6 -> (-maxZ - c.y) / d.y
                else -> Double.MAX_VALUE
            },
        )
        best = min(best, wall)
        if (!best.isFinite() || best <= 0.0) return c + d * 1.0
        return c + d * best
    }

    private fun drawAimGuide() {
        if (!showAim()) return
        val target = aimTarget()
        val start = frameCue + frameAim.normalized() * (TableSpec.BALL_RADIUS * 1.2)
        val dx = target.x - start.x
        val dz = target.y - start.y
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-5) return
        val ux = (dx / len).toFloat()
        val uz = (dz / len).toFloat()
        var n = 0
        val dash = 0.045f
        val gap = 0.026f
        var travelled = 0f
        val ringReserve = 28 * 6
        while (travelled < len && n < lineVerts.size - ringReserve - 6) {
            val end = min(len.toFloat(), travelled + dash)
            lineVerts[n++] = (start.x + ux * travelled).toFloat()
            lineVerts[n++] = 0.006f
            lineVerts[n++] = (start.y + uz * travelled).toFloat()
            lineVerts[n++] = (start.x + ux * end).toFloat()
            lineVerts[n++] = 0.006f
            lineVerts[n++] = (start.y + uz * end).toFloat()
            travelled = end + gap
        }
        // contact ring on the cloth
        val rr = TableSpec.BALL_RADIUS.toFloat()
        val segments = 28
        for (i in 0 until segments) {
            val a0 = i.toFloat() / segments * (Math.PI * 2).toFloat()
            val a1 = (i + 1).toFloat() / segments * (Math.PI * 2).toFloat()
            lineVerts[n++] = target.x.toFloat() + cos(a0) * rr
            lineVerts[n++] = 0.006f
            lineVerts[n++] = target.y.toFloat() + sin(a0) * rr
            lineVerts[n++] = target.x.toFloat() + cos(a1) * rr
            lineVerts[n++] = 0.006f
            lineVerts[n++] = target.y.toFloat() + sin(a1) * rr
        }
        GLES30.glUseProgram(lineProgram)
        setViewProj(lineProgram)
        setMat4(lineProgram, "uModel", root)
        GLES30.glUniform4f(loc(lineProgram, "uColor"), 0.96f, 0.82f, 0.48f, 0.58f)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        lineBuffer.clear()
        lineBuffer.put(lineVerts, 0, n).position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 4, lineBuffer)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, n / 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }

    private fun drawCue() {
        if (!showAim()) return
        val d = frameAim.normalized()
        val pull = 0.055 + framePower * 0.20
        val tipX = frameCue.x - d.x * pull
        val tipZ = frameCue.y - d.y * pull
        val angle = -atan2(d.y, d.x).toFloat()
        val base = Mat4.multiply(root, Mat4.multiply(
            Mat4.translate(tipX.toFloat(), 0.040f, tipZ.toFloat()),
            Mat4.rotateY(angle),
        ))
        GLES30.glUseProgram(tableProgram)
        setViewProj(tableProgram)
        setVec3(tableProgram, "uCam", eye)
        setVec3(tableProgram, "uLight", lightPos())
        setFloat(tableProgram, "uAlpha", 1f)
        setVec2(tableProgram, "uUvScale", 3f, 1f)
        setVec3(tableProgram, "uTint", 0.90f, 0.68f, 0.39f)
        bindTex(tableProgram, woodTexture)
        val shaft = Mat4.multiply(base, Mat4.multiply(
            Mat4.translate(-0.055f, 0f, 0f),
            Mat4.scale(1.38f, 0.0062f, 0.0062f),
        ))
        setMat4(tableProgram, "uModel", shaft)
        cueMesh?.draw()

        setVec3(tableProgram, "uTint", 0.90f, 0.86f, 0.76f)
        bindTex(tableProgram, whiteTexture)
        val ferrule = Mat4.multiply(base, Mat4.multiply(
            Mat4.translate(-0.032f, 0f, 0f),
            Mat4.scale(0.055f, 0.0064f, 0.0064f),
        ))
        setMat4(tableProgram, "uModel", ferrule)
        cueMesh?.draw()

        setVec3(tableProgram, "uTint", 0.16f, 0.36f, 0.66f)
        val tip = Mat4.multiply(base, Mat4.multiply(
            Mat4.translate(-0.009f, 0f, 0f),
            Mat4.scale(0.022f, 0.0068f, 0.0068f),
        ))
        setMat4(tableProgram, "uModel", tip)
        cueMesh?.draw()
    }

    private fun part(
        mesh: Mesh,
        model: FloatArray,
        texture: Int,
        tr: Float,
        tg: Float,
        tb: Float,
        us: Float,
        vs: Float,
    ) {
        setMat4(tableProgram, "uModel", model)
        setVec3(tableProgram, "uTint", tr, tg, tb)
        setVec2(tableProgram, "uUvScale", us, vs)
        bindTex(tableProgram, texture)
        mesh.draw()
    }

    private fun lightPos(): Vec3 = Vec3(
        0.30 * sin(elapsed * 0.22).toDouble(),
        1.15,
        0.28 * cos(elapsed * 0.19).toDouble(),
    )

    private fun setViewProj(program: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(loc(program, "uViewProj"), 1, false, viewProj, 0)
    }

    private fun setMat4(program: Int, name: String, m: FloatArray) {
        GLES30.glUniformMatrix4fv(loc(program, name), 1, false, m, 0)
    }

    private fun setFloat(program: Int, name: String, v: Float) {
        GLES30.glUniform1f(loc(program, name), v)
    }

    private fun setVec2(program: Int, name: String, x: Float, y: Float) {
        GLES30.glUniform2f(loc(program, name), x, y)
    }

    private fun setVec3(program: Int, name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(loc(program, name), x, y, z)
    }

    private fun setVec3(program: Int, name: String, v: Vec3) {
        GLES30.glUniform3f(loc(program, name), v.x.toFloat(), v.y.toFloat(), v.z.toFloat())
    }

    private fun bindTex(program: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(loc(program, "uTex"), 0)
    }

    private fun loc(program: Int, name: String): Int {
        val key = "$program:$name"
        uniforms[key]?.let { return it }
        val l = GLES30.glGetUniformLocation(program, name)
        uniforms[key] = l
        return l
    }

    /** Converts a view-space touch into physical table coordinates, or null if it misses. */
    fun screenToTable(px: Float, py: Float): Vec2? {
        if (surfaceW <= 0 || surfaceH <= 0) return null
        val x = 2f * px / surfaceW - 1f
        val y = 1f - 2f * py / surfaceH
        val m: FloatArray
        val isPortrait: Boolean
        synchronized(lock) {
            m = invViewProj.copyOf()
            isPortrait = portrait
        }
        val near = Mat4.transformPoint(m, x, y, -1f)
        val far = Mat4.transformPoint(m, x, y, 1f)
        val dy = far.y - near.y
        if (abs(dy) < 1e-6) return null
        val t = -near.y / dy
        if (t < 0.0 || t > 1.0) return null
        val wx = near.x + (far.x - near.x) * t
        val wz = near.z + (far.z - near.z) * t
        // In portrait the whole table is rotated +90 degrees around Y:
        // world = (tableY, 0, -tableX), so tableX = -worldZ and tableY = worldX.
        return TableSpace.worldToTable(wx, wz, isPortrait)
    }

    fun tableToScreen(x: Double, z: Double): Vec2? {
        if (surfaceW <= 0 || surfaceH <= 0) return null
        val m: FloatArray
        val isPortrait: Boolean
        synchronized(lock) {
            m = viewProj.copyOf()
            isPortrait = portrait
        }
        val world = TableSpace.tableToWorld(x, z, isPortrait)
        val p = Mat4.transformPoint(m, world.x.toFloat(), 0f, world.y.toFloat())
        if (p.z < -1.0 || p.z > 1.0) return null
        return Vec2((p.x + 1.0) * 0.5 * surfaceW, (1.0 - p.y) * 0.5 * surfaceH)
    }

    companion object {
        private const val BALL_VERT = """
#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    gl_Position = uViewProj * w;
}
"""
        private const val BALL_FRAG = """
#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uLight;
uniform float uAlpha;
out vec4 fragColor;
void main() {
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCam - vWorld);
    vec3 L = normalize(uLight - vWorld);
    vec3 H = normalize(L + V);
    float diff = max(dot(N, L), 0.0);
    float spec = pow(max(dot(N, H), 0.0), 110.0);
    vec3 tex = texture(uTex, vUv).rgb;
    vec3 base = tex * tex;
    vec3 color = base * (0.16 + 0.92 * diff);
    color += vec3(1.0, 0.94, 0.82) * spec * 1.55;
    float fill = max(dot(N, normalize(vec3(-0.35, 0.55, 0.75))), 0.0);
    color += base * fill * 0.16;
    vec3 R = reflect(-V, N);
    float up = clamp(R.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 env = mix(vec3(0.015, 0.045, 0.035), vec3(0.62, 0.46, 0.28), up);
    float fres = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 4.0);
    color += env * fres * 1.05;
    color += vec3(0.018, 0.085, 0.052) * max(-N.y, 0.0);
    fragColor = vec4(pow(max(color, 0.0), vec3(0.92)), uAlpha);
}
"""
        private const val TABLE_VERT = """
#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vUv;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    gl_Position = uViewProj * w;
}
"""
        private const val TABLE_FRAG = """
#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uCam;
uniform vec3 uLight;
uniform vec3 uTint;
uniform vec2 uUvScale;
uniform float uAlpha;
out vec4 fragColor;
void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(uLight - vWorld);
    vec3 V = normalize(uCam - vWorld);
    float diff = max(dot(N, L), 0.0);
    vec3 base = texture(uTex, vUv * uUvScale).rgb * uTint;
    float d = distance(vWorld, uLight);
    float pool = exp(-d * d * 1.7);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 42.0) * 0.10;
    vec3 color = base * (0.19 + 0.84 * diff);
    color += vec3(1.0, 0.83, 0.56) * pool * 0.10;
    color += vec3(1.0, 0.92, 0.78) * spec;
    fragColor = vec4(pow(max(color, 0.0), vec3(0.94)), uAlpha);
}
"""
        private const val SIMPLE_VERT = """
#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec2 vUv;
void main() {
    vUv = aPos.xz * 0.5 + 0.5;
    gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
}
"""
        private const val SHADOW_FRAG = """
#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
out vec4 fragColor;
void main() {
    float a = texture(uTex, vUv).a;
    fragColor = vec4(0.0, 0.02, 0.01, a * uAlpha);
}
"""
        private const val LINE_VERT = """
#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uModel;
uniform mat4 uViewProj;
void main() { gl_Position = uViewProj * uModel * vec4(aPos, 1.0); }
"""
        private const val LINE_FRAG = """
#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }
"""
    }
}
