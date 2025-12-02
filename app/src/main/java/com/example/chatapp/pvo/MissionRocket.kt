package com.example.chatapp.pvo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.abs

class MissionRocket(position: Vector2D) : GameObject(position, Vector2D(0f, 0f), 20f) {

    private val baseSpeed = 3f
    var isDestroyed = false
    private var horizontalSpeed = 0f
    private val maxHorizontalSpeed = 8f
    private val acceleration = 0.5f
    private val deceleration = 0.3f

    // Траектория полета
    private val trail = mutableListOf<Vector2D>()
    private val maxTrailLength = 15

    // ДОБАВЛЯЕМ ЭТОТ МЕТОД:
    fun setHorizontalMovement(direction: Float) {
        // -1: влево, 0: нет движения, 1: вправо
        horizontalSpeed = when {
            direction < -0.1f -> (horizontalSpeed - acceleration).coerceAtLeast(-maxHorizontalSpeed)
            direction > 0.1f -> (horizontalSpeed + acceleration).coerceAtMost(maxHorizontalSpeed)
            else -> {
                // Плавное замедление
                if (horizontalSpeed > 0) (horizontalSpeed - deceleration).coerceAtLeast(0f)
                else (horizontalSpeed + deceleration).coerceAtMost(0f)
            }
        }
    }

    override fun update(deltaTime: Float) {
        if (!isDestroyed) {
            // Движение вперед (вверх на экране) с постоянной скоростью
            // Горизонтальное движение с инерцией
            velocity = Vector2D(horizontalSpeed * 60f, -baseSpeed * 60f)
            super.update(deltaTime)

            // Ограничение по краям экрана
            position.x = position.x.coerceIn(radius, 1000f - radius) // предполагаемая ширина

            // Добавляем точку в траекторию
            trail.add(Vector2D(position.x, position.y))
            if (trail.size > maxTrailLength) {
                trail.removeAt(0)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (isDestroyed) return

        val rocketPaint = Paint().apply {
            color = Color.parseColor("#FF6B35")
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val detailPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }

        // Рисуем траекторию
        if (trail.size > 1) {
            val trailPaint = Paint().apply {
                color = Color.argb(150, 255, 107, 53)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }

            for (i in 1 until trail.size) {
                val alpha = (i * 255 / trail.size).toInt()
                trailPaint.alpha = alpha
                canvas.drawLine(
                    trail[i-1].x, trail[i-1].y,
                    trail[i].x, trail[i].y,
                    trailPaint
                )
            }
        }

        // Корпус ракеты
        val path = Path()
        path.moveTo(position.x, position.y - radius * 1.8f) // Нос
        path.lineTo(position.x - radius * 0.8f, position.y + radius * 1.2f) // Левое основание
        path.lineTo(position.x - radius * 0.4f, position.y + radius * 0.8f) // Левое сужение
        path.lineTo(position.x - radius * 0.3f, position.y + radius * 1.5f) // Левое крыло
        path.lineTo(position.x + radius * 0.3f, position.y + radius * 1.5f) // Правое крыло
        path.lineTo(position.x + radius * 0.4f, position.y + radius * 0.8f) // Правое сужение
        path.lineTo(position.x + radius * 0.8f, position.y + radius * 1.2f) // Правое основание
        path.close()

        canvas.drawPath(path, borderPaint)
        canvas.drawPath(path, rocketPaint)

        // Окно кабины
        canvas.drawCircle(position.x, position.y - radius * 0.5f, radius * 0.4f, detailPaint)
        canvas.drawCircle(position.x, position.y - radius * 0.5f, radius * 0.3f, borderPaint)

        // Пламя двигателя
        val flameLength = radius * 1.5f + abs(horizontalSpeed) * 0.5f
        val flamePath = Path()
        flamePath.moveTo(position.x - radius * 0.3f, position.y + radius * 1.5f)
        flamePath.lineTo(position.x, position.y + radius * 1.5f + flameLength)
        flamePath.lineTo(position.x + radius * 0.3f, position.y + radius * 1.5f)
        flamePath.close()

        val flamePaint = Paint().apply {
            shader = LinearGradient(
                position.x, position.y + radius * 1.5f,
                position.x, position.y + radius * 1.5f + flameLength,
                Color.YELLOW, Color.RED,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(flamePath, flamePaint)
    }

    fun getSpeed(): Float {
        return baseSpeed
    }
}