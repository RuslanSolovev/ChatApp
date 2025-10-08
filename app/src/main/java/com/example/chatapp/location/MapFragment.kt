package com.example.chatapp.location

import android.Manifest
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.content.Intent
import android.graphics.Rect
import android.location.Geocoder
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
import com.google.firebase.database.*
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBoxHelper
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yandex.mapkit.location.*
import com.yandex.mapkit.search.*
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

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

    private var locationsChildEventListener: ChildEventListener? = null

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
                Log.d(TAG, "Периодическое обновление геокодирования для $currentPoint")
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
    private val MARKER_UPDATE_THROTTLE = 2000L

    private var isMyAvatarLoading = false
    private var lastMyAvatarLoadTime: Long = 0
    private val MY_AVATAR_LOAD_THROTTLE = 5000L

    companion object {
        private const val TAG = "MapFragment"
        private const val MIN_MOVE_DISTANCE = 50.0
    }

    private var shouldReloadData = true
    private val lastReceivedLocation = mutableMapOf<String, UserLocation>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView: НАЧАЛО")
        val layout = inflater.inflate(R.layout.fragment_map_with_friends_button, container, false)

        cityTextView = layout.findViewById(R.id.cityTextView)
        cityProgressBar = layout.findViewById(R.id.cityProgressBar)
        cityTextView.text = ""
        cityProgressBar.visibility = View.GONE

        val friendsButton = layout.findViewById<Button>(R.id.friendsButton)
        friendsButton.setOnClickListener {
            startActivity(Intent(requireContext(), FriendsListActivity::class.java))
        }

        val findMeButton = layout.findViewById<Button>(R.id.findMeButton)
        findMeButton.visibility = View.VISIBLE
        findMeButton.setOnClickListener { centerCameraOnMyLocation() }

        mapView = layout.findViewById(R.id.mapview)
        mapView.map.isRotateGesturesEnabled = false

        locationManager = MapKitFactory.getInstance().createLocationManager()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                observeUserLocations()
                fixTouchInterception()
            }
        }, 1000)

        Log.d(TAG, "onCreateView: КОНЕЦ")
        return layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.mapview)
        cityTextView = view.findViewById(R.id.cityTextView)
        cityProgressBar = view.findViewById(R.id.cityProgressBar)

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
                Log.d(TAG, "Первое геокодирование через 8 секунд для точки $currentPoint")
                reverseGeocode(currentPoint)
                setupNormalCameraUpdates()
            }
        }, 8000)

        setupMapTouchHandling()
    }

    private fun setupMapTouchHandling() {
        mapView.isClickable = true
        mapView.isFocusable = true
        mapView.isFocusableInTouchMode = true
        mapView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        mapView.map.addInputListener(object : InputListener {
            override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {
                Log.d(TAG, "onMapTap: $point")
                scheduleGeocode(point)
            }

            override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {
                Log.d(TAG, "onMapLongTap: $point")
            }
        })

        mapView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "MapView touch: ACTION_DOWN")
                }
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "MapView touch: ACTION_UP")
                }
            }
            false
        }
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
            Log.w(TAG, "fixTouchInterception: ошибка", e)
        }
    }

    private fun findAndFixTouchInterceptors(root: View) {
        try {
            val interceptors = mutableListOf<View>()

            fun traverseViewHierarchy(view: View, depth: Int = 0) {
                if (view.visibility != View.VISIBLE) return

                if (view.isClickable || view.isFocusable || view.isFocusableInTouchMode) {
                    if (view != mapView && isViewOverMap(view)) {
                        interceptors.add(view)
                        Log.w(TAG, "TouchInterceptor found: ${view.javaClass.simpleName} at depth $depth")
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
                    Log.d(TAG, "Fixed interceptor: ${view.javaClass.simpleName}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fix interceptor: ${view.javaClass.simpleName}", e)
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "findAndFixTouchInterceptors: error", e)
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
            Log.d(TAG, "Проверка доступности касаний. Текущая позиция: $point")

            if (System.currentTimeMillis() - lastCameraUpdateTime > 120000) {
                Log.w(TAG, "Возможна блокировка касаний карты")
                fixTouchInterception()
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkTouchAvailability: ошибка", e)
        }
    }

    private fun setupNormalCameraUpdates() {
        Log.d(TAG, "setupNormalCameraUpdates: Настройка обычных обновлений камеры")

        mapView.map.addCameraListener(object : CameraListener {
            private var cameraUpdateHandler = Handler(Looper.getMainLooper())
            private var cameraUpdateRunnable: Runnable? = null

            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (finished && !isFragmentDestroyed && isAdded) {
                    cameraUpdateRunnable?.let { cameraUpdateHandler.removeCallbacks(it) }

                    cameraUpdateRunnable = Runnable {
                        if (!isFragmentDestroyed && isAdded) {
                            val target = cameraPosition.target
                            Log.d(TAG, "Обычное обновление геокодирования для $target")
                            scheduleGeocode(target)
                        }
                    }

                    cameraUpdateHandler.postDelayed(cameraUpdateRunnable!!, 2000)
                }
            }
        })
    }

    private fun centerCameraOnMyLocation() {
        Log.d(TAG, "centerCameraOnMyLocation: НАЧАЛО")

        myLocation?.let { location ->
            Log.d(TAG, "centerCameraOnMyLocation: Перемещение камеры на мою позицию $location")

            mapView.map.move(
                CameraPosition(location, 15.0f, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 0.5f),
                null
            )

            handler.postDelayed({
                if (!isFragmentDestroyed && isAdded) {
                    Log.d(TAG, "centerCameraOnMyLocation: геокодирование после перемещения камеры")
                    reverseGeocode(location)
                }
            }, 2000)

        } ?: run {
            Log.w(TAG, "centerCameraOnMyLocation: Моя локация неизвестна")
            Toast.makeText(requireContext(), "Локация не определена", Toast.LENGTH_SHORT).show()
        }

        Log.d(TAG, "centerCameraOnMyLocation: КОНЕЦ")
    }

    private fun reverseGeocode(point: Point) {
        if (isFragmentDestroyed || !isAdded) {
            Log.w(TAG, "reverseGeocode: Fragment не attached или уничтожается")
            return
        }

        if (lastGeocodePoint != null && distanceBetween(lastGeocodePoint!!, point) < 5.0) {
            Log.d(TAG, "reverseGeocode: Пропускаем геокодирование - точка почти не изменилась")
            return
        }

        lastGeocodePoint = point

        Log.d(TAG, "reverseGeocode: запрос для точки $point")
        cityProgressBar.visibility = View.VISIBLE
        cityTextView.text = "Определяем..."

        geocoderExecutor.execute {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)

                Log.d(TAG, "reverseGeocode: получено ${addresses?.size ?: 0} адресов")

                handler.post {
                    if (isFragmentDestroyed || !isAdded) {
                        Log.w(TAG, "reverseGeocode: Fragment не attached после геокодирования")
                        return@post
                    }

                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val locationName = buildDetailedAddress(address)

                        cityTextView.text = locationName
                        Log.d(TAG, "reverseGeocode: адрес определён: $locationName")
                    } else {
                        val coordinatesText = String.format(Locale.getDefault(),
                            "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                        cityTextView.text = coordinatesText
                        Log.w(TAG, "reverseGeocode: адреса не найдены, показываем координаты")
                    }
                    cityProgressBar.visibility = View.GONE
                }

            } catch (e: Exception) {
                Log.e(TAG, "reverseGeocode: ошибка", e)
                handler.post {
                    if (!isFragmentDestroyed && isAdded) {
                        val coordinatesText = String.format(Locale.getDefault(),
                            "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                        cityTextView.text = coordinatesText
                        cityProgressBar.visibility = View.GONE
                        Log.w(TAG, "reverseGeocode: ошибка геокодирования, показываем координаты")
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

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: НАЧАЛО")

        isFragmentDestroyed = false

        locationUpdateHandler.postDelayed(locationUpdateRunnable, 15000)
        locationRestartHandler.postDelayed(locationRestartRunnable, 45000)

        fixTouchInterception()

        startLocationTracking()

        if (shouldReloadData) {
            Log.d(TAG, "onResume: Перезагрузка всех данных")
            reloadAllMarkers()
            shouldReloadData = false

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFragmentDestroyed && isAdded) {
                    setupNormalCameraUpdates()
                    val currentPoint = mapView.map.cameraPosition.target
                    Log.d(TAG, "onResume: обновление геокодирования после возвращения")
                    reverseGeocode(currentPoint)
                }
            }, 3000)
        }

        Log.d(TAG, "onResume: КОНЕЦ")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: НАЧАЛО")

        locationUpdateHandler.removeCallbacksAndMessages(null)
        locationRestartHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)

        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
                Log.d(TAG, "onPause: LocationListener отписан")
            } catch (e: Exception) {
                Log.w(TAG, "onPause: Ошибка отписки LocationListener", e)
            }
        }

        shouldReloadData = true
        cityUpdateHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "onPause: КОНЕЦ")
    }

    private fun observeUserLocations() {
        Log.d(TAG, "observeUserLocations: НАЧАЛО")

        locationsChildEventListener?.let {
            try {
                database.child("user_locations").removeEventListener(it)
                Log.d(TAG, "observeUserLocations: удалён предыдущий ChildEventListener")
            } catch (e: Exception) {
                Log.w(TAG, "observeUserLocations: невозможно удалить предыдущий слушатель", e)
            }
            locationsChildEventListener = null
        }

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                Log.v(TAG, "observeUserLocations: onChildAdded для ${snapshot.key}")
                if (!isFragmentDestroyed && isAdded) {
                    updateUserMarker(snapshot)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                Log.v(TAG, "observeUserLocations: onChildChanged для ${snapshot.key}")
                if (!isFragmentDestroyed && isAdded) {
                    updateUserMarker(snapshot)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                Log.v(TAG, "observeUserLocations: onChildRemoved для ${snapshot.key}")
                if (!isFragmentDestroyed && isAdded) {
                    val userId = snapshot.key
                    userId?.let {
                        removeMarkerSafely(it)
                    }
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                Log.v(TAG, "observeUserLocations: onChildMoved для ${snapshot.key}")
                if (!isFragmentDestroyed && isAdded) {
                    updateUserMarker(snapshot)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeUserLocations: onCancelled", error.toException())
            }
        }

        try {
            database.child("user_locations").addChildEventListener(listener)
            locationsChildEventListener = listener
            Log.d(TAG, "observeUserLocations: ChildEventListener добавлен")
        } catch (e: Exception) {
            Log.e(TAG, "observeUserLocations: Ошибка при добавлении ChildEventListener", e)
        }
        Log.d(TAG, "observeUserLocations: КОНЕЦ")
    }

    private fun removeLocationsListener() {
        locationsChildEventListener?.let {
            try {
                database.child("user_locations").removeEventListener(it)
                Log.d(TAG, "removeLocationsListener: ChildEventListener удалён")
            } catch (e: Exception) {
                Log.w(TAG, "removeLocationsListener: Ошибка удаления ChildEventListener", e)
            } finally {
                locationsChildEventListener = null
            }
        }
    }

    private fun reloadAllMarkers() {
        Log.d(TAG, "reloadAllMarkers: НАЧАЛО")
        clearAllMarkers()
        removeLocationsListener()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentDestroyed && isAdded) {
                observeUserLocations()
            }
        }, 1000)

        Log.d(TAG, "reloadAllMarkers: КОНЕЦ")
    }

    private fun clearAllMarkers() {
        Log.d(TAG, "clearAllMarkers: НАЧАЛО")
        val markersToRemove = markers.keys.toList()
        for (userId in markersToRemove) {
            removeMarkerSafely(userId)
        }

        myMarker?.let { marker ->
            if (marker.isValid) {
                try {
                    mapView.map.mapObjects.remove(marker)
                } catch (e: Exception) {
                    Log.w(TAG, "clearAllMarkers: Ошибка удаления myMarker", e)
                }
            }
            myMarker = null
        }

        userInfoCache.clear()
        isOnline.clear()
        lastActiveTime.clear()
        lastReceivedLocation.clear()
        markerUpdateTimes.clear()
        Log.d(TAG, "clearAllMarkers: КОНЕЦ")
    }

    private fun updateUserMarker(snapshot: DataSnapshot) {
        if (isFragmentDestroyed || !isAdded) {
            Log.w(TAG, "updateUserMarker: Fragment not attached or destroyed, skipping update")
            return
        }
        val userLocation = snapshot.getValue(UserLocation::class.java)
        val userId = snapshot.key
        if (userLocation != null && userId != null) {
            val lastUpdateTime = markerUpdateTimes[userId] ?: 0L
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateTime < MARKER_UPDATE_THROTTLE) {
                Log.v(TAG, "updateUserMarker: Throttling update for $userId")
                return
            }
            val lastKnownLocation = lastReceivedLocation[userId]
            if (lastKnownLocation != null &&
                lastKnownLocation.lat == userLocation.lat &&
                lastKnownLocation.lng == userLocation.lng &&
                lastKnownLocation.timestamp == userLocation.timestamp) {
                Log.v(TAG, "updateUserMarker: Data for $userId hasn't changed, skipping")
                return
            }
            lastReceivedLocation[userId] = userLocation
            markerUpdateTimes[userId] = currentTime
            val point = Point(userLocation.lat, userLocation.lng)
            lastActiveTime[userId] = userLocation.timestamp
            val currentOnlineStatus = (System.currentTimeMillis() - userLocation.timestamp) < 30000
            if (userId != auth.currentUser?.uid) {
                loadUserInfo(userId)
            }
            if (userId == auth.currentUser?.uid) {
                updateMyMarker(point)
                myLocation = point
            } else {
                val existingMarker = markers[userId]
                if (existingMarker != null) {
                    val oldPoint = existingMarker.geometry
                    if (distanceBetween(oldPoint, point) > MIN_MOVE_DISTANCE) {
                        animateMarkerToBezierCubicWithRotation(existingMarker, point)
                    } else {
                        try {
                            existingMarker.geometry = point
                        } catch (e: Exception) {
                            Log.w(TAG, "updateUserMarker: Error setting geometry for $userId", e)
                        }
                    }
                    if (this.isOnline[userId] != currentOnlineStatus) {
                        updateMarkerAppearance(userId, existingMarker, currentOnlineStatus)
                    }
                } else {
                    addMarker(userId, point, currentOnlineStatus, "")
                }
            }
            this.isOnline[userId] = currentOnlineStatus
        }
    }

    private fun loadUserInfo(userId: String) {
        Log.d(TAG, "loadUserInfo: Начало загрузки для $userId")
        if (isFragmentDestroyed || !isAdded) {
            Log.w(TAG, "loadUserInfo: Fragment not attached or destroyed, skipping load for $userId")
            return
        }

        val lastLoadTime = lastActiveTime[userId] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastLoadTime < 10000) {
            Log.d(TAG, "loadUserInfo: Слишком частый вызов для $userId, пропускаем")
            return
        }
        lastActiveTime[userId] = now

        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) {
                        Log.w(TAG, "loadUserInfo: Fragment not attached or destroyed, skipping update for $userId")
                        return
                    }

                    Log.d(TAG, "loadUserInfo: onDataChange для $userId")
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName()
                    val avatarUrl = user?.profileImageUrl

                    userInfoCache[userId] = UserInfo(name, avatarUrl)
                    Log.d(TAG, "loadUserInfo: Информация загружена для $userId: имя='$name'")

                    markers[userId]?.let { marker ->
                        Log.d(TAG, "loadUserInfo: Маркер для $userId найден, обновляем текст и аватар")
                        val finalName = name ?: ""
                        marker.setText(finalName)
                        updateMarkerAppearance(userId, marker, isOnline[userId] ?: true)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isFragmentDestroyed || !isAdded) return
                    Log.e(TAG, "loadUserInfo: onCancelled для $userId", error.toException())
                    userInfoCache[userId] = UserInfo(null, null)
                    markers[userId]?.let { marker ->
                        marker.setText("")
                        updateMarkerAppearance(userId, marker, isOnline[userId] ?: true)
                    }
                }
            })
    }

    private fun updateMyMarker(point: Point) {
        Log.d(TAG, "updateMyMarker: НАЧАЛО")

        if (isFragmentDestroyed || !isAdded || context == null || isDetached) {
            Log.w(TAG, "updateMyMarker: Фрагмент не прикреплён или уничтожается, пропуск обновления")
            return
        }

        try {
            if (myMarker == null) {
                Log.d(TAG, "updateMyMarker: Создание нового 'моего' маркера")
                myMarker = mapView.map.mapObjects.addPlacemark(point).apply {
                    setIcon(
                        ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                        IconStyle().setScale(0.7f)
                    )
                    setText("Я")
                    zIndex = 100f
                }
                loadMyAvatar(point)
            } else {
                Log.d(TAG, "updateMyMarker: Обновление позиции 'моего' маркера")
                if (myMarker?.isValid == true) {
                    myMarker?.geometry = point
                } else {
                    Log.w(TAG, "updateMyMarker: myMarker недействителен, пересоздание")
                    try {
                        myMarker?.let { marker ->
                            if (marker.isValid) {
                                mapView.map.mapObjects.remove(marker)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "updateMyMarker: Ошибка при удалении старого myMarker", e)
                    }
                    myMarker = mapView.map.mapObjects.addPlacemark(point).apply {
                        setIcon(
                            ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                            IconStyle().setScale(0.7f)
                        )
                        setText("Я")
                        zIndex = 100f
                    }
                    loadMyAvatar(point)
                }
            }
            myLocation = point
        } catch (e: Exception) {
            Log.e(TAG, "updateMyMarker: Ошибка обновления 'моего' маркера", e)
        }
        Log.d(TAG, "updateMyMarker: КОНЕЦ")
    }

    private fun loadMyAvatar(point: Point) {
        Log.d(TAG, "loadMyAvatar: НАЧАЛО")

        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
            Log.w(TAG, "loadMyAvatar: Фрагмент уничтожается, пропуск загрузки аватара")
            return
        }

        if (isMyAvatarLoading) {
            Log.d(TAG, "loadMyAvatar: Загрузка уже выполняется, пропускаем")
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMyAvatarLoadTime < MY_AVATAR_LOAD_THROTTLE) {
            Log.d(TAG, "loadMyAvatar: Слишком частый вызов, пропускаем (троттлинг)")
            return
        }

        isMyAvatarLoading = true
        lastMyAvatarLoadTime = currentTime

        val userId = auth.currentUser?.uid ?: run {
            Log.w(TAG, "loadMyAvatar: Пользователь не авторизован")
            isMyAvatarLoading = false
            return
        }

        database.child("users").child(userId).get().addOnSuccessListener { snapshot ->
            if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
                Log.w(TAG, "loadMyAvatar: Фрагмент уничтожается после загрузки данных")
                isMyAvatarLoading = false
                return@addOnSuccessListener
            }

            val user = snapshot.getValue(User::class.java)
            val name = user?.getFullName() ?: "Я"
            val avatarUrl = user?.profileImageUrl

            Log.d(TAG, "loadMyAvatar: Загрузка аватара для $userId с URL: $avatarUrl")

            val finalAvatarUrl = avatarUrl ?: "https://storage.yandexcloud.net/chatskii/profile_$userId.jpg"

            Glide.with(this)
                .asBitmap()
                .load(finalAvatarUrl)
                .circleCrop()
                .override(150, 150)
                .placeholder(R.drawable.ic_account_circle)
                .error(R.drawable.ic_account_circle)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        Log.d(TAG, "loadMyAvatar: Аватар загружен для $userId")
                        isMyAvatarLoading = false

                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
                            Log.w(TAG, "loadMyAvatar: Фрагмент уничтожается после загрузки аватара")
                            return
                        }
                        try {
                            val finalBitmap = createMarkerBitmap(resource, name, true)
                            if (myMarker?.isValid == true) {
                                myMarker?.setIcon(ImageProvider.fromBitmap(finalBitmap), IconStyle().setScale(1.2f))
                            } else {
                                Log.w(TAG, "loadMyAvatar: myMarker недействителен при установке иконки")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadMyAvatar: Ошибка создания иконки для $userId", e)
                            setDefaultMyMarkerIcon(point, name)
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        Log.d(TAG, "loadMyAvatar: onLoadCleared для $userId")
                        isMyAvatarLoading = false
                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return
                        setDefaultMyMarkerIcon(point, name)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        Log.w(TAG, "loadMyAvatar: Ошибка загрузки аватара для $userId")
                        isMyAvatarLoading = false
                        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return
                        setDefaultMyMarkerIcon(point, name)
                    }
                })
        }.addOnFailureListener { e ->
            Log.e(TAG, "loadMyAvatar: Ошибка загрузки данных пользователя $userId", e)
            isMyAvatarLoading = false
            if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) return@addOnFailureListener
            setDefaultMyMarkerIcon(point, "Я")
        }
        Log.d(TAG, "loadMyAvatar: КОНЕЦ")
    }

    private fun setDefaultMyMarkerIcon(point: Point, name: String) {
        Log.d(TAG, "setDefaultMyMarkerIcon: Установка дефолтной иконки для 'моего' маркера с именем '$name'")
        if (isFragmentDestroyed || !isAdded || context == null || isDetached || isRemoving) {
            Log.w(TAG, "setDefaultMyMarkerIcon: Фрагмент уничтожается, пропуск установки иконки")
            return
        }
        if (myMarker?.isValid == true && !myMarker!!.isValid) {
            Log.w(TAG, "setDefaultMyMarkerIcon: myMarker недействителен")
            return
        }
        if (myMarker?.isValid == true) {
            myMarker?.setIcon(
                ImageProvider.fromResource(requireContext(), R.drawable.ic_account_circle),
                IconStyle().setScale(0.7f)
            )
            myMarker?.setText(name)
        } else {
            Log.w(TAG, "setDefaultMyMarkerIcon: myMarker недействителен")
        }
    }

    private fun updateCityName(point: Point) {
        lastRequestedPoint = point
        Log.d(TAG, "updateCityName: запрос на $point")

        val searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
        searchManager.submit(
            point,
            16,
            SearchOptions(),
            object : Session.SearchListener {
                override fun onSearchResponse(response: Response) {
                    if (point != lastRequestedPoint) {
                        Log.d(TAG, "updateCityName: устаревший ответ, игнорируем")
                        return
                    }
                    val name = response.collection.children.firstOrNull()?.obj?.name ?: "Неизвестное место"
                    updateCityText(name)
                }

                override fun onSearchError(error: com.yandex.runtime.Error) {
                    Log.w(TAG, "updateCityName: ошибка $error")
                }
            }
        )
    }

    private fun updateCityText(text: String) {
        requireActivity().runOnUiThread {
            view?.findViewById<TextView>(R.id.cityTextView)?.text = text
            view?.findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.GONE
            Log.d(TAG, "updateCityText: UI обновлен '$text'")
        }
    }

    private fun setMarkerIconWithListener(
        userId: String,
        marker: PlacemarkMapObject,
        bitmap: Bitmap
    ) {
        if (!marker.isValid) return

        avatarCache[userId] = bitmap

        marker.setIcon(ImageProvider.fromBitmap(bitmap), IconStyle().setScale(1.2f))
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
        val avatarUrl = userInfo?.avatarUrl
            ?: "https://storage.yandexcloud.net/chatskii/profile_$userId.jpg"

        val placeholderBitmap = createPlaceholderBitmap(name, onlineStatus)

        Glide.with(this)
            .asBitmap()
            .load(avatarUrl)
            .circleCrop()
            .override(150, 150)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (isFragmentDestroyed || !isAdded) return
                    val finalBitmap = try {
                        createMarkerBitmap(resource, name, onlineStatus)
                    } catch (e: Exception) {
                        placeholderBitmap
                    }
                    if (placemark.isValid) {
                        setMarkerIconWithListener(userId, placemark, finalBitmap)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (!isFragmentDestroyed && isAdded && placemark.isValid) {
                        setMarkerIconWithListener(userId, placemark, placeholderBitmap)
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (!isFragmentDestroyed && isAdded && placemark.isValid) {
                        val finalBitmap = try {
                            createMarkerBitmap(placeholderBitmap, name, onlineStatus)
                        } catch (e: Exception) {
                            placeholderBitmap
                        }
                        setMarkerIconWithListener(userId, placemark, finalBitmap)
                    }
                }
            })
    }

    private fun getCurrentLocationAndCenter() {
        Log.d(TAG, "getCurrentLocationAndCenter: НАЧАЛО")
        if (isFragmentDestroyed || !isAdded) return

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "getCurrentLocationAndCenter: Разрешение есть, подписываемся на обновления")

            locationListener = object : LocationListener {
                override fun onLocationUpdated(location: Location) {
                    if (isFragmentDestroyed) return
                    Log.d(TAG, "getCurrentLocationAndCenter: onLocationUpdated")
                    val point = Point(location.position.latitude, location.position.longitude)
                    if (myLocation == null || distanceBetween(myLocation!!, point) > 10) {
                        Log.d(TAG, "getCurrentLocationAndCenter: Перемещение камеры к новой позиции")
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
                        Log.w(TAG, "getCurrentLocationAndCenter: Ошибка отписки после получения локации", e)
                    }
                }

                override fun onLocationStatusUpdated(locationStatus: LocationStatus) {
                    if (isFragmentDestroyed) return
                    Log.d(TAG, "getCurrentLocationAndCenter: onLocationStatusUpdated")
                }
            }

            try {
                locationManager.subscribeForLocationUpdates(
                    2000.0,
                    10L,
                    0.0,
                    true,
                    FilteringMode.OFF,
                    locationListener!!
                )
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocationAndCenter: Ошибка подписки на LocationManager", e)
            }

        } else {
            Log.w(TAG, "getCurrentLocationAndCenter: Нет разрешения ACCESS_FINE_LOCATION")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
        Log.d(TAG, "getCurrentLocationAndCenter: КОНЕЦ")
    }

    private fun createPlaceholderBitmap(name: String, onlineStatus: Boolean): Bitmap {
        Log.v(TAG, "createPlaceholderBitmap: НАЧАЛО")
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

        Log.v(TAG, "createPlaceholderBitmap: КОНЕЦ")
        return bitmap
    }

    private fun setDefaultMarkerIcon(placemark: PlacemarkMapObject, name: String) {
        Log.d(TAG, "setDefaultMarkerIcon: Установка дефолтной иконки с именем '$name'")
        if (placemark.isValid && !isFragmentDestroyed && isAdded) {
            placemark.setIcon(
                ImageProvider.fromResource(requireContext(), R.drawable.ic_user_marker),
                IconStyle().setScale(0.7f)
            )
            placemark.setText(name)
        } else {
            Log.w(TAG, "setDefaultMarkerIcon: Маркер недействителен или фрагмент не прикреплен")
        }
    }

    private fun createMarkerBitmap(avatar: Bitmap, name: String, onlineStatus: Boolean): Bitmap {
        Log.v(TAG, "createMarkerBitmap: НАЧАЛО")
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

        Log.v(TAG, "createMarkerBitmap: КОНЕЦ")
        return bitmap
    }

    private fun removeMarkerSafely(userId: String) {
        Log.d(TAG, "removeMarkerSafely: НАЧАЛО для $userId")
        markers[userId]?.let { marker ->
            if (marker.isValid) {
                try {
                    mapView.map.mapObjects.remove(marker)
                    Log.d(TAG, "removeMarkerSafely: Маркер для $userId удален")
                } catch (e: Exception) {
                    Log.w(TAG, "removeMarkerSafely: Ошибка удаления маркера для $userId", e)
                }
            } else {
                Log.d(TAG, "removeMarkerSafely: Маркер для $userId уже недействителен")
            }
        }
        markers.remove(userId)
        userInfoCache.remove(userId)
        isOnline.remove(userId)
        lastActiveTime.remove(userId)
        lastReceivedLocation.remove(userId)
        markerUpdateTimes.remove(userId)
        Log.d(TAG, "removeMarkerSafely: КОНЕЦ для $userId")
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
            dialogBuilder.setMessage("Имя: $name\nСтатус: $status\nПоследняя активность: $lastActive\n\nЭто ваш профиль")
        }

        dialogBuilder.show()
    }

    private fun sendMessage(userId: String) {
        Log.d(TAG, "sendMessage: Отправка сообщения пользователю $userId")
        if (isFragmentDestroyed || !isAdded) return

        val currentUserId = auth.currentUser?.uid ?: run {
            Log.w(TAG, "sendMessage: Пользователь не авторизован")
            Toast.makeText(requireContext(), "Необходимо войти в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }

        // Дополнительная проверка: не отправляем сообщение себе
        if (userId == currentUserId) {
            Log.w(TAG, "sendMessage: Попытка отправить сообщение самому себе")
            Toast.makeText(requireContext(), "Нельзя отправить сообщение самому себе", Toast.LENGTH_SHORT).show()
            return
        }

        val chatId = if (currentUserId < userId) "$currentUserId-$userId" else "$userId-$currentUserId"
        val chatRef = database.child("chats").child(chatId)

        // Проверяем, существует ли чат
        chatRef.get().addOnSuccessListener { chatSnapshot ->
            if (!isFragmentDestroyed && isAdded) {
                if (chatSnapshot.exists()) {
                    Log.d(TAG, "sendMessage: Чат с $userId уже существует, открываем.")
                    // Чат уже есть, сразу открываем
                    openChatActivity(chatId) // Передаём только chatId
                } else {
                    Log.d(TAG, "sendMessage: Создание нового чата с $userId")
                    // Загружаем данные другого пользователя
                    database.child("users").child(userId).get().addOnSuccessListener { userSnapshot ->
                        if (!isFragmentDestroyed && isAdded) {
                            val otherUser = userSnapshot.getValue(User::class.java)
                            if (otherUser != null) {
                                val otherUserName = otherUser.getFullName() ?: "Пользователь $userId"
                                val otherUserAvatarUrl = otherUser.profileImageUrl

                                // Загружаем данные текущего пользователя
                                database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(currentUserSnapshot: DataSnapshot) {
                                        if (!isFragmentDestroyed && isAdded) {
                                            val currentUser = currentUserSnapshot.getValue(User::class.java)
                                            if (currentUser != null) {
                                                val currentUserName = currentUser.getFullName() ?: "Я"
                                                val currentUserAvatarUrl = currentUser.profileImageUrl

                                                // Создаём объект Chat с правильной структурой для ChatDetailActivity
                                                val newChat = mapOf(
                                                    "creatorId" to currentUserId,
                                                    "creatorName" to currentUserName,
                                                    "imageUrl" to currentUserAvatarUrl,
                                                    "createdAt" to System.currentTimeMillis(),
                                                    // !! КЛЮЧЕВОЕ ИЗМЕНЕНИЕ: используем "participants" !!
                                                    "participants" to mapOf(currentUserId to true, userId to true),
                                                    "name" to otherUserName, // Имя другого пользователя для отображения
                                                    "avatarUrl" to otherUserAvatarUrl, // Аватар другого пользователя для отображения
                                                    "lastMessage" to "", // Или null
                                                    "lastMessageTime" to 0L // Или System.currentTimeMillis() если это время создания
                                                )

                                                // Сохраняем чат
                                                val updates = mutableMapOf<String, Any>()
                                                updates["chats/$chatId"] = newChat
                                                // Опционально: обновить списки чатов у обоих пользователей (если используется)
                                                // updates["user_chats/$currentUserId/$chatId"] = true
                                                // updates["user_chats/$userId/$chatId"] = true

                                                database.updateChildren(updates)
                                                    .addOnSuccessListener {
                                                        Log.d(TAG, "sendMessage: Чат с $userId успешно создан в базе данных.")
                                                        // После успешного создания открываем чат
                                                        openChatActivity(chatId) // Передаём только chatId
                                                    }
                                                    .addOnFailureListener { e ->
                                                        Log.e(TAG, "sendMessage: Ошибка сохранения чата в базе данных", e)
                                                        if (!isFragmentDestroyed && isAdded) {
                                                            Toast.makeText(requireContext(), "Ошибка сохранения чата: ${e.message}", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                            } else {
                                                Log.w(TAG, "sendMessage: Данные текущего пользователя не найдены")
                                                if (!isFragmentDestroyed && isAdded) {
                                                    Toast.makeText(requireContext(), "Ошибка: данные текущего пользователя", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            Log.d(TAG, "sendMessage: Fragment destroyed или detached после загрузки данных текущего пользователя.")
                                        }
                                    }

                                    override fun onCancelled(databaseError: DatabaseError) {
                                        Log.e(TAG, "sendMessage: Ошибка загрузки данных текущего пользователя", databaseError.toException())
                                        if (!isFragmentDestroyed && isAdded) {
                                            Toast.makeText(requireContext(), "Ошибка загрузки данных текущего пользователя", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            } else {
                                Log.w(TAG, "sendMessage: Пользователь с ID $userId не найден в базе данных")
                                if (!isFragmentDestroyed && isAdded) {
                                    Toast.makeText(requireContext(), "Пользователь не найден", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Log.d(TAG, "sendMessage: Fragment destroyed или detached после загрузки данных другого пользователя.")
                        }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "sendMessage: Ошибка загрузки данных пользователя $userId", e)
                        if (!isFragmentDestroyed && isAdded) {
                            Toast.makeText(requireContext(), "Ошибка загрузки данных пользователя: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Log.d(TAG, "sendMessage: Fragment destroyed или detached после проверки существования чата.")
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "sendMessage: Ошибка проверки существования чата", e)
            if (!isFragmentDestroyed && isAdded) {
                Toast.makeText(requireContext(), "Ошибка проверки чата: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Обновлённая функция openChatActivity - передаём только chatId
    private fun openChatActivity(chatId: String) {
        Log.d(TAG, "openChatActivity: Открытие чата $chatId")
        val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(Constants.CHAT_ID, chatId) // Используем Constants.CHAT_ID
            // Убираем putExtra для FRIEND_NAME и FRIEND_AVATAR_URL
        }
        startActivity(intent)
        // Дополнительно: можно добавить анимацию перехода
        // overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun addFriend(userId: String) {
        Log.d(TAG, "addFriend: НАЧАЛО для $userId")
        if (isFragmentDestroyed || !isAdded) return

        val currentUser = auth.currentUser?.uid ?: run {
            Log.w(TAG, "addFriend: Пользователь не авторизован")
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
                        Log.d(TAG, "addFriend: Пользователь $userId добавлен в друзья")
                        Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "addFriend: Ошибка добавления $userId в друзья", e)
                        Toast.makeText(requireContext(), "Ошибка добавления в друзья", Toast.LENGTH_SHORT).show()
                    }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "addFriend: Ошибка проверки статуса дружбы", e)
            friendsRef.setValue(true)
                .addOnSuccessListener {
                    Log.d(TAG, "addFriend: Пользователь $userId добавлен в друзья (после ошибки проверки)")
                    Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e2 ->
                    Log.e(TAG, "addFriend: Ошибка добавления $userId в друзья", e2)
                    Toast.makeText(requireContext(), "Ошибка добавления в друзья", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun openProfile(userId: String) {
        Log.d(TAG, "openProfile: Открытие профиля $userId")
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
        Log.v(TAG, "animateMarkerToBezierCubicWithRotation: НАЧАЛО")
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
        Log.v(TAG, "animateMarkerToBezierCubicWithRotation: КОНЕЦ")
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
        Log.d(TAG, "updateMyOnlineStatus: НАЧАЛО")
        if (isFragmentDestroyed || !isAdded) {
            Log.w(TAG, "updateMyOnlineStatus: Fragment not attached or destroyed, skipping")
            return
        }

        val uid = auth.currentUser?.uid ?: run {
            Log.w(TAG, "updateMyOnlineStatus: Пользователь не авторизован")
            return
        }
        val location = myLocation ?: run {
            Log.w(TAG, "updateMyOnlineStatus: Моя локация неизвестна")
            return
        }

        val userLocation = UserLocation(
            lat = location.latitude,
            lng = location.longitude,
            timestamp = System.currentTimeMillis()
        )

        database.child("user_locations").child(uid).setValue(userLocation)
            .addOnSuccessListener {
                Log.d(TAG, "updateMyOnlineStatus: Моё местоположение обновлено")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "updateMyOnlineStatus: Ошибка обновления моего местоположения", e)
            }
        Log.d(TAG, "updateMyOnlineStatus: КОНЕЦ")
    }

    private fun checkOtherUsersOnlineStatus() {
        Log.v(TAG, "checkOtherUsersOnlineStatus: НАЧАЛО")
        if (isFragmentDestroyed || !isAdded) {
            Log.w(TAG, "checkOtherUsersOnlineStatus: Fragment not attached or destroyed, skipping")
            return
        }

        val now = System.currentTimeMillis()
        markers.keys.forEach { userId ->
            if (userId == auth.currentUser?.uid) return@forEach

            val lastTime = lastActiveTime[userId] ?: 0
            val online = (now - lastTime) < 240000

            if (this.isOnline[userId] != online) {
                Log.d(TAG, "checkOtherUsersOnlineStatus: Статус изменился для $userId")
                this.isOnline[userId] = online
                markers[userId]?.let { marker ->
                    val name = userInfoCache[userId]?.name ?: userId
                    loadUserAvatar(userId, marker, name, online)
                }
            }
        }
        Log.v(TAG, "checkOtherUsersOnlineStatus: КОНЕЦ")
    }

    private fun restartLocationUpdates() {
        if (isFragmentDestroyed || !isAdded) return

        Log.d(TAG, "restartLocationUpdates: Перезапуск геолокации")

        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
            } catch (e: Exception) {
                Log.w(TAG, "restartLocationUpdates: Ошибка отписки", e)
            }
        }

        startLocationTracking()
    }

    private fun startLocationTracking() {
        if (isFragmentDestroyed || !isAdded) return

        locationListener = object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                if (isFragmentDestroyed) return
                Log.d(TAG, "startLocationTracking: Получена новая локация")
                val point = Point(location.position.latitude, location.position.longitude)
                myLocation = point
                updateMyMarker(point)
                updateMyOnlineStatus()
            }

            override fun onLocationStatusUpdated(locationStatus: LocationStatus) {
                if (isFragmentDestroyed) return
                Log.d(TAG, "startLocationTracking: Статус локации: $locationStatus")
            }
        }

        try {
            locationManager.subscribeForLocationUpdates(
                2000.0,
                10L,
                0.0,
                true,
                FilteringMode.OFF,
                locationListener!!
            )
            Log.d(TAG, "startLocationTracking: Подписка на обновления локации успешна")
        } catch (e: Exception) {
            Log.e(TAG, "startLocationTracking: Ошибка подписки", e)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: НАЧАЛО")
        mapView.onStart()
        MapKitFactory.getInstance().onStart()
        Log.d(TAG, "onStart: КОНЕЦ")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: НАЧАЛО")
        mapView.onStop()
        MapKitFactory.getInstance().onStop()

        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
                Log.d(TAG, "onStop: LocationListener отписан")
            } catch (e: Exception) {
                Log.w(TAG, "onStop: Ошибка отписки LocationListener", e)
            }
        }

        handler.removeCallbacksAndMessages(null)
        onlineHandler.removeCallbacksAndMessages(null)
        cityUpdateHandler.removeCallbacksAndMessages(null)
        locationUpdateHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)

        removeLocationsListener()

        Log.d(TAG, "onStop: КОНЕЦ")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: НАЧАЛО")

        isFragmentDestroyed = true

        geocoderExecutor.shutdownNow()

        handler.removeCallbacksAndMessages(null)
        locationRestartHandler.removeCallbacksAndMessages(null)
        onlineHandler.removeCallbacksAndMessages(null)
        cityUpdateHandler.removeCallbacksAndMessages(null)
        locationUpdateHandler.removeCallbacksAndMessages(null)
        geocodeHandler.removeCallbacksAndMessages(null)

        try {
            Glide.with(this).pauseAllRequests()
            Log.d(TAG, "onDestroyView: Glide остановлен")
        } catch (e: Exception) {
            Log.w(TAG, "onDestroyView: Ошибка остановки Glide", e)
        }

        try {
            searchSession?.cancel()
            searchSession = null
            Log.d(TAG, "onDestroyView: Поиск остановлен")
        } catch (e: Exception) {
            Log.w(TAG, "onDestroyView: Ошибка остановки поиска", e)
        }

        removeLocationsListener()

        locationListener?.let {
            try {
                locationManager.unsubscribe(it)
                Log.d(TAG, "onDestroyView: LocationListener отписан")
            } catch (e: Exception) {
                Log.w(TAG, "onDestroyView: Ошибка отписки LocationListener", e)
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

            Log.d(TAG, "onDestroyView: Все коллекции очищены")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroyView: Ошибка при очистке данных", e)
        }

        lastCityPoint = null
        myMarker = null
        myLocation = null
        lastGeocodePoint = null

        Log.d(TAG, "onDestroyView: КОНЕЦ")
    }
}