package com.example.chatapp.step

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
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
import com.example.chatapp.R
import com.example.chatapp.TopUsersActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.util.concurrent.Executors
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class StepCounterFragment : Fragment() {
    private lateinit var viewModel: StepCounterViewModel
    private val executor = Executors.newFixedThreadPool(4)

    // Views
    private lateinit var textViewToday: TextView
    private lateinit var textViewWeek: TextView
    private lateinit var textViewMonth: TextView
    private lateinit var textViewYear: TextView
    private lateinit var textViewAverage: TextView
    private lateinit var textViewMaxDay: TextView
    private lateinit var textViewGoal: TextView
    private lateinit var progressBarSteps: ProgressBar
    private lateinit var cardViewToday: CardView
    private lateinit var cardViewWeek: CardView
    private lateinit var cardViewMonth: CardView
    private lateinit var cardViewYear: CardView
    private lateinit var cardViewAverage: CardView
    private lateinit var cardViewMax: CardView
    private lateinit var barChart: BarChart // 🔹

    companion object {
        private const val STEP_SERVICE_WORK_NAME = "StepCounterServicePeriodicWork"
        private const val STEP_SERVICE_INTERVAL_MINUTES = 30L
        private const val NOTIFICATION_CHANNEL_ID = "steps_milestone_channel"
        private const val NOTIFICATION_ID_MILESTONE = 12346
    }

    private val stepsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == StepCounterService.ACTION_STEPS_UPDATED) {
                viewModel.loadStatistics()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
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

        viewModel = ViewModelProvider(requireActivity()).get(StepCounterViewModel::class.java)
        initializeViews(view)
        setupBarChart() // 🔹
        setupCardAnimations()
        setupTopUsersButton(view)

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
            Log.d("StepCounterFragment", "onViewCreated: Планирование периодической работы шагомера")
        } else {
            Log.d("StepCounterFragment", "onViewCreated: Разрешения не предоставлены, планирование отложено")
        }
    }

    private fun initializeViews(view: View) {
        textViewToday = view.findViewById(R.id.tv_today)
        textViewWeek = view.findViewById(R.id.tv_week)
        textViewMonth = view.findViewById(R.id.tv_month)
        textViewYear = view.findViewById(R.id.tv_year)
        textViewAverage = view.findViewById(R.id.tv_average)
        textViewMaxDay = view.findViewById(R.id.tv_max_day)
        textViewGoal = view.findViewById(R.id.tv_goal)
        progressBarSteps = view.findViewById(R.id.progress_steps)
        cardViewToday = view.findViewById(R.id.card_today)
        cardViewWeek = view.findViewById(R.id.card_week)
        cardViewMonth = view.findViewById(R.id.card_month)
        cardViewYear = view.findViewById(R.id.card_year)
        cardViewAverage = view.findViewById(R.id.card_average)
        cardViewMax = view.findViewById(R.id.card_max)
        barChart = view.findViewById(R.id.chart_activity)
    }

    // 🔹 Настройка графика
    private fun setupBarChart() {
        with(barChart) {
            description = Description().apply { text = "Шаги за последние 7 дней" }
            setTouchEnabled(false)
            isDragEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            setDrawBarShadow(false)
            setDrawValueAboveBar(true)
            isHighlightFullBarEnabled = false
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                valueFormatter = IndexAxisValueFormatter(days)
            }

            axisRight.isEnabled = false
            axisLeft.setDrawGridLines(false)
        }
    }

    private fun setupCardAnimations() {
        val cards = listOf(cardViewToday, cardViewWeek, cardViewMonth, cardViewYear, cardViewAverage, cardViewMax)
        cards.forEachIndexed { index, card ->
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

    private fun setupTopUsersButton(view: View) {
        view.findViewById<Button>(R.id.btn_show_top).setOnClickListener {
            startActivity(Intent(requireContext(), TopUsersActivity::class.java))
        }
    }

    private fun setupObservers() {
        viewModel.todaySteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewToday, steps, "Сегодня: %d шагов")
            animateProgressBar(steps)
            updateCardColors(steps)
        }
        viewModel.weeklySteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewWeek, steps, "Неделя: %d шагов")
        }
        viewModel.monthlySteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewMonth, steps, "Месяц: %d шагов")
        }
        viewModel.yearlySteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewYear, steps, "Год: %d шагов")
        }
        viewModel.averageSteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewAverage, steps, "Среднее: %d шагов/день")
        }
        viewModel.maxSteps.observe(viewLifecycleOwner) { steps ->
            animateValueChange(textViewMaxDay, steps, "Рекорд: %d шагов")
        }
        viewModel.goalProgress.observe(viewLifecycleOwner) { (current, goal) ->
            updateGoalText(current, goal)
        }

        // 🔹 Observer для графика
        viewModel.weeklyChartData.observe(viewLifecycleOwner) { stepsList ->
            if (stepsList.size == 7) {
                updateBarChart(stepsList)
            }
        }
    }

    // 🔹 Обновление данных графика
    private fun updateBarChart(stepsByDay: List<Int>) {
        val entries = stepsByDay.mapIndexed { index, value ->
            BarEntry(index.toFloat(), value.toFloat())
        }

        val dataSet = BarDataSet(entries, "Шаги").apply {
            color = ContextCompat.getColor(requireContext(), R.color.colorPrimary)
            valueTextColor = Color.WHITE
            setDrawValues(true)
        }

        val barData = BarData(dataSet).apply {
            barWidth = 0.6f
            setValueTextSize(10f)
        }

        barChart.data = barData
        barChart.invalidate()
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
            Log.d("StepCounterFragment", "startStepService: Сервис запущен")
        } else {
            Log.d("StepCounterFragment", "startStepService: Сервис уже запущен")
        }
        schedulePeriodicStepServiceWork()
        Log.d("StepCounterFragment", "startStepService: Планирование периодической работы шагомера")
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun animateValueChange(textView: TextView, newValue: Int, format: String) {
        val oldValue = textView.text
            .replace(Regex("[^\\d]"), "")
            .toIntOrNull() ?: 0
        if (oldValue != newValue) {
            val animator = ValueAnimator.ofInt(oldValue, newValue)
            animator.duration = 1000
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                textView.text = String.format(format, animatedValue)
            }
            animator.start()
        }
    }

    private fun animateProgressBar(newProgress: Int) {
        ObjectAnimator.ofInt(progressBarSteps, "progress", progressBarSteps.progress, newProgress)
            .setDuration(1000)
            .apply {
                interpolator = DecelerateInterpolator()
                start()
            }
    }

    private fun updateCardColors(stepsToday: Int) {
        if (stepsToday >= (viewModel.goalProgress.value?.second ?: 10000)) {
            cardViewToday.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
            progressBarSteps.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.goal_achieved)
        } else {
            cardViewToday.setCardBackgroundColor(Color.WHITE)
            progressBarSteps.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary)
        }
    }

    private fun updateGoalText(currentSteps: Int, goal: Int) {
        val goalText = "$currentSteps / $goal шагов"
        textViewGoal.text = goalText
    }

    private fun schedulePeriodicStepServiceWork() {
        try {
            val workManager = WorkManager.getInstance(requireContext())
            val constraints = Constraints.Builder().build()
            val periodicWorkRequest = PeriodicWorkRequestBuilder<StepCounterServiceWorker>(
                STEP_SERVICE_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(constraints).build()

            workManager.enqueueUniquePeriodicWork(
                STEP_SERVICE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
        } catch (e: Exception) {
            Log.e("StepCounterFragment", "Ошибка планирования", e)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        viewModel.loadStatistics()
    }

    private fun registerReceiver() {
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        requireContext().registerReceiver(
            stepsReceiver,
            IntentFilter(StepCounterService.ACTION_STEPS_UPDATED),
            receiverFlags
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            requireContext().unregisterReceiver(stepsReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdown()
    }
}