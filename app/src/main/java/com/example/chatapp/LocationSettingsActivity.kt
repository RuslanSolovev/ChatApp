package com.example.chatapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.databinding.ActivityLocationSettingsBinding
import com.example.chatapp.models.LocationSettings
import com.example.chatapp.location.LocationUpdateService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LocationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocationSettingsBinding
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_ChatApp_NoActionBar)
        binding = ActivityLocationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        loadSettings()

        binding.switchLocationSharing.setOnCheckedChangeListener { _, isChecked ->
            updateLocationSharing(isChecked)
            if (isChecked) startLocationService() else stopLocationService()
        }

        binding.radioGroupVisibility.setOnCheckedChangeListener { _, checkedId ->
            val visibility = when (checkedId) {
                R.id.radioEveryone -> "everyone"
                R.id.radioNone -> "none"
                else -> "friends"
            }
            updateVisibilitySetting(visibility)
        }

        binding.btnSave.setOnClickListener {
            val updateInterval = binding.sliderUpdateInterval.value.toInt()
            updateIntervalSetting(updateInterval)
            finish()
        }
    }

    private fun loadSettings() {
        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId).get()
            .addOnSuccessListener { snapshot ->
                val settings = snapshot.getValue(LocationSettings::class.java) ?: return@addOnSuccessListener
                binding.switchLocationSharing.isChecked = settings.enabled
                when (settings.visibility) {
                    "everyone" -> binding.radioEveryone.isChecked = true
                    "none" -> binding.radioNone.isChecked = true

                }
                binding.sliderUpdateInterval.value = settings.updateInterval.toFloat()
            }
    }

    private fun updateLocationSharing(enabled: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId).child("enabled").setValue(enabled)
    }

    private fun updateVisibilitySetting(visibility: String) {
        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId).child("visibility").setValue(visibility)
    }

    private fun updateIntervalSetting(interval: Int) {
        val userId = auth.currentUser?.uid ?: return
        database.child("location_settings").child(userId).child("updateInterval").setValue(interval)
    }

    private fun startLocationService() {
        val serviceIntent = Intent(this, LocationUpdateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopLocationService() {
        stopService(Intent(this, LocationUpdateService::class.java))
    }
}