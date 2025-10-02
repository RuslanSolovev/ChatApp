package com.example.chatapp.fragments

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.chatapp.R
import com.example.chatapp.databinding.FragmentLocationHistoryBinding
import com.example.chatapp.models.UserLocation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import java.util.*

class LocationHistoryFragment : Fragment(), CameraListener {

    private var _binding: FragmentLocationHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var mapView: MapView
    private lateinit var map: com.yandex.mapkit.map.Map
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference
    private val TAG = "LocationHistoryFragment"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapKitFactory.initialize(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            initializeMap()
            setupUI()
            loadLocationHistory()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            showToast("Ошибка инициализации")
        }
    }

    private fun initializeMap() {
        mapView = binding.mapView
        map = mapView.mapWindow.map
        auth = FirebaseAuth.getInstance()

        if (!mapView.mapWindow.map.isValid) {
            throw IllegalStateException("Map is not valid")
        }

        map.addCameraListener(this)

        with(map) {
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
            isRotateGesturesEnabled = true
            setMapStyle("dark")
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnClear.setOnClickListener {
            showClearConfirmationDialog()
        }
    }

    private fun showClearConfirmationDialog() {
        if (!isAdded || context == null) return

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clear_route_title))
            .setMessage(getString(R.string.clear_route_message))
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                clearLocalMap()
                clearFirebaseHistory()
                showToast(getString(R.string.route_cleared))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setIcon(R.drawable.ic_brain)
            .show()
    }

    private fun clearLocalMap() {
        try {
            if (::map.isInitialized && mapView.mapWindow.map.isValid) {
                map.mapObjects.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing map", e)
        }
    }

    private fun clearFirebaseHistory() {
        if (!isAdded || auth.currentUser == null) return

        try {
            database.child("user_location_history").child(auth.currentUser!!.uid)
                .removeValue()
                .addOnSuccessListener {
                    if (isAdded) {
                        showToast(getString(R.string.route_cleared))
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firebase clear error", e)
                    if (isAdded) {
                        showToast(getString(R.string.route_cleared))
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase operation failed", e)
        }
    }

    private fun loadLocationHistory() {
        if (!isAdded || auth.currentUser == null) {
            binding.progressBar.visibility = View.GONE
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        val userId = auth.currentUser!!.uid

        database.child("user_location_history").child(userId)
            .orderByKey()
            .get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded) return@addOnSuccessListener

                try {
                    val locations = parseLocationPoints(snapshot)
                    if (locations.size >= 2) {
                        drawRoute(locations)
                    } else {
                        showToast(getString(R.string.not_enough_data_for_route))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing points", e)
                    showToast(getString(R.string.route_cleared))
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Load history error", e)
                showToast(getString(R.string.route_cleared))
            }
            .addOnCompleteListener {
                if (isAdded) {
                    binding.progressBar.visibility = View.GONE
                }
            }
    }

    private fun parseLocationPoints(snapshot: com.google.firebase.database.DataSnapshot): List<UserLocation> {
        return snapshot.children.mapNotNull {
            it.getValue(UserLocation::class.java)
        }.sortedBy { it.timestamp }
    }

    private fun drawRoute(locations: List<UserLocation>) {
        if (locations.size < 2) return

        try {
            // Группируем точки по цветам
            val segments = mutableListOf<Pair<Int, List<Point>>>()
            var currentColor = locations.first().color
            var currentSegment = mutableListOf<Point>()

            locations.forEach { location ->
                if (location.color == currentColor) {
                    currentSegment.add(Point(location.lat, location.lng))
                } else {
                    segments.add(currentColor to currentSegment)
                    currentColor = location.color
                    currentSegment = mutableListOf(Point(location.lat, location.lng))
                }
            }
            segments.add(currentColor to currentSegment)

            // Очищаем предыдущие объекты
            map.mapObjects.clear()

            // Рисуем каждый сегмент своим цветом
            segments.forEach { (color, points) ->
                if (points.size >= 2) {
                    map.mapObjects.addPolyline(Polyline(points)).apply {
                        setStrokeWidth(7f)
                        setStrokeColor(color)
                        zIndex = 10f
                    }
                }
            }

            // Добавляем маркеры начала и конца
            addStartAndEndMarkers(
                Point(locations.first().lat, locations.first().lng),
                Point(locations.last().lat, locations.last().lng)
            )

            // Центрируем карту на маршруте
            adjustCameraToRoute(locations.map { Point(it.lat, it.lng) })

        } catch (e: Exception) {
            Log.e(TAG, "Error drawing route", e)
            showToast("Ошибка отображения маршрута")
        }
    }

    private fun addStartAndEndMarkers(startPoint: Point, endPoint: Point) {
        // Маркер начала
        map.mapObjects.addPlacemark().apply {
            geometry = startPoint
            setIcon(ImageProvider.fromResource(requireContext(), R.drawable.blue_marker_45x45))
            setIconStyle(IconStyle().apply { scale = 1.5f })
            setText(getString(R.string.start_point))
        }

        // Маркер конца
        map.mapObjects.addPlacemark().apply {
            geometry = endPoint
            setIcon(ImageProvider.fromResource(requireContext(), R.drawable.red_marker_45x45))
            setIconStyle(IconStyle().apply { scale = 1.5f })
            setText(getString(R.string.end_point))
        }
    }

    private fun adjustCameraToRoute(points: List<Point>) {
        val bbox = calculateBoundingBox(points)
        map.move(
            CameraPosition(
                calculateRouteCenter(bbox),
                calculateZoomLevel(bbox),
                0f,
                0f
            ),
            Animation(Animation.Type.SMOOTH, 2f),
            null
        )
    }

    private fun calculateRouteCenter(bbox: BoundingBox): Point {
        return Point(
            (bbox.southWest.latitude + bbox.northEast.latitude) / 2,
            (bbox.southWest.longitude + bbox.northEast.longitude) / 2
        )
    }

    private fun calculateBoundingBox(points: List<Point>): BoundingBox {
        var minLat = points[0].latitude
        var maxLat = points[0].latitude
        var minLon = points[0].longitude
        var maxLon = points[0].longitude

        for (point in points) {
            minLat = minOf(minLat, point.latitude)
            maxLat = maxOf(maxLat, point.latitude)
            minLon = minOf(minLon, point.longitude)
            maxLon = maxOf(maxLon, point.longitude)
        }

        return BoundingBox(
            Point(minLat, minLon),
            Point(maxLat, maxLon)
        )
    }

    private fun calculateZoomLevel(bbox: BoundingBox): Float {
        val latDiff = bbox.northEast.latitude - bbox.southWest.latitude
        val lonDiff = bbox.northEast.longitude - bbox.southWest.longitude
        val maxDiff = maxOf(latDiff, lonDiff)

        return when {
            maxDiff < 0.01 -> 18f
            maxDiff < 0.02 -> 17f
            maxDiff < 0.05 -> 16f
            maxDiff < 0.1 -> 15f
            maxDiff < 0.2 -> 14f
            maxDiff < 0.5 -> 13f
            maxDiff < 1.0 -> 12f
            maxDiff < 2.0 -> 11f
            maxDiff < 5.0 -> 10f
            else -> 9f
        }.coerceIn(9f, 18f)
    }

    private fun showToast(message: String) {
        if (isAdded && context != null) {
            try {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast error", e)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
        MapKitFactory.getInstance().onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            map.removeCameraListener(this)
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
        _binding = null
    }

    override fun onCameraPositionChanged(
        map: com.yandex.mapkit.map.Map,
        cameraPosition: CameraPosition,
        cameraUpdateReason: CameraUpdateReason,
        finished: Boolean
    ) {
        // Не требуется
    }
}