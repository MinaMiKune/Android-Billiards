package com.minamikune.billiards.gl

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifies the GLSL sources embedded in TableRenderer:
 * structural sanity, attribute layouts vs the Mesh.build vertex stride,
 * the uniform contract the renderer relies on, and — when the glslang
 * validator is available — actual compilation.
 */
class ShaderSourceTest {

    private fun shader(name: String): String {
        val f = TableRenderer::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(null) as String
    }

    companion object {
        val ALL = listOf(
            "BALL_VERT", "BALL_FRAG", "TABLE_VERT", "TABLE_FRAG",
            "SIMPLE_VERT", "SHADOW_FRAG", "LINE_VERT", "LINE_FRAG",
        )
        val VERTEX = setOf("BALL_VERT", "TABLE_VERT", "SIMPLE_VERT", "LINE_VERT")
        val FRAGMENT = setOf("BALL_FRAG", "TABLE_FRAG", "SHADOW_FRAG", "LINE_FRAG")

        /** The exact uniform contract TableRenderer draws with (loc() lookups). */
        val EXPECTED_UNIFORMS = mapOf(
            "BALL_VERT" to setOf("uModel", "uViewProj"),
            "BALL_FRAG" to setOf("uTex", "uCam", "uLight", "uAlpha"),
            "TABLE_VERT" to setOf("uModel", "uViewProj"),
            "TABLE_FRAG" to setOf("uTex", "uCam", "uLight", "uTint", "uUvScale", "uAlpha"),
            "SIMPLE_VERT" to setOf("uModel", "uViewProj"),
            "SHADOW_FRAG" to setOf("uTex", "uAlpha"),
            "LINE_VERT" to setOf("uModel", "uViewProj"),
            "LINE_FRAG" to setOf("uColor"),
        )
    }

    private fun uniforms(src: String): Set<String> {
        val re = Regex("""^uniform\s+\w+\s+(\w+)\s*;""")
        return src.lineSequence().mapNotNull { re.find(it.trim())?.groupValues?.get(1) }.toSet()
    }

    private fun attributes(src: String): Map<Int, String> {
        val re = Regex("""layout\(location=(\d+)\)\s+in\s+(\w+)\s+\w+\s*;""")
        return src.lineSequence().mapNotNull {
            val m = re.find(it.trim()) ?: return@mapNotNull null
            m.groupValues[1].toInt() to m.groupValues[2]
        }.toMap()
    }

    @Test
    fun allShadersArePresentAndWellFormed() {
        for (name in ALL) {
            val src = shader(name)
            assertTrue("$name starts with #version 300 es", src.trimStart().startsWith("#version 300 es"))
            assertTrue("$name has no CR characters", !src.contains('\r'))
            assertTrue("$name has a main()", src.contains("void main()"))
            assertEquals("$name balanced braces", 0, src.count { it == '{' } - src.count { it == '}' })
            assertEquals("$name balanced parens", 0, src.count { it == '(' } - src.count { it == ')' })
            assertTrue("$name declares a gl_Position or fragColor output",
                if (name in VERTEX) src.contains("gl_Position") else src.contains("fragColor"))
        }
    }

    @Test
    fun vertexAttributeLayoutsMatchMeshStride() {
        // Mesh.build interleaves position(3), normal(3), uv(2) at locations 0,1,2.
        assertEquals(mapOf(0 to "vec3", 1 to "vec3", 2 to "vec2"), attributes(shader("BALL_VERT")))
        assertEquals(mapOf(0 to "vec3", 1 to "vec3", 2 to "vec2"), attributes(shader("TABLE_VERT")))
        // lightweight meshes only stream positions
        assertEquals(mapOf(0 to "vec3"), attributes(shader("SIMPLE_VERT")))
        assertEquals(mapOf(0 to "vec3"), attributes(shader("LINE_VERT")))
    }

    @Test
    fun uniformsMatchRendererContract() {
        for (name in ALL) {
            assertEquals("$name uniforms", EXPECTED_UNIFORMS.getValue(name), uniforms(shader(name)))
        }
    }

    @Test
    fun fragmentShadersDeclareExactlyOneOutput() {
        for (name in FRAGMENT) {
            val outs = shader(name).lineSequence().count { it.trim().startsWith("out ") }
            assertEquals("$name declares exactly one fragment output", 1, outs)
        }
    }

    private fun findValidator(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, ".tools/root/usr/bin/glslangValidator")
            if (candidate.isFile && candidate.canExecute()) return candidate
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun shadersCompileWithGlslangValidator() {
        val validator = findValidator()
        assumeTrue("glslangValidator not available in this environment", validator != null)
        val exe = validator!!.absolutePath
        val tmp = createTempDir(prefix = "shaders").apply { deleteOnExit() }
        for (name in ALL) {
            val stage = if (name in VERTEX) "vert" else "frag"
            val file = File(tmp, "$name.$stage")
            // GLSL requires #version first: drop the leading newline of the Kotlin raw string
            file.writeText(shader(name).trim())
            val proc = ProcessBuilder(exe, file.absolutePath)
                .redirectErrorStream(true)
                .start()
            assertTrue("validator timed out on $name", proc.waitFor(30, TimeUnit.SECONDS))
            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals("$name failed to compile:\n$out", 0, proc.exitValue())
        }
    }
}
