package com.example.chatapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.databinding.ActivityLocationBinding
import com.example.chatapp.models.LocationSettings
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.google.android.gms.location.*
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

class LocationActivity : AppCompatActivity(), CameraListener, InputListener {

    private lateinit var binding: ActivityLocationBinding
    private lateinit var mapView: MapView
    private lateinit var map: com.yandex.mapkit.map.Map
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference
    private val otherUserMarkers = mutableMapOf<String, PlacemarkMapObject>() // Только для других пользователей
    private var myMarker: PlacemarkMapObject? = null // Только для себя
    private var currentLocation: Point? = null
    private var locationUpdatesRunning = false

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1001
        private const val DEFAULT_UPDATE_INTERVAL = 5 // minutes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Инициализация карты
        mapView = binding.mapView
        map = mapView.mapWindow.map
        map.addCameraListener(this)
        map.addInputListener(this)

        // Настройки карты
        with(map) {
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
            isRotateGesturesEnabled = true
            setMapStyle("normal")
        }

        // Обработчик кнопки настроек
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, LocationSettingsActivity::class.java))
        }

        // Настройка периодического обновления локации
        setupLocationUpdates()

        if (checkLocationPermission()) {
            enableMyLocation()
            loadUserLocations()
        }

        // Слушатель изменений настроек
        setupSettingsListener()
    }

    override fun onStart() {
        super.onStart()
        try {
            MapKitFactory.getInstance().onStart()
            mapView.onStart()

            // Проверка необходимости обновления позиции
            if (currentLocation == null && checkLocationPermission()) {
                enableMyLocation()
            }
        } catch (e: Exception) {
            Log.e("LocationActivity", "Map start error", e)
            Toast.makeText(this, "Ошибка запуска карты", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkLocationPermission() && !locationUpdatesRunning) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        try {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        } catch (e: Exception) {
            Log.e("LocationActivity", "Map stop error", e)
        }
    }

    override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {
        // Обработка тапа по карте (не по маркеру)
    }

    override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {
        // Обработка долгого тапа
    }

    override fun onCameraPositionChanged(
        map: com.yandex.mapkit.map.Map,
        cameraPosition: CameraPosition,
        cameraUpdateReason: CameraUpdateReason,
        finished: Boolean
    ) {
        // Обработка изменения позиции камеры
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        Log.d("LocationActivity", "Trying to get last location...")
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                Log.d("LocationActivity", "Location received: ${it.latitude}, ${it.longitude}")
                currentLocation = Point(it.latitude, it.longitude)
                moveCamera(currentLocation!!, 14f)
                updateUserLocation(it.latitude, it.longitude)
                showMyLocation(it.latitude, it.longitude) // Добавляем маркер сразу
            } ?: run {
                Log.w("LocationActivity", "Last location is null")
                Toast.makeText(this, "Не удалось получить локацию", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            Log.e("LocationActivity", "Error getting location", e)
            Toast.makeText(this, "Ошибка получения локации", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationUpdate", "New location: ${location.latitude}, ${location.longitude}")
                    currentLocation = Point(location.latitude, location.longitude)
                    updateUserLocation(location.latitude, location.longitude)
                    showMyLocation(location.latitude, location.longitude) // Обновляем маркер
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!checkLocationPermission()) return

        val userId = auth.currentUser?.uid ?: return

        database.child("location_settings").child(userId).get().addOnSuccessListener { snapshot ->
            val settings = snapshot.getValue(LocationSettings::class.java)
            val updateInterval = settings?.updateInterval ?: DEFAULT_UPDATE_INTERVAL

            val locationRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    updateInterval * 60 * 1000L
                ).setMinUpdateIntervalMillis(5000).build()
            } else {
                @Suppress("DEPRECATION")
                LocationRequest.create().apply {
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    interval = updateInterval * 60 * 1000L
                    fastestInterval = 5000
                }
            }

            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                locationUpdatesRunning = true
                Log.d("LocationActivity", "Location updates started")
            } catch (e: SecurityException) {
                Log.e("LocationActivity", "Security exception: ${e.message}")
            } catch (e: Exception) {
                Log.e("LocationActivity", "Error starting location updates", e)
            }
        }
    }

    private fun stopLocationUpdates() {
        if (locationUpdatesRunning) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                locationUpdatesRunning = false
                Log.d("LocationActivity", "Location updates stopped")
            } catch (e: Exception) {
                Log.e("LocationActivity", "Error stopping location updates", e)
            }
        }
    }

    private fun updateUserLocation(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val location = UserLocation(lat, lng, System.currentTimeMillis())
        database.child("user_locations").child(userId).setValue(location)
            .addOnSuccessListener {
                Log.d("LocationActivity", "Location updated in Firebase: $lat, $lng")
            }
            .addOnFailureListener { e ->
                Log.e("LocationActivity", "Error updating location in Firebase", e)
            }
    }

    private fun loadUserLocations() {
        Log.d("LocationActivity", "Loading user locations from Firebase...")
        database.child("user_locations").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d("LocationActivity", "Received ${snapshot.childrenCount} locations from Firebase")

                // Удаляем только маркеры других пользователей
                otherUserMarkers.values.forEach { marker ->
                    map.mapObjects.remove(marker)
                }
                otherUserMarkers.clear()

                // Обрабатываем новые данные
                for (ds in snapshot.children) {
                    val userId = ds.key ?: continue

                    // Пропускаем себя - свой маркер обрабатывается отдельно
                    if (userId == auth.currentUser?.uid) continue

                    val location = ds.getValue(UserLocation::class.java) ?: continue
                    val userPoint = Point(location.lat, location.lng)

                    // Проверяем настройки приватности
                    checkLocationVisibility(userId, userPoint)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("LocationActivity", "Load locations error: ${error.message}")
                Toast.makeText(this@LocationActivity, "Ошибка загрузки локаций", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun checkLocationVisibility(userId: String, point: Point) {
        database.child("location_settings").child(userId).get()
            .addOnSuccessListener { settingsSnap ->
                val settings = settingsSnap.getValue(LocationSettings::class.java)

                when {
                    settings?.enabled != true -> return@addOnSuccessListener
                    settings.visibility == "none" -> return@addOnSuccessListener
                    settings.visibility == "friends" && !isFriend(userId) -> return@addOnSuccessListener
                    else -> {
                        Log.d("VisibilityCheck", "Showing marker for $userId")
                        loadUserAndAddMarker(userId, point)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("LocationActivity", "Error loading settings for $userId", e)
            }
    }

    private fun loadUserAndAddMarker(userId: String, point: Point) {
        database.child("users").child(userId).get().addOnSuccessListener { userSnap ->
            val user = userSnap.getValue(User::class.java)
            user?.let {
                addOtherUserMarker(userId, it.getFullName(), point)
            } ?: run {
                Log.w("LocationActivity", "User not found: $userId")
            }
        }.addOnFailureListener { e ->
            Log.e("LocationActivity", "Error loading user $userId", e)
        }
    }

    private fun addOtherUserMarker(userId: String, name: String, position: Point) {
        try {
            Log.d("MarkerDebug", "Adding marker for other user $userId at $position")

            val marker = map.mapObjects.addPlacemark().apply {
                geometry = position
                setIcon(ImageProvider.fromResource(this@LocationActivity,
                    R.drawable.blue_marker_45x45
                ))
                setIconStyle(IconStyle().apply {
                    scale = 1.0f
                    zIndex = 1.0f
                })
                setText(name)
                addTapListener { mapObject, point ->
                    openUserProfile(userId)
                    true
                }
            }

            otherUserMarkers[userId] = marker
        } catch (e: Exception) {
            Log.e("LocationActivity", "Marker creation error", e)
        }
    }

    private fun showMyLocation(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val point = Point(lat, lng)

        Log.d("MarkerDebug", "Adding/updating MY marker at $point")

        database.child("users").child(userId).get().addOnSuccessListener { userSnap ->
            val user = userSnap.getValue(User::class.java)
            if (user == null) {
                Log.w("LocationActivity", "Current user not found in database")
                return@addOnSuccessListener
            }

            runOnUiThread {
                try {
                    // Удаляем старый маркер если есть
                    myMarker?.let { marker ->
                        map.mapObjects.remove(marker)
                    }

                    // Создаем новый маркер
                    myMarker = map.mapObjects.addPlacemark().apply {
                        geometry = point
                        setIcon(ImageProvider.fromResource(this@LocationActivity,
                            R.drawable.red_marker_45x45
                        ))
                        setIconStyle(IconStyle().apply {
                            scale = 1.5f
                            zIndex = 10.0f
                        })
                        setText("Я (${user.getFullName()})")
                    }

                    Log.d("MarkerDebug", "New marker created: $myMarker")
                    Toast.makeText(this@LocationActivity, "Мой маркер обновлён", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("MarkerDebug", "Marker creation error", e)
                    Toast.makeText(this@LocationActivity, "Ошибка создания маркера: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener { e ->
            Log.e("MarkerDebug", "Error loading user data", e)
        }
    }

    private fun openUserProfile(userId: String) {
        startActivity(Intent(this, UserProfileActivity::class.java).apply {
            putExtra("USER_ID", userId)
        })
    }

    private fun moveCamera(point: Point, zoom: Float = 14f) {
        try {
            map.move(
                CameraPosition(point, zoom, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 1f),
                null
            )
            Log.d("LocationActivity", "Camera moved to $point")
        } catch (e: Exception) {
            Log.e("LocationActivity", "Camera move error", e)
        }
    }

    private fun isFriend(userId: String): Boolean {
        // Заглушка - всегда возвращаем true для теста
        return true
    }

    private fun setupSettingsListener() {
        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d("SettingsListener", "Location settings changed")
                    loadUserLocations() // Перезагружаем маркеры
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("SettingsListener", error.message)
                }
            })
    }

    private fun checkLocationPermission(): Boolean {
        return if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQ
            )
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
            loadUserLocations()
            startLocationUpdates()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopLocationUpdates()
            // Удаляем все маркеры при уничтожении активности
            myMarker?.let { map.mapObjects.remove(it) }
            otherUserMarkers.values.forEach { map.mapObjects.remove(it) }
        } catch (e: Exception) {
            Log.e("LocationActivity", "Cleanup error", e)
        }
    }
}