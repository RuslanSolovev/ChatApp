package com.example.chatapp.pvo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

enum class MissileType(
    val speed: Float,
    val turnRate: Float,
    val color: Int,
    val lifetime: Float,
    val radius: Float = 15f
) {
    STANDARD(200f, 2f, Color.RED, 15f),
    FAST(300f, 4f, Color.YELLOW, 12f),
    ZIGZAG(180f, 6f, Color.CYAN, 18f),
    HOMING(150f, 8f, Color.MAGENTA, 20f, 12f),
    HEAVY(120f, 1f, Color.parseColor("#8B4513"), 25f, 20f),
    SPLITTING(180f, 3f, Color.parseColor("#FF6B35"), 12f),
    TELEPORTING(220f, 2f, Color.parseColor("#9B59B6"), 18f),
    SNIPER(250f, 1f, Color.parseColor("#2ECC71"), 10f),
    MIRROR(160f, 4f, Color.parseColor("#F1C40F"), 16f)
}

class Missile(
    startPosition: Vector2D,
    private val target: GameObject,
    val type: MissileType = MissileType.STANDARD,
    private val gameView: GameView? = null
) : GameObject(startPosition, Vector2D(0f, 0f), type.radius) {

    private val speed: Float = type.speed
    private val turnRate: Float = type.turnRate
    private var lifetime = 0f
    private val maxLifetime = type.lifetime
    private var zigzagTimer = 0f
    private var zigzagPhase = Random.nextFloat() * PI.toFloat() * 2f
    private var homingOffset = Random.nextFloat() * PI.toFloat() * 2f

    // Новые переменные для специальных ракет
    private var hasSplit = false
    private var teleportTimer = 0f
    private var lastTeleportTime = 0f
    private var sniperChargeTime = 0f
    private var isSniperCharged = false
    private var mirrorInitialized = false

    override fun update(deltaTime: Float) {
        lifetime += deltaTime

        if (lifetime >= maxLifetime) {
            return
        }

        when (type) {
            MissileType.STANDARD -> updateStandardMissile(deltaTime)
            MissileType.FAST -> updateFastMissile(deltaTime)
            MissileType.ZIGZAG -> updateZigzagMissile(deltaTime)
            MissileType.HOMING -> updateHomingMissile(deltaTime)
            MissileType.HEAVY -> updateHeavyMissile(deltaTime)
            MissileType.SPLITTING -> updateSplittingMissile(deltaTime)
            MissileType.TELEPORTING -> updateTeleportingMissile(deltaTime)
            MissileType.SNIPER -> updateSniperMissile(deltaTime)
            MissileType.MIRROR -> updateMirrorMissile(deltaTime)
        }

        super.update(deltaTime)
    }

    private fun updateStandardMissile(deltaTime: Float) {
        val directionToTarget = (target.position - position).normalize()

        if (velocity.length() > 0) {
            val currentDirection = velocity.normalize()
            val crossProduct = currentDirection.x * directionToTarget.y - currentDirection.y * directionToTarget.x

            val turnAngle = if (crossProduct > 0) turnRate * deltaTime else -turnRate * deltaTime
            val newDirection = currentDirection.rotate(turnAngle)
            velocity = newDirection * speed
        } else {
            velocity = directionToTarget * speed
        }
    }

    private fun updateFastMissile(deltaTime: Float) {
        val directionToTarget = (target.position - position).normalize()

        if (velocity.length() > 0) {
            val currentDirection = velocity.normalize()
            val newDirection = (currentDirection * 0.3f + directionToTarget * 0.7f).normalize()
            velocity = newDirection * speed
        } else {
            velocity = directionToTarget * speed
        }
    }

    private fun updateZigzagMissile(deltaTime: Float) {
        zigzagTimer += deltaTime

        val baseDirection = (target.position - position).normalize()
        val perpendicular = Vector2D(-baseDirection.y, baseDirection.x)

        val zigzagOffset = sin(zigzagTimer * 4f * 2f * PI.toFloat() + zigzagPhase) * 1.2f
        val zigzagDirection = (baseDirection + perpendicular * zigzagOffset).normalize()

        velocity = zigzagDirection * speed
    }

    private fun updateHomingMissile(deltaTime: Float) {
        zigzagTimer += deltaTime

        val targetDirection = (target.position - position).normalize()
        val targetVelocity = target.velocity.normalize() * 50f

        val prediction = target.position + targetVelocity
        val directionToPrediction = (prediction - position).normalize()

        val homingFactor = sin(zigzagTimer * 3f + homingOffset) * 0.4f
        val perpendicular = Vector2D(-directionToPrediction.y, directionToPrediction.x)
        val finalDirection = (directionToPrediction + perpendicular * homingFactor).normalize()

        velocity = finalDirection * speed
    }

    private fun updateHeavyMissile(deltaTime: Float) {
        val directionToTarget = (target.position - position).normalize()

        if (velocity.length() > 0) {
            val currentDirection = velocity.normalize()
            val newDirection = (currentDirection * 0.8f + directionToTarget * 0.2f).normalize()
            velocity = newDirection * speed
        } else {
            velocity = directionToTarget * speed
        }
    }

    private fun updateSplittingMissile(deltaTime: Float) {
        val directionToTarget = (target.position - position).normalize()

        if (!hasSplit && lifetime > maxLifetime * 0.6f) {
            splitIntoSmallMissiles()
            hasSplit = true
        }

        if (!hasSplit) {
            velocity = directionToTarget * speed
        } else {
            velocity = velocity * 0.95f
        }
    }

    private fun updateTeleportingMissile(deltaTime: Float) {
        teleportTimer += deltaTime
        lastTeleportTime += deltaTime

        if (lastTeleportTime > 2f + Random.nextFloat() * 2f) {
            teleport()
            lastTeleportTime = 0f
        }

        val directionToTarget = (target.position - position).normalize()
        velocity = directionToTarget * speed
    }

    private fun updateSniperMissile(deltaTime: Float) {
        sniperChargeTime += deltaTime

        if (!isSniperCharged) {
            velocity = velocity * 0.8f

            if (sniperChargeTime > 2f) {
                isSniperCharged = true
                val directionToTarget = (target.position - position).normalize()
                velocity = directionToTarget * speed * 3f
            }
        }
    }

    private fun updateMirrorMissile(deltaTime: Float) {
        if (!mirrorInitialized) {
            mirrorInitialized = true
            val centerX = target.position.x
            val centerY = target.position.y

            val mirroredX = 2 * centerX - position.x
            val mirroredY = 2 * centerY - position.y
            val direction = Vector2D(mirroredX - position.x, mirroredY - position.y).normalize()
            velocity = direction * speed
        }

        val currentDirection = velocity.normalize()
        val playerDirection = target.velocity.normalize()
        val mirroredPlayerDirection = Vector2D(-playerDirection.x, -playerDirection.y)
        val finalDirection = (currentDirection * 0.8f + mirroredPlayerDirection * 0.2f).normalize()
        velocity = finalDirection * speed
    }

    private fun splitIntoSmallMissiles() {
        gameView?.let { view ->
            for (i in 0 until 3) {
                val angle = i * 120f * (PI.toFloat() / 180f)
                val direction = velocity.normalize().rotate(angle)
                val childVelocity = direction * speed * 1.2f

                // Исправлено: используем новый Vector2D вместо copy()
                val childPosition = Vector2D(position.x, position.y)

                val childMissile = Missile(
                    childPosition,
                    target,
                    MissileType.FAST,
                    null
                ).apply {
                    this.velocity = childVelocity
                    this.radius = 8f
                }

                view.addChildMissile(childMissile)
            }
        }
    }

    private fun teleport() {
        val minDistance = 100f
        val maxDistance = 300f

        val angle = Random.nextFloat() * 2f * PI.toFloat()
        val distance = minDistance + Random.nextFloat() * (maxDistance - minDistance)

        val newX = target.position.x + cos(angle) * distance
        val newY = target.position.y + sin(angle) * distance

        position.x = newX.coerceIn(50f, gameView?.width?.toFloat()?.minus(50f) ?: newX)
        position.y = newY.coerceIn(50f, gameView?.height?.toFloat()?.minus(50f) ?: newY)

        val randomAngle = (Random.nextFloat() - 0.5f) * 0.5f
        velocity = velocity.rotate(randomAngle)
    }

    override fun draw(canvas: Canvas) {
        val paint = Paint().apply {
            color = getMissileColor()
            style = Paint.Style.FILL
        }

        val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        when (type) {
            MissileType.STANDARD -> drawStandardMissile(canvas, paint, borderPaint)
            MissileType.FAST -> drawFastMissile(canvas, paint, borderPaint)
            MissileType.ZIGZAG -> drawZigzagMissile(canvas, paint, borderPaint)
            MissileType.HOMING -> drawHomingMissile(canvas, paint, borderPaint)
            MissileType.HEAVY -> drawHeavyMissile(canvas, paint, borderPaint)
            MissileType.SPLITTING -> drawSplittingMissile(canvas, paint, borderPaint)
            MissileType.TELEPORTING -> drawTeleportingMissile(canvas, paint, borderPaint)
            MissileType.SNIPER -> drawSniperMissile(canvas, paint, borderPaint)
            MissileType.MIRROR -> drawMirrorMissile(canvas, paint, borderPaint)
        }

        drawLifetimeIndicator(canvas)
    }

    private fun drawStandardMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        if (velocity.length() > 0) {
            val direction = velocity.normalize()
            val perpendicular = Vector2D(-direction.y, direction.x)

            val path = Path()
            val head = position + direction * radius * 1.8f
            val tail1 = position - direction * radius * 0.5f + perpendicular * radius * 0.8f
            val tail2 = position - direction * radius * 0.5f - perpendicular * radius * 0.8f

            path.moveTo(head.x, head.y)
            path.lineTo(tail1.x, tail1.y)
            path.lineTo(tail2.x, tail2.y)
            path.close()

            canvas.drawPath(path, borderPaint)
            canvas.drawPath(path, paint)
        } else {
            canvas.drawCircle(position.x, position.y, radius, borderPaint)
            canvas.drawCircle(position.x, position.y, radius - 2, paint)
        }
    }

    private fun drawFastMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        if (velocity.length() > 0) {
            val direction = velocity.normalize()
            val perpendicular = Vector2D(-direction.y, direction.x)

            val path = Path()
            val head = position + direction * radius * 2.2f
            val tail1 = position - direction * radius * 0.3f + perpendicular * radius * 0.4f
            val tail2 = position - direction * radius * 0.3f - perpendicular * radius * 0.4f

            path.moveTo(head.x, head.y)
            path.lineTo(tail1.x, tail1.y)
            path.lineTo(tail2.x, tail2.y)
            path.close()

            canvas.drawPath(path, borderPaint)
            canvas.drawPath(path, paint)
        } else {
            canvas.drawCircle(position.x, position.y, radius, borderPaint)
            canvas.drawCircle(position.x, position.y, radius - 2, paint)
        }
    }

    private fun drawZigzagMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        if (velocity.length() > 0) {
            val direction = velocity.normalize()
            val perpendicular = Vector2D(-direction.y, direction.x)

            val path = Path()
            val top = position + direction * radius * 1.2f
            val bottom = position - direction * radius * 1.2f
            val left = position + perpendicular * radius * 0.8f
            val right = position - perpendicular * radius * 0.8f

            path.moveTo(top.x, top.y)
            path.lineTo(right.x, right.y)
            path.lineTo(bottom.x, bottom.y)
            path.lineTo(left.x, left.y)
            path.close()

            canvas.drawPath(path, borderPaint)
            canvas.drawPath(path, paint)
        } else {
            canvas.drawCircle(position.x, position.y, radius, borderPaint)
            canvas.drawCircle(position.x, position.y, radius - 2, paint)
        }
    }

    private fun drawHomingMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        canvas.drawCircle(position.x, position.y, radius, borderPaint)
        canvas.drawCircle(position.x, position.y, radius - 2, paint)

        if (velocity.length() > 0) {
            val direction = velocity.normalize()
            val eyePaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            val eyeOffset = radius * 0.5f
            val leftEyeX = position.x - direction.y * eyeOffset
            val leftEyeY = position.y + direction.x * eyeOffset
            val rightEyeX = position.x + direction.y * eyeOffset
            val rightEyeY = position.y - direction.x * eyeOffset

            canvas.drawCircle(leftEyeX, leftEyeY, radius * 0.3f, eyePaint)
            canvas.drawCircle(rightEyeX, rightEyeY, radius * 0.3f, eyePaint)
        }
    }

    private fun drawHeavyMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        canvas.drawCircle(position.x, position.y, radius, borderPaint)
        canvas.drawCircle(position.x, position.y, radius - 2, paint)

        val innerBorderPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(position.x, position.y, radius - 4, innerBorderPaint)
    }

    private fun drawSplittingMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        if (velocity.length() > 0) {
            val direction = velocity.normalize()
            val perpendicular = Vector2D(-direction.y, direction.x)

            val path = Path()
            val head = position + direction * radius * 1.5f
            val middle = position + direction * radius * 0.5f
            val tail1 = position - direction * radius + perpendicular * radius
            val tail2 = position - direction * radius - perpendicular * radius

            path.moveTo(head.x, head.y)
            path.lineTo(tail1.x, tail1.y)
            path.lineTo(middle.x, middle.y)
            path.close()

            path.moveTo(head.x, head.y)
            path.lineTo(tail2.x, tail2.y)
            path.lineTo(middle.x, middle.y)

            canvas.drawPath(path, borderPaint)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawTeleportingMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        canvas.drawCircle(position.x, position.y, radius, borderPaint)
        canvas.drawCircle(position.x, position.y, radius - 2, paint)

        val ringPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
            alpha = (sin(teleportTimer * 5f) * 127 + 128).toInt()
        }

        val ringRadius = radius + 5f + sin(teleportTimer * 8f) * 3f
        canvas.drawCircle(position.x, position.y, ringRadius, ringPaint)
    }

    private fun drawSniperMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        if (!isSniperCharged) {
            val chargeAlpha = (sin(sniperChargeTime * 10f) * 50 + 100).toInt()
            paint.alpha = chargeAlpha

            canvas.drawCircle(position.x, position.y, radius, borderPaint)
            canvas.drawCircle(position.x, position.y, radius - 2, paint)

            if (sniperChargeTime > 1f) {
                val sightPaint = Paint().apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    alpha = chargeAlpha
                }
                val targetDir = (target.position - position).normalize()
                val sightEnd = position + targetDir * 100f
                canvas.drawLine(position.x, position.y, sightEnd.x, sightEnd.y, sightPaint)
            }
        } else {
            if (velocity.length() > 0) {
                val direction = velocity.normalize()
                val path = Path()
                val head = position + direction * radius * 3f
                val tail = position - direction * radius * 0.5f

                path.moveTo(head.x, head.y)
                path.lineTo(tail.x, tail.y)

                val linePaint = Paint().apply {
                    color = paint.color
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }

    private fun drawMirrorMissile(canvas: Canvas, paint: Paint, borderPaint: Paint) {
        canvas.drawCircle(position.x, position.y, radius, borderPaint)

        val patternPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        canvas.drawLine(
            position.x - radius * 0.7f, position.y,
            position.x + radius * 0.7f, position.y,
            patternPaint
        )
        canvas.drawLine(
            position.x, position.y - radius * 0.7f,
            position.x, position.y + radius * 0.7f,
            patternPaint
        )

        canvas.drawCircle(position.x, position.y, radius - 2, paint)
    }

    private fun getMissileColor(): Int {
        return if (lifetime > maxLifetime * 0.7f) {
            when (type) {
                MissileType.STANDARD -> Color.argb(255, 255, 150, 150)
                MissileType.FAST -> Color.argb(255, 255, 255, 150)
                MissileType.ZIGZAG -> Color.argb(255, 150, 255, 255)
                MissileType.HOMING -> Color.argb(255, 255, 150, 255)
                MissileType.HEAVY -> Color.argb(255, 165, 100, 50)
                MissileType.SPLITTING -> Color.argb(255, 255, 150, 100)
                MissileType.TELEPORTING -> Color.argb(255, 200, 150, 255)
                MissileType.SNIPER -> Color.argb(255, 150, 255, 150)
                MissileType.MIRROR -> Color.argb(255, 255, 230, 100)
            }
        } else {
            type.color
        }
    }

    private fun drawLifetimeIndicator(canvas: Canvas) {
        val indicatorPaint = Paint().apply {
            color = when {
                lifetime > maxLifetime * 0.7f -> Color.RED
                lifetime > maxLifetime * 0.4f -> Color.YELLOW
                else -> Color.GREEN
            }
            style = Paint.Style.FILL
        }

        val indicatorX = position.x
        val indicatorY = position.y - radius - 8f
        val indicatorRadius = 4f

        canvas.drawCircle(indicatorX, indicatorY, indicatorRadius, indicatorPaint)
    }

    fun shouldDestroy(): Boolean {
        return lifetime >= maxLifetime
    }

    fun getTypeName(): String {
        return when (type) {
            MissileType.STANDARD -> "Обычная"
            MissileType.FAST -> "Быстрая"
            MissileType.ZIGZAG -> "Зигзаг"
            MissileType.HOMING -> "Умная"
            MissileType.HEAVY -> "Тяжелая"
            MissileType.SPLITTING -> "Разделяющаяся"
            MissileType.TELEPORTING -> "Телепортирующая"
            MissileType.SNIPER -> "Снайперская"
            MissileType.MIRROR -> "Зеркальная"
        }
    }
}