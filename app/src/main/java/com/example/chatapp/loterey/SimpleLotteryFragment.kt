package com.example.chatapp.loterey

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.databinding.FragmentSimpleLotteryBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SimpleLotteryFragment : Fragment() {

    private var _binding: FragmentSimpleLotteryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LotteryViewModel by viewModels()
    private val auth = FirebaseAuth.getInstance()

    private val yourCardNumber = "5536914012345678"
    private val yourName = "Иван Иванов"

    // Список всех популярных банковских приложений
    private val allBankApps = listOf(
        "ru.sberbankmobile" to "🏦 Сбербанк Онлайн",
        "com.tinkoff.android" to "💳 Тинькофф",
        "com.vkbank.app" to "📱 ВТБ",
        "com.alfa.bank" to "🔵 Альфа-Банк",
        "ru.raiffeisen" to "🟡 Райффайзен",
        "com.openbank" to "🟢 Открытие",
        "com.gazprombank" to "🔴 Газпромбанк",
        "com.psb" to "🟣 Промсвязьбанк",
        "ru.vtb24.mobile" to "🔷 ВТБ",
        "com.rshb" to "🌾 Россельхозбанк",
        "com.akbars" to "⚫ АК Барс",
        "ru.mkb.app" to "🟤 МКБ",
        "com.sovcombank" to "🔶 Совкомбанк",
        "com.uralsibbank" to "🟡 Уралсиб",
        "ru.unicredit" to "🔵 ЮниКредит"
    )

    // Переменные для хранения текущей покупки
    private var currentAmount: Double = 0.0
    private var currentTicketCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimpleLotteryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        setupBankDetails()
        setupClickListeners()
        checkAdminStatus()

        binding.adminPanel.visibility = View.GONE

        Log.d("LotteryFragment", "🎰 Фрагмент лотереи создан")
    }


    private fun setupObservers() {
        // Используем repeatOnLifecycle для безопасного сбора Flow
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentLottery.collect { lottery ->
                        // Проверяем, что фрагмент еще активен
                        if (isAdded && view != null) {
                            lottery?.let {
                                updateLotteryInfo(it)
                                Log.d("LotteryFragment", "🎰 Обновлена лотерея: ${it.id}, приз: ${it.currentPrize}")
                            } ?: run {
                                Log.w("LotteryFragment", "⚠️ Лотерея не найдена")
                                binding.tvPrizePool.text = "Призовой фонд: 0 ₽"
                                binding.tvTimeLeft.text = "Лотерея не активна"
                            }
                        }
                    }
                }

                launch {
                    viewModel.userTickets.collect { tickets ->
                        if (isAdded && view != null) {
                            // Получаем ID текущей лотереи
                            val currentLotteryId = viewModel.currentLottery.value?.id

                            // Фильтруем только билеты текущей активной лотереи
                            val currentTickets = if (currentLotteryId != null) {
                                tickets.filter { it.lotteryId == currentLotteryId }
                            } else {
                                emptyList()
                            }

                            binding.tvTicketCount.text = "Ваши билеты: ${currentTickets.size}"
                            binding.btnCheckTickets.text = "📋 Мои билеты (${currentTickets.size})"

                            // Логируем для отладки
                            Log.d("LotteryFragment", "🎫 Всего билетов: ${tickets.size}, текущей лотереи: ${currentTickets.size}, лотерея: $currentLotteryId")
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (isAdded && view != null) {
                            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                            binding.btnBuyTickets.isEnabled = !isLoading
                            binding.btnConfirmPayment.isEnabled = !isLoading
                            binding.btnCheckTickets.isEnabled = !isLoading
                            binding.btnDrawWinner.isEnabled = !isLoading
                            binding.btnAdminPanel.isEnabled = !isLoading
                            binding.btnQuickAdminPanel.isEnabled = !isLoading
                        }
                    }
                }


                launch {
                    viewModel.currentLottery.collect { lottery ->
                        if (isAdded && view != null) {
                            lottery?.let {
                                updateLotteryInfo(it)
                                Log.d("LotteryFragment", "🎰 Обновлена лотерея: ${it.id}, приз: ${it.currentPrize}, статус: ${it.status}")
                            } ?: run {
                                Log.w("LotteryFragment", "⚠️ Лотерея не найдена, возможно нужно создать новую")
                                binding.tvPrizePool.text = "Призовой фонд: 0 ₽"
                                binding.tvTimeLeft.text = "Лотерея не активна"
                                binding.tvLotteryId.text = "Лотерея не запущена"
                            }
                        }
                    }
                }

                launch {
                    viewModel.message.collect { message ->
                        if (isAdded && view != null) {
                            message?.let {
                                binding.tvPaymentStatus.text = it
                                binding.tvPaymentStatus.visibility = View.VISIBLE
                                binding.tvPaymentStatus.postDelayed({
                                    if (isAdded && view != null) {
                                        binding.tvPaymentStatus.visibility = View.GONE
                                    }
                                }, 5000)
                                Log.d("LotteryFragment", "📢 Сообщение: $it")
                            }
                        }
                    }
                }

                // Добавляем наблюдение за isAdmin для правильного отображения кнопок
                launch {
                    viewModel.isAdmin.collect { isAdmin ->
                        if (isAdded && view != null) {
                            binding.adminPanel.visibility = if (isAdmin) View.VISIBLE else View.GONE
                            binding.btnQuickAdminPanel.visibility = if (isAdmin) View.VISIBLE else View.GONE

                            if (isAdmin) {
                                Log.d("LotteryFragment", "👑 Пользователь является администратором")
                            } else {
                                Log.d("LotteryFragment", "👤 Обычный пользователь")
                            }
                        }
                    }
                }

                // Добавляем наблюдение за pendingPayments для админа
                launch {
                    viewModel.pendingPayments.collect { payments ->
                        if (isAdded && view != null && viewModel.isAdmin.value) {
                            Log.d("LotteryFragment", "🔄 Обновление платежей: ${payments.size}")
                        }
                    }
                }
            }
        }
    }

    private fun setupBankDetails() {
        val formattedCard = formatCardNumber(yourCardNumber)

        binding.tvBankDetails.text = """
            🏦 ПЕРЕВОД НА КАРТУ

            💳 Номер карты:
            $formattedCard

            👤 Получатель:
            $yourName

            💰 СТОИМОСТЬ БИЛЕТОВ:
            • 1 билет = 100 рублей
            • 3 билета = 300 рублей
            • 5 билетов = 500 рублей
            • 10 билетов = 1000 рублей
            
            🎯 90% от суммы идет в призовой фонд
            ⏰ Розыгрыш каждые 24 часа
        """.trimIndent()
    }

    private fun formatCardNumber(cardNumber: String): String {
        return cardNumber.chunked(4).joinToString(" ")
    }

    private fun updateLotteryInfo(lottery: SimpleLottery) {
        // Дополнительная проверка на null binding
        if (_binding == null) return

        binding.tvPrizePool.text = "Призовой фонд: ${lottery.currentPrize.toInt()} ₽"

        // Показываем ID лотереи для отладки
        binding.tvLotteryId.text = "Лотерея #${lottery.id.takeLast(6).uppercase()}"

        val timeLeft = lottery.endTime - System.currentTimeMillis()
        if (timeLeft > 0) {
            val hours = timeLeft / (1000 * 60 * 60)
            val minutes = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)
            binding.tvTimeLeft.text = "До розыгрыша: ${hours}ч ${minutes}м"
            binding.tvLastWinner.visibility = View.GONE
        } else {
            binding.tvTimeLeft.text = "🎰 Розыгрыш начался!"
            if (lottery.status == "ACTIVE") {
                binding.tvLastWinner.text = "⏰ Определяем победителя..."
                binding.tvLastWinner.visibility = View.VISIBLE
            }
        }

        if (lottery.status == "FINISHED") {
            if (lottery.winnerName != null && lottery.winnerName.isNotEmpty()) {
                binding.tvLastWinner.text = "🏆 Победитель: ${lottery.winnerName} - ${lottery.prizeAmount.toInt()} ₽"
                binding.tvLastWinner.visibility = View.VISIBLE
            } else {
                binding.tvLastWinner.text = "🏆 В этой лотерее не было победителя"
                binding.tvLastWinner.visibility = View.VISIBLE
            }
        }

        Log.d("LotteryFragment", "📊 Обновлена информация о лотерее: ${lottery.id}, статус: ${lottery.status}")
    }

    private fun setupClickListeners() {
        binding.btnBuyTickets.setOnClickListener {
            showCustomTicketDialog()
        }

        binding.btnConfirmPayment.setOnClickListener {
            showCustomConfirmationDialog()
        }

        binding.btnCheckTickets.setOnClickListener {
            showUserTicketsDialog()
        }

        binding.tvLastWinner.setOnClickListener {
            showWinnerInfoDialog()
        }

        binding.tvBankDetails.setOnClickListener {
            showBankDetailsInfo()
        }

        binding.btnHistory.setOnClickListener {
            showLotteryHistory()
        }

        // АДМИН КНОПКИ
        binding.btnAdminPanel.setOnClickListener {
            showAdminPanel()
        }

        binding.btnQuickAdminPanel.setOnClickListener {
            showAdminPanel()
        }

        binding.btnDrawWinner.setOnClickListener {
            showDrawConfirmationDialog()
        }
    }

    private fun checkAdminStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isAdmin.collect { isAdmin ->
                if (isAdded && view != null) {
                    binding.adminPanel.visibility = if (isAdmin) View.VISIBLE else View.GONE
                    binding.btnQuickAdminPanel.visibility = if (isAdmin) View.VISIBLE else View.GONE

                    if (isAdmin) {
                        Log.d("Lottery", "👑 Пользователь является администратором")
                    } else {
                        Log.d("Lottery", "👤 Обычный пользователь")
                    }
                }
            }
        }
    }

    // АДМИН ПАНЕЛЬ
    private fun showAdminPanel() {
        val adminFragment = AdminLotteryFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, adminFragment)
            .addToBackStack("admin_panel")
            .commit()
        Log.d("LotteryFragment", "🔧 Открыта админ-панель")
    }

    private fun showDrawConfirmationDialog() {
        viewModel.currentLottery.value?.let { lottery ->
            AlertDialog.Builder(requireContext())
                .setTitle("🎰 Запуск розыгрыша")
                .setMessage("""
                    Вы уверены, что хотите запустить розыгрыш?
                    
                    💰 Призовой фонд: ${lottery.currentPrize.toInt()} ₽
                    🏆 Победитель получит: ${(lottery.currentPrize * 0.9).toInt()} ₽
                    
                    После розыгрыша будет создана новая лотерея.
                """.trimIndent())
                .setPositiveButton("🎰 ЗАПУСТИТЬ РОЗЫГРЫШ") { dialog, _ ->
                    viewModel.drawWinner()
                    dialog.dismiss()
                }
                .setNegativeButton("ОТМЕНА") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } ?: showSuccessMessage("❌ Нет активной лотереи для розыгрыша")
    }

    private fun showLotteryHistory() {
        val historyFragment = LotteryHistoryFragment()
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, historyFragment)
            .addToBackStack("lottery_history")
            .commit()
    }

    // КАСТОМНЫЙ ДИАЛОГ: Покупка билетов
    private fun showCustomTicketDialog() {
        val options = listOf(
            "1 билет (100 ₽)" to 100.0,
            "3 билета (300 ₽)" to 300.0,
            "5 билетов (500 ₽)" to 500.0,
            "10 билетов (1000 ₽)" to 1000.0,
            "Другое количество" to 0.0
        )

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)

        titleView.text = "🎫 Покупка билетов"

        val adapter = SimpleAdapter(options.map { it.first }) { position ->
            when (position) {
                0 -> startPaymentProcess(100.0, 1)
                1 -> startPaymentProcess(300.0, 3)
                2 -> startPaymentProcess(500.0, 5)
                3 -> startPaymentProcess(1000.0, 10)
                4 -> showCustomQuantityDialog()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    // КАСТОМНЫЙ ДИАЛОГ: Подтверждение перевода
    private fun showCustomConfirmationDialog() {
        val options = listOf(
            "✅ Я перевел 100 ₽ (1 билет)",
            "✅ Я перевел 300 ₽ (3 билета)",
            "✅ Я перевел 500 ₽ (5 билетов)",
            "✅ Я перевел 1000 ₽ (10 билетов)",
            "✅ Я перевел другую сумму"
        )

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)

        titleView.text = "💰 Подтверждение перевода"

        val adapter = SimpleAdapter(options) { position ->
            when (position) {
                0 -> confirmPayment(100.0, 1)
                1 -> confirmPayment(300.0, 3)
                2 -> confirmPayment(500.0, 5)
                3 -> confirmPayment(1000.0, 10)
                4 -> showCustomAmountDialog()
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("Еще не перевел") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    // Простой адаптер для списка
    class SimpleAdapter(
        private val items: List<String>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<SimpleAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = items[position]
            holder.textView.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
            holder.textView.textSize = 16f
            holder.textView.setPadding(32, 32, 32, 32)

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount() = items.size
    }

    // ЗАПУСК ПРОЦЕССА ОПЛАТЫ
    private fun startPaymentProcess(amount: Double, ticketCount: Int) {
        currentAmount = amount
        currentTicketCount = ticketCount
        showAvailableBankApps(amount, ticketCount)
    }

    // ПОКАЗАТЬ ДОСТУПНЫЕ БАНКОВСКИЕ ПРИЛОЖЕНИЯ
    private fun showAvailableBankApps(amount: Double, ticketCount: Int) {
        val availableBanks = getInstalledBankApps()

        if (availableBanks.isNotEmpty()) {
            // Показываем установленные банковские приложения
            showBankSelectionDialog(availableBanks, amount, ticketCount)
        } else {
            // Если нет банковских приложений, показываем альтернативные способы
            showNoBanksDialog(amount, ticketCount)
        }
    }

    // ПОЛУЧИТЬ УСТАНОВЛЕННЫЕ БАНКОВСКИЕ ПРИЛОЖЕНИЯ
    private fun getInstalledBankApps(): List<Pair<String, String>> {
        val installedBanks = mutableListOf<Pair<String, String>>()
        val pm = requireContext().packageManager

        for ((packageName, bankName) in allBankApps) {
            try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                installedBanks.add(packageName to bankName)
            } catch (e: PackageManager.NameNotFoundException) {
                // Приложение не установлено, пропускаем
            }
        }

        // Добавляем универсальные способы в конец списка
        installedBanks.add("copy" to "📋 Скопировать реквизиты")
        installedBanks.add("share" to "📤 Поделиться реквизитами")
        installedBanks.add("any" to "🌐 Любой другой банк")

        return installedBanks
    }

    // ДИАЛОГ ВЫБОРА БАНКА
    private fun showBankSelectionDialog(availableBanks: List<Pair<String, String>>, amount: Double, ticketCount: Int) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)

        titleView.text = "💳 Выберите банк\n$amount ₽ • $ticketCount билетов"

        val adapter = SimpleAdapter(availableBanks.map { it.second }) { position ->
            val (packageName, bankName) = availableBanks[position]
            when (packageName) {
                "copy" -> copyBankDetailsWithInstructions(amount, ticketCount)
                "share" -> shareBankDetails(amount, ticketCount)
                "any" -> showAllBanksDialog(amount, ticketCount)
                else -> openBankApp(packageName, bankName, amount, ticketCount)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    // ДИАЛОГ ВСЕХ БАНКОВ (если нет установленных)
    private fun showAllBanksDialog(amount: Double, ticketCount: Int) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)

        titleView.text = "🏦 Все банки\n$amount ₽ • $ticketCount билетов"

        val allBanksWithOptions = allBankApps.map { it.second } +
                listOf("📋 Скопировать реквизиты", "📤 Поделиться реквизитами")

        val adapter = SimpleAdapter(allBanksWithOptions) { position ->
            if (position < allBankApps.size) {
                val (packageName, bankName) = allBankApps[position]
                showBankInstructionsWithDownload(bankName, packageName, amount, ticketCount)
            } else if (position == allBanksWithOptions.size - 2) {
                copyBankDetailsWithInstructions(amount, ticketCount)
            } else {
                shareBankDetails(amount, ticketCount)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    // ДИАЛОГ ЕСЛИ НЕТ БАНКОВСКИХ ПРИЛОЖЕНИЙ
    private fun showNoBanksDialog(amount: Double, ticketCount: Int) {
        val options = listOf(
            "📋 Скопировать реквизиты",
            "📤 Поделиться реквизитами",
            "🌐 Показать все банки"
        )

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)

        titleView.text = "💳 Способы оплаты\n$amount ₽ • $ticketCount билетов"

        val adapter = SimpleAdapter(options) { position ->
            when (position) {
                0 -> copyBankDetailsWithInstructions(amount, ticketCount)
                1 -> shareBankDetails(amount, ticketCount)
                2 -> showAllBanksDialog(amount, ticketCount)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    // ОТКРЫТИЕ БАНКОВСКОГО ПРИЛОЖЕНИЯ
    private fun openBankApp(packageName: String, bankName: String, amount: Double, ticketCount: Int) {
        try {
            val intent = requireContext().packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                // Создаем запрос на оплату
                viewModel.createPaymentRequest(amount)

                // Показываем инструкции
                showQuickBankInstructions(bankName, amount, ticketCount)

                // Открываем приложение банка
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("Lottery", "Ошибка открытия $bankName", e)
            copyBankDetailsWithInstructions(amount, ticketCount)
        }
    }

    // ИНСТРУКЦИИ С ВОЗМОЖНОСТЬЮ СКАЧАТЬ ПРИЛОЖЕНИЕ
    private fun showBankInstructionsWithDownload(bankName: String, packageName: String, amount: Double, ticketCount: Int) {
        val instructions = """
            $bankName
            
            💳 Карта: ${formatCardNumber(yourCardNumber)}
            👤 Получатель: $yourName  
            💵 Сумма: $amount ₽
            📝 Комментарий: Лотерея $ticketCount билетов
            
            Откройте приложение $bankName и сделайте перевод.
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💳 $bankName")
            .setMessage(instructions)
            .setPositiveButton("Открыть банк") { dialog, _ ->
                openBankApp(packageName, bankName, amount, ticketCount)
                dialog.dismiss()
            }
            .setNeutralButton("Скачать приложение") { dialog, _ ->
                downloadBankApp(packageName)
                dialog.dismiss()
            }
            .setNegativeButton("Скопировать реквизиты") { dialog, _ ->
                copyBankDetailsWithInstructions(amount, ticketCount)
                dialog.dismiss()
            }
            .show()
    }

    // КРАТКИЕ ИНСТРУКЦИИ ДЛЯ ОТКРЫТИЯ БАНКА
    private fun showQuickBankInstructions(bankName: String, amount: Double, ticketCount: Int) {
        val instructions = """
            Открывается $bankName...
            
            💰 Сумма: $amount ₽
            📝 Комментарий: Лотерея $ticketCount билетов
            
            Сделайте перевод и вернитесь для подтверждения.
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("💳 $bankName")
            .setMessage(instructions)
            .setPositiveButton("Понятно", null)
            .show()
    }

    // СКАЧАТЬ БАНКОВСКОЕ ПРИЛОЖЕНИЕ
    private fun downloadBankApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            startActivity(intent)
        }
    }

    // КОПИРОВАНИЕ РЕКВИЗИТОВ С ИНСТРУКЦИЯМИ
    private fun copyBankDetailsWithInstructions(amount: Double, ticketCount: Int) {
        val bankDetails = """
            💰 Перевод для лотереи
            
            💳 Карта: ${formatCardNumber(yourCardNumber)}
            👤 Получатель: $yourName
            💵 Сумма: $amount ₽
            📝 Комментарий: Лотерея $ticketCount билетов
            
            После перевода вернитесь в приложение и нажмите "✅ Я ПЕРЕВЕЛ(А) ДЕНЬГИ"
        """.trimIndent()

        val clipboard = android.content.ClipData.newPlainText("Реквизиты", bankDetails)
        val clipboardManager = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboardManager.setPrimaryClip(clipboard)

        // Создаем запрос на оплату
        viewModel.createPaymentRequest(amount)

        AlertDialog.Builder(requireContext())
            .setTitle("✅ Реквизиты скопированы!")
            .setMessage("Реквизиты скопированы в буфер обмена.\n\nОткройте приложение вашего банка, вставьте реквизиты и сделайте перевод.")
            .setPositiveButton("Понятно") { dialog, _ ->
                showSuccessMessage("✅ Запрос создан! После перевода нажмите '✅ Я ПЕРЕВЕЛ(А) ДЕНЬГИ'")
                dialog.dismiss()
            }
            .show()
    }

    // ПОДЕЛИТЬСЯ РЕКВИЗИТАМИ
    private fun shareBankDetails(amount: Double, ticketCount: Int) {
        val shareText = """
            💰 Перевод для лотереи
            
            💳 Карта: ${formatCardNumber(yourCardNumber)}
            👤 Получатель: $yourName
            💵 Сумма: $amount ₽
            📝 Комментарий: Лотерея $ticketCount билетов
            
            После перевода подтвердите оплату в приложении лотереи.
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Реквизиты для перевода в лотерею")
        }

        try {
            startActivity(Intent.createChooser(intent, "Поделиться реквизитами"))
            viewModel.createPaymentRequest(amount)
            showSuccessMessage("✅ Запрос создан! После перевода подтвердите оплату.")
        } catch (e: Exception) {
            showSuccessMessage("❌ Не удалось поделиться реквизитами")
        }
    }

    private fun confirmPayment(amount: Double, ticketCount: Int) {
        viewModel.createPaymentRequest(amount)
        showSuccessMessage("✅ Запрос на $ticketCount билетов создан! Ожидайте подтверждения администратора.")
    }

    private fun showCustomQuantityDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "Введите количество билетов"

        AlertDialog.Builder(requireContext())
            .setTitle("🎫 Выберите количество")
            .setMessage("1 билет = 100 рублей")
            .setView(input)
            .setPositiveButton("Продолжить") { dialog, _ ->
                val quantity = input.text.toString().toIntOrNull() ?: 0
                if (quantity > 0) {
                    val amount = quantity * 100.0
                    startPaymentProcess(amount, quantity)
                } else {
                    showSuccessMessage("❌ Введите корректное количество")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCustomAmountDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "Введите сумму в рублях"

        AlertDialog.Builder(requireContext())
            .setTitle("💰 Введите сумму")
            .setMessage("1 билет = 100 рублей")
            .setView(input)
            .setPositiveButton("Подтвердить") { dialog, _ ->
                val amount = input.text.toString().toDoubleOrNull() ?: 0.0
                if (amount >= 100) {
                    val ticketCount = (amount / 100).toInt()
                    confirmPayment(amount, ticketCount)
                } else {
                    showSuccessMessage("❌ Минимальная сумма 100 рублей")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSuccessMessage(message: String) {
        if (_binding == null) return
        binding.tvPaymentStatus.text = message
        binding.tvPaymentStatus.visibility = View.VISIBLE
        binding.tvPaymentStatus.postDelayed({
            if (_binding != null) {
                binding.tvPaymentStatus.visibility = View.GONE
            }
        }, 5000)
    }

    // ОБНОВЛЕННЫЙ МЕТОД: показываем только билеты текущей лотереи
    private fun showUserTicketsDialog() {
        val allTickets = viewModel.userTickets.value
        val currentLotteryId = viewModel.currentLottery.value?.id

        // Фильтруем только билеты текущей лотереи
        val currentTickets = if (currentLotteryId != null) {
            allTickets.filter { it.lotteryId == currentLotteryId }
        } else {
            emptyList()
        }

        if (currentTickets.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("🎫 Ваши билеты")
                .setMessage("У вас пока нет билетов в текущей лотерее.\n\nКупите билеты, чтобы участвовать!")
                .setPositiveButton("Купить билеты") { dialog, _ ->
                    showCustomTicketDialog()
                    dialog.dismiss()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        } else {
            val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            val ticketList = currentTickets.sortedByDescending { it.purchaseTime }
                .joinToString("\n") { ticket ->
                    val date = Date(ticket.purchaseTime)
                    "🎫 Билет от ${dateFormat.format(date)}" +
                            if (ticket.isWinner) " 🏆 ПОБЕДИТЕЛЬ!" else ""
                }

            AlertDialog.Builder(requireContext())
                .setTitle("🎫 Ваши билеты (${currentTickets.size})")
                .setMessage("Текущая лотерея: #${currentLotteryId?.takeLast(6)?.uppercase()}\n\n$ticketList")
                .setPositiveButton("Купить еще") { dialog, _ ->
                    showCustomTicketDialog()
                    dialog.dismiss()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
    }

    private fun showWinnerInfoDialog() {
        val lottery = viewModel.currentLottery.value
        if (lottery?.status == "FINISHED" && lottery.winnerName != null) {
            AlertDialog.Builder(requireContext())
                .setTitle("🏆 Победитель")
                .setMessage("Поздравляем!\n\n👤 ${lottery.winnerName}\n💰 ${lottery.prizeAmount.toInt()} ₽")
                .setPositiveButton("OK", null)
                .show()
        } else {
            showSuccessMessage("🏆 Информация о победителе появится после розыгрыша")
        }
    }

    private fun showBankDetailsInfo() {
        AlertDialog.Builder(requireContext())
            .setTitle("💳 Реквизиты")
            .setMessage("Для покупки билетов:\n\n💳 ${formatCardNumber(yourCardNumber)}\n👤 $yourName\n\n💰 1 билет = 100 рублей")
            .setPositiveButton("OK", null)
            .show()
    }

    // В файле SimpleLotteryFragment.kt замените метод onResume:

    override fun onResume() {
        super.onResume()
        Log.d("LotteryFragment", "🔄 Фрагмент возобновлен, принудительно обновляем данные...")

        // Убеждаемся, что есть активная лотерея
        viewModel.ensureActiveLottery()

        // Принудительное обновление всех данных
        viewModel.forceRefreshAll()

        Log.d("LotteryFragment", "✅ Данные обновлены")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}