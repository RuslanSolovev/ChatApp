package com.example.chatapp.location

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import timber.log.Timber
import kotlin.math.*
import java.util.*
import kotlin.collections.ArrayList

class RouteTrackerFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvCalories: TextView
    private lateinit var btnClear: Button
    private lateinit var btnStartTracking: Button
    private lateinit var btnStopTracking: Button
    private lateinit var btnToggleSheet: ImageButton
    private lateinit var bottomSheet: LinearLayout
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var polyline: PolylineMapObject? = null
    private var startMarker: PlacemarkMapObject? = null
    private var endMarker: PlacemarkMapObject? = null

    private var totalDistance = 0.0
    private var totalTimeInMotion = 0L
    private var maxSpeed = 0.0
    private var avgSpeed = 0.0
    private val userWeight = 70.0

    private var isDrawing = false
    private var lastLocationTime = 0L
    private var routePoints = mutableListOf<Point>()
    private var locationList = mutableListOf<UserLocation>()
    private var filteredLocationList = mutableListOf<UserLocation>()

    private var userLocationsListener: ValueEventListener? = null
    private var userLocationsRef: DatabaseReference? = null

    // Буфер для Kalman фильтра
    private val kalmanBuffer = LinkedList<Point>()


    // Настройки точности
    private var accuracyThreshold = 15.0f // метров (теперь Float)
    private var smoothingEnabled = true


    companion object {
        private const val TAG = "RouteTrackerFragment"
        private const val MIN_POINT_DISTANCE = 2.0
        private const val MAX_POINT_DISTANCE = 100.0
        private const val MAX_VALID_SPEED_MPS = 25.0f // Float для скорости
        private const val MIN_VALID_SPEED_MPS = 0.1f  // Float для скорости
        private const val MAX_TIME_DIFF = 30000L
        private const val MIN_TIME_DIFF = 1000L
        private const val MAX_ACCELERATION = 10.0f // Float для ускорения
        private const val BEARING_CHANGE_THRESHOLD = 45.0
        private const val KALMAN_BUFFER_SIZE = 5
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_route_tracker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupMap()
        setupBottomSheet()
        setupButtons()

        loadRouteForToday()
        checkDrawingStatus()
    }

    private fun initViews(view: View) {
        tvDistance = view.findViewById(R.id.Distance)
        tvTime = view.findViewById(R.id.tvTime)
        tvAvgSpeed = view.findViewById(R.id.AvgSpeed)
        tvMaxSpeed = view.findViewById(R.id.MaxSpeed)
        tvCalories = view.findViewById(R.id.Calories)
        btnClear = view.findViewById(R.id.btnClear)
        btnStartTracking = view.findViewById(R.id.btnStartTracking)
        btnStopTracking = view.findViewById(R.id.btnStopTracking)
        btnToggleSheet = view.findViewById(R.id.btnToggleSheet)
        bottomSheet = view.findViewById(R.id.bottomSheet)
    }

    private fun setupMap() {
        mapView = MapView(requireContext())
        val mapContainer = view?.findViewById<ViewGroup>(R.id.mapContainer)
        mapContainer?.removeAllViews()
        mapContainer?.addView(mapView)

        mapView.map.move(
            CameraPosition(Point(55.7558, 37.6173), 15f, 0f, 0f), // Увеличил zoom для лучшей детализации
            Animation(Animation.Type.SMOOTH, 0f),
            null
        )
    }

    // Остальные методы setupBottomSheet, toggleBottomSheet, checkDrawingStatus остаются без изменений
    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        bottomSheetBehavior.apply {
            peekHeight = 80
            isHideable = false
            state = BottomSheetBehavior.STATE_COLLAPSED

            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            btnToggleSheet.setImageResource(R.drawable.ic_arrow_down)
                        }
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            btnToggleSheet.setImageResource(R.drawable.ic_stats)
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    btnToggleSheet.alpha = 1f - slideOffset
                }
            })
        }

        btnToggleSheet.setOnClickListener {
            toggleBottomSheet()
        }
    }

    private fun toggleBottomSheet() {
        when (bottomSheetBehavior.state) {
            BottomSheetBehavior.STATE_COLLAPSED -> {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            BottomSheetBehavior.STATE_EXPANDED -> {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
            else -> bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun checkDrawingStatus() {
        val userId = auth.currentUser?.uid ?: return

        database.child("drawing_status").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    isDrawing = snapshot.getValue(Boolean::class.java) ?: false
                    updateButtonStates()
                    if (isDrawing) {
                        startLocationListener()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка проверки статуса отрисовки", error.toException())
                }
            })
    }

    private fun startLocationListener() {
        val userId = auth.currentUser?.uid ?: return

        removeLocationListener()

        userLocationsRef = database.child("user_locations").child(userId)

        userLocationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isDrawing) return

                val location = snapshot.getValue(UserLocation::class.java)
                location?.let {
                    if (isValidNewLocation(it)) {
                        processNewLocation(it)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка слушателя локаций", error.toException())
            }
        }

        userLocationsRef?.addValueEventListener(userLocationsListener as ValueEventListener)
        Log.d(TAG, "Слушатель локаций запущен для отрисовки")
    }

    private fun removeLocationListener() {
        try {
            userLocationsRef?.let { ref ->
                userLocationsListener?.let { listener ->
                    ref.removeEventListener(listener)
                    Log.d(TAG, "Слушатель локаций удален")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка удаления слушателя", e)
        } finally {
            userLocationsListener = null
            userLocationsRef = null
        }
    }

    // НОВЫЙ МЕТОД: Обработка новой локации с улучшенной фильтрацией
    private fun processNewLocation(location: UserLocation) {
        locationList.add(location)

        // Применяем улучшенную фильтрацию
        val filteredLocation = applyAdvancedFiltering(location)
        filteredLocationList.add(filteredLocation)

        val newPoint = Point(filteredLocation.lat, filteredLocation.lng)

        // Добавляем точку с учетом улучшенной логики
        if (routePoints.isEmpty()) {
            routePoints.add(newPoint)
        } else {
            val lastPoint = routePoints.last()
            val distance = calculateDistance(lastPoint, newPoint)
            val bearingChange = calculateBearingChange(routePoints, newPoint)

            // Условия добавления точки:
            // 1. Достаточное расстояние ИЛИ
            // 2. Значительное изменение направления
            if (distance >= MIN_POINT_DISTANCE || bearingChange > BEARING_CHANGE_THRESHOLD) {
                // Применяем сглаживание траектории
                val smoothedPoint = if (smoothingEnabled && routePoints.size >= 2) {
                    applyTrajectorySmoothing(newPoint)
                } else {
                    newPoint
                }

                routePoints.add(smoothedPoint)

                // Обновляем статистику и маршрут
                recalculateStatsFromAllLocations()
                updateRouteInRealTime()
            }
        }

        lastLocationTime = location.timestamp
    }

    private fun applyAdvancedFiltering(location: UserLocation): UserLocation {
        if (filteredLocationList.size < 2) {
            return location // Недостаточно данных для фильтрации
        }

        val recentLocations = filteredLocationList.takeLast(3)
        val currentPoint = Point(location.lat, location.lng)

        // 1. Фильтр скорости и ускорения
        if (!isSpeedAndAccelerationValid(location, recentLocations)) {
            // Возвращаем последнюю валидную точку
            return filteredLocationList.last()
        }

        // 2. Фильтр по точности (если есть данные о точности)
        if (location.accuracy > 0 && location.accuracy > accuracyThreshold) {
            Log.w(TAG, "Низкая точность локации: ${location.accuracy} м")
            // Можно вернуть прогнозируемую точку или последнюю валидную
        }

        // 3. Kalman фильтр для сглаживания
        val kalmanFiltered = applyKalmanFilter(currentPoint)

        return UserLocation(
            kalmanFiltered.latitude,
            kalmanFiltered.longitude,
            location.timestamp,
            maxOf(1.0f, location.accuracy), // Используем Float вместо Double
            location.speed,
            location.color
        )
    }

    // НОВЫЙ МЕТОД: Kalman фильтр для сглаживания координат
    private fun applyKalmanFilter(newPoint: Point): Point {
        kalmanBuffer.add(newPoint)

        if (kalmanBuffer.size > KALMAN_BUFFER_SIZE) {
            kalmanBuffer.removeFirst()
        }

        // Простое усреднение (можно заменить на полноценный Kalman фильтр)
        return if (kalmanBuffer.size >= 2) {
            val avgLat = kalmanBuffer.map { it.latitude }.average()
            val avgLon = kalmanBuffer.map { it.longitude }.average()
            Point(avgLat, avgLon)
        } else {
            newPoint
        }
    }

    // НОВЫЙ МЕТОД: Сглаживание траектории
    private fun applyTrajectorySmoothing(newPoint: Point): Point {
        val lastPoints = routePoints.takeLast(3)
        if (lastPoints.size < 3) return newPoint

        // Простое сглаживание - среднее между последними точками и новой
        val smoothedLat = (lastPoints[0].latitude + lastPoints[1].latitude + newPoint.latitude) / 3
        val smoothedLon = (lastPoints[0].longitude + lastPoints[1].longitude + newPoint.longitude) / 3

        return Point(smoothedLat, smoothedLon)
    }

    // НОВЫЙ МЕТОД: Расчет изменения направления
    private fun calculateBearingChange(points: List<Point>, newPoint: Point): Double {
        if (points.size < 2) return 0.0

        val prevBearing = calculateBearing(points[points.size - 2], points.last())
        val newBearing = calculateBearing(points.last(), newPoint)

        return abs(newBearing - prevBearing).coerceAtMost(360.0 - abs(newBearing - prevBearing))
    }

    // НОВЫЙ МЕТОД: Расчет азимута между двумя точками
    private fun calculateBearing(from: Point, to: Point): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360

        return bearing
    }

    private fun isSpeedAndAccelerationValid(location: UserLocation, recentLocations: List<UserLocation>): Boolean {
        if (recentLocations.isEmpty()) return true

        val lastLocation = recentLocations.last()
        val timeDiff = location.timestamp - lastLocation.timestamp
        val distance = calculateDistance(
            Point(lastLocation.lat, lastLocation.lng),
            Point(location.lat, location.lng)
        )

        // Проверка минимального расстояния
        if (distance < 0.5) return false

        // Проверка времени
        if (timeDiff < MIN_TIME_DIFF || timeDiff > MAX_TIME_DIFF) {
            return false
        }

        // Расчет скорости
        val speed = distance / (timeDiff / 1000.0)
        if (speed < MIN_VALID_SPEED_MPS || speed > MAX_VALID_SPEED_MPS) {
            Log.w(TAG, "Недопустимая скорость: ${speed * 3.6} км/ч")
            return false
        }

        // Проверка ускорения (если есть предыдущие точки)
        if (recentLocations.size >= 2) {
            val prevLocation = recentLocations[recentLocations.size - 2]
            val prevTimeDiff = lastLocation.timestamp - prevLocation.timestamp
            val prevDistance = calculateDistance(
                Point(prevLocation.lat, prevLocation.lng),
                Point(lastLocation.lat, lastLocation.lng)
            )

            if (prevTimeDiff > 0) {
                val prevSpeed = prevDistance / (prevTimeDiff / 1000.0)
                val acceleration = abs(speed - prevSpeed) / (timeDiff / 1000.0)

                if (acceleration > MAX_ACCELERATION) {
                    Log.w(TAG, "Недопустимое ускорение: $acceleration м/с²")
                    return false
                }
            }
        }

        return true
    }

    // УЛУЧШЕННЫЙ МЕТОД: Валидация новой локации
    private fun isValidNewLocation(location: UserLocation): Boolean {
        if (locationList.isEmpty()) return true

        val lastLocation = locationList.last()
        val timeDiff = location.timestamp - lastLocation.timestamp

        // Базовая проверка времени
        if (timeDiff < MIN_TIME_DIFF || timeDiff > MAX_TIME_DIFF) {
            return false
        }

        return true // Детальная проверка в processNewLocation
    }

    private fun updateButtonStates() {
        btnStartTracking.isEnabled = !isDrawing
        btnStopTracking.isEnabled = isDrawing
        btnStartTracking.alpha = if (isDrawing) 0.5f else 1.0f
        btnStopTracking.alpha = if (isDrawing) 1.0f else 0.5f
        btnStartTracking.text = if (isDrawing) "Отрисовка активна" else "Начать отрисовку"
        btnStopTracking.text = if (isDrawing) "Остановить отрисовку" else "Отрисовка остановлена"
    }

    // УЛУЧШЕННЫЙ МЕТОД: Перерасчет статистики
    private fun recalculateStatsFromAllLocations() {
        if (filteredLocationList.size < 2) {
            resetStats()
            return
        }

        totalDistance = 0.0
        totalTimeInMotion = 0L
        maxSpeed = 0.0
        var totalSpeed = 0.0
        var validSegments = 0

        val sortedLocations = filteredLocationList.sortedBy { it.timestamp }

        for (i in 1 until sortedLocations.size) {
            val prev = sortedLocations[i - 1]
            val curr = sortedLocations[i]

            val timeDiff = curr.timestamp - prev.timestamp
            val distance = calculateDistance(
                Point(prev.lat, prev.lng),
                Point(curr.lat, curr.lng)
            )

            if (timeDiff in MIN_TIME_DIFF..MAX_TIME_DIFF &&
                distance >= MIN_POINT_DISTANCE) {

                val speed = distance / (timeDiff / 1000.0)

                if (speed in MIN_VALID_SPEED_MPS..MAX_VALID_SPEED_MPS) {
                    // Дополнительная проверка ускорения
                    if (i >= 2) {
                        val prevPrev = sortedLocations[i - 2]
                        val prevTimeDiff = prev.timestamp - prevPrev.timestamp
                        val prevDistance = calculateDistance(
                            Point(prevPrev.lat, prevPrev.lng),
                            Point(prev.lat, prev.lng)
                        )
                        if (prevTimeDiff > 0) {
                            val prevSpeed = prevDistance / (prevTimeDiff / 1000.0)
                            val acceleration = abs(speed - prevSpeed) / (timeDiff / 1000.0)
                            if (acceleration > MAX_ACCELERATION) {
                                continue
                            }
                        }
                    }

                    totalDistance += distance
                    totalTimeInMotion += timeDiff
                    totalSpeed += speed
                    validSegments++

                    if (speed > maxSpeed) {
                        maxSpeed = speed
                    }
                }
            }
        }

        avgSpeed = if (validSegments > 0 && totalTimeInMotion > 0) {
            totalSpeed / validSegments
        } else {
            0.0
        }

        updateUI()
    }

    private fun resetStats() {
        totalDistance = 0.0
        totalTimeInMotion = 0L
        maxSpeed = 0.0
        avgSpeed = 0.0
        updateUI()
    }

    private fun setupButtons() {
        btnClear.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Подтверждение")
                .setMessage("Вы уверены, что хотите очистить текущий маршрут?")
                .setPositiveButton("Да") { _, _ ->
                    clearRoute()
                    clearRouteFromDatabase()
                    Toast.makeText(context, "Маршрут очищен", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        btnStartTracking.setOnClickListener {
            startDrawing()
        }

        btnStopTracking.setOnClickListener {
            stopDrawing()
        }
    }

    private fun startDrawing() {
        val userId = auth.currentUser?.uid ?: return

        database.child("drawing_status").child(userId).setValue(true)
        isDrawing = true
        updateButtonStates()
        startLocationListener()

        Toast.makeText(context, "Отрисовка маршрута начата", Toast.LENGTH_SHORT).show()
    }

    private fun stopDrawing() {
        val userId = auth.currentUser?.uid ?: return

        database.child("drawing_status").child(userId).setValue(false)
        isDrawing = false
        updateButtonStates()
        removeLocationListener()
        updateFinalMarker()

        Toast.makeText(context, "Отрисовка маршрута остановлена", Toast.LENGTH_LONG).show()
    }

    private fun updateFinalMarker() {
        if (routePoints.isNotEmpty()) {
            endMarker?.let { mapView.map.mapObjects.remove(it) }
            endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_marker),
                    IconStyle().setScale(1.2f))
                setText("Текущая позиция")
                zIndex = 20f
            }
        }
    }

    // УЛУЧШЕННЫЙ МЕТОД: Обновление маршрута в реальном времени
    private fun updateRouteInRealTime() {
        if (routePoints.size < 2) return

        try {
            if (polyline == null) {
                polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                    setStrokeColor(Color.parseColor("#1E88E5"))
                    setStrokeWidth(6f) // Уменьшил толщину для большей точности
                    setOutlineColor(Color.WHITE)
                    setOutlineWidth(1f)
                    zIndex = 10f
                }
            } else {
                try {
                    polyline?.geometry = Polyline(routePoints)
                } catch (e: Exception) {
                    Log.w(TAG, "Ошибка обновления geometry polyline, пересоздаю", e)
                    mapView.map.mapObjects.remove(polyline!!)
                    polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                        setStrokeColor(Color.parseColor("#1E88E5"))
                        setStrokeWidth(6f)
                        setOutlineColor(Color.WHITE)
                        setOutlineWidth(1f)
                        zIndex = 10f
                    }
                }
            }

            if (startMarker == null && routePoints.isNotEmpty()) {
                startMarker = mapView.map.mapObjects.addPlacemark(routePoints.first()).apply {
                    setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location),
                        IconStyle().setScale(1.0f)) // Уменьшил масштаб маркера
                    setText("Старт")
                    zIndex = 20f
                }
            }

            endMarker?.let { mapView.map.mapObjects.remove(it) }
            endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_marker),
                    IconStyle().setScale(1.0f))
                setText("Текущая позиция")
                zIndex = 20f
            }

            // Плавное перемещение камеры с учетом всей траектории
            if (isDrawing && routePoints.size > 5) {
                adjustCameraToRoute(routePoints.takeLast(10)) // Фокусируемся на последних точках
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления маршрута", e)
        }
    }

    private fun drawRoute(points: List<Point>) {
        clearRoute()
        if (points.size < 2) return

        try {
            polyline = mapView.map.mapObjects.addPolyline(Polyline(points)).apply {
                setStrokeColor(Color.parseColor("#1E88E5"))
                setStrokeWidth(6f)
                setOutlineColor(Color.WHITE)
                setOutlineWidth(1f)
                zIndex = 10f
            }

            startMarker = mapView.map.mapObjects.addPlacemark(points.first()).apply {
                setIcon(
                    ImageProvider.fromResource(requireContext(), R.drawable.ic_location),
                    IconStyle().setScale(1.0f)
                )
                setText("Старт")
                zIndex = 20f
            }

            endMarker = mapView.map.mapObjects.addPlacemark(points.last()).apply {
                setIcon(
                    ImageProvider.fromResource(requireContext(), R.drawable.ic_marker),
                    IconStyle().setScale(1.0f)
                )
                setText("Финиш")
                zIndex = 20f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка построения маршрута", e)
        }
    }

    private fun updateUI() {
        val distanceKm = totalDistance / 1000
        val timeMinutes = totalTimeInMotion / (1000.0 * 60.0)
        val timeHours = totalTimeInMotion / (1000.0 * 3600.0)

        val avgSpeedKmh = avgSpeed * 3.6
        val maxSpeedKmh = maxSpeed * 3.6
        val calories = estimateCalories(distanceKm, timeHours, avgSpeedKmh)

        tvDistance.text = "🛣️ Пройдено: ${String.format("%.3f", distanceKm.coerceAtLeast(0.0))} км"
        tvTime.text = "⏱️ Время: ${String.format("%.1f", timeMinutes.coerceAtLeast(0.0))} мин"
        tvAvgSpeed.text = "🚶 Ср. скорость: ${String.format("%.1f", avgSpeedKmh.coerceIn(0.0, 200.0))} км/ч"
        tvMaxSpeed.text = "💨 Макс. скорость: ${String.format("%.1f", maxSpeedKmh.coerceIn(0.0, 200.0))} км/ч"
        tvCalories.text = "🔥 Калории: ~${calories.toInt().coerceAtLeast(0)}"
    }

    private fun estimateCalories(distanceKm: Double, timeHours: Double, avgSpeedKmh: Double): Double {
        val met = when {
            avgSpeedKmh < 3 -> 2.0
            avgSpeedKmh < 5 -> 3.0
            avgSpeedKmh < 7 -> 5.0
            avgSpeedKmh < 10 -> 6.0
            else -> 8.0
        }
        return met * userWeight * timeHours
    }

    // Остальные методы (loadRouteForToday, calculateDistance, adjustCameraToRoute и т.д.)
    // остаются аналогичными, но используют filteredLocationList вместо locationList

    private fun loadRouteForToday() {
        val startOfDay = getStartOfToday()
        val endOfDay = getEndOfDay()
        val userId = auth.currentUser?.uid ?: return

        database.child("user_location_history")
            .child(userId)
            .orderByChild("timestamp")
            .startAt(startOfDay.toDouble())
            .endAt(endOfDay.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val locations = mutableListOf<UserLocation>()
                    for (child in snapshot.children) {
                        val location = child.getValue(UserLocation::class.java)
                        location?.let { locations.add(it) }
                    }

                    if (locations.isNotEmpty()) {
                        locations.sortBy { it.timestamp }
                        // Используем улучшенную фильтрацию для исторических данных
                        val filteredLocations = applyHistoricalDataFiltering(locations)

                        if (filteredLocations.size >= 2) {
                            val points = filteredLocations.map { Point(it.lat, it.lng) }
                            routePoints.clear()
                            routePoints.addAll(points)
                            locationList.clear()
                            locationList.addAll(locations)
                            filteredLocationList.clear()
                            filteredLocationList.addAll(filteredLocations)

                            drawRoute(points)
                            recalculateStatsFromAllLocations()
                            adjustCameraToRoute(points)
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки маршрута", error.toException())
                }
            })
    }

    // НОВЫЙ МЕТОД: Фильтрация исторических данных
    private fun applyHistoricalDataFiltering(locations: List<UserLocation>): List<UserLocation> {
        if (locations.size < 2) return locations

        val filtered = mutableListOf<UserLocation>()
        filtered.add(locations.first())

        for (i in 1 until locations.size) {
            val prev = filtered.last()
            val curr = locations[i]

            val distance = calculateDistance(
                Point(prev.lat, prev.lng),
                Point(curr.lat, curr.lng)
            )

            val timeDiff = curr.timestamp - prev.timestamp

            // Более строгая фильтрация для исторических данных
            if (distance >= MIN_POINT_DISTANCE &&
                timeDiff in MIN_TIME_DIFF..MAX_TIME_DIFF) {

                val speed = distance / (timeDiff / 1000.0)
                if (speed in MIN_VALID_SPEED_MPS..MAX_VALID_SPEED_MPS) {
                    filtered.add(curr)
                }
            }
        }

        return filtered
    }

    private fun calculateDistance(p1: Point, p2: Point): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    private fun adjustCameraToRoute(points: List<Point>) {
        if (points.size < 2) return

        val bbox = calculateBoundingBox(points)
        val center = Point(
            (bbox.southWest.latitude + bbox.northEast.latitude) / 2,
            (bbox.southWest.longitude + bbox.northEast.longitude) / 2
        )

        val latDiff = abs(bbox.northEast.latitude - bbox.southWest.latitude)
        val lonDiff = abs(bbox.northEast.longitude - bbox.southWest.longitude)
        val maxDiff = max(latDiff, lonDiff)

        val zoom = when {
            maxDiff < 0.001 -> 17f
            maxDiff < 0.002 -> 16f
            maxDiff < 0.005 -> 15f
            maxDiff < 0.01 -> 14f
            maxDiff < 0.02 -> 13f
            maxDiff < 0.05 -> 12f
            maxDiff < 0.1 -> 11f
            else -> 10f
        }.coerceIn(12f, 18f) // Увеличил минимальный zoom

        mapView.map.move(
            CameraPosition(center, zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 1f),
            null
        )
    }

    private fun calculateBoundingBox(points: List<Point>): BoundingBox {
        var minLat = points.first().latitude
        var maxLat = points.first().latitude
        var minLon = points.first().longitude
        var maxLon = points.first().longitude

        for (point in points) {
            minLat = minOf(minLat, point.latitude)
            maxLat = maxOf(maxLat, point.latitude)
            minLon = minOf(minLon, point.longitude)
            maxLon = maxOf(maxLon, point.longitude)
        }

        return BoundingBox(Point(minLat, minLon), Point(maxLat, maxLon))
    }

    private fun clearRouteFromDatabase() {
        val startOfDay = getStartOfToday()
        val endOfDay = getEndOfDay()
        val userId = auth.currentUser?.uid ?: return

        database.child("user_location_history").child(userId)
            .orderByChild("timestamp")
            .startAt(startOfDay.toDouble())
            .endAt(endOfDay.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { child -> child.ref.removeValue() }
                    clearUI()
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка очистки БД", error.toException())
                }
            })
    }

    private fun clearRoute() {
        polyline?.let { mapView.map.mapObjects.remove(it) }
        startMarker?.let { mapView.map.mapObjects.remove(it) }
        endMarker?.let { mapView.map.mapObjects.remove(it) }
        polyline = null
        startMarker = null
        endMarker = null
    }

    private fun clearUI() {
        totalDistance = 0.0
        totalTimeInMotion = 0L
        maxSpeed = 0.0
        avgSpeed = 0.0
        routePoints.clear()
        locationList.clear()
        filteredLocationList.clear()
        kalmanBuffer.clear()

        tvDistance.text = "🛣️ Пройдено: 0 км"
        tvTime.text = "⏱️ Время: 0 мин"
        tvAvgSpeed.text = "🚶 Ср. скорость: 0 км/ч"
        tvMaxSpeed.text = "💨 Макс. скорость: 0 км/ч"
        tvCalories.text = "🔥 Калории: ~0"
    }

    private fun getStartOfToday(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun getEndOfDay(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 23)
        calendar.set(java.util.Calendar.MINUTE, 59)
        calendar.set(java.util.Calendar.SECOND, 59)
        calendar.set(java.util.Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        MapKitFactory.getInstance().onStart()

        if (isDrawing) {
            startLocationListener()
        }
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removeLocationListener()
        clearRoute()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Fragment уничтожен")
    }
}