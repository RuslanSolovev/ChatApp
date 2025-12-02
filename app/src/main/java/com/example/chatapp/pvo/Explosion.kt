package com.example.chatapp.pvo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

class Explosion(position: Vector2D) {
    var position = position.copy()
    private var lifetime = 0f
    private val maxLifetime = 0.5f // Полсекунды анимации
    private var radius = 5f
    private val maxRadius = 40f
    private val particles = mutableListOf<Particle>()

    init {
        // Создаем частицы взрыва
        for (i in 0..15) {
            val angle = Math.random() * Math.PI * 2
            val speed = 100f + Math.random().toFloat() * 200f
            particles.add(Particle(
                position.copy(),
                Vector2D(cos(angle).toFloat() * speed, sin(angle).toFloat() * speed)
            ))
        }
    }

    fun update(deltaTime: Float): Boolean {
        lifetime += deltaTime

        // Увеличиваем радиус взрыва
        val progress = lifetime / maxLifetime
        radius = maxRadius * progress.coerceAtMost(1f)

        // Обновляем частицы
        particles.forEach { it.update(deltaTime) }

        // Удаляем взрыв по истечении времени
        return lifetime >= maxLifetime
    }

    fun draw(canvas: Canvas) {
        val alpha = ((1f - lifetime / maxLifetime) * 200).toInt()

        // Внешний круг взрыва (огненная волна)
        val outerPaint = Paint().apply {
            color = Color.argb(alpha / 2, 255, 200, 0)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(position.x, position.y, radius, outerPaint)

        // Внутренний круг (ядро взрыва)
        val innerPaint = Paint().apply {
            color = Color.argb(alpha, 255, 100, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(position.x, position.y, radius * 0.7f, innerPaint)

        // Рисуем частицы
        particles.forEach { it.draw(canvas, alpha) }
    }

    private class Particle(
        var position: Vector2D,
        var velocity: Vector2D
    ) {
        private var lifetime = 0f
        private val maxLifetime = 0.3f

        fun update(deltaTime: Float) {
            lifetime += deltaTime
            velocity.y += 300f * deltaTime // гравитация
            position += velocity * deltaTime
        }

        fun draw(canvas: Canvas, baseAlpha: Int) {
            val particleAlpha = (baseAlpha * (1f - lifetime / maxLifetime)).toInt()
            val particleRadius = 2f + (1f - lifetime / maxLifetime) * 3f

            val paint = Paint().apply {
                color = Color.argb(particleAlpha, 255, 150, 50)
                style = Paint.Style.FILL
            }

            canvas.drawCircle(position.x, position.y, particleRadius, paint)
        }
    }
}