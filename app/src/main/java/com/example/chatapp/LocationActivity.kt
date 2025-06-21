package com.example.chatapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.databinding.ActivityLocationBinding
import com.example.chatapp.models.LocationSettings
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.example.chatapp.services.LocationUpdateService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
import java.util.concurrent.ConcurrentHashMap

class LocationActivity : AppCompatActivity(), CameraListener, InputListener {

    private lateinit var binding: ActivityLocationBinding
    private lateinit var mapView: MapView
    private lateinit var map: com.yandex.mapkit.map.Map
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var auth: FirebaseAuth

    private val database = FirebaseDatabase.getInstance().reference
    private val otherUserMarkers = ConcurrentHashMap<String, PlacemarkMapObject>()
    private var myMarker: PlacemarkMapObject? = null
    private var currentLocation: Point? = null
    private val TAG = "LocationActivity"
    private var isMapInitialized = false

    // Слушатели данных
    private var locationSettingsListener: ValueEventListener? = null
    private var userLocationsListener: ValueEventListener? = null
    private var friendsListener: ValueEventListener? = null
    private val friendList = mutableSetOf<String>()

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1001
        private const val BACKGROUND_LOCATION_PERMISSION_REQ = 1002

        private val FOREGROUND_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        private val BACKGROUND_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapView
        map = mapView.mapWindow.map

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            map.addCameraListener(this)
            map.addInputListener(this)

            with(map) {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isRotateGesturesEnabled = true
                setMapStyle("normal")
            }

            binding.btnSettings.setOnClickListener {
                startActivity(Intent(this, LocationSettingsActivity::class.java))
            }

            // Загружаем список друзей до проверки разрешений
            loadFriendsList()
            checkLocationPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            Toast.makeText(this, "Ошибка инициализации карты", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadFriendsList() {
        val currentUserId = auth.currentUser?.uid ?: return

        friendsListener?.let {
            database.child("friends").child(currentUserId).removeEventListener(it)
        }

        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                friendList.clear()
                for (ds in snapshot.children) {
                    val friendId = ds.key
                    if (friendId != null && ds.getValue(Boolean::class.java) == true) {
                        friendList.add(friendId)
                    }
                }
                Log.d(TAG, "Friend list updated: ${friendList.size} friends")

                // При изменении списка друзей перезагружаем маркеры
                if (isMapInitialized) {
                    reloadAllMarkers()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Friends load cancelled", error.toException())
            }
        }

        database.child("friends").child(currentUserId)
            .addValueEventListener(friendsListener!!)
    }

    private fun checkLocationPermissions() {
        Log.d(TAG, "Checking permissions")
        when {
            hasAllPermissions() -> {
                Log.d(TAG, "All permissions granted")
                initializeMapFeatures()
            }
            hasForegroundPermissions() -> {
                Log.d(TAG, "Requesting background permission")
                requestBackgroundPermission()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) -> {
                Log.d(TAG, "Showing rationale")
                showRationaleBeforeRequest()
            }
            else -> {
                Log.d(TAG, "Requesting foreground permissions")
                requestForegroundPermissions()
            }
        }
    }

    private fun hasForegroundPermissions(): Boolean {
        return FOREGROUND_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestForegroundPermissions() {
        ActivityCompat.requestPermissions(
            this,
            FOREGROUND_PERMISSIONS,
            LOCATION_PERMISSION_REQ
        )
    }

    private fun requestBackgroundPermission() {
        if (BACKGROUND_PERMISSION.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                BACKGROUND_PERMISSION,
                BACKGROUND_LOCATION_PERMISSION_REQ
            )
        } else {
            initializeMapFeatures()
        }
    }

    private fun showRationaleBeforeRequest() {
        AlertDialog.Builder(this)
            .setTitle("Требуется доступ к местоположению")
            .setMessage("Для отображения карты и вашего местоположения необходимо предоставить разрешения. Рекомендуется дать разрешение! ( Разрешить в любом режиме)")
            .setPositiveButton("OK") { _, _ ->
                Log.d(TAG, "User agreed to permissions")
                requestForegroundPermissions()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                Log.d(TAG, "User denied permissions")
                dialog.dismiss()
                Toast.makeText(this, "Функции карты будут ограничены", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: $requestCode")

        when (requestCode) {
            LOCATION_PERMISSION_REQ -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d(TAG, "Foreground permissions granted")
                    requestBackgroundPermission()
                } else {
                    Log.d(TAG, "Foreground permissions denied")
                    handlePermissionDenial()
                }
            }
            BACKGROUND_LOCATION_PERMISSION_REQ -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Background permission granted")
                    initializeMapFeatures()
                } else {
                    Log.d(TAG, "Background permission denied")
                    Toast.makeText(
                        this,
                        "Фоновое обновление недоступно",
                        Toast.LENGTH_LONG
                    ).show()
                    initializeMapFeatures()
                }
            }
        }
    }

    private fun handlePermissionDenial() {
        Log.d(TAG, "Handling permission denial")
        if (permissionsPermanentlyDenied()) {
            Toast.makeText(
                this,
                "Вы отказались от разрешений. Вы можете включить их в настройках приложения.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "Функции карты ограничены без разрешения на местоположение",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun permissionsPermanentlyDenied(): Boolean {
        return FOREGROUND_PERMISSIONS.any {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it) &&
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        Log.d(TAG, "Enabling location")
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    Log.d(TAG, "Location received: ${it.latitude}, ${it.longitude}")
                    currentLocation = Point(it.latitude, it.longitude)
                    moveCamera(currentLocation!!, 14f)
                    updateUserLocation(it.latitude, it.longitude)
                    showMyLocation(it.latitude, it.longitude)
                } ?: run {
                    Log.w(TAG, "Location is null")
                    Toast.makeText(this, "Не удалось получить местоположение", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Location request failed", e)
                Toast.makeText(this, "Ошибка получения местоположения", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableMyLocation error", e)
            Toast.makeText(this, "Ошибка доступа к местоположению", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUserLocation(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val location = UserLocation(lat, lng, System.currentTimeMillis())
        database.child("user_locations").child(userId).setValue(location)
            .addOnSuccessListener { Log.d(TAG, "Location updated in database") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to update location", e) }
    }

    private fun showMyLocation(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val point = Point(lat, lng)

        database.child("users").child(userId).get().addOnSuccessListener { userSnap ->
            try {
                val user = userSnap.getValue(User::class.java)
                user?.let {
                    myMarker?.let { old -> map.mapObjects.remove(old) }
                    myMarker = map.mapObjects.addPlacemark().apply {
                        geometry = point
                        setIcon(ImageProvider.fromResource(this@LocationActivity, R.drawable.red_marker_45x45))
                        setIconStyle(IconStyle().apply {
                            scale = 1.5f
                            zIndex = 10.0f
                        })
                        setText("Я (${it.getFullName()})")
                        Log.d(TAG, "My location marker added")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing user data", e)
                myMarker = map.mapObjects.addPlacemark().apply {
                    geometry = point
                    setIcon(ImageProvider.fromResource(this@LocationActivity, R.drawable.red_marker_45x45))
                    setIconStyle(IconStyle().apply {
                        scale = 1.5f
                        zIndex = 10.0f
                    })
                    setText("Я")
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get user data", e)
            myMarker = map.mapObjects.addPlacemark().apply {
                geometry = point
                setIcon(ImageProvider.fromResource(this@LocationActivity, R.drawable.red_marker_45x45))
                setIconStyle(IconStyle().apply {
                    scale = 1.5f
                    zIndex = 10.0f
                })
                setText("Я")
            }
        }
    }

    private fun moveCamera(point: Point, zoom: Float = 14f) {
        try {
            map.move(
                CameraPosition(point, zoom, 0f, 0f),
                Animation(Animation.Type.SMOOTH, 1f),
                null
            )
            Log.d(TAG, "Camera moved to position")
        } catch (e: Exception) {
            Log.e(TAG, "moveCamera error", e)
        }
    }

    private fun initializeMapFeatures() {
        Log.d(TAG, "Initializing map features")
        isMapInitialized = true
        enableMyLocation()
        setupUserLocationsListener()
        startLocationService()
        setupSettingsListener()
    }

    private fun setupUserLocationsListener() {
        userLocationsListener?.let {
            database.child("user_locations").removeEventListener(it)
        }

        userLocationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "User locations data changed")
                val userIdsFromSnapshot = mutableSetOf<String>()

                for (ds in snapshot.children) {
                    val userId = ds.key ?: continue
                    if (userId == auth.currentUser?.uid) continue

                    try {
                        val location = ds.getValue(UserLocation::class.java) ?: continue
                        val userPoint = Point(location.lat, location.lng)
                        userIdsFromSnapshot.add(userId)

                        // Проверяем, нужно ли обновить маркер
                        val existingMarker = otherUserMarkers[userId]
                        if (existingMarker != null) {
                            if (existingMarker.geometry != userPoint) {
                                existingMarker.geometry = userPoint
                                Log.d(TAG, "Updated position for user $userId")
                            }
                        } else {
                            // Если маркер не существует, проверяем видимость
                            Log.d(TAG, "New location for user $userId, checking visibility")
                            checkLocationVisibility(userId, userPoint)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing location for user $userId", e)
                    }
                }

                // Удаляем маркеры пользователей, которых больше нет в базе
                val toRemove = otherUserMarkers.keys.filter { it !in userIdsFromSnapshot }
                toRemove.forEach { userId ->
                    otherUserMarkers.remove(userId)?.let {
                        map.mapObjects.remove(it)
                        Log.d(TAG, "Removed marker for user $userId")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "User locations load cancelled", error.toException())
                Toast.makeText(this@LocationActivity, "Ошибка загрузки локаций", Toast.LENGTH_SHORT).show()
            }
        }

        database.child("user_locations").addValueEventListener(userLocationsListener!!)
    }

    private fun reloadAllMarkers() {
        Log.d(TAG, "Reloading all markers")
        // Удаляем все существующие маркеры
        otherUserMarkers.values.forEach { map.mapObjects.remove(it) }
        otherUserMarkers.clear()

        // Перезагружаем все местоположения
        database.child("user_locations").get().addOnSuccessListener { snapshot ->
            for (ds in snapshot.children) {
                val userId = ds.key ?: continue
                if (userId == auth.currentUser?.uid) continue

                try {
                    val location = ds.getValue(UserLocation::class.java) ?: continue
                    val point = Point(location.lat, location.lng)
                    checkLocationVisibility(userId, point)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reloading marker for $userId", e)
                }
            }
        }
    }

    private fun checkLocationVisibility(userId: String, point: Point) {
        database.child("location_settings").child(userId).get()
            .addOnSuccessListener { settingsSnap ->
                try {
                    val settings = settingsSnap.getValue(LocationSettings::class.java)
                    if (settings == null) {
                        Log.d(TAG, "No settings for user $userId")
                        return@addOnSuccessListener
                    }

                    Log.d(TAG, "Settings for $userId: enabled=${settings.enabled}, visibility=${settings.visibility}")

                    if (!settings.enabled) {
                        Log.d(TAG, "Location disabled for user $userId")
                        return@addOnSuccessListener
                    }

                    when (settings.visibility) {
                        "none" -> {
                            Log.d(TAG, "Visibility none for user $userId")
                            return@addOnSuccessListener
                        }
                        "friends" -> {
                            Log.d(TAG, "Checking friendship for user $userId")
                            if (friendList.contains(userId)) {
                                loadUserAndAddMarker(userId, point)
                            } else {
                                Log.d(TAG, "User $userId is not a friend")
                            }
                        }
                        else -> { // "everyone" или другое
                            Log.d(TAG, "Visibility everyone for user $userId")
                            loadUserAndAddMarker(userId, point)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing location settings for $userId", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load location settings for $userId", e)
            }
    }

    private fun loadUserAndAddMarker(userId: String, point: Point) {
        // Проверяем, не добавлен ли уже маркер
        if (otherUserMarkers.containsKey(userId)) {
            Log.d(TAG, "Marker already exists for user $userId")
            return
        }

        database.child("users").child(userId).get()
            .addOnSuccessListener { userSnap ->
                try {
                    val user = userSnap.getValue(User::class.java)
                    if (user != null) {
                        addOtherUserMarker(userId, user.getFullName(), point)
                    } else {
                        Log.d(TAG, "User data null for $userId")
                        addOtherUserMarker(userId, "User $userId", point)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing user data for $userId", e)
                    addOtherUserMarker(userId, "User $userId", point)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to load user data for $userId", e)
                addOtherUserMarker(userId, "User $userId", point)
            }
    }

    private fun addOtherUserMarker(userId: String, name: String, position: Point) {
        try {
            // Проверяем, не добавлен ли уже маркер
            if (otherUserMarkers.containsKey(userId)) {
                Log.d(TAG, "Marker for user $userId already exists")
                return
            }

            val marker = map.mapObjects.addPlacemark().apply {
                geometry = position
                setIcon(ImageProvider.fromResource(this@LocationActivity, R.drawable.blue_marker_45x45))
                setIconStyle(IconStyle().apply {
                    scale = 1.0f
                    zIndex = 1.0f
                })
                setText(name)
                addTapListener { _, _ ->
                    openUserProfile(userId)
                    true
                }
            }
            otherUserMarkers[userId] = marker
            Log.d(TAG, "Added marker for user $userId: $name")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user marker for $userId", e)
        }
    }

    private fun openUserProfile(userId: String) {
        try {
            startActivity(Intent(this, UserProfileActivity::class.java).apply {
                putExtra("USER_ID", userId)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open user profile", e)
            Toast.makeText(this, "Не удалось открыть профиль", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsListener() {
        val userId = auth.currentUser?.uid ?: return

        // Удаляем предыдущий слушатель
        locationSettingsListener?.let {
            database.child("location_settings").child(userId).removeEventListener(it)
        }

        locationSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "Location settings changed")
                // Обновляем только свои настройки
                updateMyLocationMarker()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Location settings load cancelled", error.toException())
            }
        }

        database.child("location_settings").child(userId)
            .addValueEventListener(locationSettingsListener!!)
    }

    private fun updateMyLocationMarker() {
        val userId = auth.currentUser?.uid ?: return
        database.child("users").child(userId).get()
            .addOnSuccessListener { userSnap ->
                try {
                    val user = userSnap.getValue(User::class.java)
                    user?.let {
                        myMarker?.setText("Я (${it.getFullName()})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating user name", e)
                }
            }
    }

    private fun startLocationService() {
        try {
            val serviceIntent = Intent(this, LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Location service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location service", e)
            Toast.makeText(this, "Ошибка запуска сервиса", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {}
    override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {}
    override fun onCameraPositionChanged(
        map: com.yandex.mapkit.map.Map,
        cameraPosition: CameraPosition,
        cameraUpdateReason: CameraUpdateReason,
        finished: Boolean
    ) {}

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        Log.d(TAG, "Activity started")
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions() && currentLocation == null) {
            enableMyLocation()
        }
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
        Log.d(TAG, "Activity stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Удаляем все слушатели
            locationSettingsListener?.let {
                database.child("location_settings").child(auth.currentUser?.uid ?: "").removeEventListener(it)
            }

            userLocationsListener?.let {
                database.child("user_locations").removeEventListener(it)
            }

            friendsListener?.let {
                database.child("friends").child(auth.currentUser?.uid ?: "").removeEventListener(it)
            }

            // Очищаем карту
            myMarker?.let { map.mapObjects.remove(it) }
            otherUserMarkers.values.forEach { map.mapObjects.remove(it) }
            otherUserMarkers.clear()

            Log.d(TAG, "Activity destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }

    private fun hasAllPermissions(): Boolean {
        val hasForeground = hasForegroundPermissions()
        val hasBackground = if (BACKGROUND_PERMISSION.isNotEmpty()) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasForeground && hasBackground
    }
}