package com.minamikune.billiards.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

object GlUtil {
    fun compile(type: Int, source: String): Int {
        // GLSL requires #version on the very first line. The shader constants are
        // raw strings with a leading newline, so normalize before uploading.
        val cleaned = source.trimIndent().trim()
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, cleaned)
        GLES30.glCompileShader(shader)
        val result = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, result, 0)
        if (result[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log\n$cleaned")
        }
        return shader
    }

    fun program(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val result = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, result, 0)
        if (result[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        return program
    }

    fun texture(bitmap: Bitmap, repeat: Boolean, mipmaps: Boolean = true): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        bitmap.recycle()
        return ids[0]
    }

    fun checkError(tag: String) {
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR) {
            android.util.Log.e("GL", "$tag glError=$error")
            error = GLES30.glGetError()
        }
    }
}

class Mesh private constructor(
    private val vao: Int,
    private val vbo: Int,
    private val ebo: Int,
    private val count: Int,
) {
    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun drawLines() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_LINES, count, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        fun build(vertices: FloatArray, indices: ShortArray): Mesh {
            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            val vbo = IntArray(1)
            GLES30.glGenBuffers(1, vbo, 0)
            val ebo = IntArray(1)
            GLES30.glGenBuffers(1, ebo, 0)

            GLES30.glBindVertexArray(vao[0])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            val vb = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(vertices).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo[0])
            val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(indices).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)

            val stride = 8 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glBindVertexArray(0)
            return Mesh(vao[0], vbo[0], ebo[0], indices.size)
        }
    }
}

object Meshes {
    fun sphere(rings: Int = 24, segments: Int = 48): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (r in 0..rings) {
            val v = r.toDouble() / rings
            val phi = v * Math.PI
            for (s in 0..segments) {
                val u = s.toDouble() / segments
                val theta = u * Math.PI * 2.0
                val x = sin(phi) * cos(theta)
                val y = cos(phi)
                val z = sin(phi) * sin(theta)
                verts.add(x.toFloat()); verts.add(y.toFloat()); verts.add(z.toFloat())
                verts.add(x.toFloat()); verts.add(y.toFloat()); verts.add(z.toFloat())
                verts.add(u.toFloat()); verts.add(v.toFloat())
            }
        }
        val row = segments + 1
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = (r * row + s)
                val b = a + row
                idx.add(a.toShort())
                idx.add(b.toShort())
                idx.add((a + 1).toShort())
                idx.add((a + 1).toShort())
                idx.add(b.toShort())
                idx.add((b + 1).toShort())
            }
        }
        return Mesh.build(verts.toFloatArray(), idx.toShortArray())
    }

    fun box(): Mesh {
        val p = arrayOf(
            floatArrayOf(-0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(-0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f), floatArrayOf(0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, 0.5f, -0.5f), floatArrayOf(-0.5f, 0.5f, -0.5f),
        )
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(5, 4, 7, 6),
            intArrayOf(4, 0, 3, 7), intArrayOf(1, 5, 6, 2),
            intArrayOf(3, 2, 6, 7), intArrayOf(4, 5, 1, 0),
        )
        val uvs = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f),
        )
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (f in faces) {
            val base = verts.size / 8
            val a = p[f[0]]; val b = p[f[1]]; val c = p[f[2]]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            nx /= len; ny /= len; nz /= len
            for (k in 0..3) {
                val point = p[f[k]]
                verts.add(point[0]); verts.add(point[1]); verts.add(point[2])
                verts.add(nx); verts.add(ny); verts.add(nz)
                verts.add(uvs[k][0]); verts.add(uvs[k][1])
            }
            idx.add(base.toShort()); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
            idx.add(base.toShort()); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
        }
        return Mesh.build(verts.toFloatArray(), idx.toShortArray())
    }

    fun plane(): Mesh {
        val verts = floatArrayOf(
            -1f, 0f, -1f, 0f, 1f, 0f, 0f, 0f,
            1f, 0f, -1f, 0f, 1f, 0f, 1f, 0f,
            1f, 0f, 1f, 0f, 1f, 0f, 1f, 1f,
            -1f, 0f, 1f, 0f, 1f, 0f, 0f, 1f,
        )
        val idx = shortArrayOf(0, 2, 1, 0, 3, 2)
        return Mesh.build(verts, idx)
    }

    fun disc(segments: Int = 48): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        verts.addAll(listOf(0f, 0f, 0f, 0f, 1f, 0f, 0.5f, 0.5f))
        for (i in 0..segments) {
            val a = i.toDouble() / segments * Math.PI * 2.0
            val x = cos(a).toFloat()
            val z = sin(a).toFloat()
            verts.addAll(listOf(x, 0f, z, 0f, 1f, 0f, (x + 1f) / 2f, (z + 1f) / 2f))
        }
        for (i in 1..segments) {
            idx.add(0)
            idx.add(i.toShort())
            idx.add((i + 1).toShort())
        }
        return Mesh.build(verts.toFloatArray(), idx.toShortArray())
    }

    fun ring(inner: Float = 0.82f, segments: Int = 64): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (i in 0..segments) {
            val a = i.toDouble() / segments * Math.PI * 2.0
            val x = cos(a).toFloat(); val z = sin(a).toFloat()
            verts.addAll(listOf(x, 0f, z, 0f, 1f, 0f, 0f, 0f))
            verts.addAll(listOf(x * inner, 0f, z * inner, 0f, 1f, 0f, 0f, 0f))
        }
        for (i in 0 until segments) {
            val a = (i * 2).toShort()
            idx.add(a); idx.add((a + 1).toShort()); idx.add((a + 2).toShort())
            idx.add((a + 2).toShort()); idx.add((a + 1).toShort()); idx.add((a + 3).toShort())
        }
        return Mesh.build(verts.toFloatArray(), idx.toShortArray())
    }

    /** Unit cylinder along -X, from x=0 to x=-1, radius 1. */
    fun cueCylinder(segments: Int = 16): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (i in 0..segments) {
            val a = i.toDouble() / segments * Math.PI * 2.0
            val u = i.toFloat() / segments
            val y = cos(a).toFloat(); val z = sin(a).toFloat()
            verts.addAll(listOf(0f, y, z, 0f, y, z, u, 0f))
            verts.addAll(listOf(-1f, y, z, 0f, y, z, u, 1f))
        }
        for (i in 0 until segments) {
            val a = (i * 2).toShort()
            idx.add(a); idx.add((a + 1).toShort()); idx.add((a + 2).toShort())
            idx.add((a + 2).toShort()); idx.add((a + 1).toShort()); idx.add((a + 3).toShort())
        }
        return Mesh.build(verts.toFloatArray(), idx.toShortArray())
    }

    fun quad(): Mesh {
        val verts = floatArrayOf(
            -1f, 0f, -1f, 0f, 1f, 0f, 0f, 0f,
            1f, 0f, -1f, 0f, 1f, 0f, 1f, 0f,
            1f, 0f, 1f, 0f, 1f, 0f, 1f, 1f,
            -1f, 0f, 1f, 0f, 1f, 0f, 0f, 1f,
        )
        return Mesh.build(verts, shortArrayOf(0, 2, 1, 0, 3, 2))
    }
}
