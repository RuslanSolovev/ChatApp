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
    private lateinit var fabStartStop: FloatingActionButton
    private lateinit var fabExport: FloatingActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoading: TextView

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var polyline: PolylineMapObject? = null
    private var startMarker: PlacemarkMapObject? = null
    private var endMarker: PlacemarkMapObject? = null
    private var currentLocationMarker: PlacemarkMapObject? = null

    // Новые переменные для полилиний с разными цветами
    private var speedPolylines = mutableListOf<PolylineMapObject>()
    private var sessionPolylines = mutableListOf<PolylineMapObject>()
    private var segmentPolylines = mutableListOf<Pair<PolylineMapObject, Double>>()

    private var totalDistance = 0.0 // метры
    private var totalTime = 0L // секунды
    private var maxSpeed = 0.0 // м/с
    private var avgSpeed = 0.0 // м/с
    private var totalCalories = 0.0
    private val userWeight = 70.0 // кг (можно вынести в настройки)

    // Новые метрики
    private var elevationGain = 0.0 // метры подъема
    private var elevationLoss = 0.0 // метры спуска
    private var movingTime = 0L // секунды в движении
    private var stoppedTime = 0L // секунды на остановках
    private var pace = 0.0 // мин/км

    private var isTracking = false
    private var isPaused = false
    private var routePoints = mutableListOf<Point>()
    private var locationList = mutableListOf<UserLocation>()
    private val processedTimestamps = mutableSetOf<Long>()

    // Разделение на сессии
    private var currentSessionId = ""
    private val sessions = mutableMapOf<String, MutableList<UserLocation>>()

    // Кэширование
    private val locationCache = mutableMapOf<Long, UserLocation>()

    private var locationListener: ValueEventListener? = null
    private var trackingStatusListener: ValueEventListener? = null
    private var historyListener: ValueEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var statsUpdateRunnable: Runnable? = null
    private var locationUpdateRunnable: Runnable? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isFirstLoad = true
    private var shouldShowFullHistory = true
    private var lastCameraPosition: CameraPosition? = null
    private var isRouteLoaded = false

    // Настройки
    private var autoFollow = true
    private var showSpeedColors = true
    private var showElevation = false

    companion object {
        private const val TAG = "RouteTrackerFragment"
        private const val STATS_UPDATE_INTERVAL = 2000L
        private const val LOCATION_UPDATE_INTERVAL = 5000L
        private const val MAX_REALISTIC_SPEED = 27.78 // 100 км/ч → м/с
        private const val MIN_DISTANCE_BETWEEN_POINTS = 3.0 // метры
        private const val MIN_TIME_BETWEEN_POINTS = 2000L // 2 секунды
        private const val STOP_THRESHOLD = 1.0 // м/с - скорость ниже которой считаем остановкой
        private const val ELEVATION_THRESHOLD = 5.0 // метров - минимальный перепад высоты

        // Цвета для полилиний в зависимости от скорости
        private val SPEED_COLORS = mapOf(
            "slow" to Color.parseColor("#FF5252"),    // Красный: 0-5 км/ч
            "walking" to Color.parseColor("#4CAF50"), // Зеленый: 5-10 км/ч
            "jogging" to Color.parseColor("#2196F3"), // Синий: 10-15 км/ч
            "running" to Color.parseColor("#9C27B0"), // Фиолетовый: 15-20 км/ч
            "fast" to Color.parseColor("#FF9800")     // Оранжевый: 20+ км/ч
        )
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
        setupLocationUpdater()

        startLocationService()
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
        fabStartStop = view.findViewById(R.id.fabStartStop)
        fabExport = view.findViewById(R.id.fabExport)

        // Добавьте эти строки
        progressBar = view.findViewById(R.id.progressBar)
        tvLoading = view.findViewById(R.id.tvLoading)

        val mapContainer = view.findViewById<ViewGroup>(R.id.mapContainer)
        mapView = MapView(requireContext())
        mapContainer.addView(mapView)
    }


    private fun setupMap() {
        // Начальная позиция (Москва) или последняя позиция пользователя
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
            showClearOptionsDialog()
        }
    }

    private fun setupFloatingActions() {
        fabCenterMap.setOnClickListener {
            centerMapOnRoute()
            autoFollow = true
            fabCenterMap.setImageResource(R.drawable.ic_location_on)
        }

        fabStartStop.setOnClickListener {
            if (isTracking) {
                pauseTracking()
            } else {
                if (isPaused) {
                    resumeTracking()
                } else {
                    startNewSession()
                }
            }
        }

        fabExport.setOnClickListener {
            showExportOptions()
        }

        // Иконки по умолчанию
        updateFabIcons()
    }

    private fun showLoading(show: Boolean, message: String = "") {
        // Проверяем view на null и isAdded
        if (view == null || !isAdded || activity == null) {
            Log.w(TAG, "Фрагмент не готов для обновления UI")
            return
        }

        // Используем Handler вместо runOnUiThread
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
        val padding = 50 // пиксели

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

    private fun startNewSession() {
        val userId = auth.currentUser?.uid ?: return

        currentSessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        // Очищаем текущие данные
        locationList.clear()
        routePoints.clear()
        processedTimestamps.clear()
        clearMapObjects()

        // Обновляем UI
        resetStats()
        updateUI()

        // Включаем трекинг
        database.child("tracking_status").child(userId).setValue(true)
            .addOnSuccessListener {
                Toast.makeText(context, "Начата новая сессия", Toast.LENGTH_SHORT).show()
                updateFabIcons()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка запуска: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun pauseTracking() {
        val userId = auth.currentUser?.uid ?: return

        database.child("tracking_status").child(userId).setValue(false)
            .addOnSuccessListener {
                isPaused = true
                Toast.makeText(context, "Трекинг приостановлен", Toast.LENGTH_SHORT).show()
                updateFabIcons()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка паузы: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun resumeTracking() {
        val userId = auth.currentUser?.uid ?: return

        database.child("tracking_status").child(userId).setValue(true)
            .addOnSuccessListener {
                isPaused = false
                Toast.makeText(context, "Трекинг возобновлен", Toast.LENGTH_SHORT).show()
                updateFabIcons()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка возобновления: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateFabIcons() {
        when {
            isTracking -> {
                fabStartStop.setImageResource(R.drawable.ic_pause)
                fabStartStop.contentDescription = "Пауза"
            }
            isPaused -> {
                fabStartStop.setImageResource(R.drawable.ic_play)
                fabStartStop.contentDescription = "Возобновить"
            }
            else -> {
                fabStartStop.setImageResource(R.drawable.ic_play)
                fabStartStop.contentDescription = "Начать новую сессию"
            }
        }
    }

    private fun showExportOptions() {
        if (locationList.size < 2) {
            Toast.makeText(context, "Недостаточно данных для экспорта", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "Экспорт в GPX",
            "Экспорт в KML",
            "Поделиться маршрутом",
            "Создать изображение маршрута"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Экспорт маршрута")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToGPX()
                    1 -> exportToKML()
                    2 -> shareRoute()
                    3 -> createRouteImage()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun exportToGPX() {
        // Реализация экспорта в GPX формат
        Toast.makeText(context, "Экспорт в GPX (в разработке)", Toast.LENGTH_SHORT).show()
    }

    private fun exportToKML() {
        // Реализация экспорта в KML формат
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

    private fun createRouteImage() {
        // Создание скриншота карты с маршрутом
        Toast.makeText(context, "Создание изображения (в разработке)", Toast.LENGTH_SHORT).show()
    }

    private fun showClearOptionsDialog() {
        val options = arrayOf(
            "Очистить текущий маршрут",
            "Очистить всю историю",
            "Удалить старые маршруты (>30 дней)",
            "Отмена"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Очистка маршрута")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClearCurrentConfirmation()
                    1 -> showClearHistoryConfirmation()
                    2 -> showClearOldRoutesConfirmation()
                }
            }
            .show()
    }

    private fun showClearOldRoutesConfirmation() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

        AlertDialog.Builder(requireContext())
            .setTitle("Удаление старых маршрутов")
            .setMessage("Будут удалены все маршруты старше 30 дней. Продолжить?")
            .setPositiveButton("Удалить") { _, _ ->
                deleteOldRoutes(thirtyDaysAgo)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteOldRoutes(timestampThreshold: Long) {
        val userId = auth.currentUser?.uid ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val historySnapshot = database.child("route_history").child(userId).get().await()
                val updates = mutableMapOf<String, Any?>()

                for (child in historySnapshot.children) {
                    val timestamp = child.child("timestamp").getValue(Long::class.java)
                    if (timestamp != null && timestamp < timestampThreshold) {
                        updates["route_history/$userId/${child.key}"] = null
                    }
                }

                if (updates.isNotEmpty()) {
                    database.updateChildren(updates).await()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Удалено ${updates.size} старых маршрутов", Toast.LENGTH_SHORT).show()
                        loadFullRouteHistory()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Нет маршрутов старше 30 дней", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showClearCurrentConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Очистка текущего маршрута")
            .setMessage("Текущий активный маршрут будет очищен. Исторические данные сохранятся.")
            .setPositiveButton("Очистить") { _, _ ->
                clearCurrentSession()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Очистка всей истории")
            .setMessage("ВСЕ данные о маршрутах будут безвозвратно удалены. Это действие нельзя отменить. Вы уверены?")
            .setPositiveButton("Удалить всю историю") { _, _ ->
                clearFullHistory()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearCurrentSession() {
        val userId = auth.currentUser?.uid ?: return

        database.child("user_locations").child(userId).removeValue()
            .addOnSuccessListener {
                resetState(keepHistory = true)
                loadFullRouteHistory()
                Toast.makeText(context, "Текущий маршрут очищен", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка очистки маршрута: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun clearFullHistory() {
        val userId = auth.currentUser?.uid ?: return

        database.child("route_history").child(userId).removeValue()
            .addOnSuccessListener {
                database.child("user_locations").child(userId).removeValue()
                    .addOnSuccessListener {
                        resetState(keepHistory = false)
                        Toast.makeText(context, "Вся история маршрутов очищена", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "Ошибка очистки сессии: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка очистки истории: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun resetState(keepHistory: Boolean) {
        processedTimestamps.clear()
        locationList.clear()
        routePoints.clear()
        locationCache.clear()
        sessions.clear()

        resetStats()
        isFirstLoad = true
        shouldShowFullHistory = keepHistory
        isRouteLoaded = false

        requireActivity().runOnUiThread {
            updateUI()
            clearMapObjects()
            updateRouteInfo("", "")
            showLoading(false, "")
        }
    }

    private fun resetStats() {
        totalDistance = 0.0
        totalTime = 0L
        maxSpeed = 0.0
        avgSpeed = 0.0
        totalCalories = 0.0
        elevationGain = 0.0
        elevationLoss = 0.0
        movingTime = 0L
        stoppedTime = 0L
        pace = 0.0
    }

    private fun setupStatsUpdater() {
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                if (isAdded && locationList.size >= 2) {
                    calculateAdvancedStats()
                    updateUI()
                }
                handler.postDelayed(this, STATS_UPDATE_INTERVAL)
            }
        }
        statsUpdateRunnable?.let { handler.post(it) }
    }

    private fun setupLocationUpdater() {
        locationUpdateRunnable = object : Runnable {
            override fun run() {
                if (isAdded && isTracking && autoFollow && routePoints.isNotEmpty()) {
                    // Плавное перемещение камеры к последней точке
                    mapView.map.move(
                        CameraPosition(routePoints.last(), 16f, 0f, 0f),
                        Animation(Animation.Type.SMOOTH, 0.3f),
                        null
                    )
                }
                handler.postDelayed(this, LOCATION_UPDATE_INTERVAL)
            }
        }
        locationUpdateRunnable?.let { handler.post(it) }
    }

    private fun startLocationService() {
        try {
            LocationUpdateService.startService(requireContext())
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка запуска отслеживания: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFirebaseListeners() {
        val userId = auth.currentUser?.uid ?: run {
            Toast.makeText(context, "Требуется авторизация", Toast.LENGTH_SHORT).show()
            return
        }

        trackingStatusListener = database.child("tracking_status").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val wasTracking = isTracking
                    isTracking = snapshot.getValue(Boolean::class.java) ?: false

                    updateTrackingUI()
                    updateFabIcons()

                    if (isTracking && !wasTracking) {
                        startLocationListener()
                        shouldShowFullHistory = false
                        if (locationList.isEmpty() && !isFirstLoad) {
                            loadCurrentSession()
                        }
                    } else if (!isTracking && wasTracking) {
                        shouldShowFullHistory = true
                        stopLocationListener()
                        if (!isFirstLoad) {
                            loadFullRouteHistory()
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка статуса трекинга: ${error.message}")
                }
            })
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

    private fun loadCurrentSession() {
        val userId = auth.currentUser?.uid ?: return

        Log.d(TAG, "Загрузка текущей сессии...")
        database.child("user_locations").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    Log.d(TAG, "Получена текущая сессия: ${snapshot.childrenCount} записей")
                    processRouteData(snapshot, "session")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки сессии: ${error.message}")
                }
            })
    }

    private fun parseUserLocation(snapshot: DataSnapshot): UserLocation? {
        try {
            // Проверяем кэш
            val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: return null
            locationCache[timestamp]?.let { return it }

            // Парсим данные
            val lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
            val lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0

            if (!isValidCoordinates(lat, lng) || timestamp in processedTimestamps) {
                return null
            }

            val userLocation = UserLocation(lat, lng, timestamp)

            // Кэшируем
            locationCache[timestamp] = userLocation
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
                clearUI()
            }
            return
        }

        newLocations.sortBy { it.timestamp }

        if (source == "history" && isFirstLoad) {
            // Группируем по сессиям (разрыв > 5 минут = новая сессия)
            groupLocationsIntoSessions(newLocations)
            // Показываем последнюю сессию
            showLastSession()
            isFirstLoad = false
            isRouteLoaded = true

        } else if (source == "session") {
            addNewLocationsToRoute(newLocations)
        }
    }

    private fun groupLocationsIntoSessions(locations: List<UserLocation>) {
        sessions.clear()

        if (locations.isEmpty()) return

        var currentSession = mutableListOf(locations.first())
        var currentSessionKey = formatDateForSession(locations.first().timestamp)

        for (i in 1 until locations.size) {
            val prev = locations[i - 1]
            val curr = locations[i]

            // Разрыв > 5 минут = новая сессия
            if ((curr.timestamp - prev.timestamp) > 5 * 60 * 1000) {
                sessions[currentSessionKey] = currentSession.toMutableList()
                currentSession = mutableListOf()
                currentSessionKey = formatDateForSession(curr.timestamp)
            }

            currentSession.add(curr)
        }

        // Добавляем последнюю сессию
        if (currentSession.isNotEmpty()) {
            sessions[currentSessionKey] = currentSession
        }

        Log.d(TAG, "Сгруппировано ${sessions.size} сессий")
    }

    private fun formatDateForSession(timestamp: Long): String {
        val date = Date(timestamp)
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(date)
    }

    private fun showLastSession() {
        if (sessions.isEmpty()) {
            clearUI()
            return
        }

        val lastSessionKey = sessions.keys.last()
        val lastSession = sessions[lastSessionKey] ?: return

        // Показываем последнюю сессию
        locationList.clear()
        routePoints.clear()
        locationList.addAll(lastSession)
        routePoints.addAll(lastSession.map { Point(it.lat, it.lng) })

        requireActivity().runOnUiThread {
            updatePolylineWithSpeedColors()
            adjustCameraToRoute()
            calculateAdvancedStats()
            updateRouteInfo("Сессия", lastSessionKey)
            Log.d(TAG, "Показана последняя сессия: ${locationList.size} точек")
        }
    }

    private fun startLocationListener() {
        val userId = auth.currentUser?.uid ?: return
        stopLocationListener()

        locationListener = database.child("user_locations").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded || !isTracking) return

                    val newLocations = mutableListOf<UserLocation>()
                    for (child in snapshot.children) {
                        parseUserLocation(child)?.let { newLocations.add(it) }
                    }

                    if (newLocations.isNotEmpty()) {
                        newLocations.sortBy { it.timestamp }
                        addNewLocationsToRoute(newLocations)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка слушателя локаций: ${error.message}")
                }
            })
    }

    private fun addNewLocationsToRoute(newLocations: List<UserLocation>) {
        val trulyNew = mutableListOf<UserLocation>()

        for (loc in newLocations) {
            if (locationList.isNotEmpty()) {
                val last = locationList.last()
                val distance = calculateDistance(last.lat, last.lng, loc.lat, loc.lng)
                val timeDiff = loc.timestamp - last.timestamp

                // Фильтрация невалидных точек
                if (distance < MIN_DISTANCE_BETWEEN_POINTS) continue
                if (timeDiff < MIN_TIME_BETWEEN_POINTS) continue

                val speed = distance / (timeDiff / 1000.0)
                if (speed > MAX_REALISTIC_SPEED) continue
            }

            trulyNew.add(loc)
        }

        if (trulyNew.isEmpty()) return

        locationList.addAll(trulyNew)
        routePoints.addAll(trulyNew.map { Point(it.lat, it.lng) })

        requireActivity().runOnUiThread {
            updatePolylineWithSpeedColors()
            calculateAdvancedStats()
            updateRouteInfo("Активная сессия", formatDateForSession(System.currentTimeMillis()))

            if (isTracking && autoFollow && routePoints.isNotEmpty()) {
                mapView.map.move(
                    CameraPosition(routePoints.last(), 16f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.3f),
                    null
                )
            }
            Log.d(TAG, "Добавлено новых точек: ${trulyNew.size}, всего: ${locationList.size}")
        }
    }

    private fun updatePolylineWithSpeedColors() {
        if (locationList.size < 2) {
            Log.d(TAG, "Недостаточно точек для полилинии: ${locationList.size}")
            clearMapObjects()
            return
        }

        try {
            // Очищаем старые полилинии
            clearSpeedPolylines()

            if (!showSpeedColors) {
                // Обычная полилиния одного цвета
                if (polyline == null) {
                    polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                        setStrokeColor(Color.parseColor("#1E88E5"))
                        setStrokeWidth(6f)
                        setOutlineColor(Color.WHITE)
                        setOutlineWidth(2f)
                    }
                } else {
                    polyline?.geometry = Polyline(routePoints)
                }
            } else {
                // Полилиния с цветами по скорости
                val segments = createSpeedSegments()
                for (segment in segments) {
                    val polylineObj = mapView.map.mapObjects.addPolyline(Polyline(segment.points)).apply {
                        setStrokeColor(segment.color)
                        setStrokeWidth(6f)
                        setOutlineColor(Color.WHITE)
                        setOutlineWidth(2f)
                    }
                    speedPolylines.add(polylineObj)
                }
            }

            // Обновляем маркеры
            updateMarkers()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления полилинии: ${e.message}")
        }
    }

    private fun createSpeedSegments(): List<SpeedSegment> {
        val segments = mutableListOf<SpeedSegment>()
        var currentSegment = mutableListOf<Point>()
        var currentColor = Color.BLUE

        for (i in 1 until locationList.size) {
            val prev = locationList[i - 1]
            val curr = locationList[i]

            val distance = calculateDistance(prev.lat, prev.lng, curr.lat, curr.lng)
            val timeDiff = (curr.timestamp - prev.timestamp) / 1000.0
            val speed = if (timeDiff > 0) distance / timeDiff else 0.0

            val color = getColorForSpeed(speed)

            if (currentSegment.isEmpty()) {
                currentSegment.add(Point(prev.lat, prev.lng))
                currentColor = color
            }

            if (color == currentColor) {
                currentSegment.add(Point(curr.lat, curr.lng))
            } else {
                if (currentSegment.size >= 2) {
                    segments.add(SpeedSegment(currentSegment.toList(), currentColor))
                }
                currentSegment = mutableListOf(
                    Point(prev.lat, prev.lng),
                    Point(curr.lat, curr.lng)
                )
                currentColor = color
            }
        }

        // Добавляем последний сегмент
        if (currentSegment.size >= 2) {
            segments.add(SpeedSegment(currentSegment, currentColor))
        }

        return segments
    }

    private fun getColorForSpeed(speed: Double): Int {
        val speedKmh = speed * 3.6
        return when {
            speedKmh < 5 -> SPEED_COLORS["slow"] ?: Color.RED
            speedKmh < 10 -> SPEED_COLORS["walking"] ?: Color.GREEN
            speedKmh < 15 -> SPEED_COLORS["jogging"] ?: Color.BLUE
            speedKmh < 20 -> SPEED_COLORS["running"] ?: Color.MAGENTA
            else -> SPEED_COLORS["fast"] ?: Color.YELLOW
        }
    }

    data class SpeedSegment(val points: List<Point>, val color: Int)

    private fun updateMarkers() {
        // Стартовый маркер
        if (startMarker == null && routePoints.isNotEmpty()) {
            startMarker = mapView.map.mapObjects.addPlacemark(routePoints.first()).apply {
                setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location_start))
                setIconStyle(IconStyle().setScale(1.8f))
            }
        }

        // Конечный маркер
        if (routePoints.isNotEmpty()) {
            if (endMarker == null) {
                endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                    setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location_end))
                    setIconStyle(IconStyle().setScale(1.8f))
                }
            } else {
                endMarker?.geometry = routePoints.last()
            }
        }

        // Текущий маркер (если трекинг активен)
        if (isTracking && routePoints.isNotEmpty()) {
            if (currentLocationMarker == null) {
                currentLocationMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                    setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_current_location))
                    setIconStyle(IconStyle().setScale(1.5f))
                }
            } else {
                currentLocationMarker?.geometry = routePoints.last()
            }
        } else {
            currentLocationMarker?.let {
                mapView.map.mapObjects.remove(it)
                currentLocationMarker = null
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
        }.coerceIn(8f, 18f)

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

        // Добавляем отступы
        val latPadding = (maxLat - minLat) * 0.1 + 0.001
        val lonPadding = (maxLon - minLon) * 0.1 + 0.001

        return BoundingBox(
            Point(minLat - latPadding, minLon - lonPadding),
            Point(maxLat + latPadding, maxLon + lonPadding)
        )
    }

    private fun calculateAdvancedStats() {
        if (locationList.size < 2) {
            updateUI()
            return
        }

        var calculatedDistance = 0.0
        var calculatedTime = 0L
        var totalSpeed = 0.0
        var validSegments = 0
        var localMaxSpeed = 0.0
        var localElevationGain = 0.0
        var localElevationLoss = 0.0
        var localMovingTime = 0L
        var localStoppedTime = 0L

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

                    // Определяем движение/остановку
                    if (speed > STOP_THRESHOLD) {
                        localMovingTime += timeDiff.toLong()
                    } else {
                        localStoppedTime += timeDiff.toLong()
                    }
                }
            }
        }

        totalDistance = calculatedDistance
        totalTime = calculatedTime
        maxSpeed = localMaxSpeed
        avgSpeed = if (validSegments > 0) totalSpeed / validSegments else 0.0
        movingTime = localMovingTime
        stoppedTime = localStoppedTime
        elevationGain = localElevationGain
        elevationLoss = localElevationLoss

        // Рассчитываем темп (мин/км)
        pace = if (totalDistance > 0 && movingTime > 0) {
            (movingTime / 60.0) / (totalDistance / 1000.0)
        } else 0.0

        // Рассчитываем калории
        totalCalories = calculateCalories(totalDistance / 1000, movingTime / 3600.0, avgSpeed * 3.6)

        Log.d(TAG, "Статистика: дистанция ${totalDistance/1000} км, время $totalTime сек, средняя скорость $avgSpeed м/с")
    }

    private fun calculateCalories(distanceKm: Double, timeHours: Double, speedKmh: Double): Double {
        // Более точная формула расчета калорий
        val met = when {
            speedKmh < 3 -> 2.0  // Стояние
            speedKmh < 5 -> 2.5  // Медленная ходьба
            speedKmh < 6.5 -> 3.5 // Обычная ходьба
            speedKmh < 8 -> 5.0   // Быстрая ходьба
            speedKmh < 12 -> 8.0  // Бег трусцой
            speedKmh < 16 -> 10.0 // Бег
            else -> 12.0          // Быстрый бег
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

        // Дополнительная информация (можно добавить больше TextView)
        // tvPace.text = "${String.format("%.1f", pace)} мин/км"
        // tvElevation.text = "${elevationGain.toInt()} м ↑"
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
        val R = 6371000.0 // Земной радиус в метрах
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun updateTrackingUI() {
        val color = if (isTracking) Color.GREEN else Color.GRAY
        tvDistance.setTextColor(color)
        tvTime.setTextColor(color)
    }



    private fun stopLocationListener() {
        locationListener?.let {
            database.removeEventListener(it)
            locationListener = null
        }
    }

    private fun clearSpeedPolylines() {
        speedPolylines.forEach { mapView.map.mapObjects.remove(it) }
        speedPolylines.clear()
    }

    private fun clearSessionPolylines() {
        sessionPolylines.forEach { mapView.map.mapObjects.remove(it) }
        sessionPolylines.clear()
    }

    private fun clearSegmentPolylines() {
        segmentPolylines.forEach { (polyline, _) -> mapView.map.mapObjects.remove(polyline) }
        segmentPolylines.clear()
    }

    private fun clearMapObjects() {
        try {
            polyline?.let { mapView.map.mapObjects.remove(it) }
            startMarker?.let { mapView.map.mapObjects.remove(it) }
            endMarker?.let { mapView.map.mapObjects.remove(it) }
            currentLocationMarker?.let { mapView.map.mapObjects.remove(it) }

            clearSpeedPolylines()
            clearSessionPolylines()
            clearSegmentPolylines()
        } finally {
            polyline = null
            startMarker = null
            endMarker = null
            currentLocationMarker = null
        }
        Log.d(TAG, "Все объекты карты очищены")
    }

    private fun clearUI() {
        resetState(keepHistory = true)
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

    override fun onPause() {
        super.onPause()
        // Сохраняем позицию камеры
        lastCameraPosition = mapView.map.cameraPosition
    }

    override fun onResume() {
        super.onResume()
        // Восстанавливаем позицию камеры если есть
        lastCameraPosition?.let {
            mapView.map.move(it)
        }
    }

    override fun onDestroyView() {
        stopLocationListener()
        trackingStatusListener?.let { database.removeEventListener(it) }
        historyListener?.let { database.removeEventListener(it) }
        statsUpdateRunnable?.let { handler.removeCallbacks(it) }
        locationUpdateRunnable?.let { handler.removeCallbacks(it) }
        scope.cancel()
        clearMapObjects()
        super.onDestroyView()
    }
}