package com.example.chatapp.pvo

import android.graphics.Canvas
import android.graphics.Paint

open class GameObject(
    var position: Vector2D,
    var velocity: Vector2D,
    var radius: Float
) {
    open fun update(deltaTime: Float) {
        position = position + velocity * deltaTime
    }

    open fun draw(canvas: Canvas) {
        // Базовый метод отрисовки - переопределим в дочерних классах
    }

    fun isCollidingWith(other: GameObject): Boolean {
        return position.distanceTo(other.position) < (radius + other.radius)
    }
}