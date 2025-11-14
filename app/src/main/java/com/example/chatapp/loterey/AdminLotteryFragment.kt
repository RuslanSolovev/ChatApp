package com.example.chatapp.loterey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.databinding.FragmentAdminLotteryBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AdminLotteryFragment : Fragment() {

    private var _binding: FragmentAdminLotteryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LotteryViewModel by viewModels()
    private lateinit var paymentsAdapter: PendingPaymentsAdapter

    // Переменная для хранения количества билетов
    private var currentTicketCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminLotteryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        binding.tvAdminInfo.text = "👑 Панель администратора лотереи"
    }

    private fun setupRecyclerView() {
        paymentsAdapter = PendingPaymentsAdapter { paymentId ->
            showConfirmPaymentDialog(paymentId)
        }
        binding.rvPendingPayments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPendingPayments.adapter = paymentsAdapter
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.pendingPayments.collect { payments ->
                        if (isAdded && view != null) {
                            paymentsAdapter.submitList(payments)
                            binding.tvEmptyPayments.visibility = if (payments.isEmpty()) View.VISIBLE else View.GONE
                            binding.tvPendingCount.text = "Ожидающих платежей: ${payments.size}"
                        }
                    }
                }

                launch {
                    viewModel.currentLottery.collect { lottery ->
                        if (isAdded && view != null) {
                            lottery?.let {
                                // Получаем количество билетов для этой лотереи
                                loadTicketCountForLottery(it.id)
                                binding.tvLotteryInfo.text = """
                                    🎰 Лотерея #${it.id.takeLast(6).uppercase()}
                                    💰 Призовой фонд: ${it.currentPrize.toInt()} ₽
                                    ⏰ До розыгрыша: ${formatTimeLeft(it.endTime)}
                                    🎫 Билетов продано: $currentTicketCount
                                    📊 Статус: ${it.status}
                                """.trimIndent()
                            } ?: run {
                                binding.tvLotteryInfo.text = "❌ Активная лотерея не найдена"
                            }
                        }
                    }
                }

                launch {
                    viewModel.isLoading.collect { isLoading ->
                        if (isAdded && view != null) {
                            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                            binding.btnDrawWinner.isEnabled = !isLoading
                            binding.btnForceNewLottery.isEnabled = !isLoading
                            binding.btnRefreshPayments.isEnabled = !isLoading
                            binding.btnSendTestNotification.isEnabled = !isLoading
                        }
                    }
                }

                launch {
                    viewModel.message.collect { message ->
                        if (isAdded && view != null) {
                            message?.let {
                                showAdminMessage(it)
                            }
                        }
                    }
                }
            }
        }
    }

    // ИСПРАВЛЕННЫЙ метод для загрузки количества билетов
    private fun loadTicketCountForLottery(lotteryId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.getLotteryTicketCount(lotteryId).collect { count ->
                currentTicketCount = count
                // Обновляем информацию о лотерее
                viewModel.currentLottery.value?.let { lottery ->
                    binding.tvLotteryInfo.text = """
                        🎰 Лотерея #${lottery.id.takeLast(6).uppercase()}
                        💰 Призовой фонд: ${lottery.currentPrize.toInt()} ₽
                        ⏰ До розыгрыша: ${formatTimeLeft(lottery.endTime)}
                        🎫 Билетов продано: $currentTicketCount
                        📊 Статус: ${lottery.status}
                    """.trimIndent()
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnDrawWinner.setOnClickListener {
            showDrawConfirmationDialog()
        }

        binding.btnRefreshPayments.setOnClickListener {
            viewModel.loadPendingPayments()
        }

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressed()
        }

        binding.btnForceNewLottery.setOnClickListener {
            showForceNewLotteryDialog()
        }

        binding.btnSendTestNotification.setOnClickListener {
            sendTestNotification()
        }
    }

    private fun showConfirmPaymentDialog(paymentId: String) {
        val payment = paymentsAdapter.currentList.find { it.id == paymentId }
        payment?.let { p ->
            val ticketCount = (p.amount / 100).toInt()

            AlertDialog.Builder(requireContext())
                .setTitle("✅ Подтвердить платеж")
                .setMessage("""
                    👤 Пользователь: ${p.userName}
                    📧 Email: ${p.userEmail}
                    💰 Сумма: ${p.amount.toInt()} ₽
                    🎫 Билетов: $ticketCount
                    ⏰ Время: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(p.createdAt))}
                    
                    Подтвердить добавление $ticketCount билетов?
                """.trimIndent())
                .setPositiveButton("✅ Подтвердить") { dialog, _ ->
                    viewModel.confirmPayment(paymentId, ticketCount)
                    dialog.dismiss()
                }
                .setNegativeButton("❌ Отмена") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showDrawConfirmationDialog() {
        viewModel.currentLottery.value?.let { lottery ->
            AlertDialog.Builder(requireContext())
                .setTitle("🎰 Запуск розыгрыша")
                .setMessage("""
                    Лотерея #${lottery.id.takeLast(6).uppercase()}
                    
                    💰 Призовой фонд: ${lottery.currentPrize.toInt()} ₽
                    🎫 Билетов продано: $currentTicketCount
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
        } ?: showAdminMessage("❌ Нет активной лотереи для розыгрыша")
    }



    private fun showForceNewLotteryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("🔄 Новая лотерея")
            .setMessage("""
            Вы уверены, что хотите создать новую лотерею?
            
            Текущая лотерея будет завершена, а новая запущена.
            
            Эта операция не может быть отменена.
        """.trimIndent())
            .setPositiveButton("СОЗДАТЬ") { dialog, _ ->
                viewModel.forceCreateNewLottery()
                dialog.dismiss()
            }
            .setNegativeButton("ОТМЕНА") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun sendTestNotification() {
        viewModel.sendTestNotification()
    }

    private fun showAdminMessage(message: String) {
        if (_binding == null) return
        binding.tvAdminStatus.text = message
        binding.tvAdminStatus.visibility = View.VISIBLE

        binding.tvAdminStatus.postDelayed({
            if (_binding != null) {
                binding.tvAdminStatus.visibility = View.GONE
            }
        }, 5000)
    }

    private fun formatTimeLeft(endTime: Long): String {
        val timeLeft = endTime - System.currentTimeMillis()
        return if (timeLeft > 0) {
            val hours = timeLeft / (1000 * 60 * 60)
            val minutes = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)
            "${hours}ч ${minutes}м"
        } else {
            "ВРЕМЯ ВЫШЛО"
        }
    }

    // ИСПРАВЛЕННЫЙ метод onResume
    override fun onResume() {
        super.onResume()
        // Принудительное обновление всех данных
        viewModel.forceRefreshAll()
        viewModel.loadPendingPayments()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}