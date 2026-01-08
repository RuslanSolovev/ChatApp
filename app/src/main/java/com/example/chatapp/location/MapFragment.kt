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

    // Упрощенные кэши
    private val userInfoCache = mutableMapOf<String, Pair<String, String>>() // userId -> (name, avatarUrl)
    private val avatarBitmaps = mutableMapOf<String, Bitmap>() // userId -> Bitmap
    private val friendshipCache = mutableMapOf<String, Boolean>() // userId -> isFriend
    private val pendingFriendRequests = mutableSetOf<String>() // userId -> запрос уже отправлен

    // Coroutine scope с правильным объявлением
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Tap listeners для маркеров
    private val tapListeners = mutableMapOf<String, MapObjectTapListener>()

    companion object {
        private const val TAG = "MapFragment"
        private const val MIN_DISTANCE_FOR_UPDATE = 10.0 // метров
        private const val LOCATION_UPDATE_INTERVAL = 30000L // 30 секунд
        private const val AVATAR_SIZE = 120
        private const val MARKER_WIDTH = 150 // Ширина маркера с текстом
        private const val MARKER_HEIGHT = 170 // Высота маркера с текстом

        // Настройки Yandex Cloud Storage (такие же как в ProfileActivity)
        private const val BUCKET_NAME = "chatskii"
        private const val BASE_URL = "https://storage.yandexcloud.net/$BUCKET_NAME/"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Инициализация MapKit ДО создания View
        MapKitFactory.initialize(requireContext())

        val layout = inflater.inflate(R.layout.fragment_map_with_friends_button, container, false)

        mapView = layout.findViewById(R.id.mapview)

        cityTextView = layout.findViewById(R.id.cityTextView)
        cityProgressBar = layout.findViewById(R.id.cityProgressBar)
        cityTextView.text = "Загрузка..."
        cityProgressBar.visibility = View.GONE

        friendsButton = layout.findViewById(R.id.friendsButton)
        findMeButton = layout.findViewById(R.id.findMeButton)

        findMeButton.visibility = View.VISIBLE
        friendsButton.setOnClickListener {
            startActivity(Intent(requireContext(), FriendsListActivity::class.java))
        }
        findMeButton.setOnClickListener {
            centerCameraOnMyLocation()
        }

        return layout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()

        // Показываем текущие координаты
        val initialPoint = mapView.map.cameraPosition.target
        cityTextView.text = String.format(Locale.getDefault(),
            "Ш: %.4f, Д: %.4f", initialPoint.latitude, initialPoint.longitude)

        loadUserLocations()
        loadMyLocation()
        startPeriodicUpdates()
    }

    private fun setupMap() {
        mapView.map.isRotateGesturesEnabled = false
        mapView.map.isScrollGesturesEnabled = true
        mapView.map.isZoomGesturesEnabled = true
        mapView.map.isTiltGesturesEnabled = true

        // Обработчик движения камеры
        mapView.map.addCameraListener(object : CameraListener {
            override fun onCameraPositionChanged(
                map: com.yandex.mapkit.map.Map,
                cameraPosition: CameraPosition,
                cameraUpdateReason: CameraUpdateReason,
                finished: Boolean
            ) {
                if (finished) {
                    showLocationInfo(cameraPosition.target)
                }
            }
        })

        // Настройка тапов на карте (для отладки)
        mapView.map.addInputListener(object : com.yandex.mapkit.map.InputListener {
            override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {
                Log.d(TAG, "Tap on map at: $point")
            }

            override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {
                // Не используется
            }
        })
    }

    private fun loadUserLocations() {
        val locationsRef = database.child("last_locations")
        locationsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isFragmentDestroyed || !isAdded) return

                val currentUsers = HashSet<String>()

                for (userSnapshot in snapshot.children) {
                    val userId = userSnapshot.key ?: continue
                    val userLocation = userSnapshot.getValue(UserLocation::class.java)

                    if (userId == auth.currentUser?.uid || userLocation == null || !userLocation.isValid()) {
                        continue
                    }

                    currentUsers.add(userId)

                    val point = Point(userLocation.lat, userLocation.lng)
                    val isOnline = (System.currentTimeMillis() - userLocation.timestamp) < 5 * 60 * 1000 // 5 минут

                    markers[userId]?.let { marker ->
                        // Обновляем существующий маркер
                        if (distanceBetween(marker.geometry, point) > MIN_DISTANCE_FOR_UPDATE) {
                            marker.geometry = point
                        }
                    } ?: run {
                        // Создаем новый маркер
                        createMarker(userId, point, isOnline)
                    }
                }

                // Удаляем старые маркеры
                removeOldMarkers(currentUsers)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки локаций пользователей: ${error.message}")
            }
        })
    }

    private fun createMarker(userId: String, point: Point, isOnline: Boolean) {
        // Создаем маркер без текста
        val placemark = mapView.map.mapObjects.addPlacemark(point)

        // Устанавливаем zIndex чтобы маркеры не перекрывались слишком сильно
        placemark.zIndex = 100f
        markers[userId] = placemark

        // Загружаем информацию о пользователе
        loadUserInfo(userId) { name, avatarUrl ->
            // Сначала устанавливаем placeholder
            val placeholder = createPlaceholderBitmap(name, isOnline)
            placemark.setIcon(ImageProvider.fromBitmap(placeholder))

            // Затем асинхронно загружаем аватар
            loadAvatarForMarker(userId, name, avatarUrl, isOnline, placemark)
        }

        // Сразу добавляем обработчик нажатия
        val tapListener = MapObjectTapListener { mapObject, point ->
            Log.d(TAG, "Marker tapped: $userId")
            showUserDialog(userId)
            true
        }

        placemark.addTapListener(tapListener)
        tapListeners[userId] = tapListener
    }

    private fun loadUserInfo(userId: String, callback: (String, String?) -> Unit) {
        // Проверяем кэш
        userInfoCache[userId]?.let {
            callback(it.first, it.second)
            return
        }

        database.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName() ?: "Пользователь"

                    // Получаем URL аватарки из базы
                    val avatarUrl = user?.profileImageUrl

                    // Если URL начинается с префикса Yandex Cloud, оставляем как есть
                    // Иначе используем стандартный путь
                    val finalAvatarUrl = if (!avatarUrl.isNullOrEmpty()) {
                        if (avatarUrl.startsWith("http")) {
                            avatarUrl
                        } else {
                            // Если это просто имя файла, добавляем базовый URL
                            "$BASE_URL$avatarUrl"
                        }
                    } else {
                        // Если нет аватарки, пробуем стандартное имя файла
                        val fileName = "profile_$userId.jpg"
                        "$BASE_URL$fileName"
                    }

                    userInfoCache[userId] = Pair(name, finalAvatarUrl)
                    callback(name, finalAvatarUrl)

                    Log.d(TAG, "Загружен профиль $userId: $name, аватар: $finalAvatarUrl")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки профиля $userId: ${error.message}")
                    callback("Пользователь", null)
                }
            })
    }

    private fun loadAvatarForMarker(
        userId: String,
        userName: String,
        avatarUrl: String?,
        isOnline: Boolean,
        placemark: PlacemarkMapObject
    ) {
        scope.launch {
            try {
                // Если нет URL, оставляем placeholder
                if (avatarUrl.isNullOrEmpty()) {
                    return@launch
                }

                // Пробуем загрузить аватар
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Пытаюсь загрузить аватар: $avatarUrl")

                        // Используем Glide с обработкой ошибок
                        Glide.with(requireContext())
                            .asBitmap()
                            .load(avatarUrl)
                            .timeout(5000) // 5 секунд таймаут
                            .error(R.drawable.ic_default_profile) // Запасная иконка
                            .submit(AVATAR_SIZE, AVATAR_SIZE)
                            .get()
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка загрузки аватара $userId: ${e.message}")
                        null
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isFragmentDestroyed || !isAdded) return@withContext

                    if (bitmap != null) {
                        // Успешно загрузили - создаем маркер
                        val finalBitmap = createMarkerBitmap(bitmap, userName, isOnline)
                        avatarBitmaps[userId] = finalBitmap

                        // Устанавливаем иконку маркера с правильными настройками
                        placemark.setIcon(
                            ImageProvider.fromBitmap(finalBitmap),
                            IconStyle().apply {
                                scale = 1.0f
                                zIndex = 100f
                            }
                        )
                        Log.d(TAG, "Аватар $userId успешно загружен")
                    } else {
                        val placeholder = createPlaceholderBitmap(userName, isOnline)
                        placemark.setIcon(
                            ImageProvider.fromBitmap(placeholder),
                            IconStyle().apply {
                                scale = 1.0f
                                zIndex = 100f
                            }
                        )
                        Log.w(TAG, "Не удалось загрузить аватар $userId, оставляем placeholder")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Исключение при загрузке аватара $userId", e)
            }
        }
    }

    private fun loadMyLocation() {
        val myUserId = auth.currentUser?.uid ?: return

        database.child("last_locations").child(myUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (isFragmentDestroyed || !isAdded) return

                    val userLocation = snapshot.getValue(UserLocation::class.java)
                    if (userLocation != null && userLocation.isValid()) {
                        myLocation = Point(userLocation.lat, userLocation.lng)
                        updateMyMarker()

                        // Показываем информацию о местоположении
                        showLocationInfo(myLocation!!)
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
            myMarker?.zIndex = 200f // Мой маркер выше всех

            // Загружаем свой аватар
            loadUserInfo(myUserId) { name, avatarUrl ->
                loadAvatarForMarker(myUserId, name, avatarUrl, true, myMarker!!)

                // Добавляем обработчик нажатия на свой маркер
                myMarker?.addTapListener { mapObject, point ->
                    Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        } else {
            myMarker?.geometry = point
        }
    }

    private fun showUserDialog(userId: String) {
        if (userId == auth.currentUser?.uid) {
            Toast.makeText(requireContext(), "Это вы!", Toast.LENGTH_SHORT).show()
            return
        }

        val userInfo = userInfoCache[userId]
        val name = userInfo?.first ?: "Пользователь"

        // Проверяем кэш статуса дружбы
        friendshipCache[userId]?.let { isFriend ->
            showUserDialogWithFriendStatus(userId, name, isFriend)
        } ?: run {
            // Загружаем статус дружбы из базы
            checkFriendshipStatus(userId) { isFriend ->
                // Сохраняем в кэш
                friendshipCache[userId] = isFriend
                showUserDialogWithFriendStatus(userId, name, isFriend)
            }
        }
    }

    private fun showUserDialogWithFriendStatus(userId: String, name: String, isFriend: Boolean) {
        val dialogBuilder = AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setMessage("Выберите действие")

        // Добавляем кнопки в зависимости от статуса дружбы
        if (isFriend) {
            dialogBuilder
                .setPositiveButton("💬 Написать") { _, _ ->
                    openChat(userId)
                }
                .setNeutralButton("👤 Профиль") { _, _ ->
                    openProfile(userId)
                }
                .setNegativeButton("❌ Удалить из друзей") { _, _ ->
                    showRemoveFriendConfirmation(userId)
                }
        } else {
            dialogBuilder
                .setPositiveButton("💬 Написать") { _, _ ->
                    openChat(userId)
                }
                .setNeutralButton("👤 Профиль") { _, _ ->
                    openProfile(userId)
                }
                .setNegativeButton("➕ Добавить в друзья") { _, _ ->
                    addFriend(userId)
                }
        }

        dialogBuilder.show()
    }

    private fun checkFriendshipStatus(friendId: String, callback: (Boolean) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return callback(false)

        database.child("users").child(currentUserId).child("friends").child(friendId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    callback(snapshot.exists())
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка проверки статуса дружбы: ${error.message}")
                    callback(false)
                }
            })
    }

    private fun addFriend(friendId: String) {
        val currentUserId = auth.currentUser?.uid ?: return

        // Проверяем, не является ли пользователь самим собой
        if (currentUserId == friendId) {
            Toast.makeText(requireContext(), "Нельзя добавить себя в друзья", Toast.LENGTH_SHORT).show()
            return
        }

        // Проверяем, не отправили ли уже запрос
        if (pendingFriendRequests.contains(friendId)) {
            Toast.makeText(requireContext(), "Запрос уже отправлен", Toast.LENGTH_SHORT).show()
            return
        }

        pendingFriendRequests.add(friendId)

        // Получаем информацию о текущем пользователе для уведомления
        getCurrentUserInfo { currentUserName, currentUserAvatarUrl ->
            // Создаем двустороннюю дружбу
            val updates = hashMapOf<String, Any>(
                "users/$currentUserId/friends/$friendId" to true,
                "users/$friendId/friends/$currentUserId" to true
            )

            database.updateChildren(updates)
                .addOnSuccessListener {
                    // Обновляем кэш
                    friendshipCache[friendId] = true

                    // Отправляем уведомление
                    sendFriendNotification(friendId, currentUserName)

                    // Обновляем UI (если нужно)
                    updateFriendshipUI(friendId, true)

                    Toast.makeText(requireContext(), "Пользователь добавлен в друзья", Toast.LENGTH_SHORT).show()

                    // Убираем из списка ожидания
                    pendingFriendRequests.remove(friendId)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Ошибка добавления в друзья: ${e.message}", Toast.LENGTH_SHORT).show()
                    pendingFriendRequests.remove(friendId)
                }
        }
    }

    private fun showRemoveFriendConfirmation(friendId: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удаление из друзей")
            .setMessage("Вы уверены, что хотите удалить пользователя из друзей?")
            .setPositiveButton("Удалить") { _, _ ->
                removeFriend(friendId)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun removeFriend(friendId: String) {
        val currentUserId = auth.currentUser?.uid ?: return

        // Удаляем двустороннюю дружбу
        val updates = hashMapOf<String, Any?>(
            "users/$currentUserId/friends/$friendId" to null,
            "users/$friendId/friends/$currentUserId" to null
        )

        database.updateChildren(updates)
            .addOnSuccessListener {
                // Обновляем кэш
                friendshipCache[friendId] = false

                // Обновляем UI (если нужно)
                updateFriendshipUI(friendId, false)

                Toast.makeText(requireContext(), "Пользователь удален из друзей", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getCurrentUserInfo(callback: (String, String?) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return callback("Пользователь", null)

        // Проверяем кэш
        userInfoCache[currentUserId]?.let {
            // Возвращаем имя и nullable аватар
            callback(it.first, it.second)
            return
        }

        // Загружаем из базы
        database.child("users").child(currentUserId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    val name = user?.getFullName() ?: "Пользователь"
                    val avatarUrl = user?.profileImageUrl

                    // Сохраняем в кэш - используем nullable String для аватара
                    userInfoCache[currentUserId] = Pair(name, avatarUrl) as Pair<String, String>
                    callback(name, avatarUrl)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback("Пользователь", null)
                }
            })
    }

    private fun sendFriendNotification(friendId: String, requesterName: String) {
        // Используем утилиту уведомлений для отправки уведомления
        NotificationUtils.sendFriendRequestNotification(requireContext(), friendId, requesterName)
    }

    private fun updateFriendshipUI(userId: String, isFriend: Boolean) {
        // Здесь можно обновить UI, например, изменить цвет маркера или добавить значок друга
        // В данный момент просто логируем
        Log.d(TAG, "Статус дружбы с $userId обновлен: $isFriend")

        // Можно также обновить маркер на карте, если нужно
        // Например, добавить рамку вокруг аватара для друзей
    }

    private fun centerCameraOnMyLocation() {
        if (myLocation != null) {
            mapView.map.move(
                CameraPosition(myLocation!!, 15.0f, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 0.5f),
                null
            )
            showLocationInfo(myLocation!!)
        } else {
            loadMyLocation()
            Toast.makeText(requireContext(), "Определяем ваше местоположение...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLocationInfo(point: Point) {
        cityProgressBar.visibility = View.VISIBLE

        scope.launch {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)

                withContext(Dispatchers.Main) {
                    cityProgressBar.visibility = View.GONE

                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val locationName = buildDetailedAddress(address)
                        cityTextView.text = locationName
                    } else {
                        cityTextView.text = String.format(Locale.getDefault(),
                            "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    cityProgressBar.visibility = View.GONE
                    cityTextView.text = String.format(Locale.getDefault(),
                        "Ш: %.4f, Д: %.4f", point.latitude, point.longitude)
                }
            }
        }
    }

    private fun buildDetailedAddress(address: android.location.Address): String {
        val parts = mutableListOf<String>()

        address.thoroughfare?.let { street ->
            address.subThoroughfare?.let { number ->
                parts.add("$street, $number")
            } ?: parts.add(street)
        }

        address.subLocality?.let { parts.add(it) }
        address.locality?.let { parts.add(it) }

        return if (parts.isNotEmpty()) parts.joinToString(", ") else "Местоположение"
    }

    private fun removeOldMarkers(currentUsers: Set<String>) {
        val toRemove = markers.keys - currentUsers
        for (userId in toRemove) {
            markers[userId]?.let { marker ->
                mapView.map.mapObjects.remove(marker)
            }
            markers.remove(userId)
            userInfoCache.remove(userId)
            avatarBitmaps.remove(userId)
            friendshipCache.remove(userId)

            // Удаляем listener
            tapListeners.remove(userId)
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
        }
    }

    private fun createChat(chatId: String, friendId: String, currentUserId: String) {
        // Загружаем информацию о друге
        database.child("users").child(friendId).get().addOnSuccessListener { friendSnapshot ->
            val friend = friendSnapshot.getValue(User::class.java)
            val friendName = friend?.getFullName() ?: "Пользователь"
            val friendAvatar = friend?.profileImageUrl ?: ""

            // Загружаем информацию о себе
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

    private fun startPeriodicUpdates() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isFragmentDestroyed && isAdded) {
                    updateMyLocation()
                    handler.postDelayed(this, LOCATION_UPDATE_INTERVAL)
                }
            }
        }, LOCATION_UPDATE_INTERVAL)
    }

    private fun updateMyLocation() {
        val myUserId = auth.currentUser?.uid ?: return
        val point = myLocation ?: mapView.map.cameraPosition.target

        val userLocation = UserLocation(
            lat = point.latitude,
            lng = point.longitude,
            timestamp = System.currentTimeMillis()
        )

        database.child("last_locations").child(myUserId).setValue(userLocation)
    }

    // Вспомогательные методы для создания битмапов
    private fun createPlaceholderBitmap(name: String, isOnline: Boolean): Bitmap {
        val size = AVATAR_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Фон
        paint.color = if (isOnline) Color.GREEN else Color.GRAY
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, paint)

        // Буква
        paint.color = Color.WHITE
        paint.textSize = size / 3f
        paint.textAlign = Paint.Align.CENTER
        val firstChar = if (name.isNotEmpty()) name[0].toString().uppercase() else "?"
        canvas.drawText(firstChar, size / 2f, size / 2f + paint.textSize / 3, paint)

        return bitmap
    }

    private fun createMarkerBitmap(avatar: Bitmap, name: String, isOnline: Boolean): Bitmap {
        val avatarSize = AVATAR_SIZE
        val textHeight = 40
        val padding = 15
        val width = MARKER_WIDTH
        val height = MARKER_HEIGHT

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Прозрачный фон (важно для тапов!)
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
            padding,
            (width + avatarSize) / 2,
            padding + avatarSize
        )
        canvas.drawBitmap(avatar, null, avatarRect, paint)

        // Имя (под аватаром)
        paint.color = Color.BLACK
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val displayName = if (name.length > 12) "${name.substring(0, 10)}.." else name
        val textY = padding + avatarSize + textHeight - 10
        canvas.drawText(displayName, width / 2f, textY.toFloat(), paint)

        // Индикатор онлайн-статуса (в правом верхнем углу аватара)
        paint.color = if (isOnline) Color.GREEN else Color.GRAY
        paint.style = Paint.Style.FILL
        val indicatorX = (width + avatarSize) / 2f - 15
        val indicatorY = padding.toFloat() + 15
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
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true

        // Отменяем корутины
        scope.coroutineContext.cancel()

        // Очистка
        markers.values.forEach { marker ->
            mapView.map.mapObjects.remove(marker)
        }
        markers.clear()
        userInfoCache.clear()
        avatarBitmaps.clear()
        friendshipCache.clear()
        pendingFriendRequests.clear()
        tapListeners.clear()

        myMarker?.let {
            mapView.map.mapObjects.remove(it)
        }
        myMarker = null
    }
}