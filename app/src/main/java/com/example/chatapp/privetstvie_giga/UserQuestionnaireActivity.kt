package com.example.chatapp.privetstvie_giga

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.chatapp.R
import com.example.chatapp.databinding.ActivityUserQuestionnaireBinding
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class UserQuestionnaireActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserQuestionnaireBinding
    private val auth = Firebase.auth
    private val database = Firebase.database.reference

    companion object {
        private const val TAG = "UserQuestionnaireActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserQuestionnaireBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        loadExistingProfile()
    }

    private fun setupUI() {
        // Настройка выпадающих списков
        setupSpinners()

        // Настройка слушателей для новых полей
        setupAdditionalFields()

        // Настройка слушателей кнопок
        binding.btnSaveProfile.setOnClickListener {
            saveProfile()
        }

        binding.btnSkip.setOnClickListener {
            finish()
        }

        binding.btnLater.setOnClickListener {
            finish()
        }

        // Добавляем слушатели для обновления прогресса
        setupProgressTracking()

        // Показываем прогресс заполнения
        updateProgress()
    }

    private fun setupSpinners() {
        // Основные списки
        ArrayAdapter.createFromResource(
            this,
            R.array.gender_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerGender.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.relationship_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerRelationship.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.work_schedule_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerWorkSchedule.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.wakeup_time_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerWakeUpTime.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.workout_frequency_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerWorkoutFrequency.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.reading_habit_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerReadingHabit.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.personality_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerPersonality.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.communication_style_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCommunicationStyle.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.stress_management_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerStressManagement.adapter = adapter
        }

        // Новые списки для дополнительных полей
        ArrayAdapter.createFromResource(
            this,
            R.array.fitness_level_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFitnessLevel.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.travel_frequency_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerTravelFrequency.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.cooking_habit_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCookingHabit.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.sleep_quality_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerSleepQuality.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.social_activity_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerSocialActivity.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.learning_style_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerLearningStyle.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.pet_types_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerPetTypes.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.season_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFavoriteSeasons.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.music_genre_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMusicPreferences.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.movie_genre_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMovieGenres.adapter = adapter
        }

        ArrayAdapter.createFromResource(
            this,
            R.array.cuisine_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFoodPreferences.adapter = adapter
        }
    }

    private fun setupAdditionalFields() {
        // Слушатель для чекбокса "Есть дети"
        binding.cbHasChildren.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutChildrenAges.isVisible = isChecked
            updateProgress()
        }

        // Слушатель для чекбокса "Есть питомцы"
        binding.cbHasPets.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutPetTypes.isVisible = isChecked
            updateProgress()
        }
    }

    private fun setupProgressTracking() {
        // Слушатели для основных полей
        val basicFields = listOf(
            binding.spinnerGender,
            binding.etBirthYear,
            binding.spinnerRelationship,
            binding.etOccupation,
            binding.spinnerWorkSchedule
        )

        basicFields.forEach { view ->
            when (view) {
                is Spinner -> {
                    view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            if (position > 0) updateProgress()
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                is EditText -> {
                    view.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            updateProgress()
                        }
                    })
                }
            }
        }
    }

    private fun loadExistingProfile() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.e(TAG, "User not authenticated")
            return
        }

        database.child("user_profiles").child(currentUser.uid).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val profile = snapshot.getValue(UserProfile::class.java)
                    profile?.let { populateForm(it) }
                    updateProgress()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error loading profile", e)
            }
    }

    private fun populateForm(profile: UserProfile) {
        // Основная информация
        setSpinnerSelection(binding.spinnerGender, profile.gender)
        if (profile.birthYear > 0) {
            binding.etBirthYear.setText(profile.birthYear.toString())
        }

        // Семейное положение и работа
        setSpinnerSelection(binding.spinnerRelationship, profile.relationshipStatus)
        binding.etOccupation.setText(profile.occupation)
        binding.etJobTitle.setText(profile.jobTitle)
        setSpinnerSelection(binding.spinnerWorkSchedule, profile.workSchedule)
        binding.etWorkStartTime.setText(profile.workStartTime)
        binding.etWorkEndTime.setText(profile.workEndTime)

        // Образ жизни
        setSpinnerSelection(binding.spinnerWakeUpTime, profile.wakeUpTime)
        setSpinnerSelection(binding.spinnerSleepQuality, profile.sleepQuality)
        setSpinnerSelection(binding.spinnerWorkoutFrequency, profile.workoutFrequency)
        setSpinnerSelection(binding.spinnerReadingHabit, profile.readingHabit)
        binding.etWorkoutTypes.setText(profile.workoutTypes) // Теперь просто устанавливаем строку

        // Хобби и интересы
        binding.etHobbies.setText(profile.hobbies) // Теперь просто устанавливаем строку
        binding.etInterests.setText(profile.interests) // Теперь просто устанавливаем строку
        binding.etSports.setText(profile.sports) // Теперь просто устанавливаем строку

        // Предпочтения
        setSpinnerSelection(binding.spinnerMusicPreferences, profile.musicPreferences)
        setSpinnerSelection(binding.spinnerMovieGenres, profile.movieGenres)
        setSpinnerSelection(binding.spinnerFoodPreferences, profile.foodPreferences)
        setSpinnerSelection(binding.spinnerFavoriteSeasons, profile.favoriteSeasons)
        binding.etFavoriteCuisines?.setText(profile.favoriteCuisines) // Теперь просто устанавливаем строку

        // Цели
        binding.etCurrentGoals.setText(profile.currentGoals) // Теперь просто устанавливаем строку
        binding.etLearningInterests.setText(profile.learningInterests) // Теперь просто устанавливаем строку

        // Личностные характеристики
        setSpinnerSelection(binding.spinnerPersonality, profile.personalityType)
        setSpinnerSelection(binding.spinnerCommunicationStyle, profile.communicationStyle)
        setSpinnerSelection(binding.spinnerStressManagement, profile.stressManagement)
        setSpinnerSelection(binding.spinnerSocialActivity, profile.socialActivity)
        setSpinnerSelection(binding.spinnerLearningStyle, profile.learningStyle)

        // Местоположение
        binding.etCity.setText(profile.city)
        binding.etCommuteTime.setText(if (profile.dailyCommuteTime > 0) profile.dailyCommuteTime.toString() else "")

        // Семья и домашние условия
        binding.cbHasChildren.isChecked = profile.hasChildren
        binding.etChildrenAges.setText(profile.childrenAges) // Теперь просто устанавливаем строку
        binding.cbHasPets.isChecked = profile.hasPets
        setSpinnerSelection(binding.spinnerPetTypes, profile.petTypes)

        // Новые поля для умной персонализации
        setSpinnerSelection(binding.spinnerFitnessLevel, profile.fitnessLevel)
        setSpinnerSelection(binding.spinnerTravelFrequency, profile.travelFrequency)
        setSpinnerSelection(binding.spinnerCookingHabit, profile.cookingHabit)
        binding.etWeekendActivities?.setText(profile.weekendActivities) // Теперь просто устанавливаем строку
    }

    private fun setSpinnerSelection(spinner: Spinner, value: String) {
        if (value.isNotEmpty()) {
            for (i in 0 until spinner.count) {
                if (spinner.getItemAtPosition(i).toString() == value) {
                    spinner.setSelection(i)
                    break
                }
            }
        }
    }

    private fun saveProfile() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "Ошибка авторизации", Toast.LENGTH_SHORT).show()
            return
        }

        // Проверка обязательных полей
        if (binding.spinnerGender.selectedItemPosition == 0 ||
            binding.spinnerRelationship.selectedItemPosition == 0 ||
            binding.etOccupation.text.toString().trim().isEmpty()) {

            Toast.makeText(this, "Заполните обязательные поля (пол, семейное положение, сфера работы)", Toast.LENGTH_LONG).show()
            return
        }

        val profile = UserProfile(
            userId = currentUser.uid,
            lastUpdated = System.currentTimeMillis(),

            // Основная информация
            gender = binding.spinnerGender.selectedItem.toString(),
            birthYear = binding.etBirthYear.text.toString().toIntOrNull() ?: 0,

            // Семейное положение
            relationshipStatus = binding.spinnerRelationship.selectedItem.toString(),

            // Профессия
            occupation = binding.etOccupation.text.toString().trim(),
            jobTitle = binding.etJobTitle.text.toString().trim(),
            workSchedule = binding.spinnerWorkSchedule.selectedItem.toString(),
            workStartTime = binding.etWorkStartTime.text.toString().trim(),
            workEndTime = binding.etWorkEndTime.text.toString().trim(),

            // Образ жизни
            wakeUpTime = binding.spinnerWakeUpTime.selectedItem.toString(),
            sleepQuality = binding.spinnerSleepQuality.selectedItem.toString(),
            workoutFrequency = binding.spinnerWorkoutFrequency.selectedItem.toString(),
            readingHabit = binding.spinnerReadingHabit.selectedItem.toString(),
            workoutTypes = binding.etWorkoutTypes.text.toString().trim(),

            // Хобби и интересы
            hobbies = binding.etHobbies.text.toString().trim(),
            interests = binding.etInterests.text.toString().trim(),
            sports = binding.etSports.text.toString().trim(),

            // Предпочтения
            musicPreferences = binding.spinnerMusicPreferences.selectedItem.toString(),
            movieGenres = binding.spinnerMovieGenres.selectedItem.toString(),
            foodPreferences = binding.spinnerFoodPreferences.selectedItem.toString(),
            favoriteSeasons = binding.spinnerFavoriteSeasons.selectedItem.toString(),
            favoriteCuisines = binding.etFavoriteCuisines?.text?.toString()?.trim() ?: "",

            // Цели
            currentGoals = binding.etCurrentGoals.text.toString().trim(),
            learningInterests = binding.etLearningInterests.text.toString().trim(),

            // Личностные характеристики
            personalityType = binding.spinnerPersonality.selectedItem.toString(),
            communicationStyle = binding.spinnerCommunicationStyle.selectedItem.toString(),
            stressManagement = binding.spinnerStressManagement.selectedItem.toString(),
            socialActivity = binding.spinnerSocialActivity.selectedItem.toString(),
            learningStyle = binding.spinnerLearningStyle.selectedItem.toString(),

            // Местоположение
            city = binding.etCity.text.toString().trim(),
            dailyCommuteTime = binding.etCommuteTime.text.toString().toIntOrNull() ?: 0,

            // Семья и домашние условия
            hasChildren = binding.cbHasChildren.isChecked,
            childrenAges = binding.etChildrenAges.text.toString().trim(),
            hasPets = binding.cbHasPets.isChecked,
            petTypes = binding.spinnerPetTypes.selectedItem.toString(),

            // Новые поля для умной персонализации
            fitnessLevel = binding.spinnerFitnessLevel.selectedItem.toString(),
            travelFrequency = binding.spinnerTravelFrequency.selectedItem.toString(),
            cookingHabit = binding.spinnerCookingHabit.selectedItem.toString(),
            weekendActivities = binding.etWeekendActivities?.text?.toString()?.trim() ?: ""
        )

        database.child("user_profiles").child(currentUser.uid).setValue(profile)
            .addOnSuccessListener {
                Toast.makeText(this, "Анкета сохранена!", Toast.LENGTH_SHORT).show()
                getSharedPreferences("user_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("questionnaire_completed", true)
                    .apply()
                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving profile", e)
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateProgress() {
        val fields = listOf(
            binding.spinnerGender.selectedItem.toString(),
            binding.etBirthYear.text.toString(),
            binding.spinnerRelationship.selectedItem.toString(),
            binding.etOccupation.text.toString(),
            binding.spinnerWorkSchedule.selectedItem.toString()
        )

        val filledFields = fields.count { it.isNotEmpty() && it != "Выберите пол" && it != "Выберите статус" && it != "Выберите график" }
        val progress = (filledFields.toFloat() / fields.size * 100).toInt()

        binding.progressBar.progress = progress
        binding.tvProgress.text = "Заполнено: $progress%"

        // Показываем подсказку, если заполнено мало полей
        binding.tvProgressHint.isVisible = progress < 50

        // Меняем цвет прогресса в зависимости от заполненности
        val progressColor = when {
            progress >= 80 -> ContextCompat.getColor(this, R.color.primaryColor)
            progress >= 50 -> ContextCompat.getColor(this, R.color.alarm_stop)
            else -> ContextCompat.getColor(this, R.color.red)
        }
        binding.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(progressColor)
    }
}