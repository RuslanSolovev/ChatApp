package com.example.chatapp.location

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.chatapp.R
import com.example.chatapp.models.UserLocation
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration
import kotlin.time.DurationUnit

class RouteTrackerFragment : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvCalories: TextView
    private lateinit var tvRouteName: TextView
    private lateinit var tvRouteDate: TextView
    private lateinit var btnClear: Button
    private lateinit var btnToggleSheet: ImageButton
    private lateinit var btnCloseSheet: ImageButton
    private lateinit var bottomSheet: LinearLayout
    private lateinit var fabCenterMap: FloatingActionButton
    private lateinit var fabExport: FloatingActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var polyline: PolylineMapObject? = null
    private var startMarker: PlacemarkMapObject? = null
    private var endMarker: PlacemarkMapObject? = null

    private var totalDistance = 0.0 // метры
    private var totalTime = 0L // секунды
    private var maxSpeed = 0.0 // м/с
    private var avgSpeed = 0.0 // м/с
    private var totalCalories = 0.0
    private val userWeight = 70.0 // кг

    private val routePoints = mutableListOf<Point>()
    private val locationList = mutableListOf<UserLocation>()
    private val processedTimestamps = mutableSetOf<Long>()

    private var locationListener: ValueEventListener? = null
    private var historyListener: ValueEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var statsUpdateRunnable: Runnable? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isFirstLoad = true
    private var isRouteLoaded = false
    private var autoFollow = true

    companion object {
        private const val TAG = "RouteTrackerFragment"
        private const val STATS_UPDATE_INTERVAL = 2000L
        private const val MAX_REALISTIC_SPEED = 27.78 // 100 км/ч → м/с
        private const val MIN_DISTANCE_BETWEEN_POINTS = 2.0 // Уменьшено для большей точности
        private const val SMOOTHING_FACTOR = 0.7 // Фактор сглаживания для полилинии
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
        setupLeftSheet()
        setupButtons()
        setupFloatingActions()
        setupStatsUpdater()

        // Всегда запускаем сервис локации
        startLocationService()

        // Настраиваем слушатели Firebase
        setupFirebaseListeners()

        handler.postDelayed({
            if (isAdded) {
                showLoading(true, "Загрузка истории маршрутов...")
                loadFullRouteHistory()
            }
        }, 500)
    }

    private fun initViews(view: View) {
        tvDistance = view.findViewById(R.id.Distance)
        tvTime = view.findViewById(R.id.tvTime)
        tvAvgSpeed = view.findViewById(R.id.AvgSpeed)
        tvMaxSpeed = view.findViewById(R.id.MaxSpeed)
        tvCalories = view.findViewById(R.id.Calories)
        tvRouteName = view.findViewById(R.id.tvRouteName)
        tvRouteDate = view.findViewById(R.id.tvRouteDate)
        btnClear = view.findViewById(R.id.btnClear)
        btnToggleSheet = view.findViewById(R.id.btnToggleSheet)
        btnCloseSheet = view.findViewById(R.id.btnCloseSheet)
        bottomSheet = view.findViewById(R.id.bottomSheet)
        fabCenterMap = view.findViewById(R.id.fabCenterMap)
        fabExport = view.findViewById(R.id.fabExport)

        progressBar = view.findViewById(R.id.progressBar)
        tvLoading = view.findViewById(R.id.tvLoading)

        val mapContainer = view.findViewById<ViewGroup>(R.id.mapContainer)
        mapView = MapView(requireContext())
        mapContainer.addView(mapView)
    }

    private fun setupMap() {
        // Начальная позиция (Москва)
        mapView.map.move(
            CameraPosition(Point(55.7558, 37.6173), 12f, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )

        // Настройка жестов
        mapView.map.isRotateGesturesEnabled = true
        mapView.map.isScrollGesturesEnabled = true
        mapView.map.isZoomGesturesEnabled = true
        mapView.map.isTiltGesturesEnabled = true

        // Слушатель движения камеры для отключения автофоллоу
        mapView.map.addCameraListener(object : com.yandex.mapkit.map.CameraListener {
            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (finished && cameraUpdateReason != CameraUpdateReason.APPLICATION) {
                    autoFollow = false
                    fabCenterMap.setImageResource(R.drawable.ic_location_off)
                }
            }
        })
    }

    private fun setupLeftSheet() {
        bottomSheet.post {
            val sheetWidth = bottomSheet.width
            bottomSheet.translationX = -sheetWidth.toFloat()

            btnToggleSheet.setOnClickListener {
                toggleLeftSheetWithAnimation(sheetWidth)
            }

            btnCloseSheet.setOnClickListener {
                hideLeftSheetWithAnimation(sheetWidth)
            }
        }
    }

    private fun toggleLeftSheetWithAnimation(sheetWidth: Int) {
        if (bottomSheet.translationX < 0) {
            bottomSheet.animate()
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            btnToggleSheet.setImageResource(R.drawable.ic_arrow_back)
        } else {
            hideLeftSheetWithAnimation(sheetWidth)
        }
    }

    private fun hideLeftSheetWithAnimation(sheetWidth: Int) {
        bottomSheet.animate()
            .translationX(-sheetWidth.toFloat())
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        btnToggleSheet.setImageResource(R.drawable.ic_stats)
    }

    private fun setupButtons() {
        btnClear.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    private fun setupFloatingActions() {
        fabCenterMap.setOnClickListener {
            centerMapOnRoute()
            autoFollow = true
            fabCenterMap.setImageResource(R.drawable.ic_location_on)
        }

        fabExport.setOnClickListener {
            showExportOptions()
        }
    }

    private fun showLoading(show: Boolean, message: String = "") {
        if (view == null || !isAdded || activity == null) {
            Log.w(TAG, "Фрагмент не готов для обновления UI")
            return
        }

        handler.post {
            if (!isAdded || activity == null || isDetached) return@post

            val loadingOverlay = view?.findViewById<RelativeLayout>(R.id.loadingOverlay)
            loadingOverlay?.let {
                it.visibility = if (show) View.VISIBLE else View.GONE
                tvLoading.text = message
                progressBar.isVisible = show
            }
        }
    }

    private fun centerMapOnRoute() {
        if (routePoints.isEmpty()) {
            Toast.makeText(context, "Нет маршрута для центрирования", Toast.LENGTH_SHORT).show()
            return
        }

        val bbox = calculateBoundingBox(routePoints)

        mapView.map.move(
            CameraPosition(
                Point(
                    (bbox.southWest.latitude + bbox.northEast.latitude) / 2,
                    (bbox.southWest.longitude + bbox.northEast.longitude) / 2
                ),
                mapView.map.cameraPosition.zoom,
                0f,
                0f
            ),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )
    }

    private fun showExportOptions() {
        if (locationList.size < 2) {
            Toast.makeText(context, "Недостаточно данных для экспорта", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "Экспорт в GPX",
            "Экспорт в KML",
            "Поделиться маршрутом"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Экспорт маршрута")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToGPX()
                    1 -> exportToKML()
                    2 -> shareRoute()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun exportToGPX() {
        Toast.makeText(context, "Экспорт в GPX (в разработке)", Toast.LENGTH_SHORT).show()
    }

    private fun exportToKML() {
        Toast.makeText(context, "Экспорт в KML (в разработке)", Toast.LENGTH_SHORT).show()
    }

    private fun shareRoute() {
        if (locationList.isEmpty()) return

        val shareText = buildString {
            append("Мой маршрут:\n")
            append("Дистанция: ${String.format("%.2f", totalDistance / 1000)} км\n")
            append("Время: ${formatTime(totalTime)}\n")
            append("Средняя скорость: ${String.format("%.1f", avgSpeed * 3.6)} км/ч\n")
            append("Максимальная скорость: ${String.format("%.1f", maxSpeed * 3.6)} км/ч\n")
            append("Сожжено калорий: ${totalCalories.toInt()}")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Мой маршрут")
        }

        startActivity(Intent.createChooser(intent, "Поделиться маршрутом"))
    }

    private fun showClearConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить данные трекера")
            .setMessage("Вы действительно хотите удалить все данные трекера? Это действие нельзя отменить.")
            .setPositiveButton("Удалить все") { _, _ ->
                clearAllTrackerData()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearAllTrackerData() {
        val userId = auth.currentUser?.uid ?: return

        showLoading(true, "Удаление данных...")

        scope.launch(Dispatchers.IO) {
            try {
                // Удаляем историю маршрутов
                database.child("route_history").child(userId).removeValue().await()

                // Удаляем последние локации
                database.child("last_locations").child(userId).removeValue().await()

                withContext(Dispatchers.Main) {
                    resetState()
                    showLoading(false, "")
                    Toast.makeText(context, "Все данные трекера удалены", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false, "")
                    Toast.makeText(context, "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetState() {
        processedTimestamps.clear()
        locationList.clear()
        routePoints.clear()

        totalDistance = 0.0
        totalTime = 0L
        maxSpeed = 0.0
        avgSpeed = 0.0
        totalCalories = 0.0

        isFirstLoad = true
        isRouteLoaded = false

        requireActivity().runOnUiThread {
            updateUI()
            clearMapObjects()
            updateRouteInfo("", "")
        }
    }

    private fun setupStatsUpdater() {
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                if (isAdded && locationList.size >= 2) {
                    calculateStats()
                    updateUI()
                }
                handler.postDelayed(this, STATS_UPDATE_INTERVAL)
            }
        }
        statsUpdateRunnable?.let { handler.post(it) }
    }

    private fun startLocationService() {
        try {
            // Всегда запускаем сервис
            LocationUpdateService.startService(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сервиса: ${e.message}")
        }
    }

    private fun setupFirebaseListeners() {
        val userId = auth.currentUser?.uid ?: run {
            Toast.makeText(context, "Требуется авторизация", Toast.LENGTH_SHORT).show()
            return
        }

        // Всегда загружаем историю и начинаем слушать текущие локации
        loadFullRouteHistory()
        startLocationListener()
    }

    private fun loadFullRouteHistory() {
        val userId = auth.currentUser?.uid ?: return

        Log.d(TAG, "Загрузка полной истории маршрутов...")
        showLoading(true, "Загрузка истории...")

        historyListener?.let { database.removeEventListener(it) }

        historyListener = database.child("route_history").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    Log.d(TAG, "Получена полная история: ${snapshot.childrenCount} записей")
                    processRouteData(snapshot, "history")
                    showLoading(false, "")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки истории: ${error.message}")
                    showLoading(false, "")
                }
            })
    }

    private fun startLocationListener() {
        val userId = auth.currentUser?.uid ?: return
        stopLocationListener()

        locationListener = database.child("last_locations").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return

                    val newLocations = mutableListOf<UserLocation>()
                    parseUserLocation(snapshot)?.let { newLocations.add(it) }

                    if (newLocations.isNotEmpty()) {
                        addNewLocationsToRoute(newLocations)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка слушателя локаций: ${error.message}")
                }
            })
    }

    private fun parseUserLocation(snapshot: DataSnapshot): UserLocation? {
        try {
            val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: return null

            if (timestamp in processedTimestamps) {
                return null
            }

            val lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
            val lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0

            if (!isValidCoordinates(lat, lng)) {
                return null
            }

            val userLocation = UserLocation(lat, lng, timestamp)
            processedTimestamps.add(timestamp)

            return userLocation

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга локации: ${e.message}")
            return null
        }
    }

    private fun isValidCoordinates(lat: Double, lng: Double): Boolean {
        return lat != 0.0 && lng != 0.0 &&
                abs(lat) <= 90 && abs(lng) <= 180 &&
                lat in -90.0..90.0 && lng in -180.0..180.0
    }

    private fun processRouteData(snapshot: DataSnapshot, source: String) {
        val newLocations = mutableListOf<UserLocation>()

        for (child in snapshot.children) {
            parseUserLocation(child)?.let { newLocations.add(it) }
        }

        Log.d(TAG, "Найдено новых локаций: ${newLocations.size}")

        if (newLocations.isEmpty()) {
            if (source == "history" && isFirstLoad) {
                Log.d(TAG, "История пуста")
            }
            return
        }

        newLocations.sortBy { it.timestamp }

        if (source == "history" && isFirstLoad) {
            locationList.clear()
            routePoints.clear()
            locationList.addAll(newLocations)
            routePoints.addAll(newLocations.map { Point(it.lat, it.lng) })

            requireActivity().runOnUiThread {
                updatePolyline()
                adjustCameraToRoute()
                calculateStats()
                updateRouteInfo("История маршрута", formatDateForSession(System.currentTimeMillis()))
                isFirstLoad = false
                isRouteLoaded = true
                Log.d(TAG, "Загружена история: ${locationList.size} точек")
            }
        }
    }

    private fun formatDateForSession(timestamp: Long): String {
        val date = Date(timestamp)
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(date)
    }

    private fun addNewLocationsToRoute(newLocations: List<UserLocation>) {
        val trulyNew = mutableListOf<UserLocation>()

        for (loc in newLocations) {
            if (locationList.isNotEmpty()) {
                val last = locationList.last()
                val distance = calculateDistance(last.lat, last.lng, loc.lat, loc.lng)
                val timeDiff = loc.timestamp - last.timestamp

                // Уменьшен порог для большей точности
                if (distance < MIN_DISTANCE_BETWEEN_POINTS) continue

                val speed = distance / (timeDiff / 1000.0)
                if (speed > MAX_REALISTIC_SPEED) continue
            }

            trulyNew.add(loc)
        }

        if (trulyNew.isEmpty()) return

        locationList.addAll(trulyNew)
        val smoothedPoints = smoothRoutePoints(trulyNew.map { Point(it.lat, it.lng) })
        routePoints.addAll(smoothedPoints)

        requireActivity().runOnUiThread {
            updatePolyline()
            calculateStats()
            updateRouteInfo("Активный маршрут", formatDateForSession(System.currentTimeMillis()))

            if (autoFollow && routePoints.isNotEmpty()) {
                mapView.map.move(
                    CameraPosition(routePoints.last(), 16f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.3f),
                    null
                )
            }
            Log.d(TAG, "Добавлено новых точек: ${trulyNew.size}, всего: ${locationList.size}")
        }
    }

    private fun smoothRoutePoints(newPoints: List<Point>): List<Point> {
        if (newPoints.isEmpty()) return emptyList()

        val smoothed = mutableListOf<Point>()

        if (routePoints.isEmpty()) {
            // Для первых точек возвращаем как есть
            return newPoints
        }

        // Берем последнюю точку из существующего маршрута для сглаживания
        val lastExistingPoint = routePoints.last()

        newPoints.forEachIndexed { index, point ->
            if (index == 0) {
                // Сглаживаем первую новую точку относительно последней существующей
                val smoothedLat = lastExistingPoint.latitude * (1 - SMOOTHING_FACTOR) + point.latitude * SMOOTHING_FACTOR
                val smoothedLng = lastExistingPoint.longitude * (1 - SMOOTHING_FACTOR) + point.longitude * SMOOTHING_FACTOR
                smoothed.add(Point(smoothedLat, smoothedLng))
            } else {
                // Сглаживаем относительно предыдущей сглаженной точки
                val prevSmoothed = smoothed.last()
                val smoothedLat = prevSmoothed.latitude * (1 - SMOOTHING_FACTOR) + point.latitude * SMOOTHING_FACTOR
                val smoothedLng = prevSmoothed.longitude * (1 - SMOOTHING_FACTOR) + point.longitude * SMOOTHING_FACTOR
                smoothed.add(Point(smoothedLat, smoothedLng))
            }
        }

        return smoothed
    }

    private fun updatePolyline() {
        if (locationList.size < 2) {
            Log.d(TAG, "Недостаточно точек для полилинии: ${locationList.size}")
            clearMapObjects()
            return
        }

        try {
            if (polyline == null) {
                // Более тонкая и гладкая линия
                polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                    setStrokeColor(Color.parseColor("#2196F3")) // Более яркий синий
                    setStrokeWidth(5f) // Уменьшена толщина для большей точности
                    setOutlineColor(Color.WHITE)
                    setOutlineWidth(1.5f)
                }
            } else {
                // Обновляем геометрию с учетом сглаженных точек
                polyline?.geometry = Polyline(routePoints)
            }

            updateMarkers()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления полилинии: ${e.message}")
        }
    }

    private fun updateMarkers() {
        // Стартовый маркер
        if (startMarker == null && routePoints.isNotEmpty()) {
            startMarker = mapView.map.mapObjects.addPlacemark(routePoints.first()).apply {
                setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location_start))
                setIconStyle(IconStyle().setScale(1.5f)) // Уменьшен масштаб
            }
        }

        // Конечный маркер
        if (routePoints.isNotEmpty()) {
            if (endMarker == null) {
                endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                    setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location_end))
                    setIconStyle(IconStyle().setScale(1.5f)) // Уменьшен масштаб
                }
            } else {
                endMarker?.geometry = routePoints.last()
            }
        }
    }

    private fun adjustCameraToRoute() {
        if (routePoints.isEmpty()) {
            Log.d(TAG, "Нет точек для настройки камеры")
            return
        }

        val bbox = calculateBoundingBox(routePoints)
        val center = Point(
            (bbox.southWest.latitude + bbox.northEast.latitude) / 2,
            (bbox.southWest.longitude + bbox.northEast.longitude) / 2
        )

        val latDiff = abs(bbox.northEast.latitude - bbox.southWest.latitude)
        val lonDiff = abs(bbox.northEast.longitude - bbox.southWest.longitude)
        val maxDiff = max(latDiff, lonDiff)

        val zoom = when {
            maxDiff < 0.001 -> 17f
            maxDiff < 0.005 -> 15f
            maxDiff < 0.01 -> 14f
            maxDiff < 0.02 -> 13f
            maxDiff < 0.05 -> 12f
            maxDiff < 0.1 -> 11f
            maxDiff < 0.2 -> 10f
            else -> 9f
        }.coerceIn(9f, 19f) // Увеличена максимальная зум для большей точности

        mapView.map.move(
            CameraPosition(center, zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )

        Log.d(TAG, "Камера настроена: центр ($center), zoom $zoom")
    }

    private fun calculateBoundingBox(points: List<Point>): BoundingBox {
        if (points.isEmpty()) return BoundingBox(Point(0.0, 0.0), Point(0.0, 0.0))

        var minLat = points.first().latitude
        var maxLat = points.first().latitude
        var minLon = points.first().longitude
        var maxLon = points.first().longitude

        for (point in points) {
            minLat = min(minLat, point.latitude)
            maxLat = max(maxLat, point.latitude)
            minLon = min(minLon, point.longitude)
            maxLon = max(maxLon, point.longitude)
        }

        val latPadding = (maxLat - minLat) * 0.15 + 0.001 // Увеличено padding для лучшего обзора
        val lonPadding = (maxLon - minLon) * 0.15 + 0.001

        return BoundingBox(
            Point(minLat - latPadding, minLon - lonPadding),
            Point(maxLat + latPadding, maxLon + lonPadding)
        )
    }

    private fun calculateStats() {
        if (locationList.size < 2) {
            updateUI()
            return
        }

        var calculatedDistance = 0.0
        var calculatedTime = 0L
        var totalSpeed = 0.0
        var validSegments = 0
        var localMaxSpeed = 0.0

        for (i in 1 until locationList.size) {
            val prev = locationList[i - 1]
            val curr = locationList[i]

            val timeDiff = (curr.timestamp - prev.timestamp) / 1000.0
            val distance = calculateDistance(prev.lat, prev.lng, curr.lat, curr.lng)

            if (timeDiff > 0 && distance > 0) {
                val speed = distance / timeDiff

                if (speed <= MAX_REALISTIC_SPEED) {
                    calculatedDistance += distance
                    calculatedTime += timeDiff.toLong()
                    totalSpeed += speed
                    validSegments++

                    if (speed > localMaxSpeed) localMaxSpeed = speed
                }
            }
        }

        totalDistance = calculatedDistance
        totalTime = calculatedTime
        maxSpeed = localMaxSpeed
        avgSpeed = if (validSegments > 0) totalSpeed / validSegments else 0.0

        totalCalories = calculateCalories(totalDistance / 1000, totalTime / 3600.0, avgSpeed * 3.6)

        Log.d(TAG, "Статистика: дистанция ${totalDistance/1000} км, время $totalTime сек")
    }

    private fun calculateCalories(distanceKm: Double, timeHours: Double, speedKmh: Double): Double {
        val met = when {
            speedKmh < 3 -> 2.0
            speedKmh < 5 -> 2.5
            speedKmh < 6.5 -> 3.5
            speedKmh < 8 -> 5.0
            speedKmh < 12 -> 8.0
            speedKmh < 16 -> 10.0
            else -> 12.0
        }
        return met * userWeight * timeHours
    }

    private fun updateUI() {
        if (!isAdded) return

        val distanceKm = totalDistance / 1000
        val timeMinutes = totalTime / 60.0
        val avgSpeedKmh = avgSpeed * 3.6
        val maxSpeedKmh = maxSpeed * 3.6

        tvDistance.text = "${String.format("%.2f", distanceKm)} км"
        tvTime.text = "${String.format("%.0f", timeMinutes)} мин"
        tvAvgSpeed.text = "${String.format("%.1f", avgSpeedKmh)} км/ч"
        tvMaxSpeed.text = "${String.format("%.1f", maxSpeedKmh)} км/ч"
        tvCalories.text = "${totalCalories.toInt()} ккал"
    }

    private fun updateRouteInfo(name: String, date: String) {
        tvRouteName.text = name
        tvRouteDate.text = date
    }

    private fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun stopLocationListener() {
        locationListener?.let {
            database.removeEventListener(it)
            locationListener = null
        }
    }

    private fun clearMapObjects() {
        try {
            polyline?.let { mapView.map.mapObjects.remove(it) }
            startMarker?.let { mapView.map.mapObjects.remove(it) }
            endMarker?.let { mapView.map.mapObjects.remove(it) }
        } finally {
            polyline = null
            startMarker = null
            endMarker = null
        }
        Log.d(TAG, "Все объекты карты очищены")
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
    }

    override fun onDestroyView() {
        stopLocationListener()
        historyListener?.let { database.removeEventListener(it) }
        statsUpdateRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        clearMapObjects()
        super.onDestroyView()
    }
}