package com.example.chatapp.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.example.chatapp.utils.Constants
import com.example.chatapp.utils.NotificationUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.math.*

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private val markers = mutableMapOf<String, PlacemarkMapObject>() // живёт только в рамках одного сеанса
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var myMarker: PlacemarkMapObject? = null
    private var myLocation: Point? = null
    private lateinit var cityTextView: TextView
    private lateinit var cityProgressBar: ProgressBar
    private var isFragmentDestroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var friendsButton: androidx.appcompat.widget.AppCompatImageButton
    private lateinit var findMeButton: androidx.appcompat.widget.AppCompatImageButton

    // Флаги для управления состоянием
    private var isFirstLocationLoad = true
    private var isCameraMovingToLocation = false
    private var lastLocationUpdateTime = 0L
    private var geocodingJob: Job? = null
    private var lastGeocodedPoint: Point? = null
    private var cameraListener: CameraListener? = null
    private val processingUsers = mutableSetOf<String>()
    private var locationEventListener: ValueEventListener? = null
    private var isMapViewInitialized = false

    companion object {
        private const val TAG = "MapFragment"
        private const val MIN_DISTANCE_FOR_UPDATE = 10.0
        private const val LOCATION_UPDATE_INTERVAL = 30000L
        private const val MAX_LOCATION_AGE_FOR_ONLINE = 5 * 60 * 1000L
        private const val AVATAR_SIZE = 120
        private const val MARKER_WIDTH = 150
        private const val MARKER_HEIGHT = 170
        private const val GEOLOCATION_TIMEOUT = 10000L
        private const val LOCATION_SEARCH_TIMEOUT = 15000L
        private const val CAMERA_MOVE_DURATION = 1.0f
        private const val DEBOUNCE_DELAY = 500L
        private const val MIN_DISTANCE_FOR_GEOCODING = 100.0

        // Ключи для устройства
        private const val DEVICE_MODEL = "deviceModel"
        private const val DEVICE_BRAND = "deviceBrand"
        private const val DEVICE_MANUFACTURER = "deviceManufacturer"

        private val CHINESE_MANUFACTURERS = setOf(
            "huawei", "honor", "xiaomi", "redmi", "oppo", "vivo",
            "realme", "oneplus", "zte", "lenovo", "meizu"
        )

        // Статические кэши ДАННЫХ (не UI!)
        object CacheManager {
            val userInfoCache = mutableMapOf<String, Pair<String, String?>>()
            val avatarBitmaps = mutableMapOf<String, Bitmap>()
            val friendshipCache = mutableMapOf<String, Boolean>()
            val pendingFriendRequests: MutableSet<String> = mutableSetOf()
            val userLocations = mutableMapOf<String, UserLocation>()
            val deviceInfoCache = mutableMapOf<String, String>()
            val tapListeners = mutableMapOf<String, MapObjectTapListener>()

            fun clearUser(userId: String) {
                userInfoCache.remove(userId)
                avatarBitmaps.remove(userId)
                friendshipCache.remove(userId)
                pendingFriendRequests.remove(userId)
                tapListeners.remove(userId)
                userLocations.remove(userId)
                deviceInfoCache.remove(userId)
            }

            // ❌ УДАЛЕНО: markerReferences и restoreMarkerReferences
        }

        private const val KEY_MY_LOCATION_LAT = "my_location_lat"
        private const val KEY_MY_LOCATION_LNG = "my_location_lng"
        private const val KEY_CAMERA_TARGET_LAT = "camera_target_lat"
        private const val KEY_CAMERA_TARGET_LNG = "camera_target_lng"
        private const val KEY_CAMERA_ZOOM = "camera_zoom"
    }

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            val lat = it.getDouble(KEY_MY_LOCATION_LAT, 0.0)
            val lng = it.getDouble(KEY_MY_LOCATION_LNG, 0.0)
            if (lat != 0.0 && lng != 0.0) {
                myLocation = Point(lat, lng)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_map_with_friends_button, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupMap()
        checkPermissionsAndLoad()
        setupButtons()
        isMapViewInitialized = true
        sendDeviceInfoToFirebase()
        // ❌ НЕ восстанавливаем маркеры — они не кешируются
    }

    private fun initViews(view: View) {
        mapView = view.findViewById(R.id.mapview)
        cityTextView = view.findViewById(R.id.cityTextView)
        cityProgressBar = view.findViewById(R.id.cityProgressBar)
        friendsButton = view.findViewById(R.id.friendsButton)
        findMeButton = view.findViewById(R.id.findMeButton)
        cityTextView.text = "Загрузка карты..."
        cityProgressBar.visibility = View.GONE
        findMeButton.visibility = View.VISIBLE
    }

    private fun setupButtons() {
        friendsButton.setOnClickListener {
            startActivity(Intent(requireContext(), FriendsListActivity::class.java))
        }
        findMeButton.setOnClickListener {
            centerCameraOnMyLocation()
        }
    }

    private fun checkPermissionsAndLoad() {
        if (hasLocationPermissions()) {
            loadInitialData()
        } else {
            showPermissionWarning()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val finePermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarsePermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Разрешения: FINE=$finePermission, COARSE=$coarsePermission")
        return finePermission || coarsePermission
    }

    private fun showPermissionWarning() {
        cityTextView.text = "Требуются разрешения на локацию"
        Toast.makeText(
            requireContext(),
            "Для работы карты требуются разрешения на доступ к местоположению",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setupMap() {
        mapView.map.isRotateGesturesEnabled = false
        mapView.map.isScrollGesturesEnabled = true
        mapView.map.isZoomGesturesEnabled = true
        mapView.map.isTiltGesturesEnabled = true

        val initialPoint = Point(55.7558, 37.6173)
        mapView.map.move(
            CameraPosition(initialPoint, 10.0f, 0.0f, 0.0f),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )

        setupCameraListener()

        geocodingJob = lifecycleScope.launch {
            delay(1500)
            if (isActive && isAdded && !isFragmentDestroyed) {
                updateLocationInfo(initialPoint)
            }
        }
    }

    private fun setupCameraListener() {
        cameraListener = object : CameraListener {
            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                Log.d(TAG, "onCameraPositionChanged: finished=$finished, reason=$cameraUpdateReason, target=${cameraPosition.target}")
                geocodingJob?.cancel()
                val delay = when (cameraUpdateReason) {
                    CameraUpdateReason.GESTURES -> DEBOUNCE_DELAY / 2
                    else -> DEBOUNCE_DELAY
                }
                geocodingJob = lifecycleScope.launch {
                    delay(delay)
                    if (isActive && isAdded && !isFragmentDestroyed) {
                        shouldUpdateLocationInfo(cameraPosition.target)
                    }
                }
            }
        }
        mapView.map.addCameraListener(cameraListener!!)
    }

    private fun loadInitialData() {
        Log.d(TAG, "Загрузка начальных данных...")
        loadMyLocationAndCenterCamera()
        loadUserLocations()
        startPeriodicUpdates()
    }

    private fun loadMyLocationAndCenterCamera() {
        val myUserId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val userLocation = withTimeout(LOCATION_SEARCH_TIMEOUT) {
                    loadMyLocationFromFirebase(myUserId)
                }
                if (userLocation != null && isValidLocation(userLocation)) {
                    Log.d(TAG, "Локация успешно загружена из Firebase")
                    myLocation = Point(userLocation.lat, userLocation.lng)
                    updateMyMarker()
                    centerCameraOnMyLocationWithAnimation()
                    isFirstLocationLoad = false
                } else {
                    Log.d(TAG, "Локация не найдена в Firebase, пробуем геолокацию")
                    tryGetDeviceLocation()
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Таймаут загрузки локации из Firebase")
                tryGetDeviceLocation()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки локации", e)
                tryGetDeviceLocation()
            }
        }
    }

    private suspend fun loadMyLocationFromFirebase(userId: String): UserLocation? {
        return try {
            val snapshot = database.child("last_locations").child(userId).get().await()
            snapshot.getValue(UserLocation::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки локации из Firebase", e)
            null
        }
    }

    private fun isValidLocation(location: UserLocation): Boolean {
        if (location.lat == 0.0 || location.lng == 0.0) return false
        return true
    }

    private fun tryGetDeviceLocation() {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "Нет разрешений для получения локации устройства")
            showPermissionWarning()
            return
        }
        val cameraPoint = mapView.map.cameraPosition.target
        myLocation = cameraPoint
        updateMyMarker()
        centerCameraOnMyLocationWithAnimation()
        Toast.makeText(
            requireContext(),
            "Используется последняя известная позиция",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun centerCameraOnMyLocationWithAnimation() {
        val location = myLocation ?: return
        isCameraMovingToLocation = true
        mapView.map.move(
            CameraPosition(location, 15.0f, 0.0f, 0.0f),
            Animation(Animation.Type.SMOOTH, CAMERA_MOVE_DURATION),
            null
        )
        handler.postDelayed({
            isCameraMovingToLocation = false
            geocodingJob?.cancel()
            geocodingJob = lifecycleScope.launch {
                delay(DEBOUNCE_DELAY)
                if (isActive && isAdded && !isFragmentDestroyed) {
                    updateLocationInfo(location)
                }
            }
            if (isFirstLocationLoad) {
                Toast.makeText(
                    requireContext(),
                    "Карта сфокусирована на вашем местоположении",
                    Toast.LENGTH_SHORT
                ).show()
                isFirstLocationLoad = false
            }
        }, (CAMERA_MOVE_DURATION * 1000).toLong())
    }

    private fun centerCameraOnMyLocation() {
        if (myLocation != null) {
            centerCameraOnMyLocationWithAnimation()
        } else {
            loadMyLocationAndCenterCamera()
            Toast.makeText(requireContext(), "Определяем ваше местоположение...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserLocations() {
        locationEventListener?.let {
            database.child("last_locations").removeEventListener(it)
        }

        locationEventListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFragmentDestroyed || !isAdded || !isMapViewInitialized) {
                    Log.w(TAG, "Фрагмент не активен, игнорируем обновление")
                    return
                }
                Log.d(TAG, "Получено ${snapshot.childrenCount} локаций")
                val currentUsers = HashSet<String>()
                val userLocations = mutableMapOf<String, UserLocation>()

                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key ?: continue
                    if (userId == auth.currentUser?.uid) continue

                    try {
                        val userLocation = userSnapshot.getValue(UserLocation::class.java)
                        if (userLocation == null || userLocation.lat == 0.0 || userLocation.lng == 0.0) {
                            Log.w(TAG, "Невалидная локация для пользователя $userId")
                            continue
                        }

                        currentUsers.add(userId)
                        CacheManager.userLocations[userId] = userLocation
                        userLocations[userId] = userLocation
                        loadDeviceInfo(userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки локации пользователя $userId", e)
                    }
                }

                // Очищаем старые маркеры (удаляем с карты!)
                removeOldMarkers(currentUsers)

                // Создаём или обновляем маркеры
                for ((userId, userLocation) in userLocations) {
                    if (processingUsers.contains(userId)) continue
                    processingUsers.add(userId)
                    lifecycleScope.launch {
                        try {
                            val point = convertCoordinatesIfNeeded(userId, userLocation.lat, userLocation.lng)
                            val isOnline = isUserOnline(userId)

                            // ВСЕГДА создаём новый маркер, если его нет на карте
                            if (markers.containsKey(userId)) {
                                updateExistingMarker(userId, point, isOnline)
                            } else {
                                createNewMarker(userId, point, isOnline)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка создания маркера для пользователя $userId", e)
                        } finally {
                            processingUsers.remove(userId)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки локаций пользователей: ${error.message}")
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Ошибка загрузки данных о пользователях",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        database.child("last_locations").addValueEventListener(locationEventListener!!)
    }

    private fun isUserOnline(userId: String): Boolean {
        val cachedLocation = CacheManager.userLocations[userId]
        if (cachedLocation == null) return false
        val locationAge = System.currentTimeMillis() - cachedLocation.timestamp
        return locationAge < MAX_LOCATION_AGE_FOR_ONLINE
    }

    // --- остальные вспомогательные функции без изменений ---
    // (sendDeviceInfoToFirebase, isChineseDevice, parseDeviceInfo, convertCoordinatesIfNeeded,
    // convertGCJ02ToWGS84, isInChina, transformLat, transformLon, loadDeviceInfo,
    // updateExistingMarker, createNewMarker, loadUserInfoAndUpdateMarker, loadUserInfo,
    // updateMarkerWithUserInfo, loadAvatarAndCreateMarker, updateMarkerIcon,
    // updateMarkerAppearance, updateMyMarker, onMarkerTapped, showUserDialog,
    // checkFriendshipStatus, showUserDialogWithOptions, addFriend, getCurrentUserInfo,
    // sendFriendNotification, showRemoveFriendConfirmation, removeFriend, openChat,
    // createChat, openChatActivity, openProfile, shouldUpdateLocationInfo, updateLocationInfo,
    // geocodeLocation, buildLocationName, startPeriodicUpdates, updateMyLocationInFirebase,
    // createPlaceholderBitmap, createMarkerBitmap, distanceBetween)

    // ⬇️ Вставьте сюда остальные функции из вашего исходного файла БЕЗ ИЗМЕНЕНИЙ ⬇️
    // Они не менялись, кроме как указано выше.

    // Пример (остальные — по аналогии):
    private fun sendDeviceInfoToFirebase() {
        val myUserId = auth.currentUser?.uid ?: return
        val deviceInfo = hashMapOf<String, Any?>(
            DEVICE_MODEL to Build.MODEL,
            DEVICE_BRAND to Build.BRAND,
            DEVICE_MANUFACTURER to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "timestamp" to System.currentTimeMillis()
        )
        database.child("device_info").child(myUserId).setValue(deviceInfo)
            .addOnSuccessListener {
                Log.d(TAG, "Информация об устройстве отправлена: $deviceInfo")
                val deviceInfoString = deviceInfo.entries.joinToString(";") { "${it.key}=${it.value}" }
                CacheManager.deviceInfoCache[myUserId] = deviceInfoString
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка отправки информации об устройстве", e)
            }
    }

    private fun isChineseDevice(deviceInfo: Map<String, String>?): Boolean {
        if (deviceInfo == null || deviceInfo.isEmpty()) return false
        val manufacturer = deviceInfo[DEVICE_MANUFACTURER]?.lowercase(Locale.getDefault())
        val brand = deviceInfo[DEVICE_BRAND]?.lowercase(Locale.getDefault())
        return CHINESE_MANUFACTURERS.any { chineseBrand ->
            manufacturer?.contains(chineseBrand) == true || brand?.contains(chineseBrand) == true
        }
    }

    private fun parseDeviceInfo(deviceInfoString: String): Map<String, String> {
        return try {
            deviceInfoString.split(";").associate {
                val parts = it.split("=")
                if (parts.size == 2) parts[0] to parts[1] else "" to ""
            }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun convertCoordinatesIfNeeded(userId: String, lat: Double, lng: Double): Point {
        val deviceInfoString = CacheManager.deviceInfoCache[userId]
        val deviceInfo = if (deviceInfoString != null) parseDeviceInfo(deviceInfoString) else null
        return if (deviceInfo != null && isChineseDevice(deviceInfo)) {
            val converted = convertGCJ02ToWGS84(lat, lng)
            Log.d(TAG, "Конвертированы координаты для пользователя $userId: $lat,$lng -> ${converted.latitude},${converted.longitude}")
            converted
        } else {
            Point(lat, lng)
        }
    }

    private fun convertGCJ02ToWGS84(gcjLat: Double, gcjLon: Double): Point {
        if (!isInChina(gcjLat, gcjLon)) {
            return Point(gcjLat, gcjLon)
        }
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(gcjLon - 105.0, gcjLat - 35.0)
        var dLon = transformLon(gcjLon - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * Math.PI)
        val wgsLat = gcjLat - dLat
        val wgsLon = gcjLon - dLon
        return Point(wgsLat, wgsLon)
    }

    private fun isInChina(lat: Double, lon: Double): Boolean {
        return (lat in 0.8293..55.8271 && lon in 72.004..135.05)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private fun loadDeviceInfo(userId: String) {
        if (CacheManager.deviceInfoCache.containsKey(userId)) {
            return
        }
        database.child("device_info").child(userId).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    try {
                        val deviceInfoString = buildString {
                            snapshot.children.forEach { child ->
                                val key = child.key ?: return@forEach
                                val value = child.value?.toString() ?: ""
                                append("$key=$value;")
                            }
                        }.removeSuffix(";")
                        if (deviceInfoString.isNotEmpty()) {
                            CacheManager.deviceInfoCache[userId] = deviceInfoString
                            Log.d(TAG, "Загружена информация об устройстве пользователя $userId: $deviceInfoString")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга информации об устройстве пользователя $userId", e)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка загрузки информации об устройстве пользователя $userId", e)
            }
    }

    private fun updateExistingMarker(userId: String, point: Point, isOnline: Boolean) {
        markers[userId]?.let { marker ->
            val oldPoint = marker.geometry
            if (distanceBetween(oldPoint, point) > MIN_DISTANCE_FOR_UPDATE) {
                marker.geometry = point
                Log.d(TAG, "Обновлена позиция маркера $userId")
            }
            updateMarkerAppearance(marker, userId, isOnline)
        }
    }

    private fun createNewMarker(userId: String, point: Point, isOnline: Boolean) {
        if (markers.containsKey(userId)) {
            updateExistingMarker(userId, point, isOnline)
            return
        }
        Log.d(TAG, "Создание нового маркера для пользователя $userId в точке $point")
        val placemark = mapView.map.mapObjects.addPlacemark(point)
        placemark.zIndex = 100f
        markers[userId] = placemark

        val placeholder = createPlaceholderBitmap("?", isOnline)
        placemark.setIcon(ImageProvider.fromBitmap(placeholder))

        val tapListener = MapObjectTapListener { _, _ ->
            onMarkerTapped(userId)
            true
        }
        placemark.addTapListener(tapListener)
        CacheManager.tapListeners[userId] = tapListener

        loadUserInfoAndUpdateMarker(userId, placemark, isOnline)
    }

    private fun loadUserInfoAndUpdateMarker(userId: String, placemark: PlacemarkMapObject, isOnline: Boolean) {
        lifecycleScope.launch {
            try {
                val (name, avatarUrl) = loadUserInfo(userId)
                updateMarkerWithUserInfo(userId, name, avatarUrl, isOnline, placemark)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки информации пользователя $userId", e)
                updateMarkerWithUserInfo(userId, "Пользователь", null, isOnline, placemark)
            }
        }
    }

    private suspend fun loadUserInfo(userId: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        CacheManager.userInfoCache[userId]?.let {
            return@withContext it
        }
        return@withContext try {
            val snapshot = database.child("users").child(userId).get().await()
            val user = snapshot.getValue(User::class.java)
            val name = user?.getFullName() ?: "Пользователь"
            val avatarUrl = user?.profileImageUrl
            val userInfo = Pair(name, avatarUrl)
            CacheManager.userInfoCache[userId] = userInfo
            userInfo
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки профиля $userId из Firebase", e)
            Pair("Пользователь", null)
        }
    }

    private fun updateMarkerWithUserInfo(
        userId: String,
        userName: String,
        avatarUrl: String?,
        isOnline: Boolean,
        placemark: PlacemarkMapObject
    ) {
        if (!isAdded || isFragmentDestroyed) return
        if (avatarUrl != null && avatarUrl.isNotEmpty()) {
            loadAvatarAndCreateMarker(userId, userName, avatarUrl, isOnline, placemark)
        } else {
            val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
            updateMarkerIcon(placemark, placeholder)
        }
    }

    private fun loadAvatarAndCreateMarker(
        userId: String,
        userName: String,
        avatarUrl: String,
        isOnline: Boolean,
        placemark: PlacemarkMapObject
    ) {
        CacheManager.avatarBitmaps[userId]?.let { cachedBitmap ->
            val markerBitmap = createMarkerBitmap(cachedBitmap, userName, isOnline)
            updateMarkerIcon(placemark, markerBitmap)
            return
        }
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        Glide.with(this@MapFragment)
                            .asBitmap()
                            .load(avatarUrl)
                            .timeout(10000)
                            .error(R.drawable.ic_default_profile)
                            .placeholder(R.drawable.ic_default_profile)
                            .submit(AVATAR_SIZE, AVATAR_SIZE)
                            .get()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null && isAdded && !isFragmentDestroyed) {
                    CacheManager.avatarBitmaps[userId] = bitmap
                    val markerBitmap = createMarkerBitmap(bitmap, userName, isOnline)
                    updateMarkerIcon(placemark, markerBitmap)
                } else {
                    val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
                    updateMarkerIcon(placemark, placeholder)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки аватара $userId", e)
                val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
                updateMarkerIcon(placemark, placeholder)
            }
        }
    }

    private fun updateMarkerIcon(placemark: PlacemarkMapObject, bitmap: Bitmap) {
        try {
            placemark.setIcon(
                ImageProvider.fromBitmap(bitmap),
                IconStyle().apply {
                    anchor = android.graphics.PointF(0.5f, 0.5f)
                    scale = 1.0f
                    zIndex = 100.0f
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления иконки маркера", e)
        }
    }

    private fun updateMarkerAppearance(marker: PlacemarkMapObject, userId: String, isOnline: Boolean) {
        CacheManager.userInfoCache[userId]?.let { (name, avatarUrl) ->
            if (avatarUrl != null && avatarUrl.isNotEmpty()) {
                CacheManager.avatarBitmaps[userId]?.let { avatar ->
                    val newBitmap = createMarkerBitmap(avatar, name, isOnline)
                    updateMarkerIcon(marker, newBitmap)
                }
            }
        }
    }

    private fun updateMyMarker() {
        val point = myLocation ?: return
        val myUserId = auth.currentUser?.uid ?: return
        if (myMarker == null) {
            myMarker = mapView.map.mapObjects.addPlacemark(point)
            myMarker?.zIndex = 200f
            lifecycleScope.launch {
                try {
                    val (name, avatarUrl) = loadUserInfo(myUserId)
                    if (avatarUrl != null && avatarUrl.isNotEmpty()) {
                        loadAvatarAndCreateMarker(myUserId, name, avatarUrl, true, myMarker!!)
                    } else {
                        val placeholder = createPlaceholderBitmap(name.take(1), true)
                        updateMarkerIcon(myMarker!!, placeholder)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка загрузки своего аватара", e)
                }
            }
            myMarker?.addTapListener { _, _ ->
                Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            myMarker?.geometry = point
        }
    }

    private fun removeOldMarkers(currentUsers: Set<String>) {
        val toRemove = markers.keys.filter { userId ->
            val isCurrentUser = userId == auth.currentUser?.uid
            !isCurrentUser && !currentUsers.contains(userId) && !processingUsers.contains(userId)
        }
        for (userId in toRemove) {
            markers[userId]?.let { marker ->
                mapView.map.mapObjects.remove(marker)
            }
            markers.remove(userId)
            CacheManager.tapListeners.remove(userId)
            Log.d(TAG, "Удалён маркер $userId с карты")
        }
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Удалено ${toRemove.size} маркеров с карты")
        }
    }

    private fun onMarkerTapped(userId: String) {
        if (userId == auth.currentUser?.uid) {
            Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
            return
        }
        showUserDialog(userId)
    }

    private fun showUserDialog(userId: String) {
        val userInfo = CacheManager.userInfoCache[userId]
        val name = userInfo?.first ?: "Пользователь"
        val location = CacheManager.userLocations[userId]
        val lastSeen = location?.let {
            val timeAgo = System.currentTimeMillis() - it.timestamp
            when {
                timeAgo < 5 * 60 * 1000L -> "был(а) только что"
                timeAgo < 60 * 60 * 1000L -> "был(а) ${timeAgo / (60 * 1000)} мин назад"
                timeAgo < 24 * 60 * 60 * 1000L -> "был(а) ${timeAgo / (60 * 60 * 1000)} час назад"
                else -> "был(а) давно"
            }
        } ?: "давно не был(а)"

        checkFriendshipStatus(userId) { isFriend ->
            activity?.runOnUiThread {
                if (isAdded && !isFragmentDestroyed) {
                    showUserDialogWithOptions(userId, name, isFriend, lastSeen)
                }
            }
        }
    }

    private fun checkFriendshipStatus(friendId: String, callback: (Boolean) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return callback(false)
        CacheManager.friendshipCache[friendId]?.let {
            callback(it)
            return
        }
        database.child("users").child(currentUserId).child("friends").child(friendId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isFriend = snapshot.exists()
                    CacheManager.friendshipCache[friendId] = isFriend
                    callback(isFriend)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка проверки статуса дружбы", error.toException())
                    callback(false)
                }
            })
    }

    private fun showUserDialogWithOptions(userId: String, name: String, isFriend: Boolean, lastSeen: String) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(name)
            .setMessage("$lastSeen\nВыберите действие")
            .setCancelable(true)

        if (isFriend) {
            builder.setPositiveButton("💬 Написать сообщение") { _, _ -> openChat(userId) }
                .setNeutralButton("👤 Профиль") { _, _ -> openProfile(userId) }
                .setNegativeButton("❌ Удалить") { _, _ -> showRemoveFriendConfirmation(userId) }
        } else {
            builder.setPositiveButton("💬 Написать") { _, _ -> openChat(userId) }
                .setNeutralButton("👤 Профиль") { _, _ -> openProfile(userId) }
                .setNegativeButton("➕ Добавить") { _, _ ->
                    if (!CacheManager.pendingFriendRequests.contains(userId)) {
                        addFriend(userId)
                    } else {
                        Toast.makeText(requireContext(), "Запрос уже отправлен", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        builder.show()
    }

    private fun addFriend(friendId: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        if (currentUserId == friendId) {
            Toast.makeText(requireContext(), "Нельзя добавить себя в друзья", Toast.LENGTH_SHORT).show()
            return
        }
        CacheManager.pendingFriendRequests.add(friendId)
        getCurrentUserInfo { currentUserName, currentUserAvatarUrl ->
            val updates = hashMapOf<String, Any>(
                "users/$currentUserId/friends/$friendId" to true,
                "users/$friendId/friends/$currentUserId" to true
            )
            database.updateChildren(updates)
                .addOnSuccessListener {
                    CacheManager.friendshipCache[friendId] = true
                    sendFriendNotification(friendId, currentUserName)
                    Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()
                    CacheManager.pendingFriendRequests.remove(friendId)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    CacheManager.pendingFriendRequests.remove(friendId)
                }
        }
    }

    private fun getCurrentUserInfo(callback: (String, String?) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return callback("Пользователь", null)
        CacheManager.userInfoCache[currentUserId]?.let {
            callback(it.first, it.second)
            return
        }
        database.child("users").child(currentUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName() ?: "Пользователь"
                    val avatarUrl = user?.profileImageUrl
                    CacheManager.userInfoCache[currentUserId] = Pair(name, avatarUrl)
                    callback(name, avatarUrl)
                }
                override fun onCancelled(error: DatabaseError) {
                    callback("Пользователь", null)
                }
            })
    }

    private fun sendFriendNotification(friendId: String, requesterName: String) {
        NotificationUtils.sendFriendRequestNotification(requireContext(), friendId, requesterName)
    }

    private fun showRemoveFriendConfirmation(friendId: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удаление из друзей")
            .setMessage("Вы уверены, что хотите удалить пользователя из друзей?")
            .setPositiveButton("Удалить") { _, _ -> removeFriend(friendId) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun removeFriend(friendId: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        val updates = hashMapOf<String, Any?>(
            "users/$currentUserId/friends/$friendId" to null,
            "users/$friendId/friends/$currentUserId" to null
        )
        database.updateChildren(updates)
            .addOnSuccessListener {
                CacheManager.friendshipCache[friendId] = false
                Toast.makeText(requireContext(), "Пользователь удален из друзей", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openChat(userId: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        if (userId == currentUserId) {
            Toast.makeText(requireContext(), "Нельзя написать самому себе", Toast.LENGTH_SHORT).show()
            return
        }
        val chatId = if (currentUserId < userId) "$currentUserId-$userId" else "$userId-$currentUserId"
        database.child("chats").child(chatId).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                openChatActivity(chatId)
            } else {
                createChat(chatId, userId, currentUserId)
            }
        }.addOnFailureListener {
            Toast.makeText(requireContext(), "Ошибка доступа к чатам", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createChat(chatId: String, friendId: String, currentUserId: String) {
        database.child("users").child(friendId).get().addOnSuccessListener { friendSnapshot ->
            val friend = friendSnapshot.getValue(User::class.java)
            val friendName = friend?.getFullName() ?: "Пользователь"
            val friendAvatar = friend?.profileImageUrl ?: ""
            database.child("users").child(currentUserId).get().addOnSuccessListener { mySnapshot ->
                val me = mySnapshot.getValue(User::class.java)
                val myName = me?.getFullName() ?: "Я"
                val myAvatar = me?.profileImageUrl ?: ""
                val chatData = hashMapOf<String, Any>(
                    "id" to chatId,
                    "participants" to mapOf<String, Boolean>(currentUserId to true, friendId to true),
                    "createdAt" to System.currentTimeMillis(),
                    "lastMessage" to "",
                    "lastMessageTime" to 0L,
                    "name" to friendName,
                    "avatarUrl" to friendAvatar,
                    "creatorId" to currentUserId,
                    "creatorName" to myName,
                    "imageUrl" to myAvatar
                )
                database.child("chats").child(chatId).setValue(chatData)
                    .addOnSuccessListener {
                        openChatActivity(chatId)
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "Ошибка создания чата", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    private fun openChatActivity(chatId: String) {
        val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
        }
        startActivity(intent)
    }

    private fun openProfile(userId: String) {
        val intent = Intent(requireContext(), UserProfileActivity::class.java).apply {
            putExtra("USER_ID", userId)
        }
        startActivity(intent)
    }

    private fun shouldUpdateLocationInfo(point: Point) {
        val lastPoint = lastGeocodedPoint
        if (lastPoint == null || distanceBetween(lastPoint, point) > MIN_DISTANCE_FOR_GEOCODING) {
            updateLocationInfo(point)
        } else {
            Log.d(TAG, "Расстояние слишком мало для обновления адреса")
        }
    }

    private fun updateLocationInfo(point: Point) {
        if (!isAdded || isFragmentDestroyed) {
            Log.d(TAG, "Фрагмент не активен, пропускаем обновление адреса")
            return
        }
        Log.d(TAG, "Обновление адреса для точки: $point")
        cityProgressBar.visibility = View.VISIBLE
        cityTextView.text = "Определение адреса..."
        lifecycleScope.launch {
            try {
                withTimeout(GEOLOCATION_TIMEOUT) {
                    val locationName = geocodeLocation(point)
                    withContext(Dispatchers.Main) {
                        if (isAdded && !isFragmentDestroyed) {
                            cityProgressBar.visibility = View.GONE
                            cityTextView.text = locationName
                            lastGeocodedPoint = point
                            Log.d(TAG, "Адрес успешно обновлен: $locationName")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                withContext(Dispatchers.Main) {
                    if (isAdded && !isFragmentDestroyed) {
                        cityProgressBar.visibility = View.GONE
                        cityTextView.text = String.format(
                            Locale.getDefault(),
                            "Ш: %.6f, Д: %.6f",
                            point.latitude,
                            point.longitude
                        )
                        lastGeocodedPoint = point
                        Log.w(TAG, "Таймаут геокодирования для точки: $point")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded && !isFragmentDestroyed) {
                        cityProgressBar.visibility = View.GONE
                        cityTextView.text = String.format(
                            Locale.getDefault(),
                            "Ш: %.6f, Д: %.6f",
                            point.latitude,
                            point.longitude
                        )
                        lastGeocodedPoint = point
                        Log.e(TAG, "Ошибка геокодирования для точки: $point", e)
                    }
                }
            }
        }
    }

    private suspend fun geocodeLocation(point: Point): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Начало геокодирования для точки: $point")
        return@withContext try {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
            Log.d(TAG, "Получено адресов: ${addresses?.size ?: 0}")
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val result = buildLocationName(address)
                Log.d(TAG, "Результат геокодирования: $result")
                result
            } else {
                val result = String.format(
                    Locale.getDefault(),
                    "Ш: %.6f, Д: %.6f",
                    point.latitude,
                    point.longitude
                )
                Log.d(TAG, "Нет адресов, возвращаем координаты: $result")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Исключение при геокодировании", e)
            throw e
        }
    }

    private fun buildLocationName(address: android.location.Address): String {
        val parts = mutableListOf<String>()
        address.thoroughfare?.let { street ->
            address.subThoroughfare?.let { number ->
                parts.add("$street, $number")
            } ?: parts.add(street)
        }
        address.locality?.let { parts.add(it) }
        address.countryName?.let { parts.add(it) }
        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            String.format(
                Locale.getDefault(),
                "Ш: %.6f, Д: %.6f",
                address.latitude,
                address.longitude
            )
        }
    }

    private fun startPeriodicUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isAdded && !isFragmentDestroyed) {
                    updateMyLocationInFirebase()
                    handler.postDelayed(this, LOCATION_UPDATE_INTERVAL)
                }
            }
        }, LOCATION_UPDATE_INTERVAL)
    }

    private fun updateMyLocationInFirebase() {
        val myUserId = auth.currentUser?.uid ?: return
        val point = myLocation ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLocationUpdateTime < LOCATION_UPDATE_INTERVAL / 2) {
            return
        }
        val userLocation = UserLocation(
            lat = point.latitude,
            lng = point.longitude,
            timestamp = currentTime
        )
        database.child("last_locations").child(myUserId).setValue(userLocation)
            .addOnSuccessListener {
                lastLocationUpdateTime = currentTime
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка обновления своей локации", e)
            }
    }

    private fun createPlaceholderBitmap(initial: String, isOnline: Boolean): Bitmap {
        val size = AVATAR_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val backgroundColor = when {
            isOnline -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#757575")
        }
        paint.color = backgroundColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, paint)
        paint.color = Color.WHITE
        paint.textSize = size / 3f
        paint.textAlign = Paint.Align.CENTER
        val text = if (initial.isNotEmpty()) initial.first().toString().uppercase() else "?"
        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)
        val textY = size / 2f + (textBounds.height() / 2f) - textBounds.bottom
        canvas.drawText(text, size / 2f, textY, paint)
        return bitmap
    }

    private fun createMarkerBitmap(avatar: Bitmap, name: String, isOnline: Boolean): Bitmap {
        val avatarSize = AVATAR_SIZE
        val width = MARKER_WIDTH
        val height = MARKER_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)
        paint.setShadowLayer(6f, 3f, 3f, Color.argb(150, 0, 0, 0))
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)
        paint.clearShadowLayer()
        val avatarRect = Rect(
            (width - avatarSize) / 2,
            15,
            (width + avatarSize) / 2,
            15 + avatarSize
        )
        canvas.drawBitmap(avatar, null, avatarRect, paint)
        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        val displayName = if (name.length > 12) "${name.substring(0, 10)}.." else name
        val textY = 15 + avatarSize + 35
        canvas.drawText(displayName, width / 2f, textY.toFloat(), paint)
        paint.color = if (isOnline) Color.GREEN else Color.GRAY
        paint.style = Paint.Style.FILL
        val indicatorX = (width + avatarSize) / 2f - 15
        val indicatorY = 15f + 15
        canvas.drawCircle(indicatorX, indicatorY, 8f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(indicatorX, indicatorY, 8f, paint)
        return bitmap
    }

    private fun distanceBetween(p1: Point, p2: Point): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        myLocation?.let {
            outState.putDouble(KEY_MY_LOCATION_LAT, it.latitude)
            outState.putDouble(KEY_MY_LOCATION_LNG, it.longitude)
        }
        val cameraPosition = mapView.map.cameraPosition
        outState.putDouble(KEY_CAMERA_TARGET_LAT, cameraPosition.target.latitude)
        outState.putDouble(KEY_CAMERA_TARGET_LNG, cameraPosition.target.longitude)
        outState.putFloat(KEY_CAMERA_ZOOM, cameraPosition.zoom)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        MapKitFactory.getInstance().onStart()
        isMapViewInitialized = true
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        isMapViewInitialized = false
    }

    override fun onResume() {
        super.onResume()
        isFragmentDestroyed = false
        isMapViewInitialized = true
        if (hasLocationPermissions()) {
            geocodingJob?.cancel()
            geocodingJob = lifecycleScope.launch {
                delay(1000)
                if (isActive && isAdded && !isFragmentDestroyed) {
                    val point = mapView.map.cameraPosition.target
                    Log.d(TAG, "onResume: обновление адреса для точки $point")
                    updateLocationInfo(point)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        geocodingJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        isMapViewInitialized = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        isMapViewInitialized = false
        geocodingJob?.cancel()

        cameraListener?.let {
            mapView.map.removeCameraListener(it)
            cameraListener = null
        }

        // ✅ Удаляем ВСЕ маркеры с карты
        for (marker in markers.values) {
            mapView.map.mapObjects.remove(marker)
        }
        markers.clear()
        myMarker?.let { mapView.map.mapObjects.remove(it) }
        myMarker = null

        // Удаляем слушателя Firebase
        locationEventListener?.let {
            database.child("last_locations").removeEventListener(it)
            locationEventListener = null
        }

        processingUsers.clear()

        // ❌ НЕ очищаем кэши данных!
    }

    override fun onDestroy() {
        super.onDestroy()
        geocodingJob?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDetach() {
        super.onDetach()
        // ❌ НЕ очищаем кэши
    }
}