package com.example.chatapp.pvo

import android.graphics.*
import kotlin.math.abs
import kotlin.random.Random

class MissionPlane(position: Vector2D, private val screenWidth: Int, private val screenHeight: Int) : GameObject(position, Vector2D(0f, 0f), 35f) {

    var isDestroyed = false
    var health = 100
    private var horizontalSpeed = 0f
    private var verticalSpeed = 0f
    private val maxSpeed = 4f // ОЧЕНЬ медленно для длительной миссии
    private val acceleration = 0.4f
    private val deceleration = 0.3f

    // Траектория полета
    private val trail = mutableListOf<Vector2D>()
    private val maxTrailLength = 30

    // Дым при повреждении
    private val smokeParticles = mutableListOf<SmokeParticle>()
    private var smokeTimer = 0f

    fun setMovement(directionX: Float, directionY: Float) {
        // Плавное управление с инерцией
        horizontalSpeed = when {
            directionX < -0.1f -> (horizontalSpeed - acceleration).coerceAtLeast(-maxSpeed)
            directionX > 0.1f -> (horizontalSpeed + acceleration).coerceAtMost(maxSpeed)
            else -> {
                val decel = if (abs(horizontalSpeed) < deceleration) abs(horizontalSpeed) else deceleration
                if (horizontalSpeed > 0) horizontalSpeed - decel else horizontalSpeed + decel
            }
        }

        verticalSpeed = when {
            directionY < -0.1f -> (verticalSpeed - acceleration).coerceAtLeast(-maxSpeed)
            directionY > 0.1f -> (verticalSpeed + acceleration).coerceAtMost(maxSpeed)
            else -> {
                val decel = if (abs(verticalSpeed) < deceleration) abs(verticalSpeed) else deceleration
                if (verticalSpeed > 0) verticalSpeed - decel else verticalSpeed + decel
            }
        }
    }

    fun takeDamage(damage: Int) {
        health -= damage
        if (health < 0) health = 0

        // Создаем частицы дыма при повреждении
        for (i in 0..2) {
            smokeParticles.add(SmokeParticle(
                Vector2D(position.x + Random.nextFloat() * 40f - 20f, position.y + Random.nextFloat() * 40f - 20f),
                Vector2D(Random.nextFloat() * 2f - 1f, Random.nextFloat() * 2f - 1f),
                Random.nextFloat() * 0.5f + 0.5f
            ))
        }
    }

    override fun update(deltaTime: Float) {
        if (!isDestroyed) {
            // Движение во всех направлениях
            velocity = Vector2D(horizontalSpeed * 60f, verticalSpeed * 60f)
            super.update(deltaTime)

            // Ограничение по краям экрана
            position.x = position.x.coerceIn(radius + 30f, screenWidth - radius - 30f)
            position.y = position.y.coerceIn(radius + 30f, screenHeight - radius - 30f)

            // Добавляем точку в траекторию
            trail.add(Vector2D(position.x, position.y))
            if (trail.size > maxTrailLength) {
                trail.removeAt(0)
            }

            // Обновляем частицы дыма
            updateSmoke(deltaTime)

            // Автоматически создаем немного дыма при низком здоровье
            if (health < 50) {
                smokeTimer += deltaTime
                if (smokeTimer > 0.2f) {
                    smokeParticles.add(SmokeParticle(
                        Vector2D(position.x + Random.nextFloat() * 30f - 15f, position.y + 20f),
                        Vector2D(Random.nextFloat() * 1f - 0.5f, Random.nextFloat() * 0.5f + 0.5f),
                        Random.nextFloat() * 0.3f + 0.3f
                    ))
                    smokeTimer = 0f
                }
            }
        }
    }

    private fun updateSmoke(deltaTime: Float) {
        val iterator = smokeParticles.iterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            particle.lifetime -= deltaTime
            if (particle.lifetime <= 0) {
                iterator.remove()
            } else {
                particle.position += particle.velocity * deltaTime * 30f
                particle.velocity.y += 0.1f * deltaTime // гравитация
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (isDestroyed) return

        // Рисуем дым первым (под самолетом)
        drawSmoke(canvas)

        // Рисуем траекторию
        drawTrail(canvas)

        // Рисуем самолет
        drawPlane(canvas)

        // Рисуем здоровье
        drawHealthBar(canvas)
    }

    private fun drawTrail(canvas: Canvas) {
        if (trail.size > 1) {
            val trailPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            for (i in 1 until trail.size) {
                val progress = i.toFloat() / trail.size
                val alpha = (progress * 100).toInt()
                val width = 2f + progress * 3f

                trailPaint.color = Color.argb(alpha, 100, 200, 255)
                trailPaint.strokeWidth = width

                canvas.drawLine(
                    trail[i-1].x, trail[i-1].y,
                    trail[i].x, trail[i].y,
                    trailPaint
                )
            }
        }
    }

    private fun drawSmoke(canvas: Canvas) {
        smokeParticles.forEach { particle ->
            val alpha = (particle.lifetime * 200).toInt().coerceIn(0, 200)
            val smokePaint = Paint().apply {
                color = Color.argb(alpha, 100, 100, 100)
                style = Paint.Style.FILL
            }

            val size = particle.lifetime * 15f
            canvas.drawCircle(particle.position.x, particle.position.y, size, smokePaint)
        }
    }

    private fun drawPlane(canvas: Canvas) {
        val planeColor = if (health > 50) Color.parseColor("#606060") else Color.parseColor("#804040")
        val damageColor = if (health > 50) Color.DKGRAY else Color.parseColor("#A05050")

        val planePaint = Paint().apply {
            color = planeColor
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val detailPaint = Paint().apply {
            color = damageColor
            style = Paint.Style.FILL
        }

        // Корпус самолета
        val path = Path()

        // Нос
        path.moveTo(position.x, position.y - radius * 1.5f)
        // Фюзеляж до кабины
        path.lineTo(position.x - radius * 0.5f, position.y - radius * 0.2f)
        path.lineTo(position.x - radius * 0.8f, position.y + radius * 0.5f)
        // Левое крыло
        path.lineTo(position.x - radius * 2.0f, position.y + radius * 0.3f)
        path.lineTo(position.x - radius * 1.2f, position.y + radius * 0.7f)
        // Хвостовая часть
        path.lineTo(position.x - radius * 0.6f, position.y + radius * 1.2f)
        path.lineTo(position.x + radius * 0.6f, position.y + radius * 1.2f)
        // Правое крыло
        path.lineTo(position.x + radius * 1.2f, position.y + radius * 0.7f)
        path.lineTo(position.x + radius * 2.0f, position.y + radius * 0.3f)
        path.lineTo(position.x + radius * 0.8f, position.y + radius * 0.5f)
        path.lineTo(position.x + radius * 0.5f, position.y - radius * 0.2f)
        path.close()

        canvas.drawPath(path, borderPaint)
        canvas.drawPath(path, planePaint)

        // Кабина
        val cockpitPaint = Paint().apply {
            shader = RadialGradient(
                position.x, position.y - radius * 0.5f, radius * 0.6f,
                Color.parseColor("#A0E0FF"), Color.parseColor("#4080C0"),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawCircle(position.x, position.y - radius * 0.5f, radius * 0.6f, cockpitPaint)
        canvas.drawCircle(position.x, position.y - radius * 0.5f, radius * 0.6f, borderPaint)

        // Двигатели
        val enginePaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }

        canvas.drawRect(
            position.x - radius * 1.8f, position.y - radius * 0.1f,
            position.x - radius * 1.3f, position.y + radius * 0.3f,
            enginePaint
        )
        canvas.drawRect(
            position.x + radius * 1.3f, position.y - radius * 0.1f,
            position.x + radius * 1.8f, position.y + radius * 0.3f,
            enginePaint
        )

        // Выхлопы
        val exhaustLength = 8f + abs(horizontalSpeed) * 0.5f
        val exhaustPaint = Paint().apply {
            shader = LinearGradient(
                position.x - radius * 1.8f, position.y + 0.1f,
                position.x - radius * 1.8f - exhaustLength, position.y + 0.1f,
                Color.YELLOW, Color.RED,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }

        canvas.drawRect(
            position.x - radius * 1.8f, position.y - 3f,
            position.x - radius * 1.8f - exhaustLength, position.y + 3f,
            exhaustPaint
        )
        canvas.drawRect(
            position.x + radius * 1.8f, position.y - 3f,
            position.x + radius * 1.8f + exhaustLength, position.y + 3f,
            exhaustPaint
        )

        // Опознавательные знаки
        val markingPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        canvas.drawCircle(position.x, position.y + radius * 0.9f, radius * 0.3f, markingPaint)
        canvas.drawCircle(position.x, position.y + radius * 0.9f, radius * 0.3f, borderPaint)
    }

    private fun drawHealthBar(canvas: Canvas) {
        val barWidth = 60f
        val barHeight = 8f
        val barX = position.x - barWidth / 2
        val barY = position.y - radius * 2f

        // Фон
        val backgroundPaint = Paint().apply {
            color = Color.GRAY
            style = Paint.Style.FILL
        }
        canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, backgroundPaint)

        // Здоровье
        val healthWidth = barWidth * (health / 100f)
        val healthColor = when {
            health > 70 -> Color.GREEN
            health > 30 -> Color.YELLOW
            else -> Color.RED
        }

        val healthPaint = Paint().apply {
            color = healthColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(barX, barY, barX + healthWidth, barY + barHeight, healthPaint)

        // Граница
        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, borderPaint)
    }

    fun isAlive(): Boolean {
        return health > 0 && !isDestroyed
    }

    data class SmokeParticle(
        var position: Vector2D,
        var velocity: Vector2D,
        var lifetime: Float
    )
}