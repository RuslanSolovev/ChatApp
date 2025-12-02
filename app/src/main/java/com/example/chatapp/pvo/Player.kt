package com.example.chatapp.pvo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

class Player(position: Vector2D) : GameObject(position, Vector2D(0f, 0f), 25f) {
    private val speed: Float = 400f
    var targetPosition: Vector2D = position

    override fun update(deltaTime: Float) {
        val direction = (targetPosition - position).normalize()
        velocity = direction * speed

        if (position.distanceTo(targetPosition) < 10f) {
            velocity = Vector2D(0f, 0f)
        }

        super.update(deltaTime)
    }

    override fun draw(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        // Рисуем игрока как треугольник
        val path = Path()
        path.moveTo(position.x, position.y - radius)
        path.lineTo(position.x - radius, position.y + radius * 0.7f)
        path.lineTo(position.x + radius, position.y + radius * 0.7f)
        path.close()

        canvas.drawPath(path, borderPaint)
        canvas.drawPath(path, paint)
    }
}