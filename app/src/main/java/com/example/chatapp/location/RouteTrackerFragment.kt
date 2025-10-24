package com.example.chatapp.location

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
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private lateinit var btnCloseSheet: ImageButton
    private lateinit var bottomSheet: LinearLayout

    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var polyline: PolylineMapObject? = null
    private var startMarker: PlacemarkMapObject? = null
    private var endMarker: PlacemarkMapObject? = null

    private var totalDistance = 0.0
    private var totalTime = 0L
    private var maxSpeed = 0.0
    private var avgSpeed = 0.0
    private val userWeight = 70.0

    private var isTracking = false
    private var routePoints = mutableListOf<Point>()
    private var locationList = mutableListOf<UserLocation>()
    private var locationListener: ValueEventListener? = null
    private var trackingStatusListener: ValueEventListener? = null

    private val handler = Handler(Looper.getMainLooper())
    private var statsUpdateRunnable: Runnable? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isFirstLoad = true
    private var shouldShowFullHistory = true

    companion object {
        private const val TAG = "RouteTrackerFragment"
        private const val STATS_UPDATE_INTERVAL = 3000L
        private const val MAX_REALISTIC_SPEED = 27.78 // 100 км/ч в м/с
        private const val MAX_BIKE_SPEED = 16.67 // 60 км/ч в м/с
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
        Log.d(TAG, "🎯 Фрагмент создан")

        initViews(view)
        setupMap()
        setupLeftSheet()
        setupButtons()
        setupStatsUpdater()

        // Запускаем сервис и загружаем данные
        startLocationService()
        setupFirebaseListeners()

        // Загружаем полную историю маршрута при первом открытии
        handler.postDelayed({
            loadFullRouteHistory()
        }, 1000)

        Log.d(TAG, "✅ Фрагмент инициализирован")
    }



    private fun initViews(view: View) {
        tvDistance = view.findViewById(R.id.Distance)
        tvTime = view.findViewById(R.id.tvTime)
        tvAvgSpeed = view.findViewById(R.id.AvgSpeed)
        tvMaxSpeed = view.findViewById(R.id.MaxSpeed)
        tvCalories = view.findViewById(R.id.Calories)
        btnClear = view.findViewById(R.id.btnClear)
        btnToggleSheet = view.findViewById(R.id.btnToggleSheet)
        btnCloseSheet = view.findViewById(R.id.btnCloseSheet)
        bottomSheet = view.findViewById(R.id.bottomSheet)

        // Отладочная информация
        bottomSheet.post {
            Log.d(TAG, "📱 Размеры шторки: ${bottomSheet.width} x ${bottomSheet.height}")
            Log.d(TAG, "📱 TranslationX: ${bottomSheet.translationX}")
        }

        // Создаем MapView
        val mapContainer = view.findViewById<ViewGroup>(R.id.mapContainer)
        mapView = MapView(requireContext())
        mapContainer.addView(mapView)

        Log.d(TAG, "✅ Views инициализированы")
    }

    private fun setupMap() {
        // Начальная позиция карты (Москва)
        mapView.map.move(
            CameraPosition(Point(55.7558, 37.6173), 12f, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0f),
            null
        )
        Log.d(TAG, "🗺️ Карта настроена")
    }


    private fun toggleLeftSheetWithAnimation() {
        if (bottomSheet.translationX < 0) {
            // Показываем шторку
            bottomSheet.animate()
                .translationX(0f)
                .setDuration(300)
                .start()
            btnToggleSheet.setImageResource(R.drawable.ic_arrow_back)
        } else {
            // Скрываем шторку
            hideLeftSheetWithAnimation()
        }
    }

    private fun hideLeftSheetWithAnimation() {
        bottomSheet.animate()
            .translationX(-bottomSheet.width.toFloat())
            .setDuration(300)
            .start()
        btnToggleSheet.setImageResource(R.drawable.ic_stats)
    }


    private fun setupButtons() {
        btnClear.setOnClickListener {
            showClearOptionsDialog()
        }

        Log.d(TAG, "🔘 Кнопки настроены")
    }

    private fun showClearOptionsDialog() {
        val options = arrayOf(
            "Очистить текущий маршрут",
            "Очистить всю историю",
            "Отмена"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Очистка маршрута")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClearCurrentConfirmation()
                    1 -> showClearHistoryConfirmation()
                    // 2 - отмена
                }
            }
            .show()
    }

    private fun showClearCurrentConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Очистка текущего маршрута")
            .setMessage("Текущий активный маршрут будет очищен. Исторические данные сохранятся.")
            .setPositiveButton("Очистить") { _, _ ->
                clearCurrentSession()
                Toast.makeText(context, "Текущий маршрут очищен", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Вся история маршрутов очищена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun clearCurrentSession() {
        val userId = auth.currentUser?.uid ?: return

        database.child("user_locations").child(userId).removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "✅ Текущая сессия очищена из БД")
                shouldShowFullHistory = true
                isFirstLoad = true
                loadFullRouteHistory()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Ошибка очистки сессии", e)
                Toast.makeText(context, "Ошибка очистки маршрута", Toast.LENGTH_SHORT).show()
            }
    }

    private fun clearFullHistory() {
        val userId = auth.currentUser?.uid ?: return

        database.child("route_history").child(userId).removeValue()
            .addOnSuccessListener {
                database.child("user_locations").child(userId).removeValue()
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Вся история маршрута очищена")
                        clearUI()
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Ошибка очистки истории", e)
                Toast.makeText(context, "Ошибка очистки истории", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupStatsUpdater() {
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                if (isAdded) {
                    calculateStats()
                }
                handler.postDelayed(this, STATS_UPDATE_INTERVAL)
            }
        }
        statsUpdateRunnable?.let { handler.post(it) }
        Log.d(TAG, "📊 Обновление статистики запущено")
    }

    private fun startLocationService() {
        try {
            Log.d(TAG, "🚀 ЗАПУСК СЕРВИСА ЛОКАЦИИ")
            LocationUpdateService.startService(requireContext())
            Log.d(TAG, "✅ Сервис локации запущен")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка запуска сервиса", e)
            Toast.makeText(context, "Ошибка запуска отслеживания", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFirebaseListeners() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "❌ Пользователь не авторизован")
            Toast.makeText(context, "Требуется авторизация", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "👤 ID пользователя: $userId")

        // Слушатель статуса трекинга
        trackingStatusListener = database.child("tracking_status").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    isTracking = snapshot.getValue(Boolean::class.java) ?: false
                    Log.d(TAG, "📊 Статус трекинга: $isTracking")
                    updateTrackingUI()

                    if (isTracking) {
                        startLocationListener()
                        // При активном трекинге показываем только текущую сессию
                        shouldShowFullHistory = false
                        loadCurrentSession()
                    } else {
                        // При остановленном трекинге показываем всю историю
                        shouldShowFullHistory = true
                        stopLocationListener()
                        if (!isFirstLoad) {
                            loadFullRouteHistory()
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка статуса трекинга", error.toException())
                }
            })
    }

    private fun loadFullRouteHistory() {
        val userId = auth.currentUser?.uid ?: return

        Log.d(TAG, "📥 ЗАГРУЗКА ПОЛНОЙ ИСТОРИИ МАРШРУТА")

        database.child("route_history").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return

                    Log.d(TAG, "📦 Получено данных из истории: ${snapshot.childrenCount} записей")
                    processRouteData(snapshot, "history")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки истории маршрута", error.toException())
                }
            })
    }

    private fun loadCurrentSession() {
        val userId = auth.currentUser?.uid ?: return

        Log.d(TAG, "📥 ЗАГРУЗКА ТЕКУЩЕЙ СЕССИИ")

        database.child("user_locations").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return

                    Log.d(TAG, "📦 Получено данных из сессии: ${snapshot.childrenCount} записей")
                    processRouteData(snapshot, "session")
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки текущей сессии", error.toException())
                }
            })
    }

    private fun parseUserLocation(snapshot: DataSnapshot): UserLocation? {
        return try {
            Log.d(TAG, "🔍 Парсим данные: ${snapshot.key}")
            Log.d(TAG, "📦 Содержимое: ${snapshot.value}")

            // Пропускаем служебные поля (accuracy, color и т.д.)
            if (snapshot.key in listOf("accuracy", "color", "lat", "lng", "timestamp", "speed")) {
                Log.w(TAG, "⚠️ Пропускаем служебное поле: ${snapshot.key}")
                return null
            }

            // Проверяем, что это объект с данными локации
            if (snapshot.hasChild("lat") && snapshot.hasChild("lng")) {
                // Формат: -Oc-dxPtRrNFiZGK1-tv { lat: 55.96, lng: 38.05, ... }
                val lat = snapshot.child("lat").getValue(Double::class.java) ?: 0.0
                val lng = snapshot.child("lng").getValue(Double::class.java) ?: 0.0
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                if (isValidCoordinates(lat, lng)) {
                    Log.d(TAG, "✅ Успешно распарсено из объекта: $lat, $lng")
                    return UserLocation(lat, lng, timestamp)
                }
            } else if (snapshot.value is Map<*, *>) {
                // Альтернативный формат данных
                val data = snapshot.value as Map<*, *>
                val lat = (data["lat"] as? Double) ?: 0.0
                val lng = (data["lng"] as? Double) ?: 0.0
                val timestamp = (data["timestamp"] as? Long) ?: System.currentTimeMillis()

                if (isValidCoordinates(lat, lng)) {
                    Log.d(TAG, "✅ Успешно распарсено из Map: $lat, $lng")
                    return UserLocation(lat, lng, timestamp)
                }
            }

            Log.w(TAG, "⚠️ Не удалось распарсить данные для ключа: ${snapshot.key}")
            null

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка парсинга UserLocation для ${snapshot.key}", e)
            null
        }
    }

    private fun isValidCoordinates(lat: Double, lng: Double): Boolean {
        return lat != 0.0 && lng != 0.0 &&
                abs(lat) <= 90 && abs(lng) <= 180 &&
                lat > 1.0 && lng > 1.0 // Фильтруем тестовые координаты
    }

    private fun processRouteData(snapshot: DataSnapshot, source: String) {
        val locations = mutableListOf<UserLocation>()

        for (child in snapshot.children) {
            try {
                val location = parseUserLocation(child)
                if (location != null) {
                    locations.add(location)
                    Log.d(TAG, "✅ Точка из $source: ${location.lat}, ${location.lng}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка парсинга точки из $source", e)
            }
        }

        if (locations.isNotEmpty()) {
            locations.sortBy { it.timestamp }

            if (source == "history" && isFirstLoad) {
                // При первой загрузке истории показываем весь маршрут
                locationList.clear()
                routePoints.clear()
                locationList.addAll(locations)
                routePoints.addAll(locations.map { Point(it.lat, it.lng) })
                isFirstLoad = false
            } else if (source == "session") {
                // При загрузке сессии добавляем к существующему маршруту
                val newPoints = locations.map { Point(it.lat, it.lng) }
                routePoints.addAll(newPoints)
                locationList.addAll(locations)
            }

            updateRouteOnMap()
            calculateStats()

            Log.d(TAG, "✅ Маршрут из $source загружен: ${locations.size} точек")
        } else {
            Log.d(TAG, "📭 Нет данных в $source для загрузки")
            if (source == "history" && isFirstLoad) {
                clearUI()
            }
        }
    }

    private fun setupLeftSheet() {
        // Используем фиксированную ширину из XML (320dp)
        val sheetWidth = (320 * resources.displayMetrics.density).toInt()

        // Сразу скрываем шторку
        bottomSheet.translationX = -sheetWidth.toFloat()

        btnToggleSheet.setOnClickListener {
            toggleLeftSheetWithAnimation(sheetWidth)
        }

        btnCloseSheet.setOnClickListener {
            hideLeftSheetWithAnimation(sheetWidth)
        }

        Log.d(TAG, "📱 Левая шторка настроена, ширина: $sheetWidth px")
    }

    private fun toggleLeftSheetWithAnimation(sheetWidth: Int) {
        if (bottomSheet.translationX < 0) {
            // Показываем шторку
            bottomSheet.animate()
                .translationX(0f)
                .setDuration(300)
                .start()
            btnToggleSheet.setImageResource(R.drawable.ic_arrow_back)
            Log.d(TAG, "📱 Показываем шторку")
        } else {
            // Скрываем шторку
            hideLeftSheetWithAnimation(sheetWidth)
        }
    }

    private fun hideLeftSheetWithAnimation(sheetWidth: Int) {
        bottomSheet.animate()
            .translationX(-sheetWidth.toFloat())
            .setDuration(300)
            .start()
        btnToggleSheet.setImageResource(R.drawable.ic_stats)
        Log.d(TAG, "📱 Скрываем шторку")
    }


    private fun startLocationListener() {
        val userId = auth.currentUser?.uid ?: return
        stopLocationListener()

        Log.d(TAG, "🎯 Запуск слушателя локаций для реального времени")

        locationListener = database.child("user_locations").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded || !isTracking) return

                    Log.d(TAG, "🔄 Real-time данные: ${snapshot.childrenCount} записей")

                    val newLocations = mutableListOf<UserLocation>()
                    for (child in snapshot.children) {
                        try {
                            val location = parseUserLocation(child)
                            if (location != null) {
                                newLocations.add(location)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Ошибка парсинга real-time точки", e)
                        }
                    }

                    if (newLocations.isNotEmpty()) {
                        newLocations.sortBy { it.timestamp }
                        // Добавляем только новые точки к существующему маршруту
                        addNewLocationsToRoute(newLocations)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка real-time слушателя", error.toException())
                }
            })
    }

    private fun addNewLocationsToRoute(newLocations: List<UserLocation>) {
        val existingTimestamps = locationList.map { it.timestamp }.toSet()

        // Фильтруем и сортируем новые точки с дополнительной проверкой на реалистичность
        val trulyNewLocations = newLocations
            .filter { it.timestamp !in existingTimestamps }
            .sortedBy { it.timestamp }
            .filterIndexed { index, location ->
                if (index > 0) {
                    val prev = newLocations[index - 1]
                    val distance = calculateDistance(prev.lat, prev.lng, location.lat, location.lng)
                    val timeDiff = (location.timestamp - prev.timestamp) / 1000.0

                    if (timeDiff > 0) {
                        val speed = distance / timeDiff
                        // Фильтруем точки с нереалистичной скоростью (более 100 км/ч)
                        val isRealistic = speed <= MAX_REALISTIC_SPEED
                        if (!isRealistic) {
                            Log.w(TAG, "🚫 Отфильтрована точка с нереалистичной скоростью: ${String.format("%.1f", speed * 3.6)} км/ч")
                        }
                        isRealistic
                    } else {
                        true
                    }
                } else {
                    true
                }
            }

        if (trulyNewLocations.isEmpty()) {
            Log.d(TAG, "⚠️ Нет новых точек для добавления после фильтрации")
            return
        }

        Log.d(TAG, "📍 Добавляем ${trulyNewLocations.size} новых точек в маршрут (после фильтрации)")

        locationList.addAll(trulyNewLocations)
        // Сортируем по времени на случай, если точки пришли в неправильном порядке
        locationList.sortBy { it.timestamp }
        routePoints.clear()
        routePoints.addAll(locationList.map { Point(it.lat, it.lng) })

        updateRouteOnMap()
        calculateStats()
    }

    private fun updateRouteOnMap() {
        if (!isAdded || routePoints.isEmpty()) return

        clearRoute()

        try {
            // Рисуем полилинию если есть хотя бы 2 точки
            if (routePoints.size >= 2) {
                polyline = mapView.map.mapObjects.addPolyline(Polyline(routePoints)).apply {
                    setStrokeColor(Color.parseColor("#1E88E5"))
                    setStrokeWidth(6f)
                    setOutlineColor(Color.WHITE)
                    setOutlineWidth(2f)
                }
                Log.d(TAG, "🎯 Полилиния нарисована: ${routePoints.size} точек")
            }

            // Добавляем маркеры
            startMarker = mapView.map.mapObjects.addPlacemark(routePoints.first()).apply {
                setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_location))
                setIconStyle(IconStyle().setScale(1.5f))
            }

            if (routePoints.size > 1) {
                endMarker = mapView.map.mapObjects.addPlacemark(routePoints.last()).apply {
                    setIcon(ImageProvider.fromResource(requireContext(), R.drawable.ic_marker))
                    setIconStyle(IconStyle().setScale(1.5f))
                }
            }

            // Настраиваем камеру
            adjustCameraToRoute()

            Log.d(TAG, "✅ Маршрут обновлен на карте")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка обновления маршрута", e)
        }
    }

    private fun adjustCameraToRoute() {
        if (routePoints.isEmpty()) return

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
            else -> 11f
        }.coerceIn(10f, 18f)

        mapView.map.move(
            CameraPosition(center, zoom, 0f, 0f),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )
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

        val padding = 0.001
        return BoundingBox(
            Point(minLat - padding, minLon - padding),
            Point(maxLat + padding, maxLon + padding)
        )
    }

    private fun calculateStats() {
        if (locationList.size < 2) {
            updateUI() // Обновляем UI даже если нет статистики
            return
        }

        totalDistance = 0.0
        totalTime = 0L
        maxSpeed = 0.0
        var totalSpeed = 0.0
        var validSegments = 0

        for (i in 1 until locationList.size) {
            val prev = locationList[i - 1]
            val curr = locationList[i]

            val timeDiff = (curr.timestamp - prev.timestamp) / 1000.0 // в секундах
            val distance = calculateDistance(
                prev.lat, prev.lng,
                curr.lat, curr.lng
            )

            if (timeDiff > 0 && distance > 0) {
                val speed = distance / timeDiff // м/с

                // ФИЛЬТРАЦИЯ: игнорируем сегменты с нереалистичной скоростью
                if (speed <= MAX_REALISTIC_SPEED) { // 100 км/ч
                    totalDistance += distance
                    totalTime += timeDiff.toLong()
                    totalSpeed += speed
                    validSegments++

                    if (speed > maxSpeed) {
                        maxSpeed = speed
                    }
                } else {
                    Log.w(TAG, "📊 Игнорируем нереалистичный сегмент: ${String.format("%.1f", speed * 3.6)} км/ч")
                }
            }
        }

        avgSpeed = if (validSegments > 0) totalSpeed / validSegments else 0.0

        // Дополнительное сглаживание максимальной скорости
        if (maxSpeed * 3.6 > 80) { // Если больше 80 км/ч - вероятно ошибка
            maxSpeed = avgSpeed * 1.5 // Ограничиваем до 150% от средней
            Log.d(TAG, "📊 Скорректирована максимальная скорость")
        }

        // Для велосипедиста дополнительно ограничиваем максимальную скорость
        if (maxSpeed * 3.6 > 60) { // Если больше 60 км/ч на велосипеде
            maxSpeed = min(maxSpeed, MAX_BIKE_SPEED)
            Log.d(TAG, "📊 Ограничена максимальная скорость для велосипеда")
        }

        updateUI()

        Log.d(TAG, "📊 Статистика: ${"%.1f".format(totalDistance)}м, ${"%.1f".format(avgSpeed * 3.6)}км/ч, макс: ${"%.1f".format(maxSpeed * 3.6)}км/ч")
    }

    private fun updateUI() {
        if (!isAdded) return

        val distanceKm = totalDistance / 1000
        val timeMinutes = totalTime / 60.0
        val avgSpeedKmh = avgSpeed * 3.6
        val maxSpeedKmh = maxSpeed * 3.6
        val calories = calculateCalories(distanceKm, timeMinutes / 60.0, avgSpeedKmh)

        activity?.runOnUiThread {
            tvDistance.text = "${String.format("%.2f", distanceKm)} км"
            tvTime.text = "${String.format("%.0f", timeMinutes)} мин"
            tvAvgSpeed.text = "${String.format("%.1f", avgSpeedKmh)} км/ч"
            tvMaxSpeed.text = "${String.format("%.1f", maxSpeedKmh)} км/ч"
            tvCalories.text = "${calories.toInt()} ккал"
        }
    }

    private fun calculateCalories(distanceKm: Double, timeHours: Double, speedKmh: Double): Double {
        val met = when {
            speedKmh < 5 -> 2.0   // Ходьба
            speedKmh < 15 -> 4.0  // Медленная езда на велосипеде
            speedKmh < 25 -> 6.0  // Средняя езда на велосипеде
            else -> 8.0           // Быстрая езда на велосипеде
        }
        return met * userWeight * timeHours
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
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
        Log.d(TAG, "🛑 Слушатель локаций остановлен")
    }

    private fun clearRoute() {
        try {
            polyline?.let { mapView.map.mapObjects.remove(it) }
            startMarker?.let { mapView.map.mapObjects.remove(it) }
            endMarker?.let { mapView.map.mapObjects.remove(it) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка очистки маршрута", e)
        } finally {
            polyline = null
            startMarker = null
            endMarker = null
        }
    }

    private fun clearUI() {
        totalDistance = 0.0
        totalTime = 0L
        maxSpeed = 0.0
        avgSpeed = 0.0
        routePoints.clear()
        locationList.clear()
        isFirstLoad = true

        updateUI()
        clearRoute()
        Log.d(TAG, "🎯 UI очищен")
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        MapKitFactory.getInstance().onStart()
        Log.d(TAG, "▶️ Фрагмент started")
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        Log.d(TAG, "⏸️ Фрагмент stopped")
    }

    override fun onDestroyView() {
        stopLocationListener()
        trackingStatusListener?.let { database.removeEventListener(it) }
        statsUpdateRunnable?.let { handler.removeCallbacks(it) }
        serviceScope.cancel()
        clearRoute()
        super.onDestroyView()
        Log.d(TAG, "🗑️ Фрагмент destroyed")
    }
}