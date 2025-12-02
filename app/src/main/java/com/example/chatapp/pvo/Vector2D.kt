package com.example.chatapp.pvo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Vector2D(var x: Float, var y: Float) {
    fun length(): Float = sqrt(x * x + y * y)

    fun normalize(): Vector2D {
        val len = length()
        return if (len > 0) Vector2D(x / len, y / len) else Vector2D(0f, 0f)
    }

    operator fun times(scalar: Float): Vector2D = Vector2D(x * scalar, y * scalar)
    operator fun plus(other: Vector2D): Vector2D = Vector2D(x + other.x, y + other.y)
    operator fun minus(other: Vector2D): Vector2D = Vector2D(x - other.x, y - other.y)

    fun distanceTo(other: Vector2D): Float = (this - other).length()

    fun rotate(angle: Float): Vector2D {
        val cos = cos(angle)
        val sin = sin(angle)
        return Vector2D(
            x * cos - y * sin,
            x * sin + y * cos
        )
    }

    // Добавляем метод copy для копирования вектора
    fun copy(): Vector2D = Vector2D(x, y)
}