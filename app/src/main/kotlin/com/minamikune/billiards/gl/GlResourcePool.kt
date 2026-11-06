package com.minamikune.billiards.gl

import android.opengl.GLES30

/**
 * Per-EGL-context cache for programs, meshes and textures.
 *
 * [beginContext] is called from `onSurfaceCreated`. A new EGL context makes every
 * previous GL name invalid; the old objects are destroyed with that context, so the
 * pool drops the stale ids without issuing deletes against names owned by the new
 * context. [release] is available for an explicit teardown on the GL thread while
 * the context is still current.
 *
 * This prevents the renderer from compiling the same four programs or regenerating
 * the same meshes/textures more than once per context.
 */
class GlResourcePool {
    private val programs = LinkedHashMap<String, Int>()
    private val meshes = LinkedHashMap<String, Mesh>()
    private val textures = LinkedHashMap<String, Int>()

    fun beginContext() {
        // Do not glDelete here: these ids belong to the destroyed context.
        programs.clear()
        meshes.clear()
        textures.clear()
    }

    fun program(key: String, vertexSource: String, fragmentSource: String): Int {
        programs[key]?.let { return it }
        val id = GlUtil.program(vertexSource, fragmentSource)
        programs[key] = id
        return id
    }

    fun mesh(key: String, factory: () -> Mesh): Mesh {
        meshes[key]?.let { return it }
        val mesh = factory()
        meshes[key] = mesh
        return mesh
    }

    fun texture(key: String, factory: () -> Int): Int {
        textures[key]?.let { return it }
        val id = factory()
        textures[key] = id
        return id
    }

    fun programCount(): Int = programs.size
    fun meshCount(): Int = meshes.size
    fun textureCount(): Int = textures.size

    /** Deletes every tracked GL object. Must run on the thread owning the context. */
    fun release() {
        for (id in programs.values) {
            GLES30.glDeleteProgram(id)
        }
        for (mesh in meshes.values) {
            mesh.release()
        }
        if (textures.isNotEmpty()) {
            GLES30.glDeleteTextures(textures.size, textures.values.toIntArray(), 0)
        }
        programs.clear()
        meshes.clear()
        textures.clear()
    }
}
