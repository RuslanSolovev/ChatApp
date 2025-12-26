package com.example.chatapp.step

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.R
import com.example.chatapp.TopUsersActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.animation.Easing
import com.github.mikephil.charting.formatter.LargeValueFormatter
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.data.Entry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.mikhaellopez.circularprogressbar.CircularProgressBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class StepCounterFragment : Fragment() {
    private lateinit var viewModel: StepCounterViewModel
    private lateinit var motivationManager: MotivationManager
    private val executor = Executors.newFixedThreadPool(4)

    // Views
    private lateinit var circularProgressBar: CircularProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvProgressText: TextView
    private lateinit var tvTodaySteps: TextView
    private lateinit var tvTodayDistance: TextView
    private lateinit var tvTodayCalories: TextView
    private lateinit var tvActiveTime: TextView
    private lateinit var tvComparison: TextView
    private lateinit var tvQuickWeek: TextView
    private lateinit var tvQuickMonth: TextView
    private lateinit var tvQuickRecord: TextView
    private lateinit var tvForecast: TextView
    private lateinit var tvJourneyRoute: TextView
    private lateinit var tvJourneyProgress: TextView
    private lateinit var tvJourneyDistance: TextView
    private lateinit var tvStreakDays: TextView
    private lateinit var progressJourney: ProgressBar
    private lateinit var barChart: BarChart
    private lateinit var tabLayoutPeriod: TabLayout
    private lateinit var cardComparison: CardView
    private lateinit var cardForecast: CardView
    private lateinit var cardJourney: CardView
    private lateinit var cardStreak: CardView
    private lateinit var btnSettings: ImageView
    private lateinit var btnShowTop: Button

    private var currentChartType: ChartType = ChartType.WEEK

    enum class ChartType {
        WEEK, MONTH, YEAR
    }

    companion object {
        private const val STEP_SERVICE_WORK_NAME = "StepCounterServicePeriodicWork"
        private const val STEP_SERVICE_INTERVAL_MINUTES = 30L
        private const val TAG = "StepCounterFragment"
    }

    private val stepsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == StepCounterService.ACTION_STEPS_UPDATED) {
                Log.d(TAG, "Получено обновление шагов от сервиса")
                viewModel.loadStatistics()
                showMotivationsIfNeeded()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(requireContext(), "Разрешения получены!", Toast.LENGTH_SHORT).show()
            startStepService()
        } else {
            Toast.makeText(
                requireContext(),
                "Для работы шагомера требуются все разрешения",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_step_counter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d(TAG, "onViewCreated: Инициализация фрагмента")

        viewModel = ViewModelProvider(requireActivity()).get(StepCounterViewModel::class.java)
        motivationManager = MotivationManager(requireContext())

        initializeViews(view)
        setupBarChart()
        setupTabLayout()
        setupButtons(view)
        setupCardAnimations()

        // Обработка системных отступов
        val container = view.findViewById<View>(R.id.container_stats)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bottomInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val extraDp = 16
            val extraPx = (extraDp * resources.displayMetrics.density).toInt()
            val totalBottom = bottomInsets + extraPx
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, totalBottom)
            insets
        }

        checkRequiredPermissions()
        setupObservers()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            schedulePeriodicStepServiceWork()
        }
    }

    private fun initializeViews(view: View) {
        try {
            circularProgressBar = view.findViewById(R.id.circularProgressBar)
            tvProgressPercent = view.findViewById(R.id.tv_progress_percent)
            tvProgressText = view.findViewById(R.id.tv_progress_text)
            tvTodaySteps = view.findViewById(R.id.tv_today_steps)
            tvTodayDistance = view.findViewById(R.id.tv_today_distance)
            tvTodayCalories = view.findViewById(R.id.tv_today_calories)
            tvActiveTime = view.findViewById(R.id.tv_active_time)
            tvComparison = view.findViewById(R.id.tv_comparison)
            tvQuickWeek = view.findViewById(R.id.tv_quick_week)
            tvQuickMonth = view.findViewById(R.id.tv_quick_month)
            tvQuickRecord = view.findViewById(R.id.tv_quick_record)
            tvForecast = view.findViewById(R.id.tv_forecast)
            tvJourneyRoute = view.findViewById(R.id.tv_journey_route)
            tvJourneyProgress = view.findViewById(R.id.tv_journey_progress)
            tvJourneyDistance = view.findViewById(R.id.tv_journey_distance)
            tvStreakDays = view.findViewById(R.id.tv_streak_days)
            progressJourney = view.findViewById(R.id.progress_journey)
            barChart = view.findViewById(R.id.chart_activity)
            tabLayoutPeriod = view.findViewById(R.id.tabLayout_period)
            cardComparison = view.findViewById(R.id.card_comparison)
            cardForecast = view.findViewById(R.id.card_forecast)
            cardJourney = view.findViewById(R.id.card_journey)
            cardStreak = view.findViewById(R.id.card_streak)
            btnSettings = view.findViewById(R.id.btn_settings)
            btnShowTop = view.findViewById(R.id.btn_show_top)

            Log.d(TAG, "Все View успешно инициализированы")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации View: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupBarChart() {
        with(barChart) {
            description = Description().apply {
                text = "Активность за неделю"
                textSize = 12f
                textColor = ContextCompat.getColor(requireContext(), R.color.black)
            }

            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(false)
            setDrawBarShadow(false)
            setDrawValueAboveBar(true)
            isHighlightFullBarEnabled = false
            setNoDataText("Загрузка данных...")
            setNoDataTextColor(ContextCompat.getColor(requireContext(), R.color.black))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = ContextCompat.getColor(requireContext(), R.color.black)
                valueFormatter = IndexAxisValueFormatter(getWeekDays())
                labelCount = 7
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = ContextCompat.getColor(requireContext(), R.color.black)
                textColor = ContextCompat.getColor(requireContext(), R.color.black)
                axisMinimum = 0f
                valueFormatter = LargeValueFormatter()
                setLabelCount(5, true)
            }

            axisRight.isEnabled = false
            legend.isEnabled = false

            // Добавляем слушатель кликов
            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    e?.let {
                        val dayIndex = it.x.toInt()
                        val steps = it.y.toInt()
                        showDayDetails(dayIndex, steps)
                    }
                }

                override fun onNothingSelected() {
                    // Ничего не выбрано
                }
            })

            animateY(1000, Easing.EaseInOutQuad)
        }
    }

    private fun getWeekDays(): List<String> {
        return listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }



    private fun setupTabLayout() {
        tabLayoutPeriod.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        currentChartType = ChartType.WEEK
                        updateChartWithData(ChartType.WEEK)
                    }
                    1 -> {
                        currentChartType = ChartType.MONTH
                        updateChartWithData(ChartType.MONTH)
                    }
                    2 -> {
                        currentChartType = ChartType.YEAR
                        updateChartWithData(ChartType.YEAR)
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateChartWithData(type: ChartType) {
        when (type) {
            ChartType.WEEK -> {
                viewModel.weeklyChartData.value?.let { updateBarChart(it, type) }
            }
            ChartType.MONTH -> {
                viewModel.monthlyChartData.value?.let { updateBarChart(it, type) }
            }
            ChartType.YEAR -> {
                // TODO: Реализовать годовой график
                barChart.clear()
                barChart.invalidate()
                barChart.animateY(500)
            }
        }
    }

    private fun updateBarChart(data: List<Int>, type: ChartType = ChartType.WEEK) {
        if (data.isEmpty()) {
            barChart.clear()
            barChart.invalidate()
            return
        }

        val entries = data.mapIndexed { index, value ->
            BarEntry(index.toFloat(), value.toFloat())
        }

        val labels = when (type) {
            ChartType.WEEK -> getWeekDays()
            ChartType.MONTH -> getMonthDays(data.size)
            ChartType.YEAR -> getMonths()
        }

        val dataSet = BarDataSet(entries, "Шаги").apply {
            color = ContextCompat.getColor(requireContext(), R.color.alarm_primary)
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.black)
            setDrawValues(true)
            valueTextSize = 10f
            valueFormatter = LargeValueFormatter()
        }

        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        barChart.xAxis.labelCount = labels.size

        barChart.data = BarData(dataSet).apply {
            barWidth = 0.6f
            setValueTextSize(10f)
        }

        barChart.description.text = when (type) {
            ChartType.WEEK -> "неделя"
            ChartType.MONTH -> "месяц"
            ChartType.YEAR -> "год"
        }

        barChart.invalidate()
        barChart.animateY(800, Easing.EaseInOutQuad)
    }

    private fun getMonthDays(count: Int): List<String> {
        return (1..count).map { "$it" }
    }

    private fun getMonths(): List<String> {
        return listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")
    }

    private fun setupButtons(view: View) {
        btnShowTop.setOnClickListener {
            startActivity(Intent(requireContext(), TopUsersActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), GoalSettingsActivity::class.java))
        }
    }

    private fun setupCardAnimations() {
        val cards = listOf(cardComparison, cardForecast, cardJourney, cardStreak)
        cards.forEachIndexed { index, card ->
            if (card.visibility == View.VISIBLE) {
                card.alpha = 0f
                card.translationY = 50f
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(200L * index)
                    .setDuration(500)
                    .start()
            }
        }
    }

    private fun setupObservers() {
        // Прогресс-бар цели
        viewModel.progressPercentage.observe(viewLifecycleOwner) { percent ->
            animateCircularProgress(percent)
            tvProgressPercent.text = "$percent%"
        }

        viewModel.goalProgress.observe(viewLifecycleOwner) { (current, goal) ->
            tvProgressText.text = "$current / $goal"
        }

        // Детализированная статистика
        viewModel.todaySteps.observe(viewLifecycleOwner) { steps ->
            tvTodaySteps.text = steps.toString()
        }

        viewModel.todayDistance.observe(viewLifecycleOwner) { distance ->
            tvTodayDistance.text = String.format("%.2f км", distance)
        }

        viewModel.todayCalories.observe(viewLifecycleOwner) { calories ->
            tvTodayCalories.text = String.format("%.0f", calories)
        }

        viewModel.activeTime.observe(viewLifecycleOwner) { minutes ->
            tvActiveTime.text = "$minutes мин"
        }

        // Сравнение с прошлой неделей
        viewModel.comparisonText.observe(viewLifecycleOwner) { text ->
            if (text.isNotEmpty()) {
                tvComparison.text = text
                cardComparison.visibility = View.VISIBLE
            }
        }

        viewModel.comparisonColor.observe(viewLifecycleOwner) { colorRes ->
            tvComparison.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }

        // Быстрые карточки
        viewModel.quickWeek.observe(viewLifecycleOwner) { steps ->
            tvQuickWeek.text = formatNumber(steps)
        }

        viewModel.quickMonth.observe(viewLifecycleOwner) { steps ->
            tvQuickMonth.text = formatNumber(steps)
        }

        viewModel.quickRecord.observe(viewLifecycleOwner) { steps ->
            tvQuickRecord.text = formatNumber(steps)
        }

        // Графики
        viewModel.weeklyChartData.observe(viewLifecycleOwner) { data ->
            if (currentChartType == ChartType.WEEK) {
                updateBarChart(data, ChartType.WEEK)
            }
        }

        viewModel.monthlyChartData.observe(viewLifecycleOwner) { data ->
            if (currentChartType == ChartType.MONTH) {
                updateBarChart(data, ChartType.MONTH)
            }
        }

        // Прогноз
        viewModel.forecastText.observe(viewLifecycleOwner) { text ->
            if (text.isNotEmpty()) {
                tvForecast.text = text
                cardForecast.visibility = View.VISIBLE
            }
        }

        // Виртуальное путешествие
        viewModel.journeyProgress.observe(viewLifecycleOwner) { progress ->
            progressJourney.progress = progress
            tvJourneyProgress.text = "$progress%"
        }

        viewModel.journeyText.observe(viewLifecycleOwner) { text ->
            if (text.isNotEmpty()) {
                val lines = text.split("\n")
                if (lines.size >= 3) {
                    tvJourneyRoute.text = lines[0]
                    tvJourneyDistance.text = lines[1] + "\n" + lines[2]
                } else {
                    tvJourneyDistance.text = text
                }
                cardJourney.visibility = View.VISIBLE
            }
        }

        // Серия дней (streak)
        viewModel.streakDays.observe(viewLifecycleOwner) { streak ->
            tvStreakDays.text = "$streak дней"
            cardStreak.visibility = if (streak > 0) View.VISIBLE else View.GONE

            if (streak >= 7) {
                cardStreak.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gold))
            } else if (streak >= 3) {
                cardStreak.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.silver))
            }
        }

        // Настройки мотивации
        viewModel.motivationEnabled.observe(viewLifecycleOwner) { enabled ->
            if (enabled) {
                showMotivationsIfNeeded()
            }
        }
    }








    private fun showDayDetails(dayIndex: Int, steps: Int) {
        val dayNames = getWeekDays()
        val dayName = if (dayIndex in dayNames.indices) dayNames[dayIndex] else "День $dayIndex"

        val distance = steps * 0.00075f
        val calories = steps * 0.04f
        val activeTime = steps / 100f

        // Используем обычный AlertDialog.Builder вместо MaterialAlertDialogBuilder
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("$dayName - ${steps} шагов")
            .setMessage(
                "📏 Дистанция: ${String.format("%.2f", distance)} км\n" +
                        "🔥 Калории: ${String.format("%.0f", calories)}\n" +
                        "⏱️ Активное время: ${String.format("%.0f", activeTime)} мин"
            )
            .setPositiveButton("OK", null)
            .show()
    }




    private fun animateCircularProgress(targetProgress: Int) {
        ObjectAnimator.ofFloat(circularProgressBar, "progress", targetProgress.toFloat())
            .setDuration(1000)
            .apply {
                interpolator = DecelerateInterpolator()
                start()
            }
    }

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000f)
            number >= 1_000 -> String.format("%.1fK", number / 1_000f)
            else -> number.toString()
        }
    }

    private fun checkRequiredPermissions() {
        val requiredPermissions = mutableListOf<String>().apply {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.BODY_SENSORS)
        }.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            startStepService()
        }
    }

    private fun startStepService() {
        if (!isServiceRunning(StepCounterService::class.java)) {
            StepCounterService.startService(requireContext())
            Log.d(TAG, "Сервис шагомера запущен")
        } else {
            Log.d(TAG, "Сервис шагомера уже запущен")
        }
        schedulePeriodicStepServiceWork()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun schedulePeriodicStepServiceWork() {
        try {
            val workManager = WorkManager.getInstance(requireContext())
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<StepCounterServiceWorker>(
                STEP_SERVICE_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                STEP_SERVICE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )

            Log.d(TAG, "Периодическая работа запланирована")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка планирования периодической работы", e)
        }
    }

    private fun showMotivationsIfNeeded() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Проверяем мотивацию каждые 4 часа
            val prefs = requireContext().getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
            val lastMotivation = prefs.getLong("last_motivation", 0)
            val currentTime = System.currentTimeMillis()
            val fourHours = 4 * 60 * 60 * 1000L

            if (currentTime - lastMotivation > fourHours) {
                viewModel.todaySteps.value?.let { steps ->
                    viewModel.goalProgress.value?.let { (current, goal) ->
                        viewModel.comparisonText.value?.let { comparison ->
                            viewModel.streakDays.value?.let { streak ->
                                // Проверяем достижения
                                val milestones = motivationManager.checkForMilestones(current)
                                milestones.forEach { milestone ->
                                    Toast.makeText(requireContext(), milestone, Toast.LENGTH_LONG).show()
                                }

                                // Показываем уведомление
                                if (viewModel.motivationEnabled.value == true) {
                                    motivationManager.showMotivationNotification(
                                        current,
                                        goal,
                                        comparison,
                                        streak
                                    )
                                    prefs.edit().putLong("last_motivation", currentTime).apply()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        viewModel.loadStatistics()

        // Проверяем корректировку целей (раз в неделю)
        checkGoalAdjustment()
    }

    private fun checkGoalAdjustment() {
        val prefs = requireContext().getSharedPreferences("step_prefs", Context.MODE_PRIVATE)
        val lastAdjustment = prefs.getLong("last_goal_adjustment", 0)
        val currentTime = System.currentTimeMillis()
        val oneWeek = 7 * 24 * 60 * 60 * 1000L

        if (currentTime - lastAdjustment > oneWeek) {
            viewModel.analyzeAndAdjustGoals()
        }
    }

    private fun registerReceiver() {
        try {
            val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_NOT_EXPORTED
            } else {
                0
            }

            val filter = IntentFilter(StepCounterService.ACTION_STEPS_UPDATED)
            requireContext().registerReceiver(stepsReceiver, filter, receiverFlags)
            Log.d(TAG, "Receiver зарегистрирован")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка регистрации receiver", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(stepsReceiver)
            Log.d(TAG, "Receiver отрегистрирован")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отрегистрации receiver", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdown()
        Log.d(TAG, "Executor завершен")
    }
}