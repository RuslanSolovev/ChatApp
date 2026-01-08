package com.example.chatapp.loterey

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LotteryViewModel : ViewModel() {

    private val repository = SimpleLotteryRepository()
    private val auth = FirebaseAuth.getInstance()

    private val _currentLottery = MutableStateFlow<SimpleLottery?>(null)
    private val _userTickets = MutableStateFlow<List<LotteryTicket>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _isAdmin = MutableStateFlow(false)

    val currentLottery = _currentLottery.asStateFlow()
    val userTickets = _userTickets.asStateFlow()
    val isLoading = _isLoading.asStateFlow()
    val message = _message.asStateFlow()
    val isAdmin = _isAdmin.asStateFlow()

    private val _pendingPayments = MutableStateFlow<List<ManualPayment>>(emptyList())
    val pendingPayments = _pendingPayments.asStateFlow()

    private val _lotteryHistory = MutableStateFlow<List<LotteryHistory>>(emptyList())
    val lotteryHistory = _lotteryHistory.asStateFlow()

    init {
        checkAdminStatus()
        loadData()
        setupPaymentObserver() // Добавляем наблюдатель для платежей
    }

    private fun checkAdminStatus() {
        val user = auth.currentUser
        _isAdmin.value = user?.uid == "vuei98kyRuQC30CnQ954zQr3Qwr1"
        if (_isAdmin.value) {
            Log.d("LotteryVM", "👑 Пользователь является администратором")
        } else {
            Log.d("LotteryVM", "👤 Обычный пользователь")
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            repository.getCurrentLottery().collect { lottery ->
                _currentLottery.value = lottery
            }
        }

        val currentUser = auth.currentUser
        if (currentUser != null) {
            viewModelScope.launch {
                repository.getUserTickets(currentUser.uid).collect { tickets ->
                    _userTickets.value = tickets
                    Log.d("LotteryVM", "✅ Загружено ${tickets.size} билетов пользователя")
                }
            }
        } else {
            Log.w("LotteryVM", "⚠️ Пользователь не авторизован")
        }
    }

    // Наблюдатель для платежей (автоматическое обновление)
    private fun setupPaymentObserver() {
        viewModelScope.launch {
            repository.getPendingPayments().collect { payments ->
                _pendingPayments.value = payments
                Log.d("LotteryVM", "🔄 Автообновление платежей: ${payments.size} записей")
            }
        }
    }

    // Загрузка ожидающих платежей (ручное обновление)
    fun loadPendingPayments() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Получаем актуальные данные один раз
                val payments = repository.getPendingPayments().first()
                _pendingPayments.value = payments
                Log.d("LotteryVM", "✅ Загружены ожидающие платежи: ${payments.size}")
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка загрузки платежей", e)
                _message.value = "❌ Ошибка загрузки платежей"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ЗАМЕНИТЕ метод forceCreateNewLottery в LotteryViewModel:

    // Принудительное создание новой лотереи
    fun forceCreateNewLottery() {
        viewModelScope.launch {
            _isLoading.value = true
            _message.value = null

            try {
                val success = repository.forceCreateNewLottery()
                if (success) {
                    _message.value = "✅ Новая лотерея создана!"
                    // Принудительно обновляем данные
                    forceRefreshAll()
                } else {
                    _message.value = "❌ Ошибка создания лотереи"
                }
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка создания лотереи", e)
                _message.value = "❌ Ошибка создания лотереи: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getLotteryTicketCount(lotteryId: String): Flow<Int> = flow {
        try {
            repository.getTicketCountForLottery(lotteryId).collect { count ->
                emit(count)
                Log.d("LotteryVM", "🎫 Количество билетов для $lotteryId: $count")
            }
        } catch (e: Exception) {
            Log.e("LotteryVM", "❌ Ошибка получения количества билетов", e)
            emit(0)
        }
    }


    // Улучшенный метод обновления данных
    fun refreshAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Обновляем лотерею
                _currentLottery.value = repository.getCurrentLottery().first()

                // Обновляем билеты пользователя
                auth.currentUser?.uid?.let { userId ->
                    _userTickets.value = repository.getUserTickets(userId).first()
                }

                // Обновляем платежи
                _pendingPayments.value = repository.getPendingPayments().first()

                Log.d("LotteryVM", "✅ Все данные успешно обновлены")
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка обновления данных", e)
                _message.value = "❌ Ошибка обновления данных"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // НОВЫЙ метод: принудительное обновление всех данных
    fun forceRefreshAll() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Log.d("LotteryVM", "🔄 Принудительное обновление всех данных...")

                // Обновляем лотерею
                _currentLottery.value = repository.getCurrentLottery().first()

                // Обновляем платежи
                _pendingPayments.value = repository.getPendingPayments().first()

                // Обновляем билеты пользователя
                auth.currentUser?.uid?.let { userId ->
                    _userTickets.value = repository.getUserTickets(userId).first()
                }

                Log.d("LotteryVM", "✅ Все данные принудительно обновлены")
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка принудительного обновления", e)
                _message.value = "❌ Ошибка обновления данных"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Запуск розыгрыша
    fun drawWinner() {
        viewModelScope.launch {
            _isLoading.value = true
            _message.value = null

            try {
                val success = repository.drawWinner()
                if (success) {
                    _message.value = "🎉 Розыгрыш завершен! Победитель определен."

                    // Обновляем данные
                    forceRefreshAll()
                    loadLotteryHistory()

                } else {
                    _message.value = "❌ Ошибка проведения розыгрыша"
                }
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка розыгрыша", e)
                _message.value = "❌ Ошибка проведения розыгрыша"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Исправленный метод отправки тестового уведомления
    fun sendTestNotification() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.sendTestNotification()
                _message.value = "📢 Тестовое уведомление отправлено"
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка отправки уведомления", e)
                _message.value = "❌ Ошибка отправки уведомления"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Исправленный метод подтверждения платежа
    fun confirmPayment(paymentId: String, ticketCount: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            _message.value = null

            try {
                val success = repository.confirmPayment(paymentId, ticketCount)
                if (success) {
                    _message.value = "✅ Платеж подтвержден! Добавлено $ticketCount билетов"

                    // Принудительно обновляем все данные
                    forceRefreshAll()
                } else {
                    _message.value = "❌ Ошибка подтверждения платежа"
                }
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка подтверждения платежа", e)
                _message.value = "❌ Ошибка подтверждения платежа"
            } finally {
                _isLoading.value = false
            }
        }
    }


    // Общие функции для пользователя
    fun createPaymentRequest(amount: Double) {
        viewModelScope.launch {
            _isLoading.value = true
            _message.value = null

            // Валидация суммы
            if (amount < 100) {
                _message.value = "❌ Минимальная сумма 100 рублей"
                _isLoading.value = false
                return@launch
            }

            try {
                val paymentId = repository.createPaymentRequest(amount)
                if (paymentId != null) {
                    val ticketCount = (amount / 100).toInt()
                    _message.value = "✅ Запрос на $ticketCount билетов создан! Ожидайте подтверждения администратора."
                    Log.d("LotteryVM", "✅ Создан запрос на оплату: $paymentId на сумму $amount")
                } else {
                    _message.value = "❌ Ошибка при создании запроса. Попробуйте еще раз."
                    Log.w("LotteryVM", "❌ Не удалось создать запрос на оплату")
                }
            } catch (e: Exception) {
                _message.value = "❌ Ошибка при создании запроса"
                Log.e("LotteryVM", "❌ Ошибка создания запроса на оплату", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadLotteryHistory() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _lotteryHistory.value = repository.getLotteryHistory()
                Log.d("LotteryVM", "✅ Загружена история розыгрышей: ${_lotteryHistory.value.size} записей")
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка загрузки истории", e)
                _message.value = "❌ Ошибка загрузки истории розыгрышей"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun ensureActiveLottery() {
        viewModelScope.launch {
            try {
                repository.ensureActiveLottery()
                refreshData()
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка обеспечения активной лотереи", e)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun getCurrentUserTicketsCount(): Int {
        return _userTickets.value.size
    }

    fun refreshData() {
        viewModelScope.launch {
            try {
                // Обновляем лотерею
                _currentLottery.value = repository.getCurrentLottery().first()

                // Обновляем билеты пользователя
                auth.currentUser?.uid?.let { userId ->
                    _userTickets.value = repository.getUserTickets(userId).first()
                }

                Log.d("LotteryVM", "✅ Данные успешно обновлены")
            } catch (e: Exception) {
                Log.e("LotteryVM", "❌ Ошибка обновления данных", e)
            }
        }
    }

    // Функция для проверки статуса платежей (только информация)
    fun checkPaymentStatus() {
        _message.value = "📊 У вас ${_userTickets.value.size} билетов. Новые билеты появятся после подтверждения администратора."
    }
}