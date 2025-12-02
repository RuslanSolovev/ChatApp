package com.example.chatapp.pvo

import android.content.Context
import android.graphics.*
import kotlin.random.Random

class RealWorldMap(context: Context, private val width: Int, private val height: Int) {

    private var scrollY = 0f
    private val scrollSpeed = 250f // УМЕНЬШИЛИ скорость для более длительной миссии
    private var totalDistance = 0f
    private val mapHeight = 24000f // ОЧЕНЬ длинная карта (24,000 пикселей)

    // КАЖДАЯ СТРАНА ~3000px (1 минута при скорости 250px/с)
    private val countries = listOf(
        Country("🇷🇺 РОССИЯ", Color.parseColor("#4CAF50"), 0f, 3000f, "Система ПВО С-400"),
        Country("🇧🇾 БЕЛАРУСЬ", Color.parseColor("#8BC34A"), 3000f, 6000f, "ПВО Тор-М2"),
        Country("🇵🇱 ПОЛЬША", Color.parseColor("#CDDC39"), 6000f, 9000f, "ПВО Patriot"),
        Country("🇩🇪 ГЕРМАНИЯ", Color.parseColor("#FFEB3B"), 9000f, 12000f, "ПВО IRIS-T"),
        Country("🇫🇷 ФРАНЦИЯ", Color.parseColor("#FFC107"), 12000f, 15000f, "ПВО Mamba"),
        Country("🇪🇸 ИСПАНИЯ", Color.parseColor("#FF9800"), 15000f, 18000f, "ПВО NASAMS"),
        Country("🌊 АТЛАНТИЧЕСКИЙ ОКЕАН", Color.parseColor("#2196F3"), 18000f, 21000f, "Корабельные ПВО"),
        Country("🇺🇸 США", Color.parseColor("#F44336"), 21000f, mapHeight, "ПВО THAAD")
    )

    private val pvoSystems = listOf(
        PVOSystem("🇷🇺 РОССИЯ", 0.012f, listOf(
            MissileType.STANDARD, MissileType.STANDARD, MissileType.HEAVY
        ), "С-400"),
        PVOSystem("🇧🇾 БЕЛАРУСЬ", 0.015f, listOf(
            MissileType.STANDARD, MissileType.FAST, MissileType.ZIGZAG
        ), "Тор-М2"),
        PVOSystem("🇵🇱 ПОЛЬША", 0.018f, listOf(
            MissileType.FAST, MissileType.ZIGZAG, MissileType.HOMING
        ), "Patriot"),
        PVOSystem("🇩🇪 ГЕРМАНИЯ", 0.022f, listOf(
            MissileType.ZIGZAG, MissileType.HOMING, MissileType.SPLITTING
        ), "IRIS-T"),
        PVOSystem("🇫🇷 ФРАНЦИЯ", 0.025f, listOf(
            MissileType.HOMING, MissileType.SPLITTING, MissileType.TELEPORTING
        ), "Mamba"),
        PVOSystem("🇪🇸 ИСПАНИЯ", 0.028f, listOf(
            MissileType.SPLITTING, MissileType.TELEPORTING, MissileType.SNIPER
        ), "NASAMS"),
        PVOSystem("🌊 АТЛАНТИЧЕСКИЙ ОКЕАН", 0.008f, listOf(
            MissileType.STANDARD, MissileType.FAST
        ), "Корабельные"),
        PVOSystem("🇺🇸 США", 0.035f, listOf(
            MissileType.SNIPER, MissileType.MIRROR, MissileType.TELEPORTING,
            MissileType.HOMING, MissileType.SPLITTING
        ), "THAAD")
    )

    private var currentTimeInCountry = 0f
    private var currentCountryIndex = 0
    private var lastCountryChangeTime = 0L

    fun update(deltaTime: Float) {
        scrollY += scrollSpeed * deltaTime
        totalDistance += scrollSpeed * deltaTime / 15f
        currentTimeInCountry += deltaTime

        // Обновляем текущую страну
        val currentPos = scrollY + height / 2
        countries.forEachIndexed { index, country ->
            if (currentPos >= country.startY && currentPos <= country.endY) {
                if (currentCountryIndex != index) {
                    currentCountryIndex = index
                    currentTimeInCountry = 0f
                    lastCountryChangeTime = System.currentTimeMillis()
                }
            }
        }
    }

    fun draw(canvas: Canvas) {
        // Динамическое небо (утро/день/вечер/ночь)
        val timeOfDay = (scrollY / mapHeight).coerceIn(0f, 1f)
        val skyColor = getSkyColor(timeOfDay)

        val skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                skyColor, Color.parseColor("#001F3F"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)

        // Солнце/луна
        drawCelestialBody(canvas, timeOfDay)

        // Рисуем програмную карту
        drawProceduralMap(canvas)

        // Облака
        drawClouds(canvas, timeOfDay)

        // Звезды ночью
        // if (timeOfDay > 0.7f || timeOfDay < 0.2f) {
        //     drawStars(canvas, timeOfDay)
        // }
    }

    private fun getSkyColor(timeOfDay: Float): Int {
        return when {
            timeOfDay < 0.25f -> Color.parseColor("#FFA500") // Рассвет
            timeOfDay < 0.5f -> Color.parseColor("#87CEEB")  // День
            timeOfDay < 0.75f -> Color.parseColor("#FF6347") // Закат
            else -> Color.parseColor("#191970")              // Ночь
        }
    }

    private fun drawCelestialBody(canvas: Canvas, timeOfDay: Float) {
        val isDay = timeOfDay < 0.75f && timeOfDay > 0.25f
        val celestialPaint = Paint().apply {
            color = if (isDay) Color.YELLOW else Color.LTGRAY
            style = Paint.Style.FILL
        }

        val xPos = width * timeOfDay
        val yPos = height * 0.2f + (Math.sin(timeOfDay * Math.PI * 2) * 100).toFloat()

        canvas.drawCircle(xPos, yPos, if (isDay) 50f else 40f, celestialPaint)

        // Лучи солнца
        if (isDay) {
            val rayPaint = Paint().apply {
                color = Color.argb(100, 255, 255, 200)
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }

            for (i in 0..7) {
                val angle = i * 45f
                val rad = Math.toRadians(angle.toDouble())
                val endX = xPos + Math.cos(rad) * 80
                val endY = yPos + Math.sin(rad) * 80
                canvas.drawLine(xPos, yPos, endX.toFloat(), endY.toFloat(), rayPaint)
            }
        }
    }

    private fun drawProceduralMap(canvas: Canvas) {
        val visibleStart = scrollY
        val visibleEnd = scrollY + height

        countries.forEach { country ->
            if (country.startY < visibleEnd && country.endY > visibleStart) {
                val drawStart = (country.startY - visibleStart).coerceAtLeast(0f)
                val drawEnd = (country.endY - visibleStart).coerceAtMost(height.toFloat())

                if (drawEnd > drawStart) {
                    // Градиент для страны
                    val gradient = LinearGradient(
                        0f, drawStart, 0f, drawEnd,
                        country.color, Color.argb(200, Color.red(country.color), Color.green(country.color), Color.blue(country.color)),
                        Shader.TileMode.CLAMP
                    )

                    val countryPaint = Paint().apply {
                        shader = gradient
                        alpha = 220
                    }

                    // Основная территория
                    canvas.drawRect(0f, drawStart, width.toFloat(), drawEnd, countryPaint)

                    // Топографические линии (горизонтальные)
                    val topoPaint = Paint().apply {
                        color = Color.argb(40, 255, 255, 255)
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f
                    }

                    val lineSpacing = 80f
                    var lineY = drawStart + (lineSpacing - (drawStart % lineSpacing))
                    while (lineY < drawEnd) {
                        canvas.drawLine(0f, lineY, width.toFloat(), lineY, topoPaint)
                        lineY += lineSpacing
                    }

                    // Граница страны
                    val borderPaint = Paint().apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        alpha = 180
                    }
                    canvas.drawRect(0f, drawStart, width.toFloat(), drawEnd, borderPaint)

                    // Название страны и информация (рисуем в начале каждой страны)
                    if (drawStart < 150 && drawEnd - drawStart > 100) {
                        drawCountryInfo(canvas, country, drawStart)
                    }

                    // Города/точки интереса (случайные точки на карте)
                    drawLandmarks(canvas, country, drawStart, drawEnd)
                }
            }
        }

        // Сетка расстояний
        drawDistanceGrid(canvas, visibleStart)
    }

    private fun drawCountryInfo(canvas: Canvas, country: Country, yPos: Float) {
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            typeface = Typeface.DEFAULT_BOLD // Используем константу вместо создания
        }

        val infoPaint = Paint().apply {
            color = Color.LTGRAY
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT // Простой шрифт
        }

        val backgroundPaint = Paint().apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val text = country.name
        val infoText = "Система ПВО: ${country.pvoSystem}"
        val textWidth = textPaint.measureText(text).coerceAtLeast(infoPaint.measureText(infoText))

        val rectTop = yPos + 10f
        val rectBottom = rectTop + 120f

        // Фон
        canvas.drawRect(
            width / 2f - textWidth / 2 - 30f, rectTop,
            width / 2f + textWidth / 2 + 30f, rectBottom,
            backgroundPaint
        )

        // Текст
        canvas.drawText(text, width / 2f, rectTop + 45f, textPaint)
        canvas.drawText(infoText, width / 2f, rectTop + 85f, infoPaint)

        // Прогресс по стране
        val countryProgress = getCountryProgress()
        val progressPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }

        canvas.drawText("${(countryProgress * 100).toInt()}% территории пройдено",
            width / 2f, rectBottom + 30f, progressPaint)
    }

    private fun drawLandmarks(canvas: Canvas, country: Country, startY: Float, endY: Float) {
        val landmarkPaint = Paint().apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
        }

        // Случайные точки (города/базы ПВО)
        for (i in 0..5) {
            val landmarkY = startY + (endY - startY) * Random.nextFloat()
            val landmarkX = width * Random.nextFloat()

            // Маленькие круги для городов
            canvas.drawCircle(landmarkX, landmarkY, 8f, landmarkPaint)

            // ЭТОТ БЛОК НУЖНО ЗАКОММЕНТИРОВАТЬ (желтые столицы):
            // Иногда добавляем большие круги (столицы/крупные базы)
            /*
            if (Random.nextFloat() < 0.3f) {
                val cityPaint = Paint().apply {
                    color = Color.YELLOW
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(landmarkX, landmarkY, 12f, cityPaint)

                // Радиус вокруг крупного города
                val radiusPaint = Paint().apply {
                    color = Color.argb(50, 255, 255, 0)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                canvas.drawCircle(landmarkX, landmarkY, 40f, radiusPaint)
            }
            */
        }
    }

    private fun drawClouds(canvas: Canvas, timeOfDay: Float) {
        val cloudAlpha = if (timeOfDay > 0.7f) 80 else 150
        val cloudPaint = Paint().apply {
            color = Color.argb(cloudAlpha, 255, 255, 255)
            style = Paint.Style.FILL
        }

        // Много слоев облаков
        for (layer in 0..2) {
            val layerSpeed = 0.05f + layer * 0.03f
            val layerScale = 0.8f + layer * 0.2f

            for (i in 0..8) {
                val cloudX = (scrollY * layerSpeed + i * 400) % (width + 500) - 250
                val cloudY = (scrollY * (layerSpeed * 0.5f) + i * 200 + layer * 100) % (height + 400) - 200

                if (cloudY in -200f..(height + 200f)) {
                    drawCloud(canvas, cloudPaint, cloudX, cloudY, layerScale)
                }
            }
        }
    }

    private fun drawCloud(canvas: Canvas, paint: Paint, x: Float, y: Float, scale: Float) {
        val size = 60f * scale

        canvas.drawCircle(x, y, size, paint)
        canvas.drawCircle(x + size * 0.7f, y - size * 0.4f, size * 0.8f, paint)
        canvas.drawCircle(x + size * 1.4f, y, size, paint)
        canvas.drawCircle(x + size * 0.7f, y + size * 0.4f, size * 0.6f, paint)
        canvas.drawCircle(x - size * 0.4f, y + size * 0.3f, size * 0.7f, paint)
        canvas.drawCircle(x - size * 0.7f, y - size * 0.2f, size * 0.5f, paint)
    }

    private fun drawStars(canvas: Canvas, timeOfDay: Float) {
        val starIntensity = if (timeOfDay > 0.7f) (timeOfDay - 0.7f) * 3.33f else (0.2f - timeOfDay) * 5f
        val starAlpha = (starIntensity * 255).toInt().coerceIn(0, 255)

        val starPaint = Paint().apply {
            color = Color.argb(starAlpha, 255, 255, 255)
            style = Paint.Style.FILL
        }

        // Созвездия
        for (i in 0..100) {
            val starX = (i * 241) % width
            val starY = (scrollY * 0.02f + i * 137) % height
            val starSize = ((i % 5) + 1).toFloat() * 1.5f
            val twinkle = (Math.sin(System.currentTimeMillis() * 0.001 + i) * 0.3 + 0.7).toFloat()

            starPaint.alpha = (starAlpha * twinkle).toInt()
            canvas.drawCircle(starX.toFloat(), starY, starSize, starPaint)

            // Иногда соединяем звезды линиями (созвездия)
            if (i % 7 == 0 && Random.nextFloat() < 0.3f) {
                val constellationPaint = Paint().apply {
                    color = Color.argb(starAlpha / 3, 255, 255, 255)
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                val nextStarX = ((i + 3) * 241) % width
                val nextStarY = (scrollY * 0.02f + (i + 3) * 137) % height
                canvas.drawLine(starX.toFloat(), starY, nextStarX.toFloat(), nextStarY, constellationPaint)
            }
        }
    }

    private fun drawDistanceGrid(canvas: Canvas, visibleStart: Float) {
        val gridPaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            strokeWidth = 1f
        }

        val textPaint = Paint().apply {
            color = Color.LTGRAY
            textSize = 20f
            textAlign = Paint.Align.RIGHT
        }

        // Горизонтальные линии каждые 500 пикселей (50 км)
        for (i in 0..(mapHeight / 500).toInt()) {
            val lineY = i * 500 - visibleStart
            if (lineY in 0f..height.toFloat()) {
                canvas.drawLine(0f, lineY, width.toFloat(), lineY, gridPaint)
                val distanceKm = ((scrollY + lineY) / 10).toInt()
                canvas.drawText("${distanceKm} км", width - 20f, lineY - 10f, textPaint)
            }
        }
    }

    fun getCurrentCountry(): String {
        val currentPos = scrollY + height / 2
        return countries.find {
            currentPos >= it.startY && currentPos <= it.endY
        }?.name ?: "Неизвестно"
    }

    fun getCountryPVOSystem(): String {
        val currentPos = scrollY + height / 2
        return countries.find {
            currentPos >= it.startY && currentPos <= it.endY
        }?.pvoSystem ?: "Неизвестно"
    }

    fun getCountryProgress(): Float {
        val currentPos = scrollY + height / 2
        countries.forEach { country ->
            if (currentPos >= country.startY && currentPos <= country.endY) {
                return ((currentPos - country.startY) / (country.endY - country.startY)).coerceIn(0f, 1f)
            }
        }
        return 0f
    }

    fun getPVOForCurrentCountry(): PVOSystem? {
        val country = getCurrentCountry()
        return pvoSystems.find { it.country == country }
    }

    fun getProgress(): Float {
        return (scrollY / (mapHeight - height)).coerceIn(0f, 1f) * 100f
    }

    fun isMissionComplete(): Boolean {
        return scrollY >= mapHeight - height
    }

    fun getTotalDistance(): Int {
        return totalDistance.toInt()
    }

    fun getTimeInCurrentCountry(): Float {
        return currentTimeInCountry
    }

    fun getEstimatedTimeRemaining(): String {
        val distanceRemaining = mapHeight - scrollY
        val timeRemainingSeconds = distanceRemaining / scrollSpeed

        val hours = (timeRemainingSeconds / 3600).toInt()
        val minutes = ((timeRemainingSeconds % 3600) / 60).toInt()
        val seconds = (timeRemainingSeconds % 60).toInt()

        return if (hours > 0) {
            "${hours}ч ${minutes}м"
        } else if (minutes > 0) {
            "${minutes}м ${seconds}с"
        } else {
            "${seconds}с"
        }
    }

    fun getCurrentCountryTime(): String {
        val minutes = (currentTimeInCountry / 60).toInt()
        val seconds = (currentTimeInCountry % 60).toInt()
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun getTotalMissionTime(): String {
        val totalSeconds = scrollY / scrollSpeed
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun getCountryIndex(): Int {
        return currentCountryIndex
    }

    fun getCountryCount(): Int {
        return countries.size
    }

    data class Country(
        val name: String,
        val color: Int,
        val startY: Float,
        val endY: Float,
        val pvoSystem: String
    )

    data class PVOSystem(
        val country: String,
        val spawnRate: Float,
        val missileTypes: List<MissileType>,
        val systemName: String
    )
}