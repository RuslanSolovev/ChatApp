package com.example.chatapp.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.blob.BlobStoreManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import com.yandex.mapkit.map.PlacemarkMapObject
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.chatapp.LocationServiceWorker
import com.example.chatapp.Participant
import com.example.chatapp.ParticipantAdapter
import com.example.chatapp.R
import com.example.chatapp.activities.ChatDetailActivity
import com.example.chatapp.activities.UserProfileActivity
import com.example.chatapp.chess.ChessActivity
import com.example.chatapp.databinding.ActivityLocationBinding
import com.example.chatapp.models.Chat
import com.example.chatapp.models.LocationSettings
import com.example.chatapp.models.User
import com.example.chatapp.models.UserLocation
import com.example.chatapp.receivers.HistoryCleanupReceiver
import com.example.chatapp.location.LocationUpdateService
import com.example.chatapp.utils.Constants
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.search.*
import com.yandex.runtime.image.ImageProvider
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.*
import android.net.Uri
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import com.example.chatapp.ServiceMonitorWorker


class LocationFragment : Fragment(), CameraListener, InputListener {
    private var _binding: ActivityLocationBinding? = null
    private val binding get() = _binding!!
    private lateinit var mapView: MapView
    private lateinit var map: com.yandex.mapkit.map.Map
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference
    private val otherUserMarkers = ConcurrentHashMap<String, PlacemarkMapObject>()
    private var myMarker: PlacemarkMapObject? = null
    private var currentLocation: com.yandex.mapkit.geometry.Point? = null
    private val TAG = "LocationFragment"
    private var isMapInitialized = false
    private var locationSettingsListener: ValueEventListener? = null
    private var userLocationsListener: ValueEventListener? = null
    private var friendsListener: ValueEventListener? = null
    private val friendList = mutableSetOf<String>()
    private val sessionRoutePoints = mutableListOf<com.yandex.mapkit.geometry.Point>()
    private var lastKnownAccuracy: Float = 50f
    private var isPeriodicUserMarkersRefreshScheduled = false // Флаг для предотвращения множественных запусков


    // Внутри класса LocationFragment, на уровне свойств
    private var routePolyline: PolylineMapObject? = null
    // Также убедись, что у тебя есть sessionRoutePoints

    private var lastLocationPoint: com.yandex.mapkit.geometry.Point? = null
    private var lastLocationTime: Long = 0


    // Добавьте в свойства класса, рядом с eventMarkers, например
    private val eventMarkerTapListeners = ConcurrentHashMap<String, Any?>()



    private val eventMarkers = ConcurrentHashMap<String, PlacemarkMapObject>()
    private var eventSession: BlobStoreManager.Session? = null

    // --- НОВЫЕ ПОЛЯ ДЛЯ АВАТАРОВ И ГОРОДА ---
    private lateinit var searchManager: SearchManager
    private lateinit var cityNameTextView: TextView
    // --- КОНЕЦ НОВЫХ ПОЛЕЙ ---

    companion object {
        private val FOREGROUND_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        private val BACKGROUND_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }

        private const val MAX_JUMP_DISTANCE = 200.0 // Максимальный "прыжок" GPS в метрах
        private const val MAX_SPEED_THRESHOLD = 50.0 // Максимальная скорость в м/с (180 км/ч)
        private const val MIN_ACCURACY_THRESHOLD = 30f // Минимальная точность в метрах
        private const val MAX_ANGLE_CHANGE = 45.0 // Максимальное изменение угла для смены цвета

        private const val MIN_DISTANCE_FOR_UPDATE = 20.0 // Увеличил немного
        private const val MAX_SPEED_THRESHOLD_MPS = 40.0 // ~144 км/ч, для авто
        private const val MAX_JUMP_DISTANCE_METERS = 150.0 // Максимальный скачок
        private const val MIN_ACCURACY_THRESHOLD_METERS = 35.0 // Минимальная приемлемая точность
        private const val MIN_TIME_DIFF_MS = 1000L // Минимальная разница во времени между точками (1 секунда)

        // Внутри companion object LocationFragment
        private const val SERVICE_MONITOR_WORK_NAME = "ServiceMonitorPeriodicWork"
        private const val SERVICE_MONITOR_INTERVAL_HOURS = 1L // Проверять каждый час
        private const val LOCATION_SERVICE_WORK_NAME = "LocationServicePeriodicWork"
        private const val LOCATION_SERVICE_INTERVAL_HOURS = 1L // 4 раза в день: 24/4 = 6



    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Получен ответ на запрос разрешений: $permissions")
        if (permissions.all { it.value }) {
            Log.d(TAG, "Все разрешения получены")
            initializeMapFeatures()
        } else {
            Log.w(TAG, "Некоторые разрешения отклонены")
            handlePermissionDenial()
        }
    }
    // Добавим поля для отслеживания скорости и времени

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate вызван")
        try {
            MapKitFactory.initialize(requireContext())
            Log.d(TAG, "MapKitFactory инициализирован")
            scheduleDailyHistoryCleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации MapKitFactory", e)
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView вызван")
        try {
            _binding = ActivityLocationBinding.inflate(inflater, container, false)
            Log.d(TAG, "Binding создан успешно")
            return binding.root
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания binding", e)
            throw e
        }
    }
    // LocationFragment.kt

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated вызван")
        try {
            // Проверка контекста
            if (context == null) {
                Log.e(TAG, "Контекст равен null")
                Toast.makeText(requireContext(), "Ошибка: контекст недоступен", Toast.LENGTH_LONG).show()
                return
            }

            // ПЕРВОЕ: Инициализация основных компонентов
            mapView = binding.mapView
            // ВАЖНО: mapWindow.map доступен только после mapView.onStart()
            // Пока просто сохраняем ссылку на mapView
            auth = FirebaseAuth.getInstance()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
            Log.d(TAG, "Основные компоненты инициализированы")

            // ВТОРОЕ: Инициализация новых компонентов
            cityNameTextView = binding.tvCityName
            // searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)
            // map.addCameraListener(this) // Лучше добавить позже, когда map будет точно доступна

            // ТРЕТЬЕ: Настройка карты
            // Настройки карты применяем позже, в onStart или onResume, когда map будет готова
            // try {
            //     with(map) { // map пока не инициализирована напрямую
            //         isScrollGesturesEnabled = true
            //         isZoomGesturesEnabled = true
            //         isRotateGesturesEnabled = true
            //         setMapStyle("normal")
            //     }
            //     Log.d(TAG, "Настройки карты применены")
            // } catch (e: Exception) {
            //     Log.e(TAG, "Ошибка настройки карты", e)
            //     Toast.makeText(requireContext(), "Ошибка настройки карты: ${e.message}", Toast.LENGTH_LONG).show()
            // }

            // ЧЕТВЕРТОЕ: Настройка кнопок (это можно оставить)
            binding.btnSettings.setOnClickListener {
                try {
                    Log.d(TAG, "Кнопка настроек нажата")
                    startActivity(Intent(requireContext(), com.example.chatapp.LocationSettingsActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка открытия настроек", e)
                    Toast.makeText(requireContext(), "Ошибка открытия настроек", Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnHistory.setOnClickListener {
                try {
                    Log.d(TAG, "Кнопка истории нажата")
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, LocationHistoryFragment())
                        .addToBackStack(null)
                        .commit()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка открытия истории", e)
                    Toast.makeText(requireContext(), "Ошибка открытия истории", Toast.LENGTH_SHORT).show()
                }
            }

            // ПЯТОЕ: Загрузка данных
            loadFriendsList()
            // checkLocationPermissions() // Переносим в onResume

            Log.d(TAG, "onViewCreated: Основная инициализация завершена")

        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка инициализации в onViewCreated", e)
            Toast.makeText(requireContext(), "Критическая ошибка инициализации: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createChat(chatId: String, otherUserId: String, otherUserName: String, otherUserAvatarUrl: String?) {
        // Получаем ID текущего пользователя
        val currentUserId = auth.currentUser?.uid ?: return
        Log.d(TAG, "Начинаем создание чата: $chatId")

        // Загружаем данные текущего пользователя, чтобы получить его имя
        database.child("users").child(currentUserId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Получаем данные текущего пользователя
                val currentUser = snapshot.getValue(User::class.java)
                // Формируем имя текущего пользователя
                val currentUserName = currentUser?.getFullName() ?: currentUser?.name ?: currentUserId

                // Создаем объект нового чата
                val chat = Chat(
                    chatId = chatId,
                    // Имя чата - имя пользователя, с которым создается чат
                    name = otherUserName,
                    // Сообщение о создании чата
                    lastMessage = "Чат создан",
                    // Участники чата
                    participants = mapOf(
                        currentUserId to true,
                        otherUserId to true
                    ),
                    // ID создателя (текущий пользователь)
                    creatorId = currentUserId,
                    // Имя создателя
                    creatorName = currentUserName,
                    // URL изображения чата. Если не было выбрано пользователем,
                    // используется аватар другого участника.

                    // Предполагается, что модель Chat имеет поле `imageUrl`.
                    imageUrl = otherUserAvatarUrl,
                    // Время создания
                    createdAt = System.currentTimeMillis()
                    // Добавьте другие необходимые поля модели Chat
                )

                Log.d(TAG, "Создаем чат в базе: $chatId")
                // Подготавливаем данные для одновременного обновления нескольких узлов в БД
                val updates = hashMapOf<String, Any>(
                    // Сохраняем сам чат
                    "chats/$chatId" to chat,
                    // Добавляем ссылку на чат в список чатов текущего пользователя
                    "users/$currentUserId/chats/$chatId" to true,
                    // Добавляем ссылку на чат в список чатов другого пользователя
                    "users/$otherUserId/chats/$chatId" to true
                )

                // Выполняем транзакцию обновления данных
                database.updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Чат успешно создан, открываем: $chatId")
                        // Если чат успешно создан, открываем его
                        openChat(chatId)
                    }
                    .addOnFailureListener { e ->
                        // Обработка ошибки при создании чата
                        Log.e(TAG, "Ошибка при создании чата: ${e.message}", e)
                        Toast.makeText(requireContext(), "Ошибка при создании чата: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                // Обработка ошибки при загрузке данных текущего пользователя
                Log.e(TAG, "Ошибка загрузки данных текущего пользователя: ${error.message}")
                Toast.makeText(requireContext(), "Ошибка загрузки данных пользователя", Toast.LENGTH_SHORT).show()
            }
        })
    }


    // --- Обработка долгого нажатия на карту ---
    override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: com.yandex.mapkit.geometry.Point) {
        if (!isAdded) {
            Log.w(TAG, "Фрагмент не активен, игнорируем long tap")
            return
        }
        Log.d(TAG, "Long tap на карте в точке: $point")
        showCreateEventDialog(point)
    }

    // --- Диалог создания события ---
    private fun showCreateEventDialog(point: Point) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_event, null)
        val etEventName = dialogView.findViewById<TextView>(R.id.etEventName)
        val etEventDescription = dialogView.findViewById<TextView>(R.id.etEventDescription)
        val npDuration = dialogView.findViewById<NumberPicker>(R.id.npDuration)

        // Настройка NumberPicker для выбора длительности
        with(npDuration) {
            minValue = 1
            maxValue = 24
            value = 2 // значение по умолчанию - 2 часа
            wrapSelectorWheel = false
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Создание события")
            .setView(dialogView)
            .setPositiveButton("Создать") { dialog, _ ->
                val name = etEventName.text.toString().trim()
                val description = etEventDescription.text.toString().trim()
                val durationHours = npDuration.value

                if (name.isNotEmpty()) {
                    createEvent(point, name, description, durationHours)
                } else {
                    Toast.makeText(requireContext(), "Введите название события", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // В LocationFragment или в отдельном файле
    data class Event(
        val id: String = "",
        val creatorId: String = "",
        val creatorName: String = "",
        val name: String = "",
        val description: String = "",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val createdAt: Long = 0,
        val expiresAt: Long = 0,
        val durationHours: Int = 0,
        val participants: Map<String, String> = emptyMap() // key: userId, value: userName
    )

    // --- Создание события ---
    private fun createEvent(point: Point, name: String, description: String, durationHours: Int) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "Необходимо авторизоваться", Toast.LENGTH_SHORT).show()
            return
        }

        val eventId = "event_${System.currentTimeMillis()}_${currentUser.uid}"
        val expirationTime = System.currentTimeMillis() + (durationHours * 60 * 60 * 1000)

        // Используем явное указание типа HashMap
        val event = HashMap<String, Any>().apply {
            put("id", eventId)
            put("creatorId", currentUser.uid)
            put("creatorName", currentUser.displayName ?: "Аноним")
            put("name", name)
            put("description", description)
            put("latitude", point.latitude)
            put("longitude", point.longitude)
            put("createdAt", System.currentTimeMillis())
            put("expiresAt", expirationTime)
            put("durationHours", durationHours)
            // Инициализируем пустой список участников
            put("participants", HashMap<String, String>())
        }

        // Сохраняем в базу данных
        database.child("events").child(eventId).setValue(event)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Событие создано на $durationHours часов", Toast.LENGTH_SHORT).show()
                // Передаем creatorId
                addEventMarker(eventId, point, name, description, expirationTime, currentUser.uid)
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка создания события: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    // --- Добавление маркера события на карту ---
    private fun addEventMarker(eventId: String, point: Point, name: String, description: String, expirationTime: Long, creatorId: String) {
        Log.d(TAG, "Добавление маркера события: $eventId в точку $point")

        val marker: PlacemarkMapObject
        try {
            marker = map.mapObjects.addPlacemark()
            marker.geometry = point
            marker.isDraggable = false
            marker.isVisible = true

            // Установка иконки
            try {
                val drawable = ResourcesCompat.getDrawable(resources,
                    R.drawable.ic_marker_large, null)
                if (drawable != null) {
                    val widthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
                    val heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
                    val bitmap = drawable.toBitmap(widthPx, heightPx)
                    marker.setIcon(ImageProvider.fromBitmap(bitmap))
                    Log.d(TAG, "Иконка события установлена из Bitmap для события $eventId")
                } else {
                    marker.setIcon(ImageProvider.fromResource(requireContext(), android.R.drawable.ic_dialog_info))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка преобразования векторной иконки события $eventId в Bitmap", e)
                marker.setIcon(ImageProvider.fromResource(requireContext(), android.R.drawable.ic_dialog_info))
            }

            marker.setIconStyle(IconStyle().apply {
                scale = 1.2f
                zIndex = 8.0f
            })

            // Установка текста
            marker.setText(name)
            Log.d(TAG, "Текст маркера события $eventId установлен: $name")

            // ВАЖНО: Всегда добавляем слушатель касания
            addTapListenerToEventMarker(marker, eventId, name, description, expirationTime, creatorId)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления маркера события $eventId", e)
            return
        }

        // Сохраняем ссылку на маркер
        eventMarkers[eventId] = marker
        Log.d(TAG, "Маркер события $eventId добавлен в ConcurrentHashMap. Всего маркеров: ${eventMarkers.size}")

        // Установка таймера для автоматического удаления
        try {
            val handler = Handler(Looper.getMainLooper())
            val delay = expirationTime - System.currentTimeMillis()
            if (delay > 0) {
                handler.postDelayed({
                    Log.d(TAG, "Автоматическое удаление маркера события $eventId по таймеру")
                    removeEventMarker(eventId)
                }, delay)
                Log.d(TAG, "Таймер установлен для события $eventId на ${delay}ms")
            } else {
                // Если время уже истекло, удаляем сразу
                Log.w(TAG, "Время события $eventId уже истекло, удаляем маркер немедленно")
                removeEventMarker(eventId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка установки таймера для события $eventId", e)
        }
    }


    // LocationFragment.kt

    /**
     * Загружает существующие события из Firebase и добавляет их маркеры на карту.
     * Вызывается при инициализации или перезагрузке маркеров.
     */
    private fun loadExistingEvents() {
        Log.d(TAG, "loadExistingEvents: НАЧАЛО")
        // Проверка жизненного цикла фрагмента
        if (!isAdded) {
            Log.w(TAG, "loadExistingEvents: Фрагмент не добавлен, загрузка событий пропущена")
            return
        }

        val currentTime = System.currentTimeMillis()
        Log.d(TAG, "loadExistingEvents: Запрос к Firebase отправлен")

        // Запрашиваем события из Firebase, отфильтрованные по времени истечения
        database.child("events")
            .orderByChild("expiresAt")
            .startAt(currentTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // Повторная проверка жизненного цикла
                    if (!isAdded) {
                        Log.w(TAG, "loadExistingEvents: onDataChange: Фрагмент отсоединен во время загрузки событий")
                        return
                    }

                    var loadedCount = 0
                    // Обрабатываем каждый дочерний элемент (событие) в снимке данных
                    for (ds in snapshot.children) {
                        try {
                            // Преобразуем данные в объект Event
                            val event = ds.getValue(Event::class.java)
                            // Проверяем, что событие успешно загружено и еще не истекло
                            if (event != null && event.expiresAt > System.currentTimeMillis()) {
                                val point = Point(event.latitude, event.longitude)

                                // Проверяем, не существует ли уже маркер для этого события
                                // Это важно, если функция вызывается повторно без полной очистки
                                if (!eventMarkers.containsKey(event.id)) {
                                    // Создаем новый маркер для события
                                    // addEventMarker также вызовет addTapListenerToEventMarker
                                    addEventMarker(
                                        event.id,
                                        point,
                                        event.name,
                                        event.description,
                                        event.expiresAt,
                                        event.creatorId // Передаем creatorId
                                    )
                                    loadedCount++
                                } else {
                                    Log.d(TAG, "loadExistingEvents: onDataChange: Маркер для события ${event.id} уже существует")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadExistingEvents: onDataChange: Ошибка обработки события из Firebase", e)
                        }
                    }
                    Log.d(TAG, "loadExistingEvents: ЗАВЕРШЕНО. Загружено новых меток: $loadedCount. Всего меток в eventMarkers: ${eventMarkers.size}")

                    // --- ДОБАВЛЕНО: Планирование принудительного обновления (таймер) ---
                    // Это временное решение для борьбы с проблемой "мертвых" TapListener'ов.
                    // Если проблема подтвердится, можно сделать это регулярным обновлением.
                    Log.d(TAG, "loadExistingEvents: Планируем принудительное обновление маркеров через таймер.")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAdded && isMapInitialized) {
                            Log.d(TAG, "loadExistingEvents: Таймер сработал. Вызов reloadEventMarkers().")
                            reloadEventMarkers() // Или другая функция перезагрузки, если она более подходящая
                        } else {
                            val reason = if (!isAdded) "фрагмент отсоединен" else "карта не инициализирована"
                            Log.w(TAG, "loadExistingEvents: Таймер сработал, но перезагрузка отменена ($reason).")
                        }
                    }, 20000) // 60 секунд. Подбери экспериментально (30-120 секунд).
                    // ---
                }

                override fun onCancelled(error: DatabaseError) {
                    if (isAdded) { // Проверяем isAdded и для onCancelled
                        Log.e(TAG, "loadExistingEvents: onCancelled: Ошибка загрузки событий", error.toException())
                        Toast.makeText(requireContext(), "Ошибка загрузки событий: ${error.message}", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "loadExistingEvents: onCancelled: Ошибка загрузки событий, фрагмент отсоединен", error.toException())
                    }
                }
            })
    }





    private fun addTapListenerToEventMarker(
        marker: PlacemarkMapObject,
        eventId: String,
        name: String,
        description: String,
        expirationTime: Long,
        creatorId: String
    ) {
        try {
            // Создаем и добавляем слушатель нажатий, сохраняя ссылку на него.
            // Предполагается, что метод addTapListener возвращает ссылку на добавленный слушатель.
            // ВАЖНО: Лямбда захватывает this@LocationFragment, поэтому фрагмент не должен быть уничтожен,
            // пока маркер существует.
            val listener = marker.addTapListener { mapObject, point ->
                // --- ДОБАВЛЕНО: Проверка жизненного цикла фрагмента ---
                // Это помогает выявить, активен ли фрагмент в момент вызова слушателя.
                // Если фрагмент не активен, это может быть признаком проблемы с жизненным циклом.
                if (!this@LocationFragment.isAdded) {
                    Log.w(TAG, "TapListener: Фрагмент НЕ АКТИВЕН для события $eventId. Возможная утечка или ошибка жизненного цикла.")
                    // Возвращаем false, сигнализируя MapKit, что событие не обработано этим "мертвым" слушателем.
                    return@addTapListener false
                } else {
                    Log.d(TAG, "Нажатие на маркер события $eventId зарегистрировано в addTapListener")
                }
                // --- КОНЕЦ ДОБАВЛЕНИЯ ---

                // Проверяем, что нажатие было именно на этот маркер
                if (mapObject == marker) {
                    Log.d(TAG, "Объект маркера совпадает, вызываем showEventDetails для $eventId")
                    // Вызываем функцию отображения деталей события
                    showEventDetails(eventId, name, description, expirationTime, creatorId)
                    // Возвращаем true, чтобы указать, что событие обработано
                    return@addTapListener true
                } else {
                    // На случай, если mapObject не совпадает (редко, но может быть)
                    Log.w(TAG, "Несовпадение объектов в addTapListener для $eventId. Ожидался: $marker, Получен: $mapObject")
                }
                // Возвращаем false, если событие не было обработано этим слушателем
                return@addTapListener false
            }

            // Сохраняем ссылку на созданный и добавленный слушатель в ConcurrentHashMap.
            // Это необходимо для возможности его последующего удаления.
            // Ссылка сохраняется как Any?, так как прямая работа с типом TapListener
            // может быть затруднена. Всегда удаляйте слушатели перед удалением маркера!
            eventMarkerTapListeners[eventId] = listener
            Log.d(TAG, "Слушатель касания добавлен и сохранен для маркера события $eventId")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при добавлении слушателя касания для маркера события $eventId", e)
            // Можно также сохранить null или не сохранять запись вообще в случае ошибки,
            // чтобы избежать попыток удаления несуществующего слушателя позже.
            // eventMarkerTapListeners[eventId] = null // Опционально
        }
    }



    // Предполагается, что эта функция находится внутри класса LocationFragment
// и имеет доступ к полям like TAG, auth, isAdded, requireContext(), activity

    private fun showEventDetails(
        eventId: String,
        name: String,
        description: String,
        expirationTime: Long, // Предполагается, что это System.currentTimeMillis()
        creatorId: String
    ) {
        // Проверка жизненного цикла фрагмента перед началом работы
        if (!isAdded) {
            Log.w(TAG, "showEventDetails: Фрагмент не активен, диалог не показан для события $eventId")
            return
        }

        // Проверка авторизации пользователя
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w(TAG, "showEventDetails: Пользователь не авторизован")
            try {
                Toast.makeText(requireContext(), "Необходима авторизация", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "showEventDetails: Ошибка отображения Toast об авторизации", e)
            }
            return
        }
        val currentUserId = currentUser.uid

        // --- НОВАЯ ЛОГИКА: Сначала показываем диалог с индикатором загрузки ---
        Log.d(TAG, "showEventDetails: Создание диалога с индикатором загрузки для события $eventId")

        // Создание AlertDialog
        val dialogBuilder = AlertDialog.Builder(requireContext())
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_event_details, null)

        // Найти элементы UI в макете диалога
        val pbParticipantsLoading = dialogView.findViewById<ProgressBar>(R.id.pbParticipantsLoading)
        val llEventDataContainer = dialogView.findViewById<LinearLayout>(R.id.llEventDataContainer)
        val pbActionLoading = dialogView.findViewById<ProgressBar>(R.id.pbActionLoading) // ProgressBar для действий (например, участие)

        val tvName = dialogView.findViewById<TextView>(R.id.tvEventName)
        val tvDescription = dialogView.findViewById<TextView>(R.id.tvEventDescription)
        val tvExpiration = dialogView.findViewById<TextView>(R.id.tvEventTimeLeft)
        val rvParticipants = dialogView.findViewById<RecyclerView>(R.id.rvParticipants)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDelete)
        val btnParticipate = dialogView.findViewById<Button>(R.id.btnParticipate)

        // Инициализировать RecyclerView и адаптер заранее
        // Передаем this@LocationFragment, адаптер сам обернет его в WeakReference
        val participantAdapter = ParticipantAdapter(emptyList(), this@LocationFragment)
        rvParticipants.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = participantAdapter
        }

        // Настроить кнопки (пока скрыты)
        btnParticipate.visibility = View.GONE
        btnDelete.visibility = View.GONE

        // Заполнить основную информацию о событии сразу
        tvName.text = name
        tvDescription.text = description
        val expirationDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(expirationTime))
        tvExpiration.text = "Истекает: $expirationDate"

        // --- Показ диалога ---
        dialogBuilder.setView(dialogView)
        // Создаем диалог
        val dialog = dialogBuilder.create()
        // (Опционально) Устанавливаем тег для идентификации диалога (если нужно извне)
        dialog.setOnShowListener {
            dialog.window?.decorView?.setTag(R.id.tag_event_id, eventId)
        }
        // Отображаем диалог
        dialog.show()
        Log.d(TAG, "showEventDetails: Диалог показан (с индикатором загрузки) для события $eventId")

        // --- Загрузка данных после показа диалога ---
        Log.d(TAG, "showEventDetails: Начало загрузки данных для события $eventId")

        // Вызываем функцию загрузки участников
        loadParticipantsForDialog(eventId, currentUserId, creatorId) { participantsList ->
            // Этот колбэк будет вызван после завершения загрузки участников
            // ВАЖНО: Проверить жизненный цикл фрагмента и состояние диалога внутри колбэка
            if (!isAdded) {
                Log.w(TAG, "showEventDetails (loadParticipants callback): Фрагмент не активен для события $eventId")
                return@loadParticipantsForDialog
            }
            if (!dialog.isShowing) {
                Log.d(TAG, "showEventDetails (loadParticipants callback): Диалог уже закрыт для события $eventId")
                return@loadParticipantsForDialog
            }

            Log.d(TAG, "showEventDetails: Данные загружены для события $eventId")

            // --- Обновление UI диалога после загрузки ---
            // Выполняем обновление UI в UI-потоке
            activity?.runOnUiThread {
                // Дополнительная проверка внутри UI-потока
                if (!isAdded || !dialog.isShowing) {
                    Log.w(TAG, "showEventDetails (UI update): Фрагмент не активен или диалог закрыт (в runOnUiThread) для события $eventId")
                    return@runOnUiThread
                }

                try {
                    // 1. Скрыть ProgressBar загрузки участников
                    pbParticipantsLoading.visibility = View.GONE

                    // 2. Показать контейнер с данными
                    llEventDataContainer.visibility = View.VISIBLE

                    // 3. Обновить адаптер с загруженным списком
                    participantAdapter.updateParticipants(participantsList)

                    // 4. Настроить видимость кнопок
                    // Кнопка "Удалить" - видна только создателю
                    if (currentUserId == creatorId) {
                        btnDelete.visibility = View.VISIBLE
                        btnDelete.setOnClickListener {
                            Log.d(TAG, "showEventDetails: Кнопка 'Удалить' нажата для события $eventId")
                            // Подтверждение удаления
                            AlertDialog.Builder(requireContext())
                                .setTitle("Удалить событие?")
                                .setMessage("Вы уверены, что хотите удалить событие '$name'?")
                                .setPositiveButton("Да") { _, _ ->
                                    Log.d(TAG, "showEventDetails: Подтверждено удаление события $eventId")
                                    deleteEvent(eventId)
                                    // deleteEvent должен сам закрывать диалог или делать это через колбэк
                                    // Если deleteEvent не закрывает диалог, добавьте dialog.dismiss() здесь
                                    // dialog.dismiss()
                                }
                                .setNegativeButton("Нет", null) // Ничего не делаем
                                .show()
                        }
                    } else {
                        btnDelete.visibility = View.GONE
                    }

                    // Кнопка "Участвовать" - скрыта, если пользователь уже участник
                    val isCurrentUserParticipant = participantsList.any { it.userId == currentUserId }
                    if (isCurrentUserParticipant) {
                        Log.d(TAG, "showEventDetails: Пользователь $currentUserId УЖЕ участвует в событии $eventId. Кнопка скрыта.")
                        btnParticipate.visibility = View.GONE
                    } else {
                        Log.d(TAG, "showEventDetails: Пользователь $currentUserId НЕ участвует в событии $eventId. Кнопка видна.")
                        btnParticipate.visibility = View.VISIBLE

                        // Логика кнопки "Участвовать"
                        btnParticipate.setOnClickListener {
                            Log.d(TAG, "showEventDetails: Кнопка 'Участвовать' нажата для события $eventId")
                            // Отключить кнопку и показать ProgressBar действия
                            btnParticipate.isEnabled = false
                            pbActionLoading.visibility = View.VISIBLE

                            // Вызываем функцию обработки участия
                            handleParticipateButtonClick(
                                eventId = eventId,
                                currentUserId = currentUserId,
                                currentUser = currentUser,
                                adapter = participantAdapter // Передаем адаптер для обновления внутри функции
                            ) { success ->
                                // Этот колбэк вызывается после завершения операции участия
                                // Выполняем обновление UI в UI-потоке
                                activity?.runOnUiThread {
                                    // Проверяем жизненный цикл внутри колбэка успеха
                                    if (!isAdded) {
                                        Log.w(TAG, "showEventDetails (handleParticipate callback): Фрагмент не активен для события $eventId")
                                        return@runOnUiThread
                                    }
                                    if (!dialog.isShowing) {
                                        Log.d(TAG, "showEventDetails (handleParticipate callback): Диалог уже закрыт для события $eventId")
                                        return@runOnUiThread
                                    }

                                    // Включить кнопку и скрыть ProgressBar действия
                                    btnParticipate.isEnabled = true
                                    pbActionLoading.visibility = View.GONE

                                    if (success) {
                                        Log.d(TAG, "showEventDetails: Участие в событии $eventId прошло успешно.")
                                        // После успешного участия кнопка должна скрыться.
                                        // Поскольку адаптер уже обновлён внутри handleParticipateButtonClick,
                                        // мы можем проверить статус снова и скрыть кнопку.
                                        val updatedList = participantAdapter.participants // Получаем обновлённый список из адаптера
                                        val isNowParticipant = updatedList.any { it.userId == currentUserId }
                                        if (isNowParticipant) {
                                            Log.d(TAG, "showEventDetails: Пользователь $currentUserId теперь участник. Кнопка скрыта.")
                                            btnParticipate.visibility = View.GONE
                                        } else {
                                            // На случай, если обновление списка не сработало как ожидалось
                                            Log.w(TAG, "showEventDetails: Пользователь $currentUserId участвует, но кнопка не скрыта (проверка списка).")
                                        }
                                    } else {
                                        Log.w(TAG, "showEventDetails: Ошибка при участии в событии $eventId.")
                                        // В случае ошибки можно показать Toast
                                        try {
                                            Toast.makeText(requireContext(), "Ошибка при участии в событии", Toast.LENGTH_SHORT).show()
                                        } catch (toastEx: Exception) {
                                            Log.e(TAG, "showEventDetails: Ошибка отображения Toast об ошибке участия", toastEx)
                                        }
                                        // Кнопка уже включена, так что пользователь может попробовать снова
                                    }
                                }
                            }
                        }
                    }
                    Log.d(TAG, "showEventDetails: UI диалога обновлён для события $eventId")
                } catch (e: Exception) {
                    Log.e(TAG, "showEventDetails: Ошибка обновления UI диалога для события $eventId", e)
                    // Пытаемся показать Toast об ошибке
                    try {
                        Toast.makeText(requireContext(), "Ошибка отображения данных события", Toast.LENGTH_SHORT).show()
                    } catch (toastEx: Exception) {
                        Log.e(TAG, "showEventDetails: Ошибка отображения Toast об ошибке UI", toastEx)
                    }
                    // Закрываем диалог в случае критической ошибки UI
                    try {
                        dialog.dismiss()
                    } catch (dismissEx: Exception) {
                        Log.e(TAG, "showEventDetails: Ошибка закрытия диалога после ошибки UI", dismissEx)
                    }
                }
            }
        }
        // --- Конец логики загрузки ---
    }





    private fun loadParticipantsForDialog(
        eventId: String,
        currentUserId: String,
        creatorId: String,
        onDataLoaded: (List<Participant>) -> Unit
    ) {
        Log.d(TAG, "loadParticipantsForDialog: Начало загрузки участников для события $eventId")

        database.child("events").child(eventId).child("participants").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "loadParticipantsForDialog: onDataChange вызван для $eventId. Children count: ${snapshot.childrenCount}")

                // 1. Сначала соберем ID пользователей
                val userIds = snapshot.children.mapNotNull { it.key }.toSet()
                Log.d(TAG, "loadParticipantsForDialog: Найдены ID участников: $userIds")

                // 2. Создадим список для хранения Participant
                val participantsList = mutableListOf<Participant>()

                // 3. Если участников нет, сразу вызываем колбэк
                if (userIds.isEmpty()) {
                    Log.d(TAG, "loadParticipantsForDialog: Нет участников, вызываем колбэк с пустым списком.")
                    onDataLoaded(emptyList())
                    return
                }

                var completedRequests = 0
                val totalRequests = userIds.size

                for (userId in userIds) {
                    // Получаем имя напрямую из узла participants (как fallback)
                    val storedName = snapshot.child(userId).getValue(String::class.java) ?: "Пользователь"

                    // Загружаем полный объект пользователя для получения аватара и точного имени
                    database.child("users").child(userId).get()
                        .addOnSuccessListener { userSnapshot ->
                            if (!isAdded) return@addOnSuccessListener // Проверка жизненного цикла

                            var participant: Participant? = null
                            try {
                                val user = userSnapshot.getValue(User::class.java)
                                val finalName = user?.getFullName().takeUnless { it.isNullOrBlank() }
                                    ?: user?.name.takeUnless { it.isNullOrBlank() }
                                    ?: storedName // fallback на имя из participants

                                val avatarUrl = user?.profileImageUrl // Получаем URL аватара

                                participant = Participant(userId, finalName, avatarUrl)
                                Log.d(TAG, "loadParticipantsForDialog: Загружен участник - ID: $userId, Name: '$finalName', Avatar: ${avatarUrl ?: "null"}")

                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка парсинга данных пользователя $userId", e)
                                // Даже если ошибка, создаем Participant с данными из participants
                                participant = Participant(userId, storedName, null)
                            } finally {
                                participantsList.add(participant!!)
                                completedRequests++
                                // Проверяем, завершены ли все запросы
                                if (completedRequests == totalRequests) {
                                    // Все данные получены, вызываем колбэк
                                    val sortedList = participantsList.sortedBy { it.name }
                                    Log.d(TAG, "loadParticipantsForDialog: Все данные загружены. Вызываем колбэк. Размер списка: ${sortedList.size}")
                                    onDataLoaded(sortedList)
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Ошибка загрузки данных пользователя $userId для события $eventId", e)
                            // Даже при ошибке добавляем участника с базовыми данными
                            val fallbackParticipant = Participant(userId, storedName, null)
                            participantsList.add(fallbackParticipant)
                            completedRequests++
                            if (completedRequests == totalRequests) {
                                val sortedList = participantsList.sortedBy { it.name }
                                Log.d(TAG, "loadParticipantsForDialog: Все данные (с ошибками) загружены. Вызываем колбэк. Размер списка: ${sortedList.size}")
                                onDataLoaded(sortedList)
                            }
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Ошибка загрузки участников для события $eventId", error.toException())
                // В случае ошибки БД, вызываем колбэк с пустым списком
                onDataLoaded(emptyList())
            }
        })
    }

    /**
     * Обрабатывает нажатие кнопки "Участвовать".
     */
    /**
     * Обрабатывает нажатие кнопки "Участвовать".
     * @param onComplete Колбэк, вызываемый после завершения операции (успех/ошибка).
     *                   Принимает `true` если успешно, `false` если ошибка.
     */
    private fun handleParticipateButtonClick(
        eventId: String,
        currentUserId: String,
        currentUser: FirebaseUser,
        adapter: ParticipantAdapter,
        onComplete: (Boolean) -> Unit // Новый параметр
    ) {
        Log.d(TAG, "handleParticipateButtonClick: Начало обработки для события $eventId")

        // 1. Получаем имя текущего пользователя
        database.child("users").child(currentUserId).get().addOnSuccessListener { userSnapshot ->
            if (!isAdded) {
                onComplete(false) // Завершить с ошибкой, если фрагмент не активен
                return@addOnSuccessListener
            }

            var correctUserName: String
            try {
                val user = userSnapshot.getValue(User::class.java)
                correctUserName = user?.getFullName().takeUnless { it.isNullOrBlank() }
                    ?: user?.name.takeUnless { it.isNullOrBlank() }
                            ?: currentUser.displayName.takeUnless { it.isNullOrBlank() }
                            ?: currentUser.email.takeUnless { it.isNullOrBlank() }
                            ?: "Аноним"
                Log.d(TAG, "handleParticipateButtonClick: Окончательное имя для участия: '$correctUserName'")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка парсинга данных пользователя для участия в событии", e)
                correctUserName = currentUser.displayName.takeUnless { it.isNullOrBlank() }
                    ?: currentUser.email.takeUnless { it.isNullOrBlank() }
                            ?: "Аноним"
                Log.d(TAG, "handleParticipateButtonClick: Имя из currentUser (fallback): '$correctUserName'")
            }

            // 2. Вызываем функцию участия
            participateInEventWithUiUpdate(
                eventId = eventId,
                userId = currentUserId,
                userName = correctUserName,
                adapterForUpdate = adapter
            ) { success -> // Передаём колбэк успеха из participateInEventWithUiUpdate
                onComplete(success) // Передаём результат дальше
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Ошибка загрузки данных пользователя для участия в событии", e)
            Toast.makeText(requireContext(), "Ошибка загрузки данных пользователя", Toast.LENGTH_SHORT).show()
            val fallbackUserName = currentUser.displayName.takeUnless { it.isNullOrBlank() }
                ?: currentUser.email.takeUnless { it.isNullOrBlank() }
                ?: "Аноним"

            participateInEventWithUiUpdate(
                eventId = eventId,
                userId = currentUserId,
                userName = fallbackUserName,
                adapterForUpdate = adapter
            ) { success ->
                onComplete(success)
            }
        }
    }

    /**
     * Добавляет пользователя в участники события и обновляет UI адаптера.
     * (Переименовал для ясности, что это версия с обновлением UI)
     */
    /**
     * Добавляет пользователя в участники события и обновляет UI адаптера.
     * @param onComplete Колбэк, вызываемый после завершения операции (успех/ошибка).
     *                   Принимает `true` если успешно, `false` если ошибка.
     */
    private fun participateInEventWithUiUpdate(
        eventId: String,
        userId: String,
        userName: String,
        adapterForUpdate: ParticipantAdapter,
        onComplete: (Boolean) -> Unit // Новый параметр
    ) {
        Log.d(TAG, "participateInEventWithUiUpdate: Начало добавления участника $userId ($userName) в событие $eventId")
        val participantData = mapOf(userId to userName)

        database.child("events").child(eventId).child("participants").updateChildren(participantData)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "Вы участвуете в событии!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "participateInEventWithUiUpdate: Пользователь $userId добавлен в Firebase с именем '$userName'")

                // Вместо прямого обновления адаптера, перезагружаем данные из Firebase
                loadParticipantsForDialog(eventId, userId, "") { updatedParticipantsList ->
                    // Колбэк вызывается после загрузки обновленного списка
                    if (!isAdded) {
                        onComplete(false) // Завершить с ошибкой, если фрагмент не активен
                        return@loadParticipantsForDialog
                    }
                    adapterForUpdate.updateParticipants(updatedParticipantsList)
                    Log.d(TAG, "participateInEventWithUiUpdate: Адаптер обновлен после участия.")
                    onComplete(true) // Завершить успешно
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка участия: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "participateInEventWithUiUpdate: Ошибка добавления участника в событие $eventId", e)
                onComplete(false) // Завершить с ошибкой
            }
    }


    private fun loadAndDisplayParticipants(
        eventId: String,
        adapter: ParticipantAdapter,
        onParticipantsLoaded: ((ParticipantAdapter) -> Unit)? = null
    ) {
        Log.d(TAG, "loadAndDisplayParticipants: Начало загрузки для события $eventId")
        database.child("events").child(eventId).child("participants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d(TAG, "loadAndDisplayParticipants: onDataChange вызван для $eventId. Children count: ${snapshot.childrenCount}")

                    // 1. Сначала соберем ID пользователей
                    val userIds = snapshot.children.mapNotNull { it.key }.toSet()
                    Log.d(TAG, "loadAndDisplayParticipants: Найдены ID участников: $userIds")

                    // 2. Создадим список для хранения Participant
                    val participantsList = mutableListOf<Participant>()

                    // 3. Для каждого ID загрузим данные пользователя
                    if (userIds.isEmpty()) {
                        // Если участников нет, обновляем адаптер пустым списком
                        adapter.updateParticipants(emptyList())
                        Log.d(TAG, "loadAndDisplayParticipants: Нет участников, адаптер обновлен пустым списком.")
                        // Вызываем колбэк
                        onParticipantsLoaded?.invoke(adapter)
                        return
                    }

                    var completedRequests = 0
                    val totalRequests = userIds.size

                    for (userId in userIds) {
                        // Получаем имя напрямую из узла participants (как fallback)
                        val storedName = snapshot.child(userId).getValue(String::class.java) ?: "Пользователь"

                        // Загружаем полный объект пользователя для получения аватара и точного имени
                        database.child("users").child(userId).get()
                            .addOnSuccessListener { userSnapshot ->
                                if (!isAdded) return@addOnSuccessListener // Проверка жизненного цикла

                                var participant: Participant? = null
                                try {
                                    val user = userSnapshot.getValue(User::class.java)
                                    val finalName = user?.getFullName().takeUnless { it.isNullOrBlank() }
                                        ?: user?.name.takeUnless { it.isNullOrBlank() }
                                        ?: storedName // fallback на имя из participants

                                    val avatarUrl = user?.profileImageUrl // Получаем URL аватара

                                    participant = Participant(userId, finalName, avatarUrl)
                                    Log.d(TAG, "loadAndDisplayParticipants: Загружен участник - ID: $userId, Name: '$finalName', Avatar: ${avatarUrl ?: "null"}")

                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка парсинга данных пользователя $userId", e)
                                    // Даже если ошибка, создаем Participant с данными из participants
                                    participant = Participant(userId, storedName, null)
                                } finally {
                                    participantsList.add(participant!!)
                                    completedRequests++
                                    // Проверяем, завершены ли все запросы
                                    if (completedRequests == totalRequests) {
                                        // Все данные получены, обновляем адаптер
                                        // Можно отсортировать список, например, по имени
                                        adapter.updateParticipants(participantsList.sortedBy { it.name })
                                        Log.d(TAG, "loadAndDisplayParticipants: Все данные загружены. Адаптер обновлен. Размер списка: ${participantsList.size}")
                                        // Вызываем колбэк
                                        onParticipantsLoaded?.invoke(adapter)
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Ошибка загрузки данных пользователя $userId для события $eventId", e)
                                // Даже при ошибке добавляем участника с базовыми данными
                                val fallbackParticipant = Participant(userId, storedName, null)
                                participantsList.add(fallbackParticipant)
                                completedRequests++
                                if (completedRequests == totalRequests) {
                                    adapter.updateParticipants(participantsList.sortedBy { it.name })
                                    Log.d(TAG, "loadAndDisplayParticipants: Все данные (с ошибками) загружены. Адаптер обновлен. Размер списка: ${participantsList.size}")
                                    // Вызываем колбэк
                                    onParticipantsLoaded?.invoke(adapter)
                                }
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Ошибка загрузки участников для события $eventId", error.toException())
                    adapter.updateParticipants(emptyList()) // Очищаем список в случае ошибки
                    // Вызываем колбэк даже в случае ошибки
                    onParticipantsLoaded?.invoke(adapter)
                }
            })
    }




    private fun addToRouteHistory(point: Point) {
        try {
            val currentTime = System.currentTimeMillis()
            val userId = auth.currentUser?.uid ?: return

            // 1. Проверка точности
            if (lastKnownAccuracy > MIN_ACCURACY_THRESHOLD_METERS) {
                Log.w(TAG, "Игнорирование точки с низкой точностью (точность: $lastKnownAccuracy)")
                return
            }

            // 2. Проверка минимального времени между обновлениями
            if (currentTime - lastLocationTime < MIN_TIME_DIFF_MS) {
                Log.d(TAG, "Обновление пришло слишком быстро, игнорирование")
                return
            }

            // 3. Если есть предыдущие точки, проверяем дистанцию, скорость и "скачки"
            if (lastLocationPoint != null && sessionRoutePoints.isNotEmpty()) {
                val lastPoint = lastLocationPoint!! // Используем последнюю *валидную* точку
                val timeDiff = (currentTime - lastLocationTime) / 1000.0 // В секундах

                // 4. Проверка на минимальное расстояние
                val distance = calculateDistance(lastPoint, point)
                if (distance < MIN_DISTANCE_FOR_UPDATE) {
                    Log.d(TAG, "Точка слишком близко ($distance м), игнорирование")
                    // Не возвращаем return здесь, так как точность и время уже проверены
                    // Но можно вернуть, если считаем это критичным
                    // return
                }

                // 5. Проверка на слишком быстрое перемещение (анти-телепортация)
                if (timeDiff > 0) { // Избегаем деления на ноль
                    val speed = distance / timeDiff // м/с
                    if (speed > MAX_SPEED_THRESHOLD_MPS) {
                        Log.w(TAG, "Обнаружено слишком быстрое перемещение (скорость: ${String.format("%.2f", speed)} м/с), игнорирование точки $point")
                        return
                    }
                } else {
                    Log.w(TAG, "Разница во времени <= 0, игнорирование точки $point")
                    return
                }

                // 6. Игнорируем аномальные "скачки" GPS (по расстоянию между последними двумя точками)
                if (distance > MAX_JUMP_DISTANCE_METERS) {
                    Log.w(TAG, "Обнаружен скачок GPS ($distance м), игнорирование точки $point")
                    // Можно также сбросить lastLocationPoint или sessionRoutePoints, если скачок критичный
                    // Например, если несколько подряд? Требует тестирования.
                    // clearRoute() // Опционально, если нужно сбросить весь маршрут
                    return
                }
            }

            // 7. Если все проверки пройдены, добавляем точку и обновляем маршрут
            sessionRoutePoints.add(point)
            lastLocationPoint = point // Обновляем последнюю валидную точку
            lastLocationTime = currentTime
            updateRoutePolyline()
            Log.d(TAG, "Точка добавлена в маршрут: $point")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления точки в маршрут", e)
        }
    }


    private fun requestBackgroundPermission() {
        Log.d(TAG, "Перенаправление в настройки для фонового местоположения")
        try {
            // Создаем интент для перехода в настройки приложения
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
            Toast.makeText(requireContext(), "Пожалуйста, включите разрешение на фоновое местоположение в настройках", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перенаправления в настройки", e)
            Toast.makeText(requireContext(), "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
        }
    }
    private fun handlePermissionDenial() {
        Log.d(TAG, "Обработка отказа в разрешениях")
        try {
            if (permissionsPermanentlyDenied()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Требуется доступ к местоположению")
                    .setMessage("Вы отказались от некоторых разрешений. Для полной функциональности приложения, пожалуйста, включите все необходимые разрешения в настройках.")
                    .setPositiveButton("Перейти в настройки") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", requireContext().packageName, null)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка перехода в настройки", e)
                            Toast.makeText(requireContext(), "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        Toast.makeText(
                            requireContext(),
                            "Функции карты будут ограничены без всех разрешений",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Функции карты ограничены без разрешения на местоположение",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки отказа в разрешениях", e)
        }
    }
    private fun checkLocationPermissions() {
        Log.d(TAG, "Проверка разрешений")
        Log.d(TAG, "Версия Android: ${Build.VERSION.SDK_INT}")
        try {
            when {
                hasAllPermissions() -> {
                    Log.d(TAG, "Все разрешения предоставлены")
                    initializeMapFeatures()
                }
                hasForegroundPermissions() -> {
                    Log.d(TAG, "Только передние разрешения предоставлены")
                    requestBackgroundPermission() // Теперь это перенаправит в настройки
                }
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                    Log.d(TAG, "Показ объяснения необходимости разрешений")
                    showRationaleBeforeRequest()
                }
                else -> {
                    Log.d(TAG, "Запрос основных разрешений")
                    requestForegroundPermissions()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки разрешений", e)
            Toast.makeText(requireContext(), "Ошибка проверки разрешений", Toast.LENGTH_SHORT).show()
        }
    }


// Убедитесь, что calculateDistance работает корректно (уже есть в коде)
// private fun calculateDistance(p1: Point, p2: Point): Double { ... }


    // --- Удаление события ---
    private fun deleteEvent(eventId: String) {
        database.child("events").child(eventId).removeValue()
            .addOnSuccessListener {
                removeEventMarker(eventId)
                Toast.makeText(requireContext(), "Событие удалено", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Событие $eventId удалено из Firebase")
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Ошибка удаления события $eventId из Firebase", e)
            }
    }

    // --- Удаление маркера события с карты ---
    private fun removeEventMarker(eventId: String) {
        eventMarkers[eventId]?.let { marker ->
            try {
                map.mapObjects.remove(marker)
                eventMarkers.remove(eventId)
                Log.d(TAG, "Маркер события $eventId удален из карты и коллекции")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления маркера события $eventId с карты", e)
            }
        } ?: Log.d(TAG, "Маркер события $eventId не найден для удаления")
    }





    private fun clearMapObjects() {
        Log.d(TAG, "clearMapObjects: НАЧАЛО")
        try {
            // --- 1. Удаляем метки событий ---
            Log.d(TAG, "clearMapObjects: Удаление меток событий, текущий размер eventMarkers: ${eventMarkers.size}")
            // Создаем копию значений для безопасной итерации, так как мы будем модифицировать оригинальную коллекцию
            val eventMarkersToRemove = eventMarkers.values.toList()
            for (marker in eventMarkersToRemove) {
                try {
                    // Безопасно удаляем маркер из MapKit
                    // map.mapObjects.remove может бросить исключение, если объект уже удален
                    map.mapObjects.remove(marker)
                    Log.d(TAG, "clearMapObjects: Метка события удалена из mapObjects")
                } catch (e: Exception) {
                    // Может быть AlreadyRemovedException, IllegalArgumentException или другая ошибка MapKit
                    Log.w(TAG, "clearMapObjects: Не удалось удалить метку события из mapObjects. Возможно, она уже удалена.", e)
                    // Продолжаем цикл, чтобы попытаться удалить остальные
                }
            }
            // Очищаем коллекции в памяти после попытки удаления из MapKit
            eventMarkers.clear()
            eventMarkerTapListeners.clear() // <-- КРИТИЧЕСКИ ВАЖНО для TapListener'ов
            Log.d(TAG, "clearMapObjects: Коллекции eventMarkers и eventMarkerTapListeners очищены")

            // --- 2. Удаляем маркеры других пользователей ---
            Log.d(TAG, "clearMapObjects: Удаление маркеров пользователей, текущий размер otherUserMarkers: ${otherUserMarkers.size}")
            val otherMarkersToRemove = otherUserMarkers.values.toList()
            for (marker in otherMarkersToRemove) {
                try {
                    map.mapObjects.remove(marker)
                    Log.d(TAG, "clearMapObjects: Маркер пользователя удален из mapObjects")
                } catch (e: Exception) {
                    Log.w(TAG, "clearMapObjects: Не удалось удалить маркер пользователя из mapObjects. Возможно, он уже удален.", e)
                }
            }
            otherUserMarkers.clear()
            Log.d(TAG, "clearMapObjects: Коллекция otherUserMarkers очищена")

            // --- 3. Удаляем 'мой' маркер ---
            Log.d(TAG, "clearMapObjects: Удаление 'моего' маркера")
            myMarker?.let { marker ->
                try {
                    map.mapObjects.remove(marker)
                    Log.d(TAG, "clearMapObjects: 'Мой' маркер удален из mapObjects")
                } catch (e: Exception) {
                    Log.w(TAG, "clearMapObjects: Не удалось удалить 'мой' маркер из mapObjects. Возможно, он уже удален.", e)
                }
                // Сбросить ссылку внутри let, чтобы избежать повторного использования
                myMarker = null
            }

            // --- 4. Очищаем данные маршрута и удаляем полилинию ---
            Log.d(TAG, "clearMapObjects: Очистка данных маршрута")
            // Очищаем список точек маршрута
            sessionRoutePoints.clear()
            lastLocationPoint = null
            lastLocationTime = 0

            // Удаляем объект полилинии с карты, если он существует
            routePolyline?.let { polylineObj ->
                try {
                    map.mapObjects.remove(polylineObj)
                    Log.d(TAG, "clearMapObjects: Полилиния маршрута удалена из mapObjects")
                } catch (e: Exception) {
                    Log.w(TAG, "clearMapObjects: Не удалось удалить полилинию маршрута из mapObjects. Возможно, она уже удалена.", e)
                }
                // Сбросить ссылку внутри let
                routePolyline = null
            }
            Log.d(TAG, "clearMapObjects: Данные маршрута и локации сброшены")

            Log.d(TAG, "clearMapObjects: ВСЕ ОБЪЕКТЫ КАРТЫ И КОЛЛЕКЦИИ ОЧИЩЕНЫ ВЫБОРОЧНО")
        } catch (e: Exception) {
            Log.e(TAG, "clearMapObjects: Критическая ошибка во время очистки объектов карты", e)
            // Даже если произошла ошибка, попробуем очистить коллекции в памяти,
            // чтобы минимизировать утечки. Используем безопасные вызовы.
            try {
                eventMarkers?.clear()
                eventMarkerTapListeners?.clear()
                otherUserMarkers?.clear()
                myMarker = null
                routePolyline = null
                sessionRoutePoints?.clear()
                lastLocationPoint = null
                lastLocationTime = 0
                Log.d(TAG, "clearMapObjects: Коллекции в памяти принудительно очищены после критической ошибки")
            } catch (cleanupError: Exception) {
                Log.e(TAG, "clearMapObjects: Ошибка при принудительной очистке коллекций в памяти", cleanupError)
            }
        }
        // --- ДЛЯ ОТЛАДКИ (временно) ---
        // Log.d(TAG, "clearMapObjects: Принудительный вызов GC для отладки (убрать в production!)")
        // System.gc()
        // ---
        Log.d(TAG, "clearMapObjects: КОНЕЦ")
    }


    private fun reloadAllMarkers() {
        Log.d(TAG, "reloadAllMarkers: НАЧАЛО. Размер otherUserMarkers до очистки: ${otherUserMarkers.size}, eventMarkers: ${eventMarkers.size}")

        // --- 1. ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА ---
        if (!isAdded || !::map.isInitialized) {
            val reason = if (!isAdded) "фрагмент отсоединен" else "карта не инициализирована"
            Log.w(TAG, "reloadAllMarkers: Операция отменена ($reason)")
            return
        }

        try {
            // --- 2. ОЧИСТКА МАРКЕРОВ СОБЫТИЙ ---
            Log.d(TAG, "reloadAllMarkers: Шаг 1 - Очистка маркеров событий")
            val eventMarkersToRemove = eventMarkers.values.toList() // Безопасная копия
            for (marker in eventMarkersToRemove) {
                try {
                    // Удаление TapListener'а (если он хранится) - КРИТИЧЕСКИ ВАЖНО
                    // Предполагается, что eventMarkerTapListeners - это ConcurrentHashMap<String, Any?>
                    // где ключ - eventId. MapKit может не требовать явного удаления listener'а,
                    // но очистка коллекции важна для предотвращения утечек.
                    // eventMarkerTapListeners[eventId]?.let { marker.removeTapListener(it) } // Если MapKit поддерживает

                    map.mapObjects.remove(marker)
                    Log.d(TAG, "reloadAllMarkers: Метка события удалена с карты")
                } catch (e: Exception) {
                    Log.w(TAG, "reloadAllMarkers: Не удалось удалить метку события. Возможно, она уже удалена.", e)
                }
            }
            eventMarkers.clear()
            eventMarkerTapListeners.clear() // <-- КРИТИЧЕСКИ ВАЖНО
            Log.d(TAG, "reloadAllMarkers: Коллекции eventMarkers и eventMarkerTapListeners очищены")


            // --- 3. ОЧИСТКА МАРКЕРОВ ПОЛЬЗОВАТЕЛЕЙ ---
            Log.d(TAG, "reloadAllMarkers: Шаг 2 - Очистка маркеров пользователей")
            val otherMarkersToRemove = otherUserMarkers.values.toList() // Безопасная копия
            for (marker in otherMarkersToRemove) {
                try {
                    // Для пользовательских маркеров: если вы храните TapListener'ы отдельно,
                    // их тоже нужно удалить. Если нет, MapKit обычно сам управляет этим,
                    // если объект удален из mapObjects.
                    // Например: marker.removeTapListener(userMarkerTapListeners[userId]) // Если есть такая коллекция

                    map.mapObjects.remove(marker)
                    Log.d(TAG, "reloadAllMarkers: Маркер пользователя удален с карты")
                } catch (e: Exception) {
                    Log.w(TAG, "reloadAllMarkers: Не удалось удалить маркер пользователя. Возможно, он уже удален.", e)
                }
            }
            otherUserMarkers.clear() // <-- Очистка коллекции ссылок на маркеры
            Log.d(TAG, "reloadAllMarkers: Коллекция otherUserMarkers очищена")


            // --- 4. ПЕРЕЗАГРУЗКА ДАННЫХ ---
            Log.d(TAG, "reloadAllMarkers: Шаг 3 - Перезагрузка данных")

            // Используем Handler.postDelayed для небольшой задержки, чтобы дать системе "перевести дыхание"
            // после очистки, особенно если данные берутся из Firebase.

            // --- 4a. Перезагрузка маркеров событий ---
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAdded) { // Повторная проверка жизненного цикла
                    Log.d(TAG, "reloadAllMarkers: Вызов loadExistingEvents для пересоздания маркеров событий")
                    loadExistingEvents() // Эта функция создаст новые маркеры событий и добавит к ним TapListener'ы
                } else {
                    Log.w(TAG, "reloadAllMarkers: Фрагмент отсоединен к моменту перезагрузки событий")
                }
            }, 300) // Задержка 300мс

            // --- 4b. Перезагрузка маркеров пользователей ---
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAdded) { // Повторная проверка жизненного цикла
                    Log.d(TAG, "reloadAllMarkers: Вызов перезагрузки маркеров пользователей")
                    // Перезагружаем данные о местоположениях пользователей
                    database.child("user_locations").get().addOnSuccessListener { snapshot ->
                        try {
                            Log.d(TAG, "reloadAllMarkers: Данные о местоположениях пользователей получены. Начинаем обработку.")
                            val currentUserUid = auth.currentUser?.uid
                            // Обрабатываем данные о местоположениях
                            for (ds in snapshot.children) {
                                val userId = ds.key ?: continue
                                // Пропускаем текущего пользователя
                                if (userId == currentUserUid) {
                                    Log.d(TAG, "reloadAllMarkers: Пропущен текущий пользователь: $userId")
                                    continue
                                }
                                try {
                                    val location = ds.getValue(UserLocation::class.java)
                                    if (location != null) {
                                        val point = Point(location.lat, location.lng)
                                        Log.d(TAG, "reloadAllMarkers: Обработка пользователя $userId в точке $point")
                                        // checkLocationVisibility должно внутри вызывать addOtherUserMarker
                                        // с правильным добавлением TapListener'а
                                        checkLocationVisibility(userId, point)
                                    } else {
                                        Log.w(TAG, "reloadAllMarkers: Данные местоположения для $userId пусты или невалидны.")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "reloadAllMarkers: Ошибка обработки данных пользователя $userId", e)
                                }
                            }
                            Log.d(TAG, "reloadAllMarkers: Перезагрузка маркеров пользователей завершена.")
                        } catch (e: Exception) {
                            Log.e(TAG, "reloadAllMarkers: Критическая ошибка обработки данных о местоположениях", e)
                        }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "reloadAllMarkers: Ошибка загрузки местоположений", e)
                    }
                } else {
                    Log.w(TAG, "reloadAllMarkers: Фрагмент отсоединен к моменту перезагрузки пользователей")
                }
            }, 600) // Задержка 600мс, чуть больше, чем для событий

        } catch (e: Exception) {
            Log.e(TAG, "reloadAllMarkers: Критическая ошибка во время перезагрузки", e)
            // Даже если произошла ошибка, попробуем очистить коллекции в памяти,
            // чтобы минимизировать утечки.
            try {
                eventMarkers?.clear()
                eventMarkerTapListeners?.clear()
                otherUserMarkers?.clear()
            } catch (clearEx: Exception) {
                Log.e(TAG, "reloadAllMarkers: Ошибка при принудительной очистке коллекций", clearEx)
            }
        }

        Log.d(TAG, "reloadAllMarkers: ЗАВЕРШЕНО")
    }



    private fun addOtherUserMarker(userId: String, name: String, position: Point, avatarUrl: String?) {
        Log.d(TAG, "addOtherUserMarker: Создание маркера для userId: '$userId', name: '$name'")
        try {
            // Удаляем старый маркер, если он существует
            otherUserMarkers[userId]?.let { oldMarker ->
                try {
                    map.mapObjects.remove(oldMarker)
                    Log.d(TAG, "addOtherUserMarker: Удален старый маркер для userId: '$userId'")
                } catch (e: Exception) {
                    Log.e(TAG, "addOtherUserMarker: Ошибка удаления старого маркера", e)
                }
            }

            // Создаем новый маркер
            val marker = map.mapObjects.addPlacemark().apply {
                geometry = position
                // Временная прозрачная иконка, позже заменится аватаром
                setIcon(ImageProvider.fromResource(requireContext(), android.R.color.transparent))
                setIconStyle(IconStyle().apply {
                    scale = 1.0f
                    zIndex = 1.0f
                })
                setText(name)

                // ДОБАВЛЯЕМ TapListener - КРИТИЧЕСКИ ВАЖНО
                addTapListener { mapObject, tapPoint ->
                    Log.d(TAG, "TapListener: Сработал для маркера userId: '$userId'")

                    // Упрощенная проверка - только что фрагмент добавлен
                    if (!this@LocationFragment.isAdded) {
                        Log.w(TAG, "TapListener: Фрагмент не добавлен")
                        return@addTapListener false
                    }

                    // Проверяем, что клик был именно на этом маркере
                    if (mapObject == this) {
                        Log.d(TAG, "TapListener: Совпадение объектов. Вызов onMarkerClick")
                        onMarkerClick(userId, name)
                        return@addTapListener true
                    }
                    return@addTapListener false
                }
            }

            // Сохраняем ссылку на маркер
            otherUserMarkers[userId] = marker
            Log.d(TAG, "addOtherUserMarker: Маркер добавлен в otherUserMarkers для userId: '$userId'")

            // Загружаем аватар и обновляем иконку
            loadAndSetMarkerIcon(requireContext(), position, name, avatarUrl, false, marker)
            Log.d(TAG, "addOtherUserMarker: Запущена загрузка иконки для userId: '$userId'")

        } catch (e: Exception) {
            Log.e(TAG, "addOtherUserMarker: Ошибка добавления маркера пользователя userId: '$userId'", e)
        }
    }


    // --- НОВЫЙ МЕТОД ДЛЯ ОБРАТНОГО ГЕОКОДИРОВАНИЯ (исправленный) ---
    private fun reverseGeocode(point: Point) {
        try {
            val searchOptions = SearchOptions()
            // Создаем сессию поиска
            val session = searchManager.submit(
                point,
                15, // Zoom level
                searchOptions,
                object : Session.SearchListener {
                    override fun onSearchResponse(response: Response) {
                        if (isAdded) { // Проверка, что фрагмент ещё активен
                            var cityName: String? = null
                            // Проверяем результаты
                            if (response.collection.children.isNotEmpty()) {
                                // Берем первый (наиболее релевантный) результат
                                val topResult = response.collection.children.firstOrNull()
                                // Используем имя объекта как название места
                                // Это может быть название улицы, здания, района или города
                                cityName = topResult?.obj?.name
                            }

                            val finalName = cityName ?: "Неизвестно"
                            Log.d(TAG, "Найденное место: $finalName для точки $point")
                            // Обновляем UI в основном потоке
                            activity?.runOnUiThread {
                                cityNameTextView.text = finalName
                            }
                        }
                    }

                    override fun onSearchError(error: com.yandex.runtime.Error) { // Уточняем тип Error
                        if (isAdded) {
                            Log.e(TAG, "Ошибка обратного геокодирования для точки $point: ${error.toString()}")
                            activity?.runOnUiThread {
                                cityNameTextView.text = "Ошибка определения"
                            }
                        }
                    }
                }
            )
            // session можно сохранить, если нужно отменить запрос позже
            // this.searchSession = session // Например, если добавить поле searchSession: Session? = null
        } catch (e: Exception) {
            if (isAdded) {
                Log.e(TAG, "Ошибка запуска обратного геокодирования для точки $point", e)
                cityNameTextView.text = "Ошибка"
            }
        }
    }
    // --- КОНЕЦ НОВОГО МЕТОДА ОБРАТНОГО ГЕОКОДИРОВАНИЯ ---







    /**
     * Создает маркер пользователя с дефолтной иконкой, используя отложенное выполнение.
     */
    private fun createUserMarkerWithDefaultIcon(userId: String, name: String, point: Point) {
        try {
            // --- ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА НА ВХОДЕ ---
            if (!isAdded) {
                Log.w(TAG, "createUserMarkerWithDefaultIcon: Фрагмент не добавлен для $userId")
                return
            }

            // --- ОТЛОЖЕННОЕ СОЗДАНИЕ МАРКЕРА ---
            Handler(Looper.getMainLooper()).postDelayed({
                // --- ПОВТОРНАЯ ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА ---
                if (!isAdded) {
                    Log.w(TAG, "createUserMarkerWithDefaultIcon: Фрагмент не добавлен (после задержки) для $userId")
                    return@postDelayed
                }
                Log.d(TAG, "createUserMarkerWithDefaultIcon: Создание маркера с задержкой для $userId")

                try {
                    // --- ДЕЛЕГИРОВАНИЕ СОЗДАНИЯ ---
                    // Передаем null для bitmap, чтобы функция использовала дефолтную иконку
                    createAndAddUserPlacemark(userId, name, point, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка создания маркера с дефолтной иконкой для $userId внутри postDelayed", e)
                }
            }, 750) // --- ЗАДЕРЖКА ---

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в createUserMarkerWithDefaultIcon (внешний блок) для $userId", e)
        }
    }






    private fun loadUserAndAddMarker(userId: String, point: Point) {
        try {
            // Избегаем дублирования
            if (otherUserMarkers.containsKey(userId)) return

            database.child("users").child(userId).get()
                .addOnSuccessListener { userSnap ->
                    if (!isAdded) return@addOnSuccessListener

                    try {
                        val user = userSnap.getValue(User::class.java)
                        val name = user?.getFullName() ?: "Пользователь $userId"
                        val avatarUrl = user?.profileImageUrl

                        // Сначала загружаем аватар, потом создаем маркер
                        loadAvatarAndCreateMarker(userId, name, point, avatarUrl)

                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга данных пользователя для $userId", e)
                        loadAvatarAndCreateMarker(userId, "Пользователь $userId", point, null)
                    }
                }
                .addOnFailureListener { e ->
                    if (isAdded) {
                        Log.e(TAG, "Ошибка загрузки данных пользователя для $userId", e)
                        loadAvatarAndCreateMarker(userId, "Пользователь $userId", point, null)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки пользователя и добавления маркера", e)
        }
    }


    private fun createUserMarkerWithIcon(userId: String, name: String, point: Point, avatarBitmap: Bitmap) {
        try {
            // --- ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА НА ВХОДЕ ---
            if (!isAdded) {
                Log.w(TAG, "createUserMarkerWithIcon: Фрагмент не добавлен для $userId")
                return
            }

            // --- ОТЛОЖЕННОЕ СОЗДАНИЕ МАРКЕРА ---
            Handler(Looper.getMainLooper()).postDelayed({
                // --- ПОВТОРНАЯ ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА ---
                if (!isAdded) {
                    Log.w(TAG, "createUserMarkerWithIcon: Фрагмент не добавлен (после задержки) для $userId")
                    return@postDelayed
                }
                Log.d(TAG, "createUserMarkerWithIcon: Создание маркера с задержкой для $userId")

                try {
                    // --- ДЕЛЕГИРОВАНИЕ СОЗДАНИЯ ---
                    // Передаем аватар
                    createAndAddUserPlacemark(userId, name, point, avatarBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка создания маркера с аватаром для $userId внутри postDelayed", e)
                    // При ошибке создания маркера с аватаром можно создать дефолтный
                    // createUserMarkerWithDefaultIcon(userId, name, point)
                }
            }, 750) // --- ЗАДЕРЖКА ---

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в createUserMarkerWithIcon (внешний блок) для $userId", e)
        }
    }



    // Новая функция, содержащая только логику создания объекта маркера пользователя
    private fun createAndAddUserPlacemark(userId: String, name: String, point: Point, bitmap: Bitmap?): PlacemarkMapObject {
        // --- УДАЛЕНИЕ СТАРОГО МАРКЕРА ---
        otherUserMarkers[userId]?.let { oldMarker ->
            try {
                map.mapObjects.remove(oldMarker)
                Log.d(TAG, "createAndAddUserPlacemark: Старый маркер удален для $userId")
            } catch (e: Exception) {
                Log.d(TAG, "createAndAddUserPlacemark: Старый маркер, возможно, уже удален для $userId")
            }
        }

        // --- ПОДГОТОВКА ИКОНКИ ---
        val iconProvider: ImageProvider
        if (bitmap != null) {
            // --- МАСШТАБИРОВАНИЕ АВАТАРА ---
            // Используем тот же размер, что и в loadAndSetMarkerIcon, для согласованности
            val targetAvatarSizePx = 130 // Размер в пикселях, подберите под себя (например, 90, 130)
            // Масштабируем исходный bitmap (круглый аватар) до целевого квадратного размера
            // Убедитесь, что bitmap.width и bitmap.height > 0, чтобы избежать ошибок
            if (bitmap.width > 0 && bitmap.height > 0) {
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetAvatarSizePx, targetAvatarSizePx, false)
                // Создаем ImageProvider из МАСШТАБИРОВАННОГО Bitmap
                iconProvider = ImageProvider.fromBitmap(scaledBitmap)
                Log.d(TAG, "createAndAddUserPlacemark: Аватар масштабирован для $userId: ${bitmap.width}x${bitmap.height} -> ${targetAvatarSizePx}x${targetAvatarSizePx}")
            } else {
                // Если исходный bitmap некорректный, используем дефолтную иконку
                Log.w(TAG, "createAndAddUserPlacemark: Исходный bitmap аватара для $userId некорректный (размер ${bitmap.width}x${bitmap.height}). Используется дефолтная иконка.")
                iconProvider = ImageProvider.fromResource(requireContext(), R.drawable.blue_marker_45x45)
            }
        } else {
            // Если bitmap null, используем дефолтную иконку
            iconProvider = ImageProvider.fromResource(requireContext(), R.drawable.blue_marker_45x45)
        }

        // --- СОЗДАНИЕ НОВОГО МАРКЕРА ---
        val marker = map.mapObjects.addPlacemark().apply {
            geometry = point
            setIcon(iconProvider) // Устанавливаем подготовленный ImageProvider
            setIconStyle(IconStyle().apply {
                // Для круглых аватаров часто лучше использовать scale = 1.0f,
                // чтобы избежать дополнительного растягивания/сжатия.
                // Размер контролируется targetAvatarSizePx выше.
                scale = 1.0f
                zIndex = 1.0f
                // Anchor по умолчанию (0.5f, 0.5f) обычно подходит для центрирования
            })
            setText(name)
            // --- ДОБАВЛЕНИЕ TAP LISTENER ---
            addTapListener { mapObject, tapPoint ->
                Log.d(TAG, "TapListener (пользователь): Сработал для маркера userId: '$userId'")
                if (!this@LocationFragment.isAdded) {
                    Log.w(TAG, "TapListener (пользователь): Фрагмент не добавлен для $userId")
                    return@addTapListener false
                }
                if (mapObject == this) {
                    Log.d(TAG, "TapListener (пользователь): Совпадение объектов. Вызов onMarkerClick для $userId")
                    onMarkerClick(userId, name)
                    return@addTapListener true
                }
                return@addTapListener false
            }
        }

        // --- СОХРАНЕНИЕ ССЫЛКИ ---
        otherUserMarkers[userId] = marker
        val iconType = if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) "аватар" else "дефолт"
        Log.d(TAG, "createAndAddUserPlacemark: Маркер ($iconType) добавлен в otherUserMarkers для userId: '$userId'")
        return marker
    }



    private fun loadAvatarAndCreateMarker(userId: String, name: String, point: Point, avatarUrl: String?) {
        val context = context ?: return // Проверка контекста
        Log.d(TAG, "loadAvatarAndCreateMarker: Начало для userId: '$userId', avatarUrl: '$avatarUrl'")

        if (!avatarUrl.isNullOrEmpty()) {
            try {
                Glide.with(context)
                    .asBitmap()
                    .load(avatarUrl)
                    // Используем размеры напрямую в Glide, если известны желаемые
                    // .override(130, 130) // Пример, если вы хотите масштабировать заранее
                    .into(object : CustomTarget<Bitmap>() { // Анонимный объект CustomTarget
                        // ВАЖНО: Убедитесь, что импортирован правильный CustomTarget
                        // import com.bumptech.glide.request.target.CustomTarget
                        // import com.bumptech.glide.request.transition.Transition

                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            // Проверка жизненного цикла сразу после загрузки
                            if (!this@LocationFragment.isAdded) { // Явно указываем this@LocationFragment
                                Log.w(TAG, "onResourceReady: Фрагмент не добавлен для $userId")
                                return
                            }

                            Log.d(TAG, "onResourceReady: Аватар загружен для $userId")

                            // Создаем круглый аватар
                            val circularBitmap = getCircularBitmap(resource)

                            // --- ОТЛОЖЕННОЕ СОЗДАНИЕ МАРКЕРА ---
                            Handler(Looper.getMainLooper()).postDelayed({
                                // Повторная проверка жизненного цикла после задержки
                                if (!this@LocationFragment.isAdded) { // Явно указываем this@LocationFragment
                                    Log.w(TAG, "onResourceReady (с задержкой): Фрагмент не добавлен для $userId")
                                    return@postDelayed
                                }
                                Log.d(TAG, "onResourceReady (с задержкой): Создание маркера с аватаром для $userId")

                                // --- ДЕЛЕГИРОВАНИЕ СОЗДАНИЯ ---
                                try {
                                    // Передаем круглый аватар
                                    createAndAddUserPlacemark(userId, name, point, circularBitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка создания маркера с аватаром для $userId внутри onResourceReady postDelayed", e)
                                    // При ошибке можно создать дефолтный маркер
                                    createUserMarkerWithDefaultIcon(userId, name, point) // <-- Добавлен вызов
                                }

                            }, 750) // --- ЗАДЕРЖКА ---
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // Этот метод вызывается, когда ресурс больше не нужен (например, при очистке)
                            // или если загрузка была отменена.
                            Log.d(TAG, "onLoadCleared: для $userId")
                            // Обычно здесь ничего не делается для маркеров, или можно установить дефолтную иконку
                            // если маркер уже создан. Но в текущей логике это не нужно, так как
                            // маркер создается только в onResourceReady или onLoadFailed.
                            // Если маркер уже создан, то он не будет пересоздаваться здесь.
                            // Если маркер еще не создан, то onLoadFailed обработает это.
                            // Поэтому можно оставить пустым или залогировать.
                            if (!this@LocationFragment.isAdded) return
                            Log.d(TAG, "onLoadCleared: Вызов создания дефолтного маркера для $userId")
                            createUserMarkerWithDefaultIcon(userId, name, point) // <-- Добавлен вызов
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            super.onLoadFailed(errorDrawable)
                            // Этот метод вызывается, если загрузка изображения завершилась неудачей.
                            Log.w(TAG, "onLoadFailed: Не удалось загрузить аватар для $userId")
                            // Создаем маркер с дефолтной иконкой
                            if (!this@LocationFragment.isAdded) return
                            Log.d(TAG, "onLoadFailed: Вызов создания дефолтного маркера для $userId")
                            createUserMarkerWithDefaultIcon(userId, name, point) // <-- Добавлен вызов
                        }
                    })
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка настройки Glide для $userId", e)
                // На случай ошибки конфигурации Glide, создаем дефолтный маркер
                createUserMarkerWithDefaultIcon(userId, name, point)
            }
        } else {
            // Если URL аватара пустой или null, сразу создаем дефолтный маркер
            Log.d(TAG, "loadAvatarAndCreateMarker: Пустой или null avatarUrl для $userId, создаём дефолтный маркер")
            createUserMarkerWithDefaultIcon(userId, name, point)
        }

    }




    // --- ОБНОВЛЕННЫЙ СЛУШАТЕЛЬ КАМЕРЫ ---
    override fun onCameraPositionChanged(
        map: com.yandex.mapkit.map.Map,
        cameraPosition: CameraPosition,
        cameraUpdateReason: CameraUpdateReason,
        finished: Boolean
    ) {
        // Вызываем геокодирование, когда камера перестала двигаться
        if (finished) {
            val targetPoint = cameraPosition.target
            Log.d(TAG, "Камера остановилась в точке: ${targetPoint.latitude}, ${targetPoint.longitude}")
            reverseGeocode(targetPoint)
        }
        // Если нужно обновлять постоянно (например, во время движения), уберите `if (finished)`
        // но это может привести к частым запросам.
    }
    // --- КОНЕЦ ОБНОВЛЕННОГО СЛУШАТЕЛЯ ---

    private fun loadFriendsList() {
        Log.d(TAG, "Загрузка списка друзей")
        val currentUserId = auth.currentUser?.uid ?: run {
            Log.w(TAG, "Пользователь не авторизован")
            return
        }
        friendsListener?.let {
            database.child("friends").child(currentUserId).removeEventListener(it)
        }
        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    friendList.clear()
                    for (ds in snapshot.children) {
                        val friendId = ds.key
                        if (friendId != null && ds.getValue(Boolean::class.java) == true) {
                            friendList.add(friendId)
                        }
                    }
                    Log.d(TAG, "Список друзей обновлен: ${friendList.size} друзей")
                    if (isMapInitialized) {
                        reloadAllMarkers()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка обработки списка друзей", e)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Загрузка друзей отменена", error.toException())
            }
        }
        database.child("friends").child(currentUserId)
            .addValueEventListener(friendsListener!!)
        Log.d(TAG, "Слушатель друзей добавлен")
    }
    private fun hasAllPermissions(): Boolean {
        return try {
            val hasForeground = hasForegroundPermissions()
            val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && BACKGROUND_PERMISSION.isNotEmpty()) {
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            hasForeground && hasBackground
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки всех разрешений", e)
            false
        }
    }

    private fun hasForegroundPermissions(): Boolean {
        return FOREGROUND_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }
    private fun requestForegroundPermissions() {
        Log.d(TAG, "Запрос основных разрешений местоположения")
        try {
            permissionLauncher.launch(FOREGROUND_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса разрешений", e)
        }
    }

    private fun showRationaleBeforeRequest() {
        Log.d(TAG, "Показ диалога объяснения разрешений")
        try {
            AlertDialog.Builder(requireContext())
                .setTitle("Требуется доступ к местоположению")
                .setMessage("Для отображения карты и вашего местоположения необходимо предоставить разрешения. Рекомендуется дать разрешение! (Разрешить в любом режиме)")
                .setPositiveButton("OK") { _, _ ->
                    Log.d(TAG, "Пользователь согласился на разрешения")
                    requestForegroundPermissions()
                }
                .setNegativeButton("Отмена") { dialog, _ ->
                    Log.d(TAG, "Пользователь отказался от разрешений")
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "Функции карты будут ограничены", Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка показа диалога разрешений", e)
            requestForegroundPermissions() // Просто запросим разрешения
        }
    }

    private fun permissionsPermanentlyDenied(): Boolean {
        return FOREGROUND_PERMISSIONS.any { permission ->
            !shouldShowRequestPermissionRationale(permission) &&
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        permission
                    ) != PackageManager.PERMISSION_GRANTED
        }
    }
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        Log.d(TAG, "Включение отслеживания местоположения")
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                try {
                    location?.let { it ->
                        Log.d(TAG, "Получено местоположение: ${it.latitude}, ${it.longitude}, точность: ${it.accuracy}")
                        lastKnownAccuracy = it.accuracy
                        // Проверяем, что точность приемлемая
                        if (it.accuracy > 50) {
                            Log.w(TAG, "Низкая точность местоположения игнорируется (${it.accuracy}м)")
                            return@let
                        }
                        currentLocation =
                            com.yandex.mapkit.geometry.Point(it.latitude, it.longitude)
                        moveCamera(currentLocation!!, 14f)
                        updateUserLocation(it.latitude, it.longitude)
                        // --- Вызов изменённого метода ---
                        showMyLocation(it.latitude, it.longitude)
                        addToRouteHistory(
                            com.yandex.mapkit.geometry.Point(
                                it.latitude,
                                it.longitude
                            )
                        )
                    } ?: run {
                        Log.w(TAG, "Местоположение равно null")
                        Toast.makeText(requireContext(), "Не удалось получить местоположение", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка обработки местоположения", e)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Ошибка запроса местоположения", e)
                Toast.makeText(requireContext(), "Ошибка получения местоположения", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка включения местоположения", e)
            Toast.makeText(requireContext(), "Ошибка доступа к местоположению", Toast.LENGTH_SHORT).show()
        }
    }
    private fun updateUserLocation(lat: Double, lng: Double) {
        Log.d(TAG, "Обновление местоположения в базе данных: $lat, $lng")
        val userId = auth.currentUser?.uid ?: return
        try {
            // Создаем объект местоположения
            val location = UserLocation(
                lat = lat,
                lng = lng,
                timestamp = System.currentTimeMillis()
            )
            // Обновляем текущее местоположение
            database.child("user_locations").child(userId).setValue(location)
                .addOnSuccessListener {
                    Log.d(TAG, "Местоположение обновлено в базе данных")
                    // Сохраняем точность, если она была обновлена (больше 0)
                    if (lastKnownAccuracy > 0) {
                        database.child("user_location_accuracy")
                            .child(userId)
                            .setValue(lastKnownAccuracy)
                            .addOnSuccessListener {
                                Log.d(TAG, "Точность обновлена: $lastKnownAccuracy")
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Ошибка обновления местоположения", e)
                }
            // Сохраняем в историю
            database.child("user_location_history")
                .child(userId)
                .child(System.currentTimeMillis().toString())
                .setValue(location)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления местоположения", e)
        }
    }

    // --- ОБНОВЛЕННЫЙ МЕТОД showMyLocation ---
    private fun showMyLocation(lat: Double, lng: Double) {
        Log.d(TAG, "Отображение моего местоположения: $lat, $lng")
        val userId = auth.currentUser?.uid ?: return
        val point = Point(lat, lng)
        try {
            // Проверка контекста заранее
            val context = context ?: run {
                Log.w(TAG, "Контекст недоступен для отображения маркера")
                return
            }

            database.child("users").child(userId).get().addOnSuccessListener { userSnap ->
                if (!isAdded) {
                    Log.w(TAG, "Фрагмент не добавлен, пропуск отображения маркера")
                    return@addOnSuccessListener
                }
                try {
                    val user = userSnap.getValue(User::class.java)
                    val fullName = user?.getFullName() ?: "Я"
                    // --- Получаем URL аватара ---
                    val avatarUrl = user?.profileImageUrl // Предполагаем, что есть поле profileImageUrl

                    // Удаляем старый маркер, если он есть
                    myMarker?.let { old ->
                        try {
                            map.mapObjects.remove(old)
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка удаления старого маркера", e)
                        }
                    }

                    // Создаем временный маркер без иконки или с дефолтной
                    myMarker = map.mapObjects.addPlacemark().apply {
                        geometry = point
                        // Иконка будет установлена позже через Glide
                        setIcon(ImageProvider.fromResource(context, android.R.color.transparent)) // Или дефолтная
                        setIconStyle(IconStyle().apply {
                            scale = 1.5f
                            zIndex = 10.0f
                        })
                        setText("Я ($fullName)")
                        Log.d(TAG, "Временный маркер моего местоположения добавлен")
                    }

                    // --- Загружаем аватар и устанавливаем иконку ---
                    loadAndSetMarkerIcon(context, point, "Я ($fullName)", avatarUrl, true, myMarker)

                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка парсинга данных пользователя", e)
                    if (!isAdded) return@addOnSuccessListener
                    createDefaultMyMarker(point, context)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Ошибка загрузки данных пользователя", e)
                if (!isAdded) return@addOnFailureListener
                createDefaultMyMarker(point, context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отображения моего местоположения", e)
        }
    }
    // --- КОНЕЦ ОБНОВЛЕННОГО МЕТОДА showMyLocation ---






    private fun schedulePeriodicUserMarkersRefresh(intervalMs: Long = 20000L) { // По умолчанию 20 секунд
        // Проверка флага, чтобы не запускать несколько циклов
        if (isPeriodicUserMarkersRefreshScheduled) {
            Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Цикл обновления уже запланирован.")
            return
        }

        Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Планирование периодического обновления маркеров пользователей каждые ${intervalMs / 1000} секунд")

        if (!isAdded || !::map.isInitialized) {
            Log.w(TAG, "schedulePeriodicUserMarkersRefresh: Фрагмент не добавлен или карта не инициализирована. Планирование остановлено.")
            return
        }

        isPeriodicUserMarkersRefreshScheduled = true // Устанавливаем флаг при запуске

        val handler = Handler(Looper.getMainLooper())

        // Определяем Runnable, который будет выполнять обновление и планировать следующий запуск
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (isAdded && isMapInitialized) { // Проверяем жизненный цикл перед выполнением
                    Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Runnable сработал. Вызов reloadAllMarkers().")
                    try {
                        // Вызываем функцию, которая обновляет маркеры пользователей
                        // Она загружает данные о *всех* пользователях из Firebase и пересоздаёт маркеры
                        // с Handler.postDelayed для каждого.
                        reloadAllMarkers() // <-- ИСПОЛЬЗУЕМ reloadAllMarkers ВМЕСТО checkLocationVisibility
                    } catch (e: Exception) {
                        Log.e(TAG, "schedulePeriodicUserMarkersRefresh: Ошибка в reloadAllMarkers", e)
                    }

                    // Планируем следующий запуск этой же задачи через интервал
                    handler.postDelayed(this, intervalMs)
                    Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Следующее обновление запланировано через ${intervalMs}мс.")
                } else {
                    val reason = if (!isAdded) "фрагмент отсоединен" else "карта не инициализирована"
                    Log.w(TAG, "schedulePeriodicUserMarkersRefresh: Runnable сработал, но перезагрузка отменена ($reason). Цикл остановлен.")
                    // Сбрасываем флаг при остановке цикла
                    isPeriodicUserMarkersRefreshScheduled = false
                    // Не планируем следующий запуск, если фрагмент уничтожен или карта не инициализирована
                }
            }
        }

        // Запускаем первый цикл обновления через указанный интервал
        handler.postDelayed(refreshRunnable, intervalMs)
        Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Первое обновление запланировано через ${intervalMs}мс.")
        Log.d(TAG, "schedulePeriodicUserMarkersRefresh: Планирование завершено.")
    }


    private fun loadAndSetMarkerIcon(
        context: Context,
        position: Point, // Не используется напрямую, но может быть полезно
        name: String,
        avatarUrl: String?,
        isMyMarker: Boolean,
        marker: PlacemarkMapObject? // Может быть null для "моего" маркера, если он ещё не создан
    ) {
        // --- Определяем ресурсы для дефолтной иконки ---
        val defaultDrawableRes = if (isMyMarker) R.drawable.red_marker_45x45 else R.drawable.blue_marker_45x45
        val scale = if (isMyMarker) 1.5f else 1.0f
        val zIndex = if (isMyMarker) 10.0f else 1.0f
        // --- Определяем целевой размер аватара ---
        val targetAvatarSizePx = 130 // Размер в пикселях, подберите под себя (например, 45, 60, 90)

        // Функция для установки иконки из Bitmap
        fun setIconFromBitmap(bitmap: Bitmap?) {
            // Проверяем, актуален ли ещё маркер (не удален ли фрагмент или сам маркер)
            val finalMarker = if (isMyMarker) {
                myMarker // Используем поле класса для "моего" маркера
            } else {
                // Для других маркеров ищем по значению в ConcurrentHashMap
                otherUserMarkers.entries.find { it.value == marker }?.value
            }

            finalMarker?.let {
                val iconProvider = if (bitmap != null) {
                    // --- Преобразуем Bitmap в круглую форму ---
                    val circularBitmap = getCircularBitmap(bitmap)
                    // --- Масштабируем круглый Bitmap до нужного размера ---
                    val scaledBitmap = Bitmap.createScaledBitmap(circularBitmap, targetAvatarSizePx, targetAvatarSizePx, false)
                    // --- Используем правильный метод fromBitmap с масштабированным и круглым Bitmap ---
                    ImageProvider.fromBitmap(scaledBitmap)
                } else {
                    ImageProvider.fromResource(context, defaultDrawableRes)
                }
                it.setIcon(iconProvider)
                // Примечание: scale=1.0f часто лучше подходит для круглых аватаров,
                // чтобы избежать их растягивания. Вы можете экспериментировать.
                it.setIconStyle(IconStyle().apply {
                    this.scale = 1.0f // Используем 1.0f для масштаба круглых аватаров
                    this.zIndex = zIndex
                    // Anchor по умолчанию (0.5f, 0.5f) обычно подходит для центрирования
                    // Если нужно смещение, можно установить:
                    // this.anchor = PointF(0.5f, 0.5f) // Центр изображения
                })
                it.setText(name)
            } ?: run {
                if (!isMyMarker) {
                    Log.w(TAG, "Маркер для установки иконки не найден или удален")
                }
                // Если это "мой" маркер и он null, возможно, фрагмент уничтожен или маркер ещё не создан
            }
        }

        // Загрузка аватара
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context)
                .asBitmap() // Важно: загружаем как Bitmap
                .load(avatarUrl)
                .placeholder(defaultDrawableRes) // Показываем стандартную иконку, пока грузится
                .error(defaultDrawableRes) // Показываем стандартную иконку, если ошибка
                .into(object : CustomTarget<Bitmap>() { // <-- Используем CustomTarget
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        if (isAdded) { // Проверяем жизненный цикл фрагмента
                            setIconFromBitmap(resource)
                        }
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // Можно установить placeholder, если нужно
                        if (isAdded) {
                            setIconFromBitmap(null) // Или загрузить стандартную иконку
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        if (isAdded) {
                            setIconFromBitmap(null) // Или загрузить стандартную иконку
                        }
                    }
                })
        } else {
            // Если URL пустой или null, используем стандартную иконку
            setIconFromBitmap(null)
        }
    }
// --- КОНЕЦ ОБНОВЛЕННОГО МЕТОДА loadAndSetMarkerIcon ---




    fun openOrCreateChat() {

    }



    private fun showUserActionsDialog(userId: String, userName: String) {
        Log.d(TAG, "showUserActionsDialog вызван для userId: '$userId', userName: '$userName'")

        val context = context ?: run {
            Log.e(TAG, "showUserActionsDialog: Контекст равен null")
            return
        }

        val options = arrayOf("Профиль", "Пригласить в шахматы", "Написать сообщение")

        try {
            AlertDialog.Builder(context)
                .setTitle("Действия с пользователем $userName")
                .setItems(options) { _, which ->
                    Log.d(TAG, "Выбран пункт меню $which для пользователя $userId")
                    when (which) {
                        0 -> openUserProfile(userId)
                        1 -> inviteToChess(userId, userName)
                        2 -> openOrCreateChat(userId, userName)
                    }
                }
                .setNegativeButton("Отмена", null)
                .setCancelable(true)
                .show()

            Log.d(TAG, "Диалог показан успешно")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания или показа диалога", e)
            Toast.makeText(context, "Ошибка открытия меню", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMarkerClick(userId: String, userName: String) {
        Log.d(TAG, "onMarkerClick ВЫЗВАН для userId: '$userId', userName: '$userName'")

        if (userId == auth.currentUser?.uid) {
            Log.d(TAG, "onMarkerClick: Клик по собственному маркеру, игнорируем.")
            return
        }

        // Упрощенная проверка жизненного цикла
        if (context == null) {
            Log.w(TAG, "onMarkerClick: Контекст равен null")
            return
        }

        try {
            Log.d(TAG, "onMarkerClick: Вызов showUserActionsDialog")
            showUserActionsDialog(userId, userName)
        } catch (e: Exception) {
            Log.e(TAG, "onMarkerClick: Ошибка при вызове showUserActionsDialog", e)

            // Показываем простой Toast в случае ошибки
            try {
                Toast.makeText(context, "Ошибка открытия меню", Toast.LENGTH_SHORT).show()
            } catch (toastEx: Exception) {
                Log.e(TAG, "onMarkerClick: Не удалось показать Toast", toastEx)
            }
        }
    }



// --- НОВАЯ ФУНКЦИЯ ДЛЯ СОЗДАНИЯ КРУГЛОГО BITMAP ---

    fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)

        // Создаем квадратный Bitmap, если исходный не квадратный
        val squareBitmap = if (width != height) {
            val startX = if (width > height) (width - height) / 2 else 0
            val startY = if (height > width) (height - width) / 2 else 0
            Bitmap.createBitmap(bitmap, startX, startY, size, size)
        } else {
            bitmap
        }

        // Создаем Bitmap для результата с прозрачным конфигом
        val outputBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(outputBitmap)
        val paint = android.graphics.Paint()
        val rect = android.graphics.Rect(0, 0, size, size)
        val rectF = android.graphics.RectF(rect)

        paint.isAntiAlias = true // Важно для гладких краев
        canvas.drawARGB(0, 0, 0, 0) // Очищаем canvas прозрачным цветом
        paint.color = android.graphics.Color.BLACK
        // Рисуем круг
        canvas.drawOval(rectF, paint)

        // Используем PorterDuffXfermode для "вырезания" круга из исходного изображения
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(squareBitmap, rect, rect, paint)

        // Очистка
        paint.xfermode = null
        // squareBitmap не нужно.recycle(), если он не был создан через createBitmap

        return outputBitmap
    }
// --- КОНЕЦ НОВОЙ ФУНКЦИИ ---

    private fun createDefaultMyMarker(point: Point, context: Context) {
        try {
            // Удаляем старый маркер, если он есть
            myMarker?.let { old ->
                try {
                    map.mapObjects.remove(old)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка удаления старого маркера", e)
                }
            }

            myMarker = map.mapObjects.addPlacemark().apply {
                geometry = point
                setIcon(ImageProvider.fromResource(context, R.drawable.red_marker_45x45))
                setIconStyle(IconStyle().apply {
                    scale = 1.5f
                    zIndex = 10.0f
                })
                setText("Я")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания стандартного маркера", e)
        }
    }
    private fun moveCamera(point: Point, zoom: Float = 14f) {
        try {
            map.move(
                CameraPosition(point, zoom, 0f, 0f),
                Animation(Animation.Type.SMOOTH, 1f),
                null
            )
            Log.d(TAG, "Камера перемещена в позицию")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перемещения камеры", e)
        }
    }

    private fun initializeMapFeatures() {
        // --- ПРЕДВАРИТЕЛЬНАЯ ПРОВЕРКА ---
        // Двойная проверка жизненного цикла фрагмента и инициализации карты для безопасности
        if (!isAdded || !::map.isInitialized) {
            Log.w(TAG, "initializeMapFeatures: Фрагмент не добавлен или объект map не инициализирован. Прерывание инициализации.")
            return
        }

        Log.d(TAG, "initializeMapFeatures: НАЧАЛО")

        try {
            // --- НАЧАЛО ИНИЦИАЛИЗАЦИИ ---
            // Устанавливаем флаг инициализации как можно раньше, чтобы другие части кода знали о процессе
            isMapInitialized = true
            Log.d(TAG, "initializeMapFeatures: Флаг isMapInitialized установлен в true")

            // --- 1. ВКЛЮЧЕНИЕ ОТОБРАЖЕНИЯ СОБСТВЕННОГО МЕСТОПОЛОЖЕНИЯ ---
            Log.d(TAG, "initializeMapFeatures: Шаг 1 - Включение отображения собственного местоположения (enableMyLocation)")
            enableMyLocation()
            Log.d(TAG, "initializeMapFeatures: Шаг 1 ЗАВЕРШЕН - Геолокация (enableMyLocation) включена")

            // --- 2. НАСТРОЙКА СЛУШАТЕЛЯ МЕСТОПОЛОЖЕНИЙ ДРУГИХ ПОЛЬЗОВАТЕЛЕЙ ---
            Log.d(TAG, "initializeMapFeatures: Шаг 2 - Настройка слушателя местоположений других пользователей (setupUserLocationsListener)")
            setupUserLocationsListener()
            Log.d(TAG, "initializeMapFeatures: Шаг 2 ЗАВЕРШЕН - Слушатель пользовательских локаций (setupUserLocationsListener) настроен")

            // --- 3. ЗАПУСК СЕРВИСА ФОНОВОГО ОТСЛЕЖИВАНИЯ МЕСТОПОЛОЖЕНИЯ ---
            Log.d(TAG, "initializeMapFeatures: Шаг 3 - Запуск сервиса фонового отслеживания местоположения (startLocationService)")
            startLocationService()
            Log.d(TAG, "initializeMapFeatures: Шаг 3 ЗАВЕРШЕН - Сервис локаций (startLocationService) запущен")

            // --- НОВЫЙ ШАГ 3.1: Планируем периодическую работу WorkManager для LocationService ---
            Log.d(TAG, "initializeMapFeatures: Шаг 3.1 - Планирование периодической работы WorkManager для LocationService")
            schedulePeriodicLocationServiceWork()
            Log.d(TAG, "initializeMapFeatures: Шаг 3.1 ЗАВЕРШЕН - Периодическая работа LocationService запланирована")

            // --- НОВЫЙ ШАГ 3.2: Планируем периодическую работу ServiceMonitorWorker ---
            Log.d(TAG, "initializeMapFeatures: Шаг 3.2 - Планирование периодической работы ServiceMonitorWorker")
            scheduleServiceMonitorWork() // Вызов нового метода
            Log.d(TAG, "initializeMapFeatures: Шаг 3.2 ЗАВЕРШЕН - Периодическая работа ServiceMonitorWorker запланирована")
            // --- КОНЕЦ НОВЫХ ШАГОВ ---

            // --- 4. НАСТРОЙКА СЛУШАТЕЛЯ ИЗМЕНЕНИЙ НАСТРОЕК МЕСТОПОЛОЖЕНИЯ ---
            Log.d(TAG, "initializeMapFeatures: Шаг 4 - Настройка слушателя изменений настроек местоположения (setupSettingsListener)")
            setupSettingsListener()
            Log.d(TAG, "initializeMapFeatures: Шаг 4 ЗАВЕРШЕН - Слушатель настроек (setupSettingsListener) настроен")

            // --- 5. ОТЛОЖЕННАЯ ЗАГРУЗКА МАРКЕРОВ СОБЫТИЙ ---
            Log.d(TAG, "initializeMapFeatures: Шаг 5 - Планирование отложенной загрузки маркеров событий")
            // Это критически важно для корректной работы слушателей TapListener
            // из-за потенциальных гонок данных при инициализации MapKit.
            // MapKit и OpenGL ES могут нуждаться во времени для полной стабилизации
            // перед тем, как смогут корректно обрабатывать сложные взаимодействия.
            Handler(Looper.getMainLooper()).postDelayed({
                // --- ПРОВЕРКА ПОСЛЕ ЗАДЕРЖКИ ---
                // Повторная проверка жизненного цикла фрагмента и флага инициализации
                // на случай, если фрагмент был уничтожен или состояние изменилось за время задержки
                if (isAdded && isMapInitialized) {
                    Log.d(TAG, "initializeMapFeatures: Отложенная загрузка событий через postDelayed")
                    // --- ЗАГРУЗКА СОБЫТИЙ ---
                    // Эта функция создаёт маркеры и добавляет к ним TapListener'ы
                    loadExistingEvents()
                    Log.d(TAG, "initializeMapFeatures: Загрузка событий (loadExistingEvents) инициирована")

                    // --- НОВЫЙ ШАГ 5.1: ПЛАНИРОВАНИЕ ПЕРИОДИЧЕСКОГО ОБНОВЛЕНИЯ МАРКЕРОВ ПОЛЬЗОВАТЕЛЕЙ ---
                    Log.d(TAG, "initializeMapFeatures: Шаг 5.1 - Планирование ПЕРИОДИЧЕСКОЙ перезагрузки маркеров пользователей каждые 20 секунд")
                    schedulePeriodicUserMarkersRefresh(20000L) // 20 секунд
                    Log.d(TAG, "initializeMapFeatures: Шаг 5.1 ЗАПЛАНИРОВАН - Циклическое обновление маркеров пользователей")

                } else {
                    // --- ОБРАБОТКА НЕКОРРЕКТНОГО СОСТОЯНИЯ ---
                    val reason = if (!isAdded) "фрагмент отсоединен" else "карта не инициализирована"
                    Log.w(TAG, "initializeMapFeatures: Фрагмент отсоединен или карта не инициализирована к моменту выполнения postDelayed для loadExistingEvents (Причина: $reason)")
                }
            }, 4000) // Задержка 4 секунды (4000 миллисекунд).
            Log.d(TAG, "initializeMapFeatures: Шаг 5 ЗАВЕРШЕН - Планирование отложенной загрузки событий (задержка 4000 мс)")

            // --- ЗАВЕРШЕНИЕ ---
            Log.d(TAG, "initializeMapFeatures: ВСЕ ШАГИ ЗАПЛАНИРОВАНЫ. Инициализация функций карты ЗАВЕРШЕНА")

        } catch (e: Exception) {
            // --- ОБРАБОТКА ОШИБОК ---
            Log.e(TAG, "initializeMapFeatures: Критическая ошибка во время инициализации функций карты", e)
            // Используем requireContext() осторожно, проверяя isAdded перед вызовом
            if (isAdded) {
                try {
                    // Пытаемся уведомить пользователя об ошибке
                    Toast.makeText(requireContext(), "Ошибка инициализации карты: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (toastEx: Exception) {
                    // Ловим ошибки самого Toast, например, если Context стал недействительным
                    Log.e(TAG, "initializeMapFeatures: Не удалось показать Toast об ошибке инициализации", toastEx)
                }
            }
            // Сбрасываем флаг, так как инициализация не удалась
            isMapInitialized = false
        }
    }





    private fun updateRoutePolyline() {
        try {
            if (sessionRoutePoints.size < 2) return
            // Фильтрация точек для сглаживания маршрута
            val filteredPoints = filterRoutePoints(sessionRoutePoints)
            if (filteredPoints.size < 2) return
            // Разбиваем на сегменты по углам поворота
            val segments = createColorSegments(filteredPoints)
            // Рисуем каждый сегмент своим цветом
            for (i in segments.indices) {
                val segment = segments[i]
                if (segment.points.size >= 2) {
                    try {
                        map.mapObjects.addPolyline(Polyline(segment.points)).apply {
                            setStrokeWidth(8f)
                            setStrokeColor(segment.color)
                            zIndex = 5f
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка отрисовки сегмента полилинии", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления маршрута", e)
        }
    }


    // Фильтрация точек для удаления "дребезга"
    private fun filterRoutePoints(points: List<Point>): List<Point> {
        try {
            // Если точек мало, фильтрация не требуется
            if (points.size <= 2) {
                Log.d(TAG, "filterRoutePoints: Точек <= 2, фильтрация не требуется.")
                return points
            }

            val filtered = mutableListOf<Point>()
            // Первая точка всегда добавляется
            filtered.add(points[0])
            Log.d(TAG, "filterRoutePoints: Добавлена первая точка: ${points[0]}")

            // Проходим по промежуточным точкам
            for (i in 1 until points.size - 1) {
                val prev = points[i - 1]
                val current = points[i]
                val next = points[i + 1]

                // Вычисляем расстояние от текущей точки до линии между предыдущей и следующей
                val distanceToLine = pointToLineDistance(prev, current, next)

                // Если точка не слишком далеко от "прямой", добавляем её
                // Иначе считаем её "выбросом" и пропускаем
                if (distanceToLine < 15.0) { // Порог в 15 метров
                    filtered.add(current)
                    // Log.d(TAG, "filterRoutePoints: Добавлена точка $i: $current (расстояние до линии: ${"%.2f".format(distanceToLine)} м)")
                } else {
                    Log.d(TAG, "filterRoutePoints: Отфильтрована точка-выброс $i: $current (расстояние до линии: ${"%.2f".format(distanceToLine)} м)")
                }
            }

            // Последняя точка всегда добавляется
            filtered.add(points.last())
            Log.d(TAG, "filterRoutePoints: Добавлена последняя точка: ${points.last()}")

            Log.d(TAG, "filterRoutePoints: Исходных точек: ${points.size}, Отфильтровано: ${filtered.size}")
            return filtered.toList() // Возвращаем неизменяемый список
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка фильтрации точек", e)
            // В случае ошибки возвращаем исходный список
            return points
        }
    }


    // Вычисление расстояния от точки до линии
    private fun pointToLineDistance(a: com.yandex.mapkit.geometry.Point, b: Point, c: Point): Double {
        try {
            val lat1 = Math.toRadians(a.latitude)
            val lon1 = Math.toRadians(a.longitude)
            val lat2 = Math.toRadians(b.latitude)
            val lon2 = Math.toRadians(b.longitude)
            val lat3 = Math.toRadians(c.latitude)
            val lon3 = Math.toRadians(c.longitude)
            val y = sin(lon3 - lon1) * cos(lat3)
            val x = cos(lat1) * sin(lat3) - sin(lat1) * cos(lat3) * cos(lon3 - lon1)
            val bearing1 = (Math.toDegrees(atan2(y, x)) + 360) % 360
            val y2 = sin(lon2 - lon1) * sin(lat2)
            val x2 = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon2 - lon1)
            val bearing2 = (Math.toDegrees(atan2(y2, x2)) + 360) % 360
            val angleDiff = abs(bearing1 - bearing2)
            val minAngle = min(angleDiff, 360 - angleDiff)
            return if (minAngle < 90) {
                calculateDistance(a, b) * sin(Math.toRadians(minAngle))
            } else {
                calculateDistance(b, c)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычисления расстояния до линии", e)
            return 0.0
        }
    }
    // Сегмент маршрута с цветом
    data class RouteSegment(
        val points: List<Point>,
        val color: Int
    )
    // Создание сегментов с разными цветами на основе углов поворота
    private fun createColorSegments(points: List<Point>): List<RouteSegment> {
        try {
            val segments = mutableListOf<RouteSegment>()
            val colors = listOf(Color.BLUE, Color.GREEN, Color.RED, Color.MAGENTA, Color.CYAN)
            if (points.size < 2) return segments
            var currentSegmentPoints = mutableListOf(points[0])
            var currentColorIndex = 0
            for (i in 1 until points.size) {
                currentSegmentPoints.add(points[i])
                // Проверяем угол поворота для сменя цвета
                if (i >= 2) {
                    val angle = calculateTurnAngle(
                        points[i-2],
                        points[i-1],
                        points[i]
                    )
                    // Меняем цвет при резком поворота (> 45 градусов)
                    if (abs(angle) > MAX_ANGLE_CHANGE) {
                        segments.add(RouteSegment(currentSegmentPoints.toList(), colors[currentColorIndex % colors.size]))
                        currentColorIndex++
                        // Начинаем новый сегмент с последней точкой
                        currentSegmentPoints = mutableListOf(points[i-1], points[i])
                    }
                }
            }
            // Добавляем последний сегмент
            if (currentSegmentPoints.size >= 2) {
                segments.add(RouteSegment(currentSegmentPoints.toList(), colors[currentColorIndex % colors.size]))
            }
            return segments
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания цветных сегментов", e)
            return mutableListOf()
        }
    }
    // Вычисление угла поворота между тремя точками
    private fun calculateTurnAngle(p1: Point, p2: Point, p3: Point): Double {
        try {
            val bearing1 = calculateBearing(p1, p2)
            val bearing2 = calculateBearing(p2, p3)
            var angle = bearing2 - bearing1
            // Нормализуем угол
            while (angle > 180) angle -= 360
            while (angle < -180) angle += 360
            return angle
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычисления угла поворота", e)
            return 0.0
        }
    }
    // Улучшенное вычисление азимута
    private fun calculateBearing(from: Point, to: Point): Double {
        try {
            val lat1 = Math.toRadians(from.latitude)
            val lon1 = Math.toRadians(from.longitude)
            val lat2 = Math.toRadians(to.latitude)
            val lon2 = Math.toRadians(to.longitude)
            val dLon = lon2 - lon1
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val bearing = Math.toDegrees(atan2(y, x))
            return (bearing + 360) % 360
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычисления азимута", e)
            return 0.0
        }
    }







    // Улучшенное вычисление расстояния
    private fun calculateDistance(p1: Point, p2: Point): Double {
        try {
            val earthRadius = 6371000.0 // радиус Земли в метрах
            val lat1Rad = Math.toRadians(p1.latitude)
            val lat2Rad = Math.toRadians(p2.latitude)
            val deltaLatRad = Math.toRadians(p2.latitude - p1.latitude)
            val deltaLonRad = Math.toRadians(p2.longitude - p1.longitude)
            val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
                    cos(lat1Rad) * cos(lat2Rad) *
                    sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вычисления расстояния", e)
            return 0.0
        }
    }

    private fun setupUserLocationsListener() {
        try {
            userLocationsListener?.let {
                database.child("user_locations").removeEventListener(it)
            }
            userLocationsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d(TAG, "Данные о местоположениях пользователей изменены")
                        val userIdsFromSnapshot = mutableSetOf<String>()
                        for (ds in snapshot.children) {
                            val userId = ds.key ?: continue
                            if (userId == auth.currentUser?.uid) continue
                            try {
                                val location = ds.getValue(UserLocation::class.java) ?: continue
                                val userPoint =
                                    com.yandex.mapkit.geometry.Point(location.lat, location.lng)
                                userIdsFromSnapshot.add(userId)
                                val existingMarker = otherUserMarkers[userId]
                                if (existingMarker != null) {
                                    if (existingMarker.geometry != userPoint) {
                                        existingMarker.geometry = userPoint
                                        Log.d(TAG, "Обновлена позиция пользователя $userId")
                                    }
                                } else {
                                    Log.d(TAG, "Новое местоположение пользователя $userId, проверка видимости")
                                    checkLocationVisibility(userId, userPoint)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка парсинга местоположения пользователя $userId", e)
                            }
                        }
                        val toRemove = mutableListOf<String>()
                        for (userId in otherUserMarkers.keys) {
                            if (userId !in userIdsFromSnapshot) {
                                toRemove.add(userId)
                            }
                        }
                        for (userId in toRemove) {
                            otherUserMarkers.remove(userId)?.let {
                                try {
                                    map.mapObjects.remove(it)
                                    Log.d(TAG, "Удален маркер пользователя $userId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка удаления маркера пользователя $userId", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки данных о местоположениях", e)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Загрузка местоположений отменена", error.toException())
                    Toast.makeText(requireContext(), "Ошибка загрузки локаций", Toast.LENGTH_SHORT).show()
                }
            }
            database.child("user_locations").addValueEventListener(userLocationsListener!!)
            Log.d(TAG, "Слушатель местоположений добавлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки слушателя местоположений", e)
        }
    }
    private fun checkLocationVisibility(userId: String, point: Point) {
        try {
            database.child("location_settings").child(userId).get()
                .addOnSuccessListener { settingsSnap ->
                    try {
                        val settings = settingsSnap.getValue(LocationSettings::class.java)
                        when {
                            settings == null -> {
                                Log.d(TAG, "🔍 Нет настроек для пользователя $userId - показываем маркер")
                                loadUserAndAddMarker(userId, point)
                            }
                            !settings.enabled -> Log.d(TAG, "👻 Местоположение отключено для пользователя $userId")
                            settings.visibility == "none" -> Log.d(TAG, "🚫 Видимость отключена для пользователя $userId")
                            settings.visibility == "friends" && friendList.contains(userId) -> {
                                Log.d(TAG, "👥 Пользователь $userId является другом - показываем маркер")
                                loadUserAndAddMarker(userId, point)
                            }
                            settings.visibility == "friends" -> Log.d(TAG, "❌ Пользователь $userId не является другом")
                            else -> {
                                Log.d(TAG, "✅ Показываем маркер для пользователя $userId (видимость: ${settings.visibility})")
                                loadUserAndAddMarker(userId, point)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка парсинга настроек для $userId", e)
                        // В случае ошибки показываем маркер
                        loadUserAndAddMarker(userId, point)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Ошибка загрузки настроек для $userId", e)
                    // В случае ошибки показываем маркер
                    loadUserAndAddMarker(userId, point)
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки видимости для $userId", e)
        }
    }








    private fun openUserProfile(userId: String) {
        try {
            val intent = Intent(requireContext(), UserProfileActivity::class.java).apply {
                putExtra("USER_ID", userId)
            }
            startActivity(intent)
            Log.d(TAG, "Открываем профиль пользователя: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия профиля: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка открытия профиля", Toast.LENGTH_SHORT).show()
        }
    }
    private fun inviteToChess(userId: String, userName: String) {
        try {
            val intent = Intent(requireContext(), ChessActivity::class.java).apply {
                putExtra("opponent_id", userId)
                putExtra("opponent_name", userName)
            }
            startActivity(intent)
            Log.d(TAG, "Приглашаем в шахматы пользователя: $userName")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия шахмат: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка открытия шахмат", Toast.LENGTH_SHORT).show()
        }
    }
    private fun openOrCreateChat(userId: String, userName: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        // Генерируем уникальный ID чата на основе ID пользователей
        val chatId = listOf(currentUserId, userId).sorted().joinToString("_")
        Log.d(TAG, "Проверяем существование чата: $chatId")

        // Проверяем, существует ли чат
        database.child("chats").child(chatId).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                // Если чат существует, просто открываем его
                Log.d(TAG, "Чат существует, открываем: $chatId")
                openChat(chatId)
            } else {
                // Если чат новый, сначала загружаем данные другого пользователя,
                // чтобы получить его аватар для использования в качестве изображения чата
                Log.d(TAG, "Чат не существует, загружаем данные пользователя $userId для получения аватара и создания чата")

                database.child("users").child(userId).get().addOnSuccessListener { userSnapshot ->
                    // Проверка жизненного цикла фрагмента
                    if (!isAdded) return@addOnSuccessListener

                    var otherUserAvatarUrl: String? = null
                    try {
                        // Пытаемся получить объект пользователя и его аватар
                        val otherUser = userSnapshot.getValue(User::class.java)
                        otherUserAvatarUrl = otherUser?.profileImageUrl // Получаем URL аватара
                        Log.d(TAG, "Аватар пользователя $userId: $otherUserAvatarUrl")
                    } catch (e: Exception) {
                        // Логируем ошибку, но продолжаем создание чата без аватара
                        Log.e(TAG, "Ошибка парсинга данных пользователя $userId для получения аватара", e)
                    }
                    // Создаем новый чат, передавая полученный аватар
                    createChat(chatId, userId, userName, otherUserAvatarUrl)

                }.addOnFailureListener { e ->
                    // Проверка жизненного цикла фрагмента
                    if (!isAdded) return@addOnFailureListener

                    Log.e(TAG, "Ошибка загрузки данных пользователя $userId для получения аватара: ${e.message}", e)
                    Toast.makeText(requireContext(), "Ошибка загрузки данных пользователя. Создаем чат без аватара.", Toast.LENGTH_SHORT).show()
                    // Создаем чат без аватара в случае ошибки загрузки данных
                    createChat(chatId, userId, userName, null)
                }
            }
        }.addOnFailureListener {
            // Обработка ошибки при проверке существования чата
            Log.e(TAG, "Ошибка при проверке чата", it)
            Toast.makeText(requireContext(), "Ошибка при проверке чата", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openChat(chatId: String) {
        Log.d(TAG, "Открываем чат: $chatId")
        try {
            val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
                putExtra(Constants.CHAT_ID, chatId)
                // Убедитесь, что Constants.CHAT_ID совпадает с ожидаемым в ChatDetailActivity
            }
            startActivity(intent)
            Log.d(TAG, "Активность чата запущена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при открытии чата: ${e.message}", e)
            Toast.makeText(requireContext(), "Ошибка при открытии чата", Toast.LENGTH_SHORT).show()
        }
    }
    private fun setupSettingsListener() {
        try {
            val userId = auth.currentUser?.uid ?: return
            locationSettingsListener?.let {
                database.child("location_settings").child(userId).removeEventListener(it)
            }
            locationSettingsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        Log.d(TAG, "Настройки местоположения изменены")
                        updateMyLocationMarker()
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки изменений настроек", e)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Загрузка настроек местоположения отменена", error.toException())
                }
            }
            database.child("location_settings").child(userId)
                .addValueEventListener(locationSettingsListener!!)
            Log.d(TAG, "Слушатель настроек добавлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки слушателя настроек", e)
        }
    }
    private fun updateMyLocationMarker() {
        try {
            val userId = auth.currentUser?.uid ?: return
            database.child("users").child(userId).get()
                .addOnSuccessListener { userSnap ->
                    try {
                        val user = userSnap.getValue(User::class.java)
                        user?.let {
                            myMarker?.setText("Я (${it.getFullName()})")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обновления имени пользователя", e)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления маркера моего местоположения", e)
        }
    }



    private fun startLocationService() {
        try {
            Log.d(TAG, "Запуск сервиса местоположения")
            val serviceIntent = Intent(requireContext(), LocationUpdateService::class.java).apply {
                action = LocationUpdateService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent)
            } else {
                requireContext().startService(serviceIntent)
            }
            Log.d(TAG, "Сервис местоположения запущен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сервиса местоположения", e)
            Toast.makeText(requireContext(), "Ошибка запуска сервиса местоположения", Toast.LENGTH_SHORT).show()
        }
    }
    private fun scheduleDailyHistoryCleanup() {
        try {
            Log.d(TAG, "Планирование ежедневной очистки истории")
            val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(requireContext(), HistoryCleanupReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
            Log.d(TAG, "Планирование очистки истории установлено")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования очистки истории", e)
        }
    }



    private fun scheduleEventMarkerRefreshes() {
        Log.d(TAG, "scheduleEventMarkerRefreshes: Планирование серии обновлений маркеров")

        if (!isAdded || !::map.isInitialized) {
            Log.w(TAG, "scheduleEventMarkerRefreshes: Фрагмент не добавлен или карта не инициализирована")
            return
        }

        val handler = Handler(Looper.getMainLooper())
        // Задержки в миллисекундах: 5с, 10с, 15с (после начальной загрузки)
        // Предполагается, что первая загрузка уже запланирована в initializeMapFeatures через 3с
        val refreshDelaysAfterInitialLoad = longArrayOf(5000, 10000, 15000)

        for (i in refreshDelaysAfterInitialLoad.indices) {
            handler.postDelayed({
                if (isAdded) {
                    Log.d(TAG, "scheduleEventMarkerRefreshes: Обновление ${i + 1}/${refreshDelaysAfterInitialLoad.size}")
                    reloadEventMarkersSimple()
                } else {
                    Log.w(TAG, "scheduleEventMarkerRefreshes: Фрагмент отсоединен во время обновления ${i + 1}")
                }
            }, refreshDelaysAfterInitialLoad[i])
        }

        Log.d(TAG, "scheduleEventMarkerRefreshes: Планирование завершено")
    }

    /**
     * Упрощенная перезагрузка маркеров событий.
     * Очищает и заново загружает маркеры.
     */
    private fun reloadEventMarkersSimple() {
        if (!isAdded || !::map.isInitialized) {
            Log.w(TAG, "reloadEventMarkersSimple: Фрагмент не добавлен или карта не инициализирована")
            return
        }

        try {
            Log.d(TAG, "reloadEventMarkersSimple: Начало")

            // 1. Удалить все текущие маркеры событий с карты и из памяти
            val markersToRemove = eventMarkers.values.toList() // .toList() для безопасной итерации
            for (marker in markersToRemove) {
                try {
                    map.mapObjects.remove(marker)
                    Log.d(TAG, "reloadEventMarkersSimple: Маркер удален с карты")
                } catch (e: Exception) {
                    // Игнорируем ошибки удаления
                    Log.d(TAG, "reloadEventMarkersSimple: Маркер, возможно, уже удален или ошибка удаления")
                }
            }
            eventMarkers.clear()
            eventMarkerTapListeners.clear()
            Log.d(TAG, "reloadEventMarkersSimple: Коллекции очищены")

            // 2. Перезагрузить данные из Firebase
            loadExistingEvents()
            Log.d(TAG, "reloadEventMarkersSimple: Запрошена перезагрузка через loadExistingEvents")

        } catch (e: Exception) {
            Log.e(TAG, "reloadEventMarkersSimple: Ошибка", e)
        }
    }

    // Метод для планирования периодической работы ServiceMonitorWorker
    private fun scheduleServiceMonitorWork() {
        try {
            Log.d(TAG, "Планирование периодической работы ServiceMonitorWorker")
            val workManager = WorkManager.getInstance(requireContext())

            // Создаем ограничения (можно оставить без ограничений или добавить по необходимости,
            // например, только при подключении к сети)
            val constraints = Constraints.Builder()
                // .setRequiredNetworkType(NetworkType.CONNECTED) // Пример ограничения
                .build()

            // Создаем запрос на периодическую работу
            val periodicWorkRequest = PeriodicWorkRequestBuilder<ServiceMonitorWorker>(
                SERVICE_MONITOR_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            // Запланируйте работу с уникальным именем
            // Используем REPLACE, чтобы при повторном вызове (например, при возвращении во фрагмент)
            // задача перепланировалась с новыми параметрами, если они изменились,
            // или просто подтверждалась, если нет.
            workManager.enqueueUniquePeriodicWork(
                SERVICE_MONITOR_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE, // Или KEEP, если хотите избежать перепланирования
                periodicWorkRequest
            )

            Log.d(TAG, "Периодическая работа ServiceMonitorWorker запланирована (раз в $SERVICE_MONITOR_INTERVAL_HOURS часов)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования периодической работы ServiceMonitorWorker", e)
        }
    }

    // Метод для отмены периодической работы ServiceMonitorWorker (например, при выходе из аккаунта)
    private fun cancelServiceMonitorWork() {
        try {
            Log.d(TAG, "Отмена периодической работы ServiceMonitorWorker")
            val workManager = WorkManager.getInstance(requireContext())
            workManager.cancelUniqueWork(SERVICE_MONITOR_WORK_NAME)
            Log.d(TAG, "Периодическая работа ServiceMonitorWorker отменена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены периодической работы ServiceMonitorWorker", e)
        }
    }


    override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {}


    override fun onStart() {
        super.onStart()
        try {
            Log.d(TAG, "onStart вызван")
            MapKitFactory.getInstance().onStart()
            mapView.onStart()
            Log.d(TAG, "MapView и MapKitFactory запущены")

            // Теперь, когда mapView "стартовал", можно получить map и настроить её
            try {
                // --- ПОЛУЧЕНИЕ ОБЪЕКТА КАРТЫ ---
                // Получаем map из mapView.mapWindow (теперь это безопасно)
                map = mapView.mapWindow.map
                Log.d(TAG, "Map объект получен")
                // -----------------------------

                // --- ИНИЦИАЛИЗАЦИЯ SEARCH MANAGER ---
                // Инициализируем searchManager сразу после получения map,
                // чтобы он был готов к первому вызову onCameraPositionChanged
                if (!::searchManager.isInitialized) { // Проверка для lateinit var
                    searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)
                    Log.d(TAG, "SearchManager инициализирован")
                } else {
                    Log.d(TAG, "SearchManager уже был инициализирован")
                }
                // -------------------------------

                // --- НАСТРОЙКА СЛУШАТЕЛЕЙ КАРТЫ ---
                // Добавляем слушатели
                map.addCameraListener(this)
                map.addInputListener(this)
                Log.d(TAG, "Слушатели карты (Camera, Input) добавлены")
                // -------------------------------

                // --- ПРИМЕНЕНИЕ НАСТРОЕК КАРТЫ ---
                // Применяем настройки
                with(map) {
                    isScrollGesturesEnabled = true
                    isZoomGesturesEnabled = true
                    isRotateGesturesEnabled = true
                    // setMapStyle("normal") // Убедитесь, что стиль "normal" существует или закомментируйте
                }
                Log.d(TAG, "Настройки карты применены в onStart")
                // -------------------------------

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка настройки карты в onStart", e)
                // Используем requireContext() осторожно
                try {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Ошибка настройки карты: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (toastEx: Exception) {
                    Log.e(TAG, "Не удалось показать Toast об ошибке настройки карты", toastEx)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в onStart", e)
            // Используем requireContext() осторожно
            try {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Ошибка в onStart: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (toastEx: Exception) {
                Log.e(TAG, "Не удалось показать Toast об ошибке в onStart", toastEx)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Метод вызван")

        try {
            // --- ПРОВЕРКА РАЗРЕШЕНИЙ ---
            // Прежде чем делать что-либо с картой или местоположением, проверим разрешения.
            if (hasAllPermissions()) {
                Log.d(TAG, "onResume: Все необходимые разрешения предоставлены")

                // --- ЛОГИКА ВОССТАНОВЛЕНИЯ ИЛИ ИНИЦИАЛИЗАЦИИ ---
                // Флаг isMapInitialized указывает, был ли фрагмент уже инициализирован ранее.
                if (isMapInitialized) {
                    // --- СЦЕНАРИЙ ВОССТАНОВЛЕНИЯ ---
                    // Этот путь выполняется, если фрагмент "восстанаваливается" (например,
                    // после сворачивания приложения, перехода на другую вкладку BottomNavigationView
                    // и возврата). Это как раз тот случай, когда могут возникнуть проблемы
                    // с "мертвыми" TapListener'ами.
                    Log.d(TAG, "onResume: Карта уже была инициализирована ранее. Планируем перезагрузку маркеров событий.")

                    // --- ОТЛОЖЕННАЯ ПЕРЕЗАГРУЗКА ---
                    // Используем Handler.postDelayed для того, чтобы:
                    // 1. Дать системе немного времени для стабилизации состояния после onResume.
                    // 2. Убедиться, что MapKit и SurfaceView полностью "прогрелись".
                    Handler(Looper.getMainLooper()).postDelayed({
                        // --- ПОВТОРНАЯ ПРОВЕРКА ЖИЗНЕННОГО ЦИКЛА ---
                        // Важно снова проверить, что фрагмент всё ещё активен (isAdded)
                        // к моменту выполнения этого отложенного кода.
                        if (isAdded) {
                            Log.d(TAG, "onResume: Отложенное задание - вызов reloadEventMarkers()")
                            // --- ПЕРЕЗАГРУЗКА ТОЛЬКО МАРКЕРОВ СОБЫТИЙ ---
                            reloadEventMarkers()
                            // reloadAllMarkers() или checkLocationVisibility() <-- УБРАТЬ ЭТИ ВЫЗОВЫ, ЕСЛИ БЫЛИ
                        } else {
                            // --- ФРАГМЕНТ БЫЛ ОТСОЕДИНЕН ---
                            Log.w(TAG, "onResume: Фрагмент был отсоединен к моменту выполнения отложенного задания. Перезагрузка маркеров отменена.")
                        }
                    }, 1000) // Задержка 1 секунда. Можно подбирать (500-2000 мс).

                } else {
                    // --- СЦЕНАРИЙ ПЕРВИЧНОЙ ИНИЦИАЛИЗАЦИИ ---
                    // Этот путь выполняется при первом открытии фрагмента или после его
                    // полного уничтожения (onDestroyView/onDestroy).
                    Log.d(TAG, "onResume: Карта ещё не инициализирована. Планируем первичную инициализацию.")

                    // --- ОТЛОЖЕННАЯ ИНИЦИАЛИЗАЦИЯ ---
                    // Аналогично, небольшая задержка для стабилизации.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAdded) {
                            Log.d(TAG, "onResume: Отложенное задание - вызов initializeMapFeatures()")
                            // --- ПЕРВИЧНАЯ ИНИЦИАЛИЗАЦИЯ ---
                            // initializeMapFeatures настраивает слушатели местоположения,
                            // запускает сервисы и, через свою внутреннюю задержку,
                            // вызывает loadExistingEvents для создания первых маркеров.
                            initializeMapFeatures()
                        } else {
                            Log.w(TAG, "onResume: Фрагмент был отсоединен к моменту выполнения отложенного задания инициализации.")
                        }
                    }, 500) // Задержка 0.5 секунды. Обычно меньше, чем для восстановления.
                }

            } else {
                // --- НЕТ РАЗРЕШЕНИЙ ---
                // Если разрешения не предоставлены, запросим их.
                Log.w(TAG, "onResume: Некоторые разрешения отклонены или не предоставлены.")
                checkLocationPermissions()
            }
        } catch (e: Exception) {
            // --- ОБРАБОТКА ОШИБОК ---
            Log.e(TAG, "onResume: Критическая ошибка во время выполнения метода", e)
            // Можно показать пользователю уведомление об ошибке.
            if (isAdded) {
                try {
                    Toast.makeText(requireContext(), "Ошибка при возобновлении работы карты: ${e.message}", Toast.LENGTH_SHORT).show()
                } catch (toastEx: Exception) {
                    Log.e(TAG, "onResume: Не удалось показать Toast об ошибке", toastEx)
                }
            }
        }

        Log.d(TAG, "onResume: Метод завершен")
    }


    // LocationFragment.kt
    private fun reloadEventMarkers() {
        Log.d(TAG, "reloadEventMarkers: Начало перезагрузки маркеров событий")
        if (!isAdded || !::map.isInitialized) {
            Log.w(TAG, "reloadEventMarkers: Фрагмент не добавлен или карта не инициализирована")
            return
        }

        try {
            // 1. Удалить все существующие маркеры событий с карты и из коллекции
            val markersToRemove = eventMarkers.values.toList() // .toList() для безопасной итерации
            for (marker in markersToRemove) {
                try {
                    map.mapObjects.remove(marker)
                    Log.d(TAG, "reloadEventMarkers: Маркер события удален с карты")
                } catch (e: Exception) {
                    Log.w(TAG, "reloadEventMarkers: Не удалось удалить маркер события с карты", e)
                }
            }
            // 2. Очищаем коллекции в памяти
            eventMarkers.clear()
            eventMarkerTapListeners.clear() // <-- КРИТИЧЕСКИ ВАЖНО
            Log.d(TAG, "reloadEventMarkers: Коллекции eventMarkers и eventMarkerTapListeners очищены")

            // 3. Перезагружаем данные из Firebase и создаем маркеры заново
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    Log.d(TAG, "reloadEventMarkers: Вызов loadExistingEvents для пересоздания")
                    loadExistingEvents()
                }
            }, 300) // Небольшая задержка 300мс

        } catch (e: Exception) {
            Log.e(TAG, "reloadEventMarkers: Ошибка при перезагрузке маркеров", e)
        }
        Log.d(TAG, "reloadEventMarkers: Завершено")
    }



    // Метод для планирования периодической работы WorkManager
    private fun schedulePeriodicLocationServiceWork() {
        try {
            Log.d(TAG, "Планирование периодической работы LocationServiceWorker")

            val workManager = WorkManager.getInstance(requireContext())

            // Создаем ограничения (по желанию)
            val constraints = androidx.work.Constraints.Builder().build() // Без ограничений для макс. надежности

            // Создаем запрос на периодическую работу
            val periodicWorkRequest = androidx.work.PeriodicWorkRequestBuilder<LocationServiceWorker>(
                LOCATION_SERVICE_INTERVAL_HOURS, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            // Запланируйте работу с уникальным именем
            workManager.enqueueUniquePeriodicWork(
                LOCATION_SERVICE_WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Или REPLACE
                periodicWorkRequest
            )

            Log.d(TAG, "Периодическая работа LocationServiceWorker запланирована (раз в $LOCATION_SERVICE_INTERVAL_HOURS часов)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования периодической работы LocationServiceWorker", e)
        }
    }

    // Метод для отмены периодической работы (например, при выходе из аккаунта)
    private fun cancelPeriodicLocationServiceWork() {
        try {
            Log.d(TAG, "Отмена периодической работы LocationServiceWorker")
            val workManager = WorkManager.getInstance(requireContext())
            workManager.cancelUniqueWork(LOCATION_SERVICE_WORK_NAME)
            Log.d(TAG, "Периодическая работа LocationServiceWorker отменена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены периодической работы LocationServiceWorker", e)
        }
    }


    override fun onStop() {
        super.onStop()
        try {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
            Log.d(TAG, "Фрагмент остановлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в onStop", e)
        }
    }





    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView: НАЧАЛО")
        super.onDestroyView()
        try {
            // Удаляем слушатели базы данных
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
            clearMapObjects()

            Log.d(TAG, "onDestroyView: После вызова clearMapObjects()")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки", e)
        }
        _binding = null
        Log.d(TAG, "onDestroyView: КОНЕЦ")
    }
}