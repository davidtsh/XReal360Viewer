package com.xreal360.viewer.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a UV sphere inside-out (normals inward) for equirectangular 360° display.
 */
class SphereGeometry(
    private val radius: Float = 1.0f,
    private val stacks: Int = 64,
    private val slices: Int = 64
) {
    val vertexBuffer: FloatBuffer
    val texCoordBuffer: FloatBuffer
    val indexBuffer: ShortBuffer
    val indexCount: Int

    init {
        val vertices = mutableListOf<Float>()
        val texCoords = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        for (stack in 0..stacks) {
            val phi = PI * stack / stacks          // 0 .. PI
            val sinPhi = sin(phi).toFloat()
            val cosPhi = cos(phi).toFloat()

            for (slice in 0..slices) {
                val theta = 2.0 * PI * slice / slices  // 0 .. 2PI
                val sinTheta = sin(theta).toFloat()
                val cosTheta = cos(theta).toFloat()

                // Inward-facing: negate X so texture is not mirrored
                vertices.add(-radius * sinPhi * cosTheta)
                vertices.add(radius * cosPhi)
                vertices.add(radius * sinPhi * sinTheta)

                // UV: u goes 0→1 left-to-right, v goes 0→1 top-to-bottom
                texCoords.add(1f - slice.toFloat() / slices)
                texCoords.add(stack.toFloat() / stacks)
            }
        }

        for (stack in 0 until stacks) {
            for (slice in 0 until slices) {
                val first = (stack * (slices + 1) + slice).toShort()
                val second = (first + slices + 1).toShort()
                indices.add(first)
                indices.add(second)
                indices.add((first + 1).toShort())
                indices.add(second)
                indices.add((second + 1).toShort())
                indices.add((first + 1).toShort())
            }
        }

        indexCount = indices.size

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(vertices.toFloatArray()); it.position(0) }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(texCoords.toFloatArray()); it.position(0) }

        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .also { it.put(indices.toShortArray()); it.position(0) }
    }
}
