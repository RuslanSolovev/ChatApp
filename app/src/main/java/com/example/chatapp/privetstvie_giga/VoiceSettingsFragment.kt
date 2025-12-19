package com.example.chatapp.privetstvie_giga

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.chatapp.R
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.databinding.FragmentVoiceSettingsBinding
import com.example.chatapp.utils.TTSManager

class VoiceSettingsFragment : Fragment() {

    private lateinit var binding: FragmentVoiceSettingsBinding
    private lateinit var ttsManager: TTSManager
    private lateinit var voiceSettings: VoiceSettings

    private var isTestingVoice = false

    companion object {
        fun newInstance() = VoiceSettingsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentVoiceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsManager =
            (activity as MainActivity).getTTSManager() // Нужно добавить метод в MainActivity
        voiceSettings = VoiceSettings(requireContext())

        setupUI()
        loadCurrentSettings()
        setupListeners()
    }

    private fun setupUI() {
        // Скрываем тулбар для полноэкранного режима
        (activity as? MainActivity)?.hideSystemUIForChat()
    }

    private fun loadCurrentSettings() {
        val settings = voiceSettings.getAllSettings()

        // Текущий голос
        val voiceName = settings["voiceName"] as String
        binding.tvCurrentVoice.text = VoiceSettings.YANDEX_VOICES[voiceName] ?: voiceName

        // Скорость речи
        val rate = settings["speechRate"] as Float
        binding.seekbarSpeed.progress = ((rate - 0.5f) * 10).toInt()
        binding.tvSpeedValue.text = String.format("%.1fx", rate)

        // Тон голоса
        val pitch = settings["pitch"] as Float
        binding.seekbarPitch.progress = ((pitch - 0.5f) * 10).toInt()
        binding.tvPitchValue.text = String.format("%.1fx", pitch)

        // Пол голоса
        val gender = settings["voiceGender"] as String
        when (gender) {
            VoiceSettings.VOICE_FEMALE -> binding.radioFemale.isChecked = true
            VoiceSettings.VOICE_MALE -> binding.radioMale.isChecked = true
            else -> binding.radioNeutral.isChecked = true
        }
    }

    private fun setupListeners() {
        // Кнопка назад
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressed()
        }

        // Выбор голоса
        binding.cardVoiceSelection.setOnClickListener {
            showVoiceSelectionDialog()
        }

        // Скорость речи
        binding.seekbarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val rate = 0.5f + (progress / 10.0f)
                    binding.tvSpeedValue.text = String.format("%.1fx", rate)
                    voiceSettings.setSpeechRate(rate)
                    ttsManager.updateVoiceSettings(speechRate = rate)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Тон голоса
        binding.seekbarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pitch = 0.5f + (progress / 10.0f)
                    binding.tvPitchValue.text = String.format("%.1fx", pitch)
                    voiceSettings.setPitch(pitch)
                    ttsManager.updateVoiceSettings(pitch = pitch)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Пол голоса
        binding.radioGroupGender.setOnCheckedChangeListener { _, checkedId ->
            val gender = when (checkedId) {
                R.id.radio_female -> VoiceSettings.VOICE_FEMALE
                R.id.radio_male -> VoiceSettings.VOICE_MALE
                else -> VoiceSettings.VOICE_NEUTRAL
            }
            voiceSettings.setVoiceGender(gender)
            ttsManager.updateVoiceSettings(gender = gender)
        }

        // Тест голоса
        binding.btnTestVoice.setOnClickListener {
            testVoiceSettings()
        }

        // Сброс настроек
        binding.btnReset.setOnClickListener {
            resetToDefaults()
        }
    }

    private fun showVoiceSelectionDialog() {
        val voices = VoiceSettings.YANDEX_VOICES.entries.toTypedArray()
        val voiceNames = voices.map { it.value }.toTypedArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Выберите голос")
            .setItems(voiceNames) { _, which ->
                val selectedVoice = voices[which].key
                voiceSettings.setVoiceName(selectedVoice)
                binding.tvCurrentVoice.text = voiceNames[which]
                ttsManager.updateVoiceSettings(voiceName = selectedVoice)
                Toast.makeText(requireContext(), "Голос изменен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testVoiceSettings() {
        if (isTestingVoice) {
            ttsManager.stop()
            isTestingVoice = false
            binding.btnTestVoice.text = "🎤 Тест голоса"
            return
        }

        isTestingVoice = true
        binding.btnTestVoice.text = "⏹ Остановить"

        val testText = "Привет! Это тестовая озвучка. Вы можете настроить голос по своему вкусу."

        ttsManager.speak(testText, TTSManager.TYPE_CHAT_BOT, interrupt = true) {
            requireActivity().runOnUiThread {
                isTestingVoice = false
                binding.btnTestVoice.text = "🎤 Тест голоса"
            }
        }
    }

    private fun resetToDefaults() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Сброс настроек")
            .setMessage("Вернуть все настройки голоса к значениям по умолчанию?")
            .setPositiveButton("Сбросить") { _, _ ->
                voiceSettings.resetToDefaults()
                ttsManager.updateVoiceSettings(
                    voiceName = "oksana",
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    gender = VoiceSettings.VOICE_FEMALE
                )
                loadCurrentSettings()
                Toast.makeText(requireContext(), "Настройки сброшены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        ttsManager.stop()

        // Уведомляем MainActivity, что нужно восстановить полноэкранный режим чата
        val mainActivity = activity as? MainActivity
        mainActivity?.let {
            // Даем время на анимацию перехода
            Handler(Looper.getMainLooper()).postDelayed({
                it.restoreChatFullscreenMode()
            }, 50)
        }
    }

}