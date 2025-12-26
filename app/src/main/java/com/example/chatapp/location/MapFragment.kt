package com.example.chatapp.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.example.chatapp.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationManager
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private val markers = mutableMapOf<String, PlacemarkMapObject>()
    private val lastActiveTime = mutableMapOf<String, Long>()
    private val isOnline = mutableMapOf<String, Boolean>()
    private val userInfoCache = mutableMapOf<String, UserInfo?>()
    private val database = FirebaseDatabase.getInstance().reference
    private val auth = FirebaseAuth.getInstance()
    private var myMarker: PlacemarkMapObject? = null
    private var myLocation: Point? = null
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val geocoderExecutor = Executors.newFixedThreadPool(1)
    private val onlineHandler = Handler(Looper.getMainLooper())
    private val onlineRunnable = object : Runnable {
        override fun run() {
            updateMyOnlineStatus()
            checkOtherUsersOnlineStatus()
            onlineHandler.postDelayed(this, 30000)
        }
    }
    private var lastCameraUpdateTime: Long = 0L
    private var lastRequestedPoint: Point? = null
    private lateinit var searchManager: com.yandex.mapkit.search.SearchManager
    private var searchSession: Session? = null
    private val avatarCache = mutableMapOf<String, Bitmap>()
    private val tapListeners = mutableMapOf<String, MapObjectTapListener>()
    data class UserInfo(val name: String?, val avatarUrl: String?)
    private lateinit var cityTextView: TextView
    private lateinit var cityProgressBar: ProgressBar
    private var lastCityPoint: Point? = null
    private var lastCityName: String? = null
    private val cityUpdateHandler = Handler(Looper.getMainLooper())
    private var isUpdatingCity = false
    private var locationsValueEventListener: ValueEventListener? = null
    private var isFragmentDestroyed = false
    private val locationRestartHandler = Handler(Looper.getMainLooper())
    private val locationRestartRunnable = object : Runnable {
        override fun run() {
            if (!isFragmentDestroyed && isAdded) {
                restartLocationUpdates()
                locationRestartHandler.postDelayed(this, 60000)
            }
        }
    }
    private val locationUpdateHandler = Handler(Looper.getMainLooper())
    private val locationUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isFragmentDestroyed && isAdded) {
                val currentPoint = mapView.map.cameraPosition.target
                reverseGeocode(currentPoint)
                checkTouchAvailability()
            }
            locationUpdateHandler.postDelayed(this, 45000)
        }
    }
    private val geocodeHandler = Handler(Looper.getMainLooper())
    private var geocodeRunnable: Runnable? = null
    private var lastGeocodePoint: Point? = null
    private val markerUpdateTimes = mutableMapOf<String, Long>()
    private val MARKER_UPDATE_THROTTLE = 5000L
    private var isMyAvatarLoading = false
    private var lastMyAvatarLoadTime: Long = 0
    private val MY_AVATAR_LOAD_THROTTLE = 5000L
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUserDataLoadTime = 0L
    private val USER_DATA_LOAD_THROTTLE = 5000L

    companion object {
        private const val MIN_MOVE_DISTANCE = 50.0
        private const val MIN_MOVE_DISTANCE_FOR_UPDATE = 10.0
    }

    private var shouldReloadData = true
    private val lastReceivedLocation = mutableMapOf<String, UserLocation>()

    // Объявляем кнопки как ImageButton
    private lateinit var friendsButton: androidx.appcompat.widget.AppCompatImageButton
    private lateinit var findMeButton: androidx.appcompat.widget.AppCompatImageButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layout = inflater.inflate(R.layout.fragment_map_with_friends_button, container, false)
        cityTextView = layout.findViewById(R.id.cityTextView)
        cityProgressBar = layout.findViewById(R.id.cityProgressBar)
        cityTextView.text = ""
        cityProgressBar.visibility = View.GONE

        // Инициализация кнопок с правильными типами
        friendsButton = layout.findViewById(R.id.friendsButton)
        findMeButton = layout.findViewById(R.id.findMeButton)

        // Настраиваем видимость и обработчики
        findMeButton.visibility = View.VISIBLE
        friendsButton.setOnClickListener {
            startActivity(Intent(requireContext(), FriendsListActivity::class.java))
        }
        findMeButton.setOnClickListener {
            centerCameraOnMyLocation()
        }

        mapView = layout.findViewById(R.id.mapview)
        mapView.map.isRotateGesturesEnabled = false

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                observeUserLocations()
                fixTouchInterception()
            }
        }, 1000)

        return layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapView = view.findViewById(R.id.mapview)
        cityTextView = view.findViewById(R.id.cityTextView)
        cityProgressBar = view.findViewById(R.id.cityProgressBar)

        // Инициализация кнопок в onViewCreated
        friendsButton = view.findViewById(R.id.friendsButton)
        findMeButton = view.findViewById(R.id.findMeButton)

        mapView.map.isRotateGesturesEnabled = false
        mapView.map.isScrollGesturesEnabled = true
        mapView.map.isZoomGesturesEnabled = true
        mapView.map.isTiltGesturesEnabled = true

        searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)

        val initialPoint = mapView.map.cameraPosition.target
        val coordinatesText = String.format(Locale.getDefault(),
            "Ш: %.4f, Д: %.4f", initialPoint.latitude, initialPoint.longitude)
        cityTextView.text = coordinatesText

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                val currentPoint = mapView.map.cameraPosition.target
                reverseGeocode(currentPoint)
                setupNormalCameraUpdates()
            }
        }, 8000)

        setupMapTouchHandling()
    }

    override fun onResume() {
        super.onResume()
        isFragmentDestroyed = false
        locationUpdateHandler.postDelayed(locationUpdateRunnable, 15000)
        locationRestartHandler.postDelayed(locationRestartRunnable, 45000)
        fixTouchInterception()
        checkLocationPermissionsAndStart()

        ioScope.launch {
            delay(100)
            withContext(Dispatchers.Main) {
                if (!isFragmentDestroyed && isAdded) {
                    if (shouldReloadData) {
                        reloadAllMarkers()
                        shouldReloadData = false
                    }
                }
            }
            delay(500)
            withContext(Dispatchers.Main) {
                if (!isFragmentDestroyed && isAdded) {
                    setupNormalCameraUpdates()
                }
            }
            delay(2000)
            withContext(Dispatchers.Main) {
                if (!isFragmentDestroyed && isAdded) {
                    val currentPoint = mapView.map.cameraPosition.target
                    reverseGeocode(currentPoint)
                }
            }
        }

        handler.postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                loadUserDataWithThrottle()
            }
        }, 1000)
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${requireContext().packageName}")
        }
        startActivity(intent)
    }

    private fun checkLocationPermissionsAndStart() {
        val finePermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val backgroundPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (finePermission) {
            if (backgroundPermission) {
                startLocationTracking()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("Требуется разрешение")
                    .setMessage("Для постоянного отслеживания местоположения необходимо разрешить доступ к геопозиции в фоне. Перейдите в настройки приложения.")
                    .setPositiveButton("Настройки") { _, _ -> openAppSettings() }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val backgroundPermission = ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!backgroundPermission) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Фоновое местоположение")
                            .setMessage("Приложению нужно разрешение на определение местоположения в фоне для корректной работы. Перейдите в настройки приложения.")
                            .setPositiveButton("Настройки") { _, _ -> openAppSettings() }
                            .setNegativeButton("Позже", null)
                            .show()
                    } else {
                        startLocationTracking()
                    }
                } else {
                    startLocationTracking()
                }
            } else {
                Toast.makeText(requireContext(), "Разрешение на местоположение необходимо", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserDataWithThrottle() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUserDataLoadTime < USER_DATA_LOAD_THROTTLE) {
            return
        }
        lastUserDataLoadTime = currentTime
        ioScope.launch {
            try {
                loadEssentialUserData()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private suspend fun loadEssentialUserData() {
        val currentUserId = auth.currentUser?.uid ?: return
        try {
            val userSnapshot = withContext(Dispatchers.IO) {
                database.child("users").child(currentUserId).get().await()
            }
            withContext(Dispatchers.Main) {
                if (!isFragmentDestroyed && isAdded) {
                    updateUserProfileIfNeeded(userSnapshot)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateUserProfileIfNeeded(snapshot: DataSnapshot) {
        try {
            val user = snapshot.getValue(User::class.java)
            user?.let {
                // profile loaded
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun setupMapTouchHandling() {
        mapView.isClickable = true
        mapView.isFocusable = true
        mapView.isFocusableInTouchMode = true
        mapView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        // Устанавливаем флаги для кнопок, чтобы они не перехватывали тапы
        friendsButton.isFocusable = false
        friendsButton.isFocusableInTouchMode = false
        findMeButton.isFocusable = false
        findMeButton.isFocusableInTouchMode = false

        mapView.map.addInputListener(object : com.yandex.mapkit.map.InputListener {
            override fun onMapTap(map: Map, point: Point) {
                scheduleGeocode(point)
            }

            override fun onMapLongTap(map: Map, point: Point) {
                // ignore
            }
        })
        mapView.setOnTouchListener { _, _ -> false }
    }

    private fun scheduleGeocode(point: Point) {
        geocodeRunnable?.let { geocodeHandler.removeCallbacks(it) }
        geocodeRunnable = Runnable {
            if (!isFragmentDestroyed && isAdded) {
                reverseGeocode(point)
            }
        }
        geocodeHandler.postDelayed(geocodeRunnable!!, 500)
    }

    private fun fixTouchInterception() {
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isFragmentDestroyed || !isAdded) return@postDelayed
                findAndFixTouchInterceptors(mapView)
            }, 2000)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun findAndFixTouchInterceptors(root: View) {
        try {
            val interceptors = mutableListOf<View>()
            fun traverseViewHierarchy(view: View, depth: Int = 0) {
                if (view.visibility != View.VISIBLE) return
                // Пропускаем наши кнопки и элементы управления
                if (view.id == R.id.friendsButton || view.id == R.id.findMeButton ||
                    view.id == R.id.cityTextView || view.id == R.id.cityProgressBar ||
                    view.id == R.id.cityContainer || view.id == R.id.buttonsContainer) {
                    return
                }
                if (view.isClickable || view.isFocusable || view.isFocusableInTouchMode) {
                    if (view != mapView && isViewOverMap(view)) {
                        interceptors.add(view)
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        traverseViewHierarchy(view.getChildAt(i), depth + 1)
                    }
                }
            }
            traverseViewHierarchy(root)
            interceptors.forEach { view ->
                try {
                    view.isClickable = false
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    view.isEnabled = false
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun isViewOverMap(view: View): Boolean {
        try {
            val mapLocation = IntArray(2)
            val viewLocation = IntArray(2)
            mapView.getLocationOnScreen(mapLocation)
            view.getLocationOnScreen(viewLocation)
            val mapRect = Rect(
                mapLocation[0], mapLocation[1],
                mapLocation[0] + mapView.width, mapLocation[1] + mapView.height
            )
            val viewRect = Rect(
                viewLocation[0], viewLocation[1],
                viewLocation[0] + view.width, viewLocation[1] + view.height
            )
            return mapRect.intersect(viewRect)
        } catch (e: Exception) {
            return false
        }
    }

    private fun checkTouchAvailability() {
        try {
            val point = mapView.map.cameraPosition.target
            if (System.currentTimeMillis() - lastCameraUpdateTime > 120000) {
                fixTouchInterception()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun setupNormalCameraUpdates() {
        mapView.map.addCameraListener(object : CameraListener {
            private var cameraUpdateHandler = Handler(Looper.getMainLooper())
            private var cameraUpdateRunnable: Runnable? = null

            override fun onCameraPositionChanged(
                map: Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (finished && !isFragmentDestroyed && isAdded) {
                    cameraUpdateRunnable?.let { cameraUpdateHandler.removeCallbacks(it) }
                    cameraUpdateRunnable = Runnable {
                        if (!isFragmentDestroyed && isAdded) {
                            val target = cameraPosition.target
                            scheduleGeocode(target)
                        }
                    }
                    cameraUpdateHandler.postDelayed(cameraUpdateRunnable!!, 2000)
                }
            }
        })
    }

    private fun centerCameraOnMyLocation() {
        if (myLocation != null) {
            mapView.map.move(
                CameraPosition(myLocation!!, 15.0f, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 0.5f),
                null
            )
            handler.postDelayed({
                if (!isFragmentDestroyed && isAdded) {
                    reverseGeocode(myLocation!!)
                }
            }, 2000)
            return
        }

        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Пользователь не авторизован", Toast.LENGTH_SHORT).show()
            return
        }

        // Показываем, что ищем
        Toast.makeText(requireContext(), "Определяем вашу позицию...", Toast.LENGTH_SHORT).show()

        // Читаем напрямую из Firebase
        database.child("last_locations").child(uid).get()
            .addOnSuccessListener { snapshot ->
                if (isFragmentDestroyed || !isAdded) return@addOnSuccessListener

                if (!snapshot.exists()) {
                    Toast.makeText(requireContext(), "Ваша локация ещё не получена", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val userLoc = snapshot.getValue(UserLocation::class.java)
                if (userLoc != null && userLoc.isValid()) {
                    val point = userLoc.toPoint()
                    myLocation = point
                    mapView.map.move(
                        CameraPosition(point, 15.0f, 0.0f, 0.0f),
                        Animation(Animation.Type.SMOOTH, 0.5f),
                        null
                    )
                    handler.postDelayed({
                        if (!isFragmentDestroyed && isAdded) {
                            reverseGeocode(point)
                            updateMyMarker(point)
                        }
                    }, 2000)
                } else {
                    Toast.makeText(requireContext(), "Некорректные данные локации", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { error ->
                if (!isFragmentDestroyed && isAdded) {
                    Toast.makeText(requireContext(), "Ошибка: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun reverseGeocode(point: Point) {
        if (isFragmentDestroyed || !isAdded) {
            return
        }
        if (point.latitude < -90 || point.latitude > 90 ||
            point.longitude < -180 || point.longitude > 180) {
            return
        }
        if (lastGeocodePoint != null && distanceBetween(lastGeocodePoint!!, point) < 2.0) {
            return
        }
        lastGeocodePoint = point
        cityProgressBar.visibility = View.VISIBLE
        cityTextView.text = "Определяем..."
        geocoderExecutor.execute {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
                handler.post {
                    if (isFragmentDestroyed || !isAdded) {
                        return@post
                    }
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val locationName = buildDetailedAddress(address)
                        cityTextView.text = locationName
                    } else {
                        val coordinatesText = String.format(Locale.getDefault(),
                            "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                        cityTextView.text = coordinatesText
                    }
                    cityProgressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                handler.post {
                    if (!isFragmentDestroyed && isAdded) {
                        val coordinatesText = String.format(Locale.getDefault(),
                            "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                        cityTextView.text = coordinatesText
                        cityProgressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun buildDetailedAddress(address: android.location.Address): String {
        val parts = mutableListOf<String>()
        val thoroughfare = address.thoroughfare
        val subThoroughfare = address.subThoroughfare
        if (!thoroughfare.isNullOrEmpty()) {
            if (!subThoroughfare.isNullOrEmpty()) {
                parts.add("$thoroughfare, $subThoroughfare")
            } else {
                parts.add(thoroughfare)
            }
        }
        val subLocality = address.subLocality
        if (!subLocality.isNullOrEmpty() && subLocality != thoroughfare) {
            parts.add(subLocality)
        }
        val locality = address.locality
        if (!locality.isNullOrEmpty()) {
            parts.add(locality)
        }
        val adminArea = address.adminArea
        if (!adminArea.isNullOrEmpty() && adminArea != locality) {
            if (!locality.isNullOrEmpty() && !adminArea.contains(locality)) {
                parts.add(adminArea)
            } else if (locality.isNullOrEmpty()) {
                parts.add(adminArea)
            }
        }
        val countryName = address.countryName
        if (!countryName.isNullOrEmpty() && countryName != "Россия") {
            parts.add(countryName)
        }
        return if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            val fullAddress = address.getAddressLine(0)
            if (!fullAddress.isNullOrEmpty()) {
                fullAddress
            } else {
                "Местоположение"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationUpdateHandler.removeCallbacksAndMessages(null)
        locationRestartHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)
        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        shouldReloadData = true
        cityUpdateHandler.removeCallbacksAndMessages(null)
    }

    private fun observeUserLocations() {
        removeLocationsListener()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFragmentDestroyed || !isAdded) return

                val currentUids = mutableSetOf<String>()
                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key
                    val userLocation = userSnapshot.getValue(UserLocation::class.java)
                    if (userId != null && userLocation != null && userLocation.isValid()) {
                        currentUids.add(userId)
                        val point = Point(userLocation.lat, userLocation.lng)
                        val online = (System.currentTimeMillis() - userLocation.timestamp) < 30000
                        lastActiveTime[userId] = userLocation.timestamp
                        isOnline[userId] = online

                        if (userId == auth.currentUser?.uid) {
                            myLocation = point
                            updateMyMarker(point)
                        } else {
                            loadUserInfo(userId)
                            val existing = markers[userId]
                            if (existing != null) {
                                if (distanceBetween(existing.geometry, point) > MIN_MOVE_DISTANCE) {
                                    animateMarkerToBezierCubicWithRotation(existing, point)
                                } else {
                                    existing.geometry = point
                                }
                                if (isOnline[userId] != online) {
                                    updateMarkerAppearance(userId, existing, online)
                                }
                            } else {
                                addMarker(userId, point, online, "")
                            }
                        }
                    }
                }

                // Удаляем маркеры пропавших пользователей
                val toRemove = markers.keys - currentUids
                for (uid in toRemove) {
                    removeMarkerSafely(uid)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // ignore
            }
        }

        database.child("last_locations").addValueEventListener(listener)
        locationsValueEventListener = listener
    }

    private fun removeLocationsListener() {
        locationsValueEventListener?.let {
            database.child("last_locations").removeEventListener(it)
            locationsValueEventListener = null
        }
    }

    private fun reloadAllMarkers() {
        clearAllMarkers()
        removeLocationsListener()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                observeUserLocations()
            }
        }, 1000)
    }

    private fun clearAllMarkers() {
        val markersToRemove = markers.keys.toList()
        for (userId in markersToRemove) {
            removeMarkerSafely(userId)
        }
        myMarker?.let { marker ->
            if (marker.isValid) {
                try {
                    mapView.map.mapObjects.remove(marker)
                } catch (e: Exception) {
                    // ignore
                }
            }
            myMarker = null
        }
        userInfoCache.clear()
        isOnline.clear()
        lastActiveTime.clear()
        lastReceivedLocation.clear()
        markerUpdateTimes.clear()
        avatarCache.clear()
        tapListeners.clear()
    }

    private fun loadUserInfo(userId: String) {
        if (isFragmentDestroyed || !isAdded) return
        val lastLoadTime = lastActiveTime[userId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastLoadTime < 30000) {
            return
        }
        lastActiveTime[userId] = now
        ioScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    database.child("users").child(userId).get().await()
                }
                if (isFragmentDestroyed || !isAdded) return@launch
                withContext(Dispatchers.Main) {
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName()
                    val avatarUrl = user?.profileImageUrl
                    userInfoCache[userId] = UserInfo(name, avatarUrl)
                    markers[userId]?.let { marker ->
                        val finalName = name ?: ""
                        marker.setText(finalName)
                        updateMarkerAppearance(userId, marker, isOnline[userId] ?: true)
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun updateMyMarker(point: Point) {
        if (isFragmentDestroyed || !isAdded || context == null || isDetached) {
            return
        }
        try {
            if (myMarker == null) {
                myMarker = mapView.map.mapObjects.addPlacemark(point).apply {
                    setIcon(
                        ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                        com.yandex.mapkit.map.IconStyle().setScale(0.7f)
                    )
                    setText("Я")
                    zIndex = 100f
                }
                loadMyAvatar(point)
            } else {
                if (myMarker?.isValid == true) {
                    myMarker?.geometry = point
                } else {
                    try {
                        myMarker?.let { marker ->
                            if (marker.isValid) {
                                mapView.map.mapObjects.remove(marker)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                    myMarker = mapView.map.mapObjects.addPlacemark(point).apply {
                        setIcon(
                            ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                            com.yandex.mapkit.map.IconStyle().setScale(0.7f)
                        )
                        setText("Я")
                        zIndex = 100f
                    }
                    loadMyAvatar(point)
                }
            }
            myLocation = point
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadMyAvatar(point: Point) {
        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
            return
        }
        if (isMyAvatarLoading) {
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMyAvatarLoadTime < MY_AVATAR_LOAD_THROTTLE) {
            return
        }
        isMyAvatarLoading = true
        lastMyAvatarLoadTime = currentTime
        val userId = auth.currentUser?.uid ?: run {
            isMyAvatarLoading = false
            return
        }
        database.child("users").child(userId).get().addOnSuccessListener { snapshot ->
            if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
                isMyAvatarLoading = false
                return@addOnSuccessListener
            }
            val user = snapshot.getValue(User::class.java)
            val name = user?.getFullName() ?: "Я"
            val avatarUrl = user?.profileImageUrl
            var finalAvatarUrl = avatarUrl ?: "https://storage.yandexcloud.net/chatskii/profile_$userId.jpg"
            if (finalAvatarUrl.isBlank() || !finalAvatarUrl.startsWith("http")) {
                finalAvatarUrl = "https://storage.yandexcloud.net/chatskii/profile_$userId.jpg"
            }
            Glide.with(this)
                .asBitmap()
                .load(finalAvatarUrl)
                .circleCrop()
                .override(150, 150)
                .placeholder(R.drawable.ic_account_circle)
                .error(R.drawable.ic_account_circle)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        isMyAvatarLoading = false
                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
                            return
                        }
                        try {
                            val finalBitmap = createMarkerBitmap(resource, name, true)
                            if (myMarker?.isValid == true) {
                                myMarker?.setIcon(ImageProvider.fromBitmap(finalBitmap), com.yandex.mapkit.map.IconStyle().setScale(1.2f))
                            }
                        } catch (e: Exception) {
                            setDefaultMyMarkerIcon(point, name)
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        isMyAvatarLoading = false
                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return
                        setDefaultMyMarkerIcon(point, name)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        isMyAvatarLoading = false
                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return
                        setDefaultMyMarkerIcon(point, name)
                    }
                })
        }.addOnFailureListener { e ->
            isMyAvatarLoading = false
            if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return@addOnFailureListener
            setDefaultMyMarkerIcon(point, "Я")
        }
    }

    private fun setDefaultMyMarkerIcon(point: Point, name: String) {
        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
            return
        }
        if (myMarker?.isValid == true && !myMarker!!.isValid) {
            return
        }
        if (myMarker?.isValid == true) {
            myMarker?.setIcon(
                ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                com.yandex.mapkit.map.IconStyle().setScale(0.7f)
            )
            myMarker?.setText(name)
        }
    }

    private fun updateCityName(point: Point) {
        lastRequestedPoint = point
        val searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
        searchManager.submit(
            point,
            16,
            SearchOptions(),
            object : Session.SearchListener {
                override fun onSearchResponse(response: Response) {
                    if (point != lastRequestedPoint) {
                        return
                    }
                    val name = response.collection.children.firstOrNull()?.obj?.name ?: "Неизвестное место"
                    updateCityText(name)
                }

                override fun onSearchError(error: com.yandex.runtime.Error) {
                    // ignore
                }
            }
        )
    }

    private fun updateCityText(text: String) {
        requireActivity().runOnUiThread {
            view?.findViewById<TextView>(R.id.cityTextView)?.text = text
            view?.findViewById<ProgressBar>(R.id.cityProgressBar)?.visibility = View.GONE
        }
    }

    private fun setMarkerIconWithListener(
        userId: String,
        marker: PlacemarkMapObject,
        bitmap: Bitmap
    ) {
        if (!marker.isValid) return
        avatarCache[userId] = bitmap
        marker.setIcon(ImageProvider.fromBitmap(bitmap), com.yandex.mapkit.map.IconStyle().setScale(1.2f))
        marker.zIndex = 50f
        tapListeners[userId]?.let { marker.removeTapListener(it) }
        val listener = MapObjectTapListener { _, _ ->
            if (!isFragmentDestroyed && isAdded) showUserDialog(userId, isOnline[userId] ?: false)
            true
        }
        marker.addTapListener(listener)
        tapListeners[userId] = listener
    }

    private fun addMarker(
        userId: String,
        point: Point,
        onlineStatus: Boolean,
        initialName: String = ""
    ) {
        val placemark = mapView.map.mapObjects.addPlacemark(point).apply {
            setText(initialName)
            zIndex = 50f
        }
        loadUserAvatar(userId, placemark, initialName, onlineStatus)
        markers[userId] = placemark
        this.isOnline[userId] = onlineStatus
    }

    private fun updateMarkerAppearance(
        userId: String,
        marker: PlacemarkMapObject,
        onlineStatus: Boolean
    ) {
        if (isFragmentDestroyed || !isAdded) return
        val name = userInfoCache[userId]?.name ?: userId
        if (marker.isValid) {
            loadUserAvatar(userId, marker, name, onlineStatus)
        }
    }

    private fun loadUserAvatar(
        userId: String,
        placemark: PlacemarkMapObject,
        name: String,
        onlineStatus: Boolean
    ) {
        if (isFragmentDestroyed || !isAdded) return
        val userInfo = userInfoCache[userId]
        var avatarUrl = userInfo?.avatarUrl
        if (avatarUrl.isNullOrEmpty() || !avatarUrl.startsWith("http")) {
            avatarUrl = "https://storage.yandexcloud.net/chatskii/profile_$userId.jpg"
        }
        val cacheKey = "$userId-${userInfo?.avatarUrl}"
        if (avatarCache.containsKey(cacheKey)) {
            avatarCache[cacheKey]?.let { bitmap ->
                setMarkerIconWithListener(userId, placemark, bitmap)
                return
            }
        }
        val placeholderBitmap = createPlaceholderBitmap(name, onlineStatus)
        ioScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        Glide.with(this@MapFragment)
                            .asBitmap()
                            .load(avatarUrl)
                            .circleCrop()
                            .override(150, 150)
                            .submit()
                            .get()
                    } catch (e: Exception) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    if (isFragmentDestroyed || !isAdded) return@withContext
                    val finalBitmap = bitmap?.let {
                        try {
                            createMarkerBitmap(it, name, onlineStatus)
                        } catch (e: Exception) {
                            placeholderBitmap
                        }
                    } ?: placeholderBitmap
                    if (placemark.isValid) {
                        avatarCache[cacheKey] = finalBitmap
                        setMarkerIconWithListener(userId, placemark, finalBitmap)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed && isAdded && placemark.isValid) {
                        setMarkerIconWithListener(userId, placemark, placeholderBitmap)
                    }
                }
            }
        }
    }

    private fun getCurrentLocationAndCenter() {
        if (isFragmentDestroyed || !isAdded) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationListener = object : LocationListener {
                override fun onLocationUpdated(location: Location) {
                    if (isFragmentDestroyed) return
                    val point = Point(location.position.latitude, location.position.longitude)
                    if (myLocation == null || distanceBetween(myLocation!!, point) > 10) {
                        mapView.map.move(CameraPosition(point, 15f, 0f, 0f), Animation(Animation.Type.SMOOTH, 1f), null)
                        myLocation = point
                        updateMyMarker(point)
                        cityUpdateHandler.postDelayed({
                            if (!isFragmentDestroyed && isAdded) {
                                updateCityName(point)
                            }
                        }, 1000)
                    }
                    try {
                        locationManager.unsubscribe(this)
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                override fun onLocationStatusUpdated(locationStatus: LocationStatus) {
                    if (isFragmentDestroyed) return
                }
            }
            try {
                locationManager.subscribeForLocationUpdates(
                    2000.0,
                    10L,
                    0.0,
                    true,
                    com.yandex.mapkit.location.FilteringMode.OFF,
                    locationListener!!
                )
            } catch (e: Exception) {
                // ignore
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    private fun createPlaceholderBitmap(name: String, onlineStatus: Boolean): Bitmap {
        val avatarSize = 120
        val textHeight = 40
        val padding = 15
        val width = avatarSize + padding * 2
        val height = avatarSize + textHeight + padding * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.setShadowLayer(6f, 3f, 3f, Color.argb(150, 0, 0, 0))
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)
        paint.clearShadowLayer()
        val borderColor = if (onlineStatus) Color.GREEN else Color.GRAY
        paint.color = borderColor
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 5f
        canvas.drawRoundRect(
            padding - 2f, padding - 2f,
            padding + avatarSize + 2f, padding + avatarSize + 2f,
            50f, 50f, paint
        )
        paint.color = if (onlineStatus) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E")
        canvas.drawRoundRect(
            padding.toFloat(), padding.toFloat(),
            (padding + avatarSize).toFloat(), (padding + avatarSize).toFloat(),
            50f, 50f, paint
        )
        paint.color = Color.WHITE
        paint.textSize = 40f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val firstLetter = if (name.isNotEmpty()) name[0].toString() else "?"
        val textX = (avatarSize / 2 + padding).toFloat()
        val textY = (avatarSize / 2 + padding + 10).toFloat()
        canvas.drawText(firstLetter, textX, textY, paint)
        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val displayName = if (name.length > 12) "${name.substring(0, 10)}.." else name
        canvas.drawText(displayName, width / 2f, (avatarSize + padding + textHeight - 5).toFloat(), paint)
        return bitmap
    }

    private fun setDefaultMarkerIcon(placemark: PlacemarkMapObject, name: String) {
        if (placemark.isValid && !isFragmentDestroyed && isAdded) {
            placemark.setIcon(
                ImageProvider.fromResource(requireContext(), R.drawable.ic_user_marker),
                com.yandex.mapkit.map.IconStyle().setScale(0.7f)
            )
            placemark.setText(name)
        }
    }

    private fun createMarkerBitmap(avatar: Bitmap, name: String, onlineStatus: Boolean): Bitmap {
        val avatarSize = 120
        val textHeight = 40
        val padding = 15
        val width = avatarSize + padding * 2
        val height = avatarSize + textHeight + padding * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.setShadowLayer(6f, 3f, 3f, Color.argb(150, 0, 0, 0))
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)
        paint.clearShadowLayer()
        val borderColor = if (onlineStatus) Color.GREEN else Color.GRAY
        paint.color = borderColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawRoundRect(
            padding - 2f, padding - 2f,
            padding + avatarSize + 2f, padding + avatarSize + 2f,
            50f, 50f, paint
        )
        paint.style = Paint.Style.FILL
        val avatarBitmap = avatar
        val avatarRect = Rect(padding, padding, padding + avatarSize, padding + avatarSize)
        canvas.drawBitmap(avatarBitmap, null, avatarRect, paint)
        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val displayName = if (name.length > 12) "${name.substring(0, 10)}.." else name
        canvas.drawText(displayName, width / 2f, (avatarSize + padding + textHeight - 5).toFloat(), paint)
        return bitmap
    }

    private fun removeMarkerSafely(userId: String) {
        markers[userId]?.let { marker ->
            if (marker.isValid) {
                try {
                    mapView.map.mapObjects.remove(marker)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        markers.remove(userId)
        userInfoCache.remove(userId)
        isOnline.remove(userId)
        lastActiveTime.remove(userId)
        lastReceivedLocation.remove(userId)
        markerUpdateTimes.remove(userId)
    }

    private fun showUserDialog(userId: String, onlineStatus: Boolean) {
        if (isFragmentDestroyed || !isAdded) return
        val status = if (onlineStatus) "🟢 онлайн" else "⚫ оффлайн"
        val name = userInfoCache[userId]?.name ?: "Неизвестный пользователь"
        val lastActive = lastActiveTime[userId]?.let { getTimeAgo(it) } ?: "неизвестно"
        val isCurrentUser = userId == auth.currentUser?.uid
        val dialogBuilder = AlertDialog.Builder(requireContext())
            .setTitle("Информация о пользователе")
            .setMessage("Имя: $name\nСтатус: $status\nПоследняя активность: $lastActive")
            .setNegativeButton("👤 Профиль") { _, _ ->
                openProfile(userId)
            }
        if (!isCurrentUser) {
            dialogBuilder
                .setPositiveButton("💬 Написать сообщение") { _, _ ->
                    sendMessage(userId)
                }
                .setNeutralButton("👥 Добавить в друзья") { _, _ ->
                    addFriend(userId)
                }
        } else {
            dialogBuilder.setMessage("Имя: $name\nСтатус: $status\nПоследняя активность: $lastActive\nЭто ваш профиль")
        }
        dialogBuilder.show()
    }

    private fun sendMessage(userId: String) {
        if (isFragmentDestroyed || !isAdded) return
        val currentUserId = auth.currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Необходимо войти в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        if (userId == currentUserId) {
            Toast.makeText(requireContext(), "Нельзя отправить сообщение самому себе", Toast.LENGTH_SHORT).show()
            return
        }
        val chatId = if (currentUserId < userId) "$currentUserId-$userId" else "$userId-$currentUserId"
        val chatRef = database.child("chats").child(chatId)
        chatRef.get().addOnSuccessListener { chatSnapshot ->
            if (!isFragmentDestroyed && isAdded) {
                if (chatSnapshot.exists()) {
                    openChatActivity(chatId)
                } else {
                    database.child("users").child(userId).get().addOnSuccessListener { userSnapshot ->
                        if (!isFragmentDestroyed && isAdded) {
                            val otherUser = userSnapshot.getValue(User::class.java)
                            if (otherUser != null) {
                                val otherUserName = otherUser.getFullName() ?: "Пользователь $userId"
                                val otherUserAvatarUrl = otherUser.profileImageUrl
                                database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                                    override fun onDataChange(currentUserSnapshot: DataSnapshot) {
                                        if (!isFragmentDestroyed && isAdded) {
                                            val currentUser = currentUserSnapshot.getValue(User::class.java)
                                            if (currentUser != null) {
                                                val currentUserName = currentUser.getFullName() ?: "Я"
                                                val currentUserAvatarUrl = currentUser.profileImageUrl
                                                val newChat = mapOf(
                                                    "creatorId" to currentUserId,
                                                    "creatorName" to currentUserName,
                                                    "imageUrl" to currentUserAvatarUrl,
                                                    "createdAt" to System.currentTimeMillis(),
                                                    "participants" to mapOf(currentUserId to true, userId to true),
                                                    "name" to otherUserName,
                                                    "avatarUrl" to otherUserAvatarUrl,
                                                    "lastMessage" to "",
                                                    "lastMessageTime" to 0L
                                                )
                                                val updates = mutableMapOf<String, Any>()
                                                updates["chats/$chatId"] = newChat
                                                database.updateChildren(updates)
                                                    .addOnSuccessListener {
                                                        openChatActivity(chatId)
                                                    }
                                                    .addOnFailureListener { e ->
                                                        Toast.makeText(requireContext(), "Ошибка сохранения чата: ${e.message}", Toast.LENGTH_LONG).show()
                                                    }
                                            } else {
                                                Toast.makeText(requireContext(), "Ошибка: данные текущего пользователя", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }

                                    override fun onCancelled(databaseError: DatabaseError) {
                                        Toast.makeText(requireContext(), "Ошибка загрузки данных текущего пользователя", Toast.LENGTH_SHORT).show()
                                    }
                                })
                            } else {
                                Toast.makeText(requireContext(), "Пользователь не найден", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.addOnFailureListener { e ->
                        Toast.makeText(requireContext(), "Ошибка загрузки данных пользователя: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(requireContext(), "Ошибка проверки чата: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openChatActivity(chatId: String) {
        val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId)
        }
        startActivity(intent)
    }

    private fun addFriend(userId: String) {
        if (isFragmentDestroyed || !isAdded) return
        val currentUser = auth.currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Необходимо войти в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }
        if (userId == currentUser) {
            Toast.makeText(requireContext(), "Нельзя добавить самого себя в друзья", Toast.LENGTH_SHORT).show()
            return
        }
        val friendsRef = database.child("users").child(currentUser).child("friends").child(userId)
        friendsRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                Toast.makeText(requireContext(), "Пользователь уже в друзьях", Toast.LENGTH_SHORT).show()
            } else {
                friendsRef.setValue(true)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(requireContext(), "Ошибка добавления в друзья", Toast.LENGTH_SHORT).show()
                    }
            }
        }.addOnFailureListener { e ->
            friendsRef.setValue(true)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e2 ->
                    Toast.makeText(requireContext(), "Ошибка добавления в друзья", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun openProfile(userId: String) {
        if (isFragmentDestroyed || !isAdded) return
        val intent = Intent(requireContext(), UserProfileActivity::class.java).apply {
            putExtra("USER_ID", userId)
        }
        startActivity(intent)
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            diff < 60 -> "только что"
            diff < 3600 -> "${diff / 60} мин назад"
            diff < 86400 -> "${diff / 3600} ч назад"
            else -> "${diff / 86400} дн. назад"
        }
    }

    private fun distanceBetween(p1: Point, p2: Point): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun animateMarkerToBezierCubicWithRotation(placemark: PlacemarkMapObject, newPoint: Point, duration: Long = 1000L) {
        val oldPoint = placemark.geometry
        val start = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val currentPoint = calculateBezierPoint(oldPoint, newPoint, t)
                if (placemark.isValid) {
                    placemark.geometry = currentPoint
                }
                if (t < 1f) {
                    val nextT = (t + 0.05f).coerceAtMost(1f)
                    val nextPoint = calculateBezierPoint(oldPoint, newPoint, nextT)
                    if (placemark.isValid) {
                        placemark.direction = calculateBearing(currentPoint, nextPoint)
                    }
                    handler.postDelayed(this, 16)
                }
            }
        }
        handler.post(runnable)
    }

    private fun calculateBezierPoint(start: Point, end: Point, t: Float): Point {
        val lat = start.latitude + (end.latitude - start.latitude) * t
        val lon = start.longitude + (end.longitude - start.longitude) * t
        return Point(lat, lon)
    }

    private fun calculateBearing(from: Point, to: Point): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun updateMyOnlineStatus() {
        if (isFragmentDestroyed || !isAdded) {
            return
        }
        val uid = auth.currentUser?.uid ?: return
        val location = myLocation ?: return
        val userLocation = UserLocation(
            lat = location.latitude,
            lng = location.longitude,
            timestamp = System.currentTimeMillis()
        )
        // ⚠️ ЗАПИСЬ ТОЖЕ ДОЛЖНА БЫТЬ В last_locations
        database.child("last_locations").child(uid).setValue(userLocation)
            .addOnSuccessListener {
                // success
            }
            .addOnFailureListener { e ->
                // ignore
            }
    }

    private fun checkOtherUsersOnlineStatus() {
        if (isFragmentDestroyed || !isAdded) {
            return
        }
        val now = System.currentTimeMillis()
        markers.keys.forEach { userId ->
            if (userId == auth.currentUser?.uid) return@forEach
            val lastTime = lastActiveTime[userId] ?: 0
            val online = (now - lastTime) < 240000
            if (this.isOnline[userId] != online) {
                this.isOnline[userId] = online
                markers[userId]?.let { marker ->
                    val name = userInfoCache[userId]?.name ?: userId
                    loadUserAvatar(userId, marker, name, online)
                }
            }
        }
    }

    private fun restartLocationUpdates() {
        if (isFragmentDestroyed || !isAdded) return
        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        startLocationTracking()
    }

    private fun startLocationTracking() {
        if (isFragmentDestroyed || !isAdded) return
        locationListener = object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                if (isFragmentDestroyed) return
                val point = Point(location.position.latitude, location.position.longitude)
                myLocation = point
                updateMyMarker(point)
                updateMyOnlineStatus()
            }

            override fun onLocationStatusUpdated(locationStatus: LocationStatus) {
                if (isFragmentDestroyed) return
            }
        }
        try {
            locationManager.subscribeForLocationUpdates(
                2000.0,
                10L,
                0.0,
                true,
                com.yandex.mapkit.location.FilteringMode.OFF,
                locationListener!!
            )
        } catch (e: Exception) {
            // ignore
        }
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
        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        handler.removeCallbacksAndMessages(null)
        onlineHandler.removeCallbacksAndMessages(null)
        cityUpdateHandler.removeCallbacksAndMessages(null)
        locationUpdateHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)
        removeLocationsListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        ioScope.cancel()
        geocoderExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        locationRestartHandler.removeCallbacksAndMessages(null)
        onlineHandler.removeCallbacksAndMessages(null)
        cityUpdateHandler.removeCallbacksAndMessages(null)
        locationUpdateHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)
        try {
            Glide.with(this).pauseAllRequests()
        } catch (e: Exception) {
            // ignore
        }
        try {
            searchSession?.cancel()
            searchSession = null
        } catch (e: Exception) {
            // ignore
        }
        removeLocationsListener()
        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        locationListener = null
        try {
            clearAllMarkers()
            markers.clear()
            userInfoCache.clear()
            isOnline.clear()
            lastActiveTime.clear()
            lastReceivedLocation.clear()
            avatarCache.clear()
            tapListeners.clear()
            markerUpdateTimes.clear()
        } catch (e: Exception) {
            // ignore
        }
        lastCityPoint = null
        myMarker = null
        myLocation = null
        lastGeocodePoint = null
    }
}