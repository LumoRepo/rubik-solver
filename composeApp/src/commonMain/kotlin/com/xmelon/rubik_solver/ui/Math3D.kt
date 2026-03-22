package com.xmelon.rubik_solver.ui

import kotlin.math.cos
import kotlin.math.sin

/** Immutable 3-component vector used by all 3D rendering in this package. */
internal data class Vec3(val x: Float, val y: Float, val z: Float)

/** Combined Rx·Ry·Rz rotation matrix (3×3, row-major). */
internal fun createRotationMatrix(rx: Float, ry: Float, rz: Float): FloatArray {
    val mX = floatArrayOf(
        1f,  0f,      0f,
        0f,  cos(rx), -sin(rx),
        0f,  sin(rx),  cos(rx)
    )
    val mY = floatArrayOf(
         cos(ry), 0f, sin(ry),
         0f,      1f, 0f,
        -sin(ry), 0f, cos(ry)
    )
    val mZ = floatArrayOf(
        cos(rz), -sin(rz), 0f,
        sin(rz),  cos(rz), 0f,
        0f,       0f,      1f
    )
    return multiplyMatrices(multiplyMatrices(mX, mY), mZ)
}

/** Single-axis rotation matrix for animation (axis: 0=X, 1=Y, 2=Z, -1=identity). */
internal fun createRotationAxisMatrix(axis: Int, angle: Float): FloatArray {
    if (axis == -1) return floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f)
    val c = cos(angle)
    val s = sin(angle)
    return when (axis) {
        0 -> floatArrayOf(1f, 0f, 0f,   0f, c, -s,  0f, s, c)  // X
        1 -> floatArrayOf(c,  0f, s,    0f, 1f, 0f, -s, 0f, c)  // Y
        2 -> floatArrayOf(c, -s,  0f,   s,  c,  0f,  0f, 0f, 1f) // Z
        else -> floatArrayOf(1f, 0f, 0f,  0f, 1f, 0f,  0f, 0f, 1f)
    }
}

internal fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
    val r = FloatArray(9)
    for (row in 0..2) for (col in 0..2)
        r[row * 3 + col] = a[row*3]*b[col] + a[row*3+1]*b[3+col] + a[row*3+2]*b[6+col]
    return r
}

internal fun multiplyMatrixVector(m: FloatArray, v: Vec3): Vec3 = Vec3(
    m[0]*v.x + m[1]*v.y + m[2]*v.z,
    m[3]*v.x + m[4]*v.y + m[5]*v.z,
    m[6]*v.x + m[7]*v.y + m[8]*v.z
)
