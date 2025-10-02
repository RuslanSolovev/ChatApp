package com.example.chatapp.budilnik

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityAlarmBinding
import com.example.chatapp.databinding.ActivityAlarmAlertBinding
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar

@Suppress("DEPRECATION")
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private lateinit var alertBinding: ActivityAlarmAlertBinding
    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingIntent: PendingIntent
    private var ringtone: Ringtone? = null
    private var alarmIsRinging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация AlarmManager
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Настройка для работы на заблокированном экране
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        if (intent?.action == "ALARM_ACTION") {
            showFullscreenAlarm()
            return
        }

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        initAlarmManager()
    }

    private fun showFullscreenAlarm() {
        alertBinding = ActivityAlarmAlertBinding.inflate(layoutInflater)
        setContentView(alertBinding.root)

        alarmIsRinging = true
        startAlarmSound()

        alertBinding.btnDismiss.setOnClickListener {
            stopAlarmSound()
            // Полное завершение активности и сервиса
            stopService(Intent(this, AlarmService::class.java))
            finishAndRemoveTask()
        }

        alertBinding.btnSnooze.setOnClickListener {
            snoozeAlarm()
            stopAlarmSound()
            // Завершаем текущий экран, но оставляем сервис
            finishAndRemoveTask()
        }
    }

    private fun startAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            ringtone = RingtoneManager.getRingtone(this, alarmUri).apply {
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка воспроизведения звука", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAlarmSound() {
        ringtone?.stop()
        ringtone = null
        alarmIsRinging = false
    }

    private fun snoozeAlarm() {
        val snoozeTime = SystemClock.elapsedRealtime() + 5 * 60 * 1000
        val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = "ALARM_ACTION"
        }

        val snoozePendingIntent = PendingIntent.getBroadcast(
            this,
            System.currentTimeMillis().toInt(), // Уникальный requestCode
            snoozeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            snoozeTime,
            snoozePendingIntent
        )

        Toast.makeText(this, "Будильник отложен на 5 минут", Toast.LENGTH_SHORT).show()
    }

    private fun setupUI() {
        binding.timePickerButton.setOnClickListener { showMaterialTimePicker() }
        binding.setAlarmButton.setOnClickListener { setAlarm() }
        binding.cancelAlarmButton.setOnClickListener { cancelAlarm() }
    }

    private fun initAlarmManager() {
        val alarmIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = "ALARM_ACTION"
        }

        pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showMaterialTimePicker() {
        val calendar = Calendar.getInstance()
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(calendar.get(Calendar.HOUR_OF_DAY))
            .setMinute(calendar.get(Calendar.MINUTE))
            .setTitleText("Выберите время будильника")
            .build()

        picker.addOnPositiveButtonClickListener {
            val hour = picker.hour
            val minute = picker.minute
            val timeString = String.format("%02d:%02d", hour, minute)
            binding.selectedTimeText.text = "(нажмите подтвердить выбор) Выбрано время: $timeString"
            binding.timePickerButton.tag = "$hour:$minute"
        }

        picker.show(supportFragmentManager, "time_picker_tag")
    }

    private fun setAlarm() {
        val time = binding.timePickerButton.tag as? String ?: run {
            Toast.makeText(this, "Пожалуйста, выберите время сначала", Toast.LENGTH_SHORT).show()
            return
        }

        val (hour, minute) = time.split(":").map { it.toInt() }
        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                setExactAlarm(calendar)
            } else {
                requestExactAlarmPermission()
            }
        } else {
            setExactAlarm(calendar)
        }
    }

    private fun setExactAlarm(calendar: Calendar) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        Toast.makeText(this, "Будильник установлен", Toast.LENGTH_SHORT).show()
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            startActivity(intent)
            Toast.makeText(this, "Требуется разрешение для точного будильника", Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelAlarm() {
        alarmManager.cancel(pendingIntent)
        Toast.makeText(this, "Будильник отменен", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmSound()
    }
}