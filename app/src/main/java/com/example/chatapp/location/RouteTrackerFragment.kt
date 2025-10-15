package com.example.chatapp.location

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*

class RouteTrackerFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvCalories: TextView
    private lateinit var btnClear: Button
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

    private var lastLocationTime = 0L
    private var routePoints = mutableListOf<Point>()
    private var locationList = mutableListOf<UserLocation>()
    private var filteredLocationList = mutableListOf<UserLocation>()

    private var userLocationsListener: ValueEventListener? = null
    private var userLocationsRef: DatabaseReference? = null
    private var trackingStatusListener: ValueEventListener? = null

    private var dailyCleanupHandler = Handler(Looper.getMainLooper())
    private var dailyCleanupRunnable: Runnable? = null

    // Улучшенные буферы для сглаживания
    private val kalmanBuffer = ArrayList<Point>()
    private val smoothingBuffer = ArrayList<Point>()
    private val trajectoryBuffer = ArrayList<TrajectoryPoint>()

    private var accuracyThreshold = 15.0f
    private var smoothingEnabled = true
    private var adaptiveSmoothingEnabled = true

    private var isFirstLaunch = true
    private var isFragmentDestroyed = false

    // Класс для хранения точек траектории с временными метками
    private data class TrajectoryPoint(
        val point: Point,
        val timestamp: Long,
        val speed: Double = 0.0,
        val accuracy: Float = 0.0f
    )

    companion object {
        private const val TAG = "RouteTrackerFragment"
        private const val MIN_POINT_DISTANCE = 2.0
        private const val MAX_POINT_DISTANCE = 100.0
        private const val MAX_VALID_SPEED_MPS = 25.0f
        private const val MIN_VALID_SPEED_MPS = 0.1f
        private const val MAX_TIME_DIFF = 30000L
        private const val MIN_TIME_DIFF = 1000L
        private const val MAX_ACCELERATION = 10.0f
        private const val BEARING_CHANGE_THRESHOLD = 45.0

        // Настройки сглаживания
        private const val KALMAN_BUFFER_SIZE = 5
        private const val SMOOTHING_BUFFER_SIZE = 7
        private const val TRAJECTORY_BUFFER_SIZE = 10
        private const val ADAPTIVE_SMOOTHING_THRESHOLD = 25.0 // км/ч
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

        Log.d(TAG, "Fragment создан, начинаем инициализацию")

        loadRouteForToday()
        scheduleDailyCleanup()
        checkAndStartTracking()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        dailyCleanupRunnable?.let { dailyCleanupHandler.removeCallbacks(it) }
        removeLocationListener()
        removeTrackingStatusListener()
        clearRoute()
        Log.d(TAG, "Fragment view destroyed")
    }

    override fun onDestroy() {
        super.onDestroy()
        isFragmentDestroyed = true
        dailyCleanupRunnable?.let { dailyCleanupHandler.removeCallbacks(it) }
        Log.d(TAG, "Fragment destroyed")
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        MapKitFactory.getInstance().onStart()

        Log.d(TAG, "Fragment started, запускаем слушатели")
        startLocationListener()
        setupTrackingStatusListener()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()

        Log.d(TAG, "Fragment stopped, останавливаем слушатели")
        removeLocationListener()
        removeTrackingStatusListener()
    }

    private fun initViews(view: View) {
        tvDistance = view.findViewById(R.id.Distance)
        tvTime = view.findViewById(R.id.tvTime)
        tvAvgSpeed = view.findViewById(R.id.AvgSpeed)
        tvMaxSpeed = view.findViewById(R.id.MaxSpeed)
        tvCalories = view.findViewById(R.id.Calories)
        btnClear = view.findViewById(R.id.btnClear)
        btnToggleSheet = view.findViewById(R.id.btnToggleSheet)
        bottomSheet = view.findViewById(R.id.bottomSheet)

        view.findViewById<Button>(R.id.btnStartTracking)?.visibility = View.GONE
        view.findViewById<Button>(R.id.btnStopTracking)?.visibility = View.GONE
    }

    private fun setupMap() {
        val context = context ?: return
        mapView = MapView(context)
        val mapContainer = view?.findViewById<ViewGroup>(R.id.mapContainer)
        mapContainer?.removeAllViews()
        mapContainer?.addView(mapView)

        mapView.map.move(
            CameraPosition(Point(55.7558, 37.6173), 12f, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0f),
            null
        )

        Log.d(TAG, "Карта инициализирована")
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        bottomSheetBehavior.apply {
            peekHeight = 160
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

    private fun checkAndStartTracking() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "Пользователь не авторизован")
            showSafeToast("Ошибка: пользователь не авторизован", Toast.LENGTH_LONG)
            return
        }

        Log.d(TAG, "Проверяем статус трекинга для пользователя: $userId")

        database.child("tracking_status").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) return

                    val isTracking = snapshot.getValue(Boolean::class.java) ?: false
                    Log.d(TAG, "Статус трекинга из БД: $isTracking")

                    if (!isTracking) {
                        Log.d(TAG, "Трекинг не активен, запускаем автоматически")
                        startAutomaticTracking()
                    } else {
                        Log.d(TAG, "Трекинг уже активен, начинаем слушать обновления")
                        startLocationListener()
                        if (isFirstLaunch) {
                            showSafeToast("Отслеживание активно")
                            isFirstLaunch = false
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isFragmentDestroyed || !isAdded) return
                    Log.e(TAG, "Ошибка проверки статуса трекинга: ${error.message}")
                    startAutomaticTracking()
                }
            })
    }

    private fun startAutomaticTracking() {
        Log.d(TAG, "Запускаем автоматическое отслеживание")

        val context = context ?: return
        LocationServiceManager.startLocationService(context)
        startLocationListener()
        setTrackingStatus(true)

        if (isFirstLaunch) {
            showSafeToast("Автоматическое отслеживание запущено")
            isFirstLaunch = false
        }
    }

    private fun startLocationListener() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "Не удалось запустить слушатель: пользователь не авторизован")
            return
        }

        removeLocationListener()

        userLocationsRef = database.child("user_locations").child(userId)

        userLocationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFragmentDestroyed || !isAdded) return

                Log.d(TAG, "Получены новые данные локации из Firebase")

                val location = snapshot.getValue(UserLocation::class.java)
                if (location != null) {
                    Log.d(TAG, "Новая локация: ${location.lat}, ${location.lng}, время: ${location.timestamp}")

                    if (isValidNewLocation(location)) {
                        processNewLocation(location)
                    } else {
                        Log.d(TAG, "Локация не прошла валидацию")
                    }
                } else {
                    Log.d(TAG, "Локация null в snapshot")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (isFragmentDestroyed || !isAdded) return
                Log.e(TAG, "Ошибка слушателя локаций: ${error.message}")
            }
        }

        userLocationsRef?.addValueEventListener(userLocationsListener as ValueEventListener)
        Log.d(TAG, "Слушатель локаций запущен для пути: user_locations/$userId")
    }

    private fun setupTrackingStatusListener() {
        val userId = auth.currentUser?.uid ?: return

        trackingStatusListener = database.child("tracking_status").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) return

                    val isTracking = snapshot.getValue(Boolean::class.java) ?: false
                    Log.d(TAG, "Статус трекинга изменен: $isTracking")

                    if (!isTracking) {
                        Log.d(TAG, "Трекинг остановлен извне, перезапускаем")
                        startAutomaticTracking()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isFragmentDestroyed || !isAdded) return
                    Log.e(TAG, "Ошибка слушателя статуса трекинга: ${error.message}")
                }
            })
    }

    private fun processNewLocation(location: UserLocation) {
        Log.d(TAG, "Обрабатываем новую локацию: ${location.lat}, ${location.lng}")

        locationList.add(location)

        val filteredLocation = applyAdvancedFiltering(location)
        filteredLocationList.add(filteredLocation)

        val newPoint = Point(filteredLocation.lat, filteredLocation.lng)

        if (routePoints.isEmpty()) {
            Log.d(TAG, "Первая точка маршрута: ${newPoint.latitude}, ${newPoint.longitude}")
            routePoints.add(newPoint)
            updateRouteInRealTime()
        } else {
            val lastPoint = routePoints.last()
            val distance = calculateDistance(lastPoint, newPoint)
            val bearingChange = calculateBearingChange(routePoints, newPoint)

            Log.d(TAG, "Расстояние до предыдущей точки: $distance м, изменение направления: $bearingChange°")

            if (distance >= MIN_POINT_DISTANCE || bearingChange > BEARING_CHANGE_THRESHOLD) {
                val smoothedPoint = if (smoothingEnabled) {
                    applyAdvancedTrajectorySmoothing(newPoint, location.timestamp)
                } else {
                    newPoint
                }

                routePoints.add(smoothedPoint)
                Log.d(TAG, "Добавлена новая точка в маршрут. Всего точек: ${routePoints.size}")

                recalculateStatsFromAllLocations()
                updateRouteInRealTime()
            } else {
                Log.d(TAG, "Точка слишком близко или нет значительного поворота, пропускаем")
            }
        }

        lastLocationTime = location.timestamp
    }

    /**
     * Улучшенное сглаживание траектории с адаптивными алгоритмами
     */
    private fun applyAdvancedTrajectorySmoothing(newPoint: Point, timestamp: Long): Point {
        // Добавляем точку в буфер траектории
        val trajectoryPoint = TrajectoryPoint(newPoint, timestamp)
        trajectoryBuffer.add(trajectoryPoint)

        if (trajectoryBuffer.size > TRAJECTORY_BUFFER_SIZE) {
            trajectoryBuffer.removeAt(0)
        }

        // Определяем текущую скорость для адаптивного сглаживания
        val currentSpeedKmh = calculateCurrentSpeedKmh()

        return when {
            // При высокой скорости используем более агрессивное сглаживание
            currentSpeedKmh > ADAPTIVE_SMOOTHING_THRESHOLD -> {
                applyHighSpeedSmoothing(newPoint)
            }
            // При низкой скорости используем мягкое сглаживание
            else -> {
                applyLowSpeedSmoothing(newPoint)
            }
        }
    }

    /**
     * Сглаживание для высокой скорости (автомобиль, велосипед)
     */
    private fun applyHighSpeedSmoothing(newPoint: Point): Point {
        if (trajectoryBuffer.size < 3) return newPoint

        // Используем взвешенное скользящее среднее с большим окном
        val windowSize = minOf(5, trajectoryBuffer.size)
        val recentPoints = trajectoryBuffer.takeLast(windowSize).map { it.point }

        // Веса: последние точки имеют больший вес
        val weights = List(windowSize) { index ->
            (index + 1).toDouble() / windowSize
        }

        val totalWeight = weights.sum()
        val weightedLat = recentPoints.mapIndexed { index, point ->
            point.latitude * weights[index]
        }.sum() / totalWeight

        val weightedLon = recentPoints.mapIndexed { index, point ->
            point.longitude * weights[index]
        }.sum() / totalWeight

        Log.d(TAG, "Применено высокоскоростное сглаживание. Окно: $windowSize")
        return Point(weightedLat, weightedLon)
    }

    /**
     * Сглаживание для низкой скорости (ходьба)
     */
    private fun applyLowSpeedSmoothing(newPoint: Point): Point {
        if (trajectoryBuffer.size < 2) return newPoint

        // Используем простое скользящее среднее с маленьким окном
        val windowSize = minOf(3, trajectoryBuffer.size)
        val recentPoints = trajectoryBuffer.takeLast(windowSize).map { it.point }

        val avgLat = recentPoints.map { it.latitude }.average()
        val avgLon = recentPoints.map { it.longitude }.average()

        Log.d(TAG, "Применено низкоскоростное сглаживание. Окно: $windowSize")
        return Point(avgLat, avgLon)
    }

    /**
     * Расчет текущей скорости в км/ч на основе последних точек
     */
    private fun calculateCurrentSpeedKmh(): Double {
        if (trajectoryBuffer.size < 2) return 0.0

        val recentPoints = trajectoryBuffer.takeLast(2)
        val point1 = recentPoints[0]
        val point2 = recentPoints[1]

        val distance = calculateDistance(point1.point, point2.point)
        val timeDiff = (point2.timestamp - point1.timestamp) / 1000.0 // в секундах

        if (timeDiff <= 0) return 0.0

        val speedMps = distance / timeDiff
        return speedMps * 3.6 // конвертация в км/ч
    }

    /**
     * Улучшенный фильтр Калмана с адаптивным шумом
     */
    private fun applyEnhancedKalmanFilter(newPoint: Point): Point {
        kalmanBuffer.add(newPoint)

        if (kalmanBuffer.size > KALMAN_BUFFER_SIZE) {
            kalmanBuffer.removeAt(0)
        }

        return when (kalmanBuffer.size) {
            0 -> newPoint
            1 -> newPoint
            else -> {
                // Адаптивный фильтр Калмана с учетом скорости
                val currentSpeed = calculateCurrentSpeedKmh()
                val processNoise = when {
                    currentSpeed > 20.0 -> 0.0001 // Меньше шума при высокой скорости
                    currentSpeed > 5.0 -> 0.0005
                    else -> 0.001 // Больше шума при низкой скорости
                }

                applyAdaptiveKalman(newPoint, processNoise)
            }
        }
    }

    /**
     * Адаптивная реализация фильтра Калмана
     */
    private fun applyAdaptiveKalman(newPoint: Point, processNoise: Double): Point {
        if (kalmanBuffer.size < 2) return newPoint

        val lastPoint = kalmanBuffer.last()

        // Простая реализация предсказания
        val predictedLat = lastPoint.latitude
        val predictedLon = lastPoint.longitude

        // Ковариация процесса
        val pLat = processNoise
        val pLon = processNoise

        // Ковариация измерения (зависит от точности GPS)
        val rLat = 0.0001
        val rLon = 0.0001

        // Коэффициент Калмана
        val kLat = pLat / (pLat + rLat)
        val kLon = pLon / (pLon + rLon)

        // Коррекция
        val correctedLat = predictedLat + kLat * (newPoint.latitude - predictedLat)
        val correctedLon = predictedLon + kLon * (newPoint.longitude - predictedLon)

        return Point(correctedLat, correctedLon)
    }

    private fun applyAdvancedFiltering(location: UserLocation): UserLocation {
        if (filteredLocationList.size < 2) {
            return location
        }

        val recentLocations = filteredLocationList.takeLast(3)
        val currentPoint = Point(location.lat, location.lng)

        if (!isSpeedAndAccelerationValid(location, recentLocations)) {
            Log.w(TAG, "Локация не прошла проверку скорости/ускорения, используем предыдущую")
            return filteredLocationList.last()
        }

        if (location.accuracy > 0 && location.accuracy > accuracyThreshold) {
            Log.w(TAG, "Низкая точность локации: ${location.accuracy} м")
        }

        val kalmanFiltered = applyEnhancedKalmanFilter(currentPoint)

        return UserLocation(
            kalmanFiltered.latitude,
            kalmanFiltered.longitude,
            location.timestamp,
            maxOf(1.0f, location.accuracy),
            location.speed,
            location.color
        )
    }

    private fun calculateBearingChange(points: List<Point>, newPoint: Point): Double {
        if (points.size < 2) return 0.0

        val prevBearing = calculateBearing(points[points.size - 2], points.last())
        val newBearing = calculateBearing(points.last(), newPoint)

        return abs(newBearing - prevBearing).coerceAtMost(360.0 - abs(newBearing - prevBearing))
    }

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

        if (distance < 0.5) return false

        if (timeDiff < MIN_TIME_DIFF || timeDiff > MAX_TIME_DIFF) {
            Log.w(TAG, "Недопустимая разница во времени: $timeDiff мс")
            return false
        }

        val speed = distance / (timeDiff / 1000.0)
        if (speed < MIN_VALID_SPEED_MPS || speed > MAX_VALID_SPEED_MPS) {
            Log.w(TAG, "Недопустимая скорость: ${String.format("%.2f", speed * 3.6)} км/ч")
            return false
        }

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
                    Log.w(TAG, "Недопустимое ускорение: ${String.format("%.2f", acceleration)} м/с²")
                    return false
                }
            }
        }

        return true
    }

    private fun isValidNewLocation(location: UserLocation): Boolean {
        if (locationList.isEmpty()) return true

        val lastLocation = locationList.last()
        val timeDiff = location.timestamp - lastLocation.timestamp

        return true
    }

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
            val context = context ?: return@setOnClickListener
            AlertDialog.Builder(context)
                .setTitle("Подтверждение")
                .setMessage("Вы уверены, что хотите очистить сегодняшний маршрут?")
                .setPositiveButton("Да") { _, _ ->
                    clearTodayRoute()
                    showSafeToast("Маршрут очищен")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun clearTodayRoute() {
        clearRouteFromDatabase()
        clearUI()
    }

    private fun updateRouteInRealTime() {
        if (routePoints.size < 2) {
            Log.d(TAG, "Недостаточно точек для отрисовки маршрута: ${routePoints.size}")
            return
        }

        try {
            Log.d(TAG, "Обновляем маршрут на карте. Всего точек: ${routePoints.size}")

            if (polyline == null) {
                Log.d(TAG, "Создаем новую полилинию")
                polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                    setStrokeColor(Color.parseColor("#1E88E5"))
                    setStrokeWidth(6f)
                    setOutlineColor(Color.WHITE)
                    setOutlineWidth(1f)
                    zIndex = 10f
                }
                Log.d(TAG, "Полилиния создана")
            } else {
                try {
                    Log.d(TAG, "Обновляем геометрию существующей полилинии")
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
                Log.d(TAG, "Добавляем стартовый маркер")
                val context = context ?: return
                startMarker = mapView.map.mapObjects.addPlacemark(routePoints.first()).apply {
                    setIcon(ImageProvider.fromResource(context, R.drawable.ic_location),
                        IconStyle().setScale(1.0f))
                    setText("Старт")
                    zIndex = 20f
                }
            }

            endMarker?.let {
                mapView.map.mapObjects.remove(it)
                Log.d(TAG, "Удален старый конечный маркер")
            }
            val context = context ?: return
            endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                setIcon(ImageProvider.fromResource(context, R.drawable.ic_marker),
                    IconStyle().setScale(1.0f))
                setText("Текущая позиция")
                zIndex = 20f
            }
            Log.d(TAG, "Добавлен новый конечный маркер")

            if (routePoints.size > 5) {
                adjustCameraToRoute(routePoints.takeLast(10))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления маршрута", e)
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

        Log.d(TAG, "Обновлена статистика: ${String.format("%.3f", distanceKm)} км, ${String.format("%.1f", timeMinutes)} мин")
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

    private fun scheduleDailyCleanup() {
        dailyCleanupRunnable = Runnable {
            if (isMidnight()) {
                Log.d(TAG, "Обнаружена полночь, очищаем маршрут")
                clearTodayRoute()
                showSafeToast("Автоматическая очистка дневного маршрута", Toast.LENGTH_LONG)
            }

            dailyCleanupHandler.postDelayed(dailyCleanupRunnable!!, 60000)
        }

        dailyCleanupHandler.post(dailyCleanupRunnable!!)
        Log.d(TAG, "Запланирована ежедневная очистка")
    }

    private fun isMidnight(): Boolean {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY) == 0 &&
                calendar.get(Calendar.MINUTE) == 0
    }

    private fun loadRouteForToday() {
        val startOfDay = getStartOfToday()
        val endOfDay = getEndOfDay()
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "Не удалось загрузить маршрут: пользователь не авторизован")
            return
        }

        Log.d(TAG, "Загружаем маршрут за сегодня. Временной диапазон: $startOfDay - $endOfDay")

        database.child("user_location_history")
            .child(userId)
            .orderByChild("timestamp")
            .startAt(startOfDay.toDouble())
            .endAt(endOfDay.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) {
                        Log.d(TAG, "Фрагмент не прикреплен, пропускаем обновление UI")
                        return
                    }

                    Log.d(TAG, "Получены данные истории маршрута. Детей: ${snapshot.childrenCount}")

                    val locations = mutableListOf<UserLocation>()
                    for (child in snapshot.children) {
                        val location = child.getValue(UserLocation::class.java)
                        location?.let {
                            locations.add(it)
                        }
                    }

                    if (locations.isNotEmpty()) {
                        Log.d(TAG, "Загружено ${locations.size} точек маршрута")
                        locations.sortBy { it.timestamp }

                        val points = locations.map { Point(it.lat, it.lng) }
                        routePoints.clear()
                        routePoints.addAll(points)
                        locationList.clear()
                        locationList.addAll(locations)
                        filteredLocationList.clear()
                        filteredLocationList.addAll(locations)

                        drawRoute(points)
                        recalculateStatsFromAllLocations()
                        adjustCameraToRoute(points)

                        showSafeToast("Загружен маршрут (${points.size} точек)")
                    } else {
                        Log.d(TAG, "Нет данных маршрута за сегодня")
                        showSafeToast("Нет данных маршрута за сегодня")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isFragmentDestroyed || !isAdded) {
                        Log.d(TAG, "Фрагмент не прикреплен, пропускаем обработку ошибки")
                        return
                    }

                    Log.e(TAG, "Ошибка загрузки маршрута: ${error.message}")
                    showSafeToast("Ошибка загрузки маршрута")
                }
            })
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
        }.coerceIn(12f, 18f)

        mapView.map.move(
            CameraPosition(center, zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 1f),
            null
        )

        Log.d(TAG, "Камера настроена на маршрут. Центр: $center, zoom: $zoom")
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

        Log.d(TAG, "Очищаем маршрут из БД за сегодня")

        database.child("user_location_history").child(userId)
            .orderByChild("timestamp")
            .startAt(startOfDay.toDouble())
            .endAt(endOfDay.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val childrenCount = snapshot.childrenCount
                    snapshot.children.forEach { child -> child.ref.removeValue() }
                    clearUI()
                    Log.d(TAG, "Удалено $childrenCount точек маршрута из БД")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка очистки БД: ${error.message}")
                }
            })
    }

    private fun drawRoute(points: List<Point>) {
        clearRoute()
        if (points.size < 2) {
            Log.d(TAG, "Недостаточно точек для отрисовки маршрута: ${points.size}")
            return
        }

        try {
            Log.d(TAG, "Отрисовываем маршрут из ${points.size} точек")

            // Показываем первую и последнюю точки для отладки
            Log.d(TAG, "Первая точка: ${points.first().latitude}, ${points.first().longitude}")
            Log.d(TAG, "Последняя точка: ${points.last().latitude}, ${points.last().longitude}")

            polyline = mapView.map.mapObjects.addPolyline(Polyline(points)).apply {
                setStrokeColor(Color.parseColor("#1E88E5"))
                setStrokeWidth(6f)
                setOutlineColor(Color.WHITE)
                setOutlineWidth(1f)
                zIndex = 10f
            }

            val context = context ?: return
            startMarker = mapView.map.mapObjects.addPlacemark(points.first()).apply {
                setIcon(
                    ImageProvider.fromResource(context, R.drawable.ic_location),
                    IconStyle().setScale(1.0f)
                )
                setText("Старт")
                zIndex = 20f
            }

            endMarker = mapView.map.mapObjects.addPlacemark(points.last()).apply {
                setIcon(
                    ImageProvider.fromResource(context, R.drawable.ic_marker),
                    IconStyle().setScale(1.0f)
                )
                setText("Финиш")
                zIndex = 20f
            }

            Log.d(TAG, "Маршрут успешно отрисован на карте")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка построения маршрута", e)
        }
    }

    private fun clearRoute() {
        polyline?.let {
            mapView.map.mapObjects.remove(it)
            Log.d(TAG, "Удалена полилиния")
        }
        startMarker?.let {
            mapView.map.mapObjects.remove(it)
            Log.d(TAG, "Удален стартовый маркер")
        }
        endMarker?.let {
            mapView.map.mapObjects.remove(it)
            Log.d(TAG, "Удален конечный маркер")
        }
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
        smoothingBuffer.clear()
        trajectoryBuffer.clear()

        tvDistance.text = "🛣️ Пройдено: 0 км"
        tvTime.text = "⏱️ Время: 0 мин"
        tvAvgSpeed.text = "🚶 Ср. скорость: 0 км/ч"
        tvMaxSpeed.text = "💨 Макс. скорость: 0 км/ч"
        tvCalories.text = "🔥 Калории: ~0"

        Log.d(TAG, "UI очищен")
    }

    private fun saveRouteToHistory() {
        val userId = auth.currentUser?.uid ?: return

        filteredLocationList.forEach { location ->
            val key = database.child("user_location_history").child(userId).push().key
            key?.let {
                database.child("user_location_history").child(userId).child(it).setValue(location)
            }
        }
    }

    private fun getStartOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun getEndOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
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
            Log.w(TAG, "Ошибка удаления слушателя локаций", e)
        } finally {
            userLocationsListener = null
            userLocationsRef = null
        }
    }

    private fun removeTrackingStatusListener() {
        try {
            trackingStatusListener?.let { listener ->
                database.removeEventListener(listener)
                Log.d(TAG, "Слушатель статуса трекинга удален")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка удаления слушателя статуса трекинга", e)
        } finally {
            trackingStatusListener = null
        }
    }

    private fun setTrackingStatus(isTracking: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        database.child("tracking_status").child(userId).setValue(isTracking)
        Log.d(TAG, "Статус трекинга установлен: $isTracking")
    }

    /**
     * Безопасный показ Toast с проверкой контекста и жизненного цикла
     */
    private fun showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            if (isFragmentDestroyed || !isAdded || context == null) {
                Log.d(TAG, "Cannot show toast - fragment detached: $message")
                return
            }

            activity?.runOnUiThread {
                try {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), message, duration).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing toast on UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showSafeToast", e)
        }
    }
}