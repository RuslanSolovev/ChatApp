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
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private val markers = mutableMapOf<String, PlacemarkMapObject>()
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

    // Исправленные кэши - теперь хранятся в ViewModel-стиле
    private object CacheManager {
        val userInfoCache = mutableMapOf<String, Pair<String, String?>>() // userId -> (name, avatarUrl?)
        val avatarBitmaps = mutableMapOf<String, Bitmap>()
        val friendshipCache = mutableMapOf<String, Boolean>()
        val pendingFriendRequests = mutableSetOf<String>()
        val tapListeners = mutableMapOf<String, MapObjectTapListener>()

        fun clearAll() {
            userInfoCache.clear()
            avatarBitmaps.clear()
            friendshipCache.clear()
            pendingFriendRequests.clear()
            tapListeners.clear()
        }

        fun clearUser(userId: String) {
            userInfoCache.remove(userId)
            avatarBitmaps.remove(userId)
            friendshipCache.remove(userId)
            pendingFriendRequests.remove(userId)
            tapListeners.remove(userId)
        }
    }

    companion object {
        private const val TAG = "MapFragment"
        private const val MIN_DISTANCE_FOR_UPDATE = 10.0 // метров
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 секунд
        private const val AVATAR_SIZE = 120
        private const val MARKER_WIDTH = 150
        private const val MARKER_HEIGHT = 170
        private const val GEOLOCATION_TIMEOUT = 10000L // 10 секунд на геокодирование

        // Настройки Yandex Cloud Storage
        private const val BUCKET_NAME = "chatskii"
        private const val BASE_URL = "https://storage.yandexcloud.net/$BUCKET_NAME/"

        // Ключи для savedInstanceState
        private const val KEY_MY_LOCATION_LAT = "my_location_lat"
        private const val KEY_MY_LOCATION_LNG = "my_location_lng"
    }

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        // Инициализация MapKit в onAttach - правильное место
        MapKitFactory.initialize(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Восстановление состояния
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
    }

    private fun initViews(view: View) {
        mapView = view.findViewById(R.id.mapview)
        cityTextView = view.findViewById(R.id.cityTextView)
        cityProgressBar = view.findViewById(R.id.cityProgressBar)
        friendsButton = view.findViewById(R.id.friendsButton)
        findMeButton = view.findViewById(R.id.findMeButton)

        cityTextView.text = "Загрузка..."
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

        // Устанавливаем начальную позицию камеры (Москва)
        val initialPoint = Point(55.7558, 37.6173)
        mapView.map.move(
            CameraPosition(initialPoint, 10.0f, 0.0f, 0.0f),
            Animation(Animation.Type.SMOOTH, 0.5f),
            null
        )

        // Обработчик движения камеры
        mapView.map.addCameraListener(object : CameraListener {
            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (finished) {
                    updateLocationInfo(cameraPosition.target)
                }
            }
        })

        // Отладочный тап на карте
        mapView.map.addInputListener(object : com.yandex.mapkit.map.InputListener {
            override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {
                Log.d(TAG, "Тап по карте: $point")
                // Можно показать координаты
                cityTextView.text = String.format(
                    Locale.getDefault(),
                    "Ш: %.6f, Д: %.6f",
                    point.latitude,
                    point.longitude
                )
            }

            override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {
                // Не используется
            }
        })
    }

    private fun loadInitialData() {
        updateLocationInfo(mapView.map.cameraPosition.target)
        loadUserLocations()
        loadMyLocation()
        startPeriodicUpdates()
    }

    private fun loadUserLocations() {
        val locationsRef = database.child("last_locations")
        locationsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFragmentDestroyed || !isAdded) {
                    Log.w(TAG, "Фрагмент не активен, игнорируем обновление")
                    return
                }

                Log.d(TAG, "Получено ${snapshot.childrenCount} локаций")
                val currentUsers = HashSet<String>()

                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key ?: continue

                    // Пропускаем себя
                    if (userId == auth.currentUser?.uid) {
                        continue
                    }

                    try {
                        val userLocation = userSnapshot.getValue(UserLocation::class.java)

                        if (userLocation == null || !userLocation.isValid()) {
                            Log.w(TAG, "Невалидная локация для пользователя $userId")
                            continue
                        }

                        // Проверяем, не устарела ли локация (больше 5 минут)
                        val locationAge = System.currentTimeMillis() - userLocation.timestamp
                        if (locationAge > 5 * 60 * 1000) {
                            Log.d(TAG, "Локация пользователя $userId устарела ($locationAge мс)")
                            // Не удаляем маркер, но пометим его как неактивный
                            markers[userId]?.let { marker ->
                                updateMarkerAppearance(marker, userId, false)
                            }
                            continue
                        }

                        currentUsers.add(userId)
                        val point = Point(userLocation.lat, userLocation.lng)
                        val isOnline = locationAge < 2 * 60 * 1000 // 2 минуты для онлайн-статуса

                        if (markers.containsKey(userId)) {
                            // Обновляем существующий маркер
                            updateExistingMarker(userId, point, isOnline)
                        } else {
                            // Создаем новый маркер
                            createNewMarker(userId, point, isOnline)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки локации пользователя $userId", e)
                    }
                }

                // Удаляем маркеры пользователей, которых больше нет в списке
                removeOldMarkers(currentUsers)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки локаций пользователей: ${error.message}")
                Toast.makeText(
                    requireContext(),
                    "Ошибка загрузки данных о пользователях",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun updateExistingMarker(userId: String, point: Point, isOnline: Boolean) {
        markers[userId]?.let { marker ->
            // Проверяем, изменилась ли позиция значительно
            val oldPoint = marker.geometry
            if (distanceBetween(oldPoint, point) > MIN_DISTANCE_FOR_UPDATE) {
                marker.geometry = point
                Log.d(TAG, "Обновлена позиция маркера $userId")
            }

            // Обновляем внешний вид маркера (онлайн статус)
            updateMarkerAppearance(marker, userId, isOnline)
        }
    }

    private fun createNewMarker(userId: String, point: Point, isOnline: Boolean) {
        val placemark = mapView.map.mapObjects.addPlacemark(point)
        placemark.zIndex = 100f
        markers[userId] = placemark

        // Сначала устанавливаем placeholder
        val placeholder = createPlaceholderBitmap("?", isOnline)
        placemark.setIcon(ImageProvider.fromBitmap(placeholder))

        // Добавляем обработчик нажатия
        val tapListener = MapObjectTapListener { _, _ ->
            onMarkerTapped(userId)
            true
        }
        placemark.addTapListener(tapListener)
        CacheManager.tapListeners[userId] = tapListener

        // Загружаем информацию о пользователе асинхронно
        loadUserInfoAndUpdateMarker(userId, placemark, isOnline)
    }

    private fun loadUserInfoAndUpdateMarker(userId: String, placemark: PlacemarkMapObject, isOnline: Boolean) {
        lifecycleScope.launch {
            try {
                val (name, avatarUrl) = loadUserInfo(userId)

                // Обновляем маркер с полученной информацией
                updateMarkerWithUserInfo(userId, name, avatarUrl, isOnline, placemark)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки информации пользователя $userId", e)
                // В случае ошибки используем placeholder с именем "Пользователь"
                updateMarkerWithUserInfo(userId, "Пользователь", null, isOnline, placemark)
            }
        }
    }

    private suspend fun loadUserInfo(userId: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        // Проверяем кэш
        CacheManager.userInfoCache[userId]?.let {
            return@withContext it
        }

        return@withContext try {
            val snapshot = database.child("users").child(userId).get().await()
            val user = snapshot.getValue(User::class.java)
            val name = user?.getFullName() ?: "Пользователь"
            val avatarUrl = processAvatarUrl(user?.profileImageUrl, userId)

            val userInfo = Pair(name, avatarUrl)
            CacheManager.userInfoCache[userId] = userInfo
            userInfo
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки профиля $userId из Firebase", e)
            Pair("Пользователь", null)
        }
    }

    private fun processAvatarUrl(avatarUrl: String?, userId: String): String? {
        return when {
            avatarUrl.isNullOrEmpty() -> null
            avatarUrl.startsWith("http") -> avatarUrl
            avatarUrl.contains(".") -> "$BASE_URL$avatarUrl"
            else -> {
                // Пробуем стандартные имена файлов
                val possibleNames = listOf(
                    "profile_$userId.jpg",
                    "avatar_$userId.jpg",
                    "$userId.jpg",
                    "profile_$userId.png",
                    "avatar_$userId.png",
                    "$userId.png"
                )

                // Возвращаем первую найденную (или null)
                possibleNames.firstOrNull {
                    // В реальном приложении здесь должна быть проверка существования файла
                    true
                }?.let { "$BASE_URL$it" }
            }
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

        // Если есть аватар, загружаем его
        if (avatarUrl != null) {
            loadAvatarAndCreateMarker(userId, userName, avatarUrl, isOnline, placemark)
        } else {
            // Иначе используем placeholder с именем
            val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
            placemark.setIcon(ImageProvider.fromBitmap(placeholder))
        }
    }

    private fun loadAvatarAndCreateMarker(
        userId: String,
        userName: String,
        avatarUrl: String,
        isOnline: Boolean,
        placemark: PlacemarkMapObject
    ) {
        // Проверяем кэш битмапов
        CacheManager.avatarBitmaps[userId]?.let { cachedBitmap ->
            val markerBitmap = createMarkerBitmap(cachedBitmap, userName, isOnline)
            updateMarkerIcon(placemark, markerBitmap)
            return
        }

        // Загружаем аватар через Glide
        try {
            Glide.with(this)
                .asBitmap()
                .load(avatarUrl)
                .timeout(10000)
                .error(R.drawable.ic_default_profile)
                .placeholder(R.drawable.ic_default_profile)
                .override(AVATAR_SIZE, AVATAR_SIZE)
                .centerCrop()
                .listener(object : com.bumptech.glide.request.RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.w(TAG, "Не удалось загрузить аватар $userId: ${e?.message}")
                        runOnUiThread {
                            if (isAdded && !isFragmentDestroyed) {
                                val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
                                updateMarkerIcon(placemark, placeholder)
                            }
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        runOnUiThread {
                            if (isAdded && !isFragmentDestroyed) {
                                try {
                                    // Сохраняем в кэш
                                    CacheManager.avatarBitmaps[userId] = resource

                                    // Создаем маркер
                                    val markerBitmap = createMarkerBitmap(resource, userName, isOnline)
                                    updateMarkerIcon(placemark, markerBitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка создания маркера для $userId", e)
                                    val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
                                    updateMarkerIcon(placemark, placeholder)
                                }
                            }
                        }
                        return false
                    }
                })
                .submit()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки аватара $userId", e)
            val placeholder = createPlaceholderBitmap(userName.take(1), isOnline)
            updateMarkerIcon(placemark, placeholder)
        }
    }

    // Вспомогательная функция для запуска на UI потоке
    private fun runOnUiThread(action: () -> Unit) {
        if (isAdded && !isFragmentDestroyed) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                handler.post { action() }
            }
        }
    }

    private fun updateMarkerIcon(placemark: PlacemarkMapObject, bitmap: Bitmap) {
        try {
            placemark.setIcon(
                ImageProvider.fromBitmap(bitmap),
                IconStyle().apply {
                    anchor = android.graphics.PointF(0.5f, 0.5f) // ✅ PointF из android.graphics
                    scale = 1.0f   // ✅ Float literal
                    zIndex = 100.0f // ✅ Float literal
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления иконки маркера", e)
        }
    }

    private fun updateMarkerAppearance(marker: PlacemarkMapObject, userId: String, isOnline: Boolean) {
        // Здесь можно обновить внешний вид маркера (например, изменить цвет рамки для онлайн статуса)
        // В текущей реализации просто перерисовываем маркер
        CacheManager.userInfoCache[userId]?.let { (name, avatarUrl) ->
            if (avatarUrl != null) {
                CacheManager.avatarBitmaps[userId]?.let { avatar ->
                    val newBitmap = createMarkerBitmap(avatar, name, isOnline)
                    updateMarkerIcon(marker, newBitmap)
                }
            }
        }
    }

    private fun loadMyLocation() {
        val myUserId = auth.currentUser?.uid ?: return

        database.child("last_locations").child(myUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) return

                    try {
                        val userLocation = snapshot.getValue(UserLocation::class.java)
                        if (userLocation != null && userLocation.isValid()) {
                            myLocation = Point(userLocation.lat, userLocation.lng)
                            updateMyMarker()
                            updateLocationInfo(myLocation!!)
                        } else {
                            Log.w(TAG, "Не удалось загрузить свою локацию или она невалидна")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга своей локации", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки своей локации: ${error.message}")
                }
            })
    }

    private fun updateMyMarker() {
        val point = myLocation ?: return
        val myUserId = auth.currentUser?.uid ?: return

        if (myMarker == null) {
            myMarker = mapView.map.mapObjects.addPlacemark(point)
            myMarker?.zIndex = 200f // Мой маркер выше остальных

            // Загружаем свой аватар
            lifecycleScope.launch {
                try {
                    val (name, avatarUrl) = loadUserInfo(myUserId)

                    if (avatarUrl != null) {
                        loadAvatarAndCreateMarker(myUserId, name, avatarUrl, true, myMarker!!)
                    } else {
                        val placeholder = createPlaceholderBitmap(name.take(1), true)
                        updateMarkerIcon(myMarker!!, placeholder)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка загрузки своего аватара", e)
                }
            }

            // Добавляем обработчик нажатия на свой маркер
            myMarker?.addTapListener { _, _ ->
                Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            myMarker?.geometry = point
        }
    }

    private fun removeOldMarkers(currentUsers: Set<String>) {
        val toRemove = markers.keys - currentUsers
        for (userId in toRemove) {
            markers[userId]?.let { marker ->
                mapView.map.mapObjects.remove(marker)
            }
            markers.remove(userId)
            CacheManager.clearUser(userId)
        }

        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "Удалено ${toRemove.size} старых маркеров")
        }
    }

    private fun onMarkerTapped(userId: String) {
        if (userId == auth.currentUser?.uid) {
            Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
            return
        }

        // Показываем диалог с пользователем
        showUserDialog(userId)
    }

    private fun showUserDialog(userId: String) {
        // Получаем информацию из кэша или загружаем
        val userInfo = CacheManager.userInfoCache[userId]
        val name = userInfo?.first ?: "Пользователь"

        // Проверяем статус дружбы
        checkFriendshipStatus(userId) { isFriend ->
            activity?.runOnUiThread {
                if (isAdded && !isFragmentDestroyed) {
                    showUserDialogWithOptions(userId, name, isFriend)
                }
            }
        }
    }

    private fun checkFriendshipStatus(friendId: String, callback: (Boolean) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return callback(false)

        // Проверяем кэш
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

    private fun showUserDialogWithOptions(userId: String, name: String, isFriend: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setMessage("Выберите действие")
            .setCancelable(true)

        if (isFriend) {
            builder.setPositiveButton("💬 Написать") { _, _ -> openChat(userId) }
                .setNeutralButton("👤 Профиль") { _, _ -> openProfile(userId) }
                .setNegativeButton("❌ Удалить") { _, _ ->
                    showRemoveFriendConfirmation(userId)
                }
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

        // Получаем информацию о текущем пользователе
        getCurrentUserInfo { currentUserName, currentUserAvatarUrl ->
            // Создаем двустороннюю дружбу
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

                    val processedUrl = processAvatarUrl(avatarUrl, currentUserId)
                    CacheManager.userInfoCache[currentUserId] = Pair(name, processedUrl)
                    callback(name, processedUrl)
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

    private fun centerCameraOnMyLocation() {
        if (myLocation != null) {
            mapView.map.move(
                CameraPosition(myLocation!!, 15.0f, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 0.5f),
                null
            )
            updateLocationInfo(myLocation!!)
        } else {
            loadMyLocation()
            Toast.makeText(requireContext(), "Определяем ваше местоположение...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationInfo(point: Point) {
        if (!isAdded || isFragmentDestroyed) return

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
                    }
                }
                Log.w(TAG, "Таймаут геокодирования")
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
                    }
                }
                Log.e(TAG, "Ошибка геокодирования", e)
            }
        }
    }

    private suspend fun geocodeLocation(point: Point): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)

            if (!addresses.isNullOrEmpty()) {
                buildLocationName(addresses[0])
            } else {
                String.format(
                    Locale.getDefault(),
                    "Ш: %.6f, Д: %.6f",
                    point.latitude,
                    point.longitude
                )
            }
        } catch (e: Exception) {
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
        val point = myLocation ?: mapView.map.cameraPosition.target

        val userLocation = UserLocation(
            lat = point.latitude,
            lng = point.longitude,
            timestamp = System.currentTimeMillis()
        )

        database.child("last_locations").child(myUserId).setValue(userLocation)
            .addOnFailureListener { e ->
                Log.e(TAG, "Ошибка обновления своей локации", e)
            }
    }

    private fun createPlaceholderBitmap(initial: String, isOnline: Boolean): Bitmap {
        val size = AVATAR_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Фон
        val backgroundColor = when {
            isOnline -> Color.parseColor("#4CAF50") // Зеленый для онлайн
            else -> Color.parseColor("#9E9E9E") // Серый для оффлайн
        }
        paint.color = backgroundColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, paint)

        // Буква/символ
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

        // Прозрачный фон
        bitmap.eraseColor(Color.TRANSPARENT)

        // Белый фон с закругленными углами
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)

        // Тень
        paint.setShadowLayer(6f, 3f, 3f, Color.argb(150, 0, 0, 0))
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 25f, 25f, paint)
        paint.clearShadowLayer()

        // Аватар
        val avatarRect = Rect(
            (width - avatarSize) / 2,
            15,
            (width + avatarSize) / 2,
            15 + avatarSize
        )
        canvas.drawBitmap(avatar, null, avatarRect, paint)

        // Имя
        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val displayName = if (name.length > 12) "${name.substring(0, 10)}.." else name
        val textY = 15 + avatarSize + 35
        canvas.drawText(displayName, width / 2f, textY.toFloat(), paint)

        // Индикатор онлайн-статуса
        paint.color = if (isOnline) Color.GREEN else Color.GRAY
        paint.style = Paint.Style.FILL
        val indicatorX = (width + avatarSize) / 2f - 15
        val indicatorY = 15f + 15
        canvas.drawCircle(indicatorX, indicatorY, 8f, paint)

        // Белая обводка индикатора
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

        val a = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        myLocation?.let {
            outState.putDouble(KEY_MY_LOCATION_LAT, it.latitude)
            outState.putDouble(KEY_MY_LOCATION_LNG, it.longitude)
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
    }

    override fun onResume() {
        super.onResume()
        isFragmentDestroyed = false
        // Проверяем разрешения при возвращении на фрагмент
        if (hasLocationPermissions()) {
            loadMyLocation() // Обновляем свою позицию
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true

        // Очищаем только view-related ресурсы
        markers.values.forEach { marker ->
            mapView.map.mapObjects.remove(marker)
        }
        markers.clear()

        myMarker?.let {
            mapView.map.mapObjects.remove(it)
            myMarker = null
        }

        // Удаляем все listeners
        CacheManager.tapListeners.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Полная очистка кэша только при полном уничтожении фрагмента
        CacheManager.clearAll()
        handler.removeCallbacksAndMessages(null)
    }
}