package com.example.chatapp.loterey

import android.util.Log
import com.example.chatapp.step.ONESIGNAL_APP_ID
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject

class SimpleLotteryRepository {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "LotteryRepo"
        private const val adminUserId = "4b3dGWLXHNO5LCeD7R8VAbnmnRg1"
        private const val ONESIGNAL_REST_API_KEY = "os_v2_app_acb55d34ubecjleitqbxe6bdpzgl3ejyyjfu2em64r2vsnypzjosk4x4zz4ymvanhwxm6bwqiglyzyaslkrcurm2f5oxe5huvssdsdq"
    }

    // ИСПРАВЛЕННЫЙ метод отправки уведомлений
    private suspend fun sendOneSignalNotification(notificationData: Map<String, Any>) {
        try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient()
                val json = JSONObject(notificationData).toString()

                val request = Request.Builder()
                    .url("https://onesignal.com/api/v1/notifications")
                    .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), json))
                    .addHeader("Authorization", "Basic $ONESIGNAL_REST_API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .build()

                try {
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Уведомление успешно отправлено")
                    } else {
                        Log.e(TAG, "❌ Ошибка отправки уведомления: ${response.body?.string()}")
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка выполнения запроса уведомления", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления через OneSignal", e)
        }
    }



    // УВЕДОМЛЕНИЕ: Победителю лотереи
    private suspend fun sendWinnerNotification(winnerUserId: String, winnerName: String, prizeAmount: Double, lotteryId: String) {
        try {
            // Получаем OneSignal ID победителя
            val winnerOneSignalId = getOneSignalId(winnerUserId)

            val notificationData = if (winnerOneSignalId != null) {
                // Отправляем конкретному пользователю
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "🏆 ПОЗДРАВЛЯЕМ! Вы выиграли ${prizeAmount.toInt()} ₽ в лотерее!",
                        "ru" to "🏆 ПОЗДРАВЛЯЕМ! Вы выиграли ${prizeAmount.toInt()} ₽ в лотерее!"
                    ),
                    "headings" to mapOf(
                        "en" to "🎰 ВЫ ПОБЕДИЛИ!",
                        "ru" to "🎰 ВЫ ПОБЕДИЛИ!"
                    ),
                    "include_player_ids" to listOf(winnerOneSignalId),
                    "data" to mapOf(
                        "type" to "lottery_win",
                        "prizeAmount" to prizeAmount,
                        "lotteryId" to lotteryId,
                        "isWinner" to true
                    )
                )
            } else {
                // Отправляем общее уведомление (если не нашли OneSignal ID)
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "🏆 ПОЗДРАВЛЯЕМ! Вы выиграли ${prizeAmount.toInt()} ₽ в лотерее! Зайдите в приложение для получения приза.",
                        "ru" to "🏆 ПОЗДРАВЛЯЕМ! Вы выиграли ${prizeAmount.toInt()} ₽ в лотерее! Зайдите в приложение для получения приза."
                    ),
                    "headings" to mapOf(
                        "en" to "🎰 ВЫ ПОБЕДИЛИ!",
                        "ru" to "🎰 ВЫ ПОБЕДИЛИ!"
                    ),
                    "included_segments" to listOf("Subscribed Users"),
                    "data" to mapOf(
                        "type" to "lottery_win",
                        "prizeAmount" to prizeAmount,
                        "lotteryId" to lotteryId,
                        "isWinner" to true
                    )
                )
            }

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Уведомление победителю отправлено: $winnerName - $prizeAmount руб")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления победителю", e)
        }
    }

    // УВЕДОМЛЕНИЕ: Админу о результатах розыгрыша
    private suspend fun sendAdminLotteryResultNotification(lotteryId: String, winnerName: String, prizeAmount: Double, ticketCount: Int) {
        try {
            // Получаем OneSignal ID админа
            val adminOneSignalId = getOneSignalId(adminUserId)

            val notificationData = if (adminOneSignalId != null) {
                // Отправляем конкретно админу
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "🎰 Розыгрыш лотереи #${lotteryId.takeLast(6)} завершен. Победитель: $winnerName. Приз: ${prizeAmount.toInt()} ₽. Билетов: $ticketCount",
                        "ru" to "🎰 Розыгрыш лотереи #${lotteryId.takeLast(6)} завершен. Победитель: $winnerName. Приз: ${prizeAmount.toInt()} ₽. Билетов: $ticketCount"
                    ),
                    "headings" to mapOf(
                        "en" to "📊 Розыгрыш завершен",
                        "ru" to "📊 Розыгрыш завершен"
                    ),
                    "include_player_ids" to listOf(adminOneSignalId),
                    "data" to mapOf(
                        "type" to "admin_lottery_result",
                        "lotteryId" to lotteryId,
                        "winnerName" to winnerName,
                        "prizeAmount" to prizeAmount,
                        "ticketCount" to ticketCount,
                        "isAdmin" to true
                    )
                )
            } else {
                // Отправляем общее уведомление (админ тоже подписан)
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "🎰 Розыгрыш лотереи #${lotteryId.takeLast(6)} завершен. Победитель: $winnerName. Приз: ${prizeAmount.toInt()} ₽",
                        "ru" to "🎰 Розыгрыш лотереи #${lotteryId.takeLast(6)} завершен. Победитель: $winnerName. Приз: ${prizeAmount.toInt()} ₽"
                    ),
                    "headings" to mapOf(
                        "en" to "📊 Розыгрыш завершен",
                        "ru" to "📊 Розыгрыш завершен"
                    ),
                    "included_segments" to listOf("Subscribed Users"),
                    "data" to mapOf(
                        "type" to "admin_lottery_result",
                        "lotteryId" to lotteryId,
                        "winnerName" to winnerName,
                        "prizeAmount" to prizeAmount,
                        "ticketCount" to ticketCount,
                        "isAdmin" to true
                    )
                )
            }

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Уведомление админу о розыгрыше отправлено")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления админу", e)
        }
    }

    // УВЕДОМЛЕНИЕ: Всем участникам о результатах
    private suspend fun sendLotteryResultsToAll(winnerName: String, prizeAmount: Double, lotteryId: String, ticketCount: Int) {
        try {
            val notificationData = mapOf(
                "app_id" to ONESIGNAL_APP_ID,
                "contents" to mapOf(
                    "en" to "🏆 Победитель лотереи: $winnerName! Приз: ${prizeAmount.toInt()} ₽. Всего билетов: $ticketCount. Участвуйте в следующей лотерее!",
                    "ru" to "🏆 Победитель лотереи: $winnerName! Приз: ${prizeAmount.toInt()} ₽. Всего билетов: $ticketCount. Участвуйте в следующей лотерее!"
                ),
                "headings" to mapOf(
                    "en" to "🎰 Результаты лотереи",
                    "ru" to "🎰 Результаты лотереи"
                ),
                "included_segments" to listOf("Subscribed Users"),
                "data" to mapOf(
                    "type" to "lottery_results",
                    "winnerName" to winnerName,
                    "prizeAmount" to prizeAmount,
                    "lotteryId" to lotteryId,
                    "ticketCount" to ticketCount,
                    "isWinner" to false
                )
            )

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Уведомление о результатах лотереи отправлено всем участникам")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления о результатах", e)
        }
    }

    // УВЕДОМЛЕНИЕ: Админу о новом платеже
    suspend fun sendNewPaymentNotification(paymentId: String, userName: String, amount: Double, ticketCount: Int) {
        try {
            val adminOneSignalId = getOneSignalId(adminUserId)

            val notificationData = if (adminOneSignalId != null) {
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "💳 Новый платеж от $userName. Сумма: ${amount.toInt()} ₽ ($ticketCount билетов). Требует подтверждения.",
                        "ru" to "💳 Новый платеж от $userName. Сумма: ${amount.toInt()} ₽ ($ticketCount билетов). Требует подтверждения."
                    ),
                    "headings" to mapOf(
                        "en" to "💰 Новый платеж",
                        "ru" to "💰 Новый платеж"
                    ),
                    "include_player_ids" to listOf(adminOneSignalId),
                    "data" to mapOf(
                        "type" to "new_payment",
                        "paymentId" to paymentId,
                        "userName" to userName,
                        "amount" to amount,
                        "ticketCount" to ticketCount,
                        "isAdmin" to true
                    )
                )
            } else {
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "💳 Новый платеж от $userName. Сумма: ${amount.toInt()} ₽ ($ticketCount билетов)",
                        "ru" to "💳 Новый платеж от $userName. Сумма: ${amount.toInt()} ₽ ($ticketCount билетов)"
                    ),
                    "headings" to mapOf(
                        "en" to "💰 Новый платеж",
                        "ru" to "💰 Новый платеж"
                    ),
                    "included_segments" to listOf("Subscribed Users"),
                    "data" to mapOf(
                        "type" to "new_payment",
                        "paymentId" to paymentId,
                        "userName" to userName,
                        "amount" to amount,
                        "ticketCount" to ticketCount,
                        "isAdmin" to true
                    )
                )
            }

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Уведомление админу о новом платеже отправлено")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления о новом платеже", e)
        }
    }

    // Получение OneSignal ID пользователя
    private suspend fun getOneSignalId(userId: String): String? {
        return try {
            val snapshot = database.reference.child("users").child(userId).child("oneSignalId").get().await()
            snapshot.getValue(String::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка получения OneSignal ID для пользователя $userId", e)
            null
        }
    }

    // ИСПРАВЛЕННЫЙ метод создания новой лотереи
    private suspend fun createNewLottery(): String? {
        return try {
            val newLotteryId = database.reference.child("simpleLotteries").push().key ?: return null

            val newLottery = SimpleLottery(
                id = newLotteryId,
                currentPrize = 0.0,
                ticketPrice = 100.0,
                endTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000, // 24 часа
                status = "ACTIVE"
            )

            database.reference.child("simpleLotteries").child(newLotteryId).setValue(newLottery).await()
            Log.d(TAG, "✅ Новая лотерея создана: $newLotteryId")
            newLotteryId
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания новой лотереи", e)
            null
        }
    }

    fun getCurrentLottery(): Flow<SimpleLottery?> = callbackFlow {
        val listener = database.reference.child("simpleLotteries")
            .orderByChild("status")
            .equalTo("ACTIVE")
            .limitToFirst(1)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (snapshot.exists()) {
                            val lotteries = snapshot.children.mapNotNull {
                                val lottery = it.getValue<SimpleLottery>()
                                lottery?.copy(id = it.key ?: "")
                            }
                            val activeLottery = lotteries.firstOrNull()
                            trySend(activeLottery)
                        } else {
                            trySend(null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка обработки лотереи", e)
                        trySend(null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки лотереи", error.toException())
                    trySend(null)
                }
            })

        awaitClose { database.reference.removeEventListener(listener) }
    }

    // Метод получения истории лотерей
    suspend fun getLotteryHistory(): List<LotteryHistory> {
        return try {
            val snapshot = database.reference.child("lotteryHistory")
                .orderByChild("drawTime")
                .limitToLast(50)
                .get().await()

            val history = snapshot.children.mapNotNull {
                it.getValue<LotteryHistory>()?.copy(id = it.key ?: "")
            }.sortedByDescending { it.drawTime }

            Log.d(TAG, "✅ Загружена история розыгрышей: ${history.size} записей")
            history
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка загрузки истории розыгрышей", e)
            emptyList()
        }
    }

    // ИСПРАВЛЕННЫЙ метод для получения количества билетов
    fun getTicketCountForLottery(lotteryId: String): Flow<Int> = callbackFlow {
        if (lotteryId.isBlank()) {
            trySend(0)
            awaitClose { }
            return@callbackFlow
        }

        val listener = database.reference.child("lotteryTickets")
            .orderByChild("lotteryId")
            .equalTo(lotteryId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val count = snapshot.childrenCount.toInt()
                        Log.d(TAG, "🎫 Количество билетов для лотереи $lotteryId: $count")
                        trySend(count)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Ошибка подсчета билетов", e)
                        trySend(0)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки билетов", error.toException())
                    trySend(0)
                }
            })

        awaitClose { database.reference.removeEventListener(listener) }
    }


    // ИСПРАВЛЕННЫЙ метод подтверждения платежа с уведомлением
    suspend fun confirmPayment(paymentId: String, ticketCount: Int): Boolean {
        val user = auth.currentUser ?: return false
        if (user.uid != adminUserId) {
            Log.w(TAG, "❌ Недостаточно прав для подтверждения платежа")
            return false
        }

        return try {
            Log.d(TAG, "🔄 Начало подтверждения платежа: $paymentId, билетов: $ticketCount")

            // Получаем данные платежа перед подтверждением
            val paymentSnapshot = database.reference.child("manualPayments").child(paymentId).get().await()
            val payment = paymentSnapshot.getValue<ManualPayment>()

            if (payment == null) {
                Log.e(TAG, "❌ Платеж не найден: $paymentId")
                return false
            }

            Log.d(TAG, "✅ Найден платеж: ${payment.userName} - ${payment.amount} ₽")

            // Обновляем статус платежа
            val paymentUpdates = mapOf(
                "status" to "CONFIRMED",
                "confirmedAt" to System.currentTimeMillis(),
                "ticketsAdded" to ticketCount
            )

            database.reference.child("manualPayments").child(paymentId)
                .updateChildren(paymentUpdates).await()

            Log.d(TAG, "✅ Статус платежа обновлен на CONFIRMED")

            // Добавляем билеты пользователю
            val success = addTicketsToUser(payment.userId, payment.userName, payment.userEmail, ticketCount)

            if (success) {
                Log.d(TAG, "✅ Билеты успешно добавлены пользователю ${payment.userName}")

                // Отправляем уведомление пользователю о подтверждении
                Log.d(TAG, "📢 Отправка уведомления пользователю ${payment.userName}...")
                sendPaymentConfirmationNotification(paymentId, ticketCount, payment.userName)

                Log.d(TAG, "✅ Платеж полностью подтвержден: $paymentId, добавлено $ticketCount билетов")
                true
            } else {
                Log.e(TAG, "❌ Ошибка добавления билетов для платежа: $paymentId")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка подтверждения платежа", e)
            false
        }
    }



    // ИСПРАВЛЕННЫЙ метод розыгрыша с полными уведомлениями
    suspend fun drawWinner(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.uid != adminUserId) {
            Log.w(TAG, "❌ Недостаточно прав для розыгрыша")
            return false
        }

        return try {
            Log.d(TAG, "🎰 Начало розыгрыша...")

            // Получаем активную лотерею
            val lotterySnapshot = database.reference.child("simpleLotteries")
                .orderByChild("status")
                .equalTo("ACTIVE")
                .limitToFirst(1)
                .get().await()

            if (!lotterySnapshot.exists()) {
                Log.w(TAG, "⚠️ Активная лотерея не найдена, создаем новую")
                createNewLottery()
                return true
            }

            val lottery = lotterySnapshot.children.mapNotNull {
                it.getValue<SimpleLottery>()?.copy(id = it.key ?: "")
            }.firstOrNull()

            if (lottery == null) {
                Log.w(TAG, "⚠️ Лотерея не найдена")
                createNewLottery()
                return true
            }

            Log.d(TAG, "🎯 Найдена лотерея: ${lottery.id}")

            // Получаем билеты для этой лотереи
            val ticketsSnapshot = database.reference.child("lotteryTickets")
                .orderByChild("lotteryId")
                .equalTo(lottery.id)
                .get().await()

            val tickets = ticketsSnapshot.children.mapNotNull {
                it.getValue<LotteryTicket>()?.copy(id = it.key ?: "")
            }

            Log.d(TAG, "🎫 Найдено билетов: ${tickets.size}")

            if (tickets.isEmpty()) {
                Log.w(TAG, "⚠️ Нет билетов для розыгрыша")
                // Завершаем лотерею без победителя
                database.reference.child("simpleLotteries").child(lottery.id)
                    .child("status").setValue("FINISHED").await()

                // Отправляем уведомление админу о пустой лотерее
                sendAdminLotteryResultNotification(lottery.id, "Нет победителя", 0.0, 0)

                // Создаем новую лотерею
                createNewLottery()
                return true
            }

            // Выбираем случайного победителя
            val winnerTicket = tickets.random()
            val prizeAmount = lottery.currentPrize * 0.9

            Log.d(TAG, "🏆 Выбран победитель: ${winnerTicket.userName}")

            // Получаем данные пользователя
            val userSnapshot = database.reference.child("users").child(winnerTicket.userId).get().await()
            val winnerUser = userSnapshot.getValue(com.example.chatapp.models.User::class.java)

            val winnerDisplayName = winnerUser?.getFullName()?.ifEmpty { winnerTicket.userName } ?: winnerTicket.userName
            val winnerEmail = winnerUser?.email ?: winnerTicket.userEmail

            // Обновляем билет победителя
            database.reference.child("lotteryTickets").child(winnerTicket.id)
                .child("isWinner").setValue(true).await()

            // Завершаем текущую лотерею
            val updates = mapOf(
                "status" to "FINISHED",
                "winnerId" to winnerTicket.userId,
                "winnerName" to winnerDisplayName,
                "prizeAmount" to prizeAmount
            )
            database.reference.child("simpleLotteries").child(lottery.id)
                .updateChildren(updates).await()

            // Сохраняем историю
            saveLotteryHistory(lottery, winnerTicket, winnerDisplayName, winnerEmail, prizeAmount, tickets.size)

            // ОТПРАВЛЯЕМ ВСЕ УВЕДОМЛЕНИЯ:

            // 1. Победителю
            sendWinnerNotification(winnerTicket.userId, winnerDisplayName, prizeAmount, lottery.id)

            // 2. Админу о результатах
            sendAdminLotteryResultNotification(lottery.id, winnerDisplayName, prizeAmount, tickets.size)

            // 3. Всем участникам о результатах
            sendLotteryResultsToAll(winnerDisplayName, prizeAmount, lottery.id, tickets.size)

            // Создаем новую лотерею
            createNewLottery()

            Log.d(TAG, "🎉 Розыгрыш завершен! Победитель: $winnerDisplayName - $prizeAmount руб")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка розыгрыша", e)
            false
        }
    }

    private suspend fun saveLotteryHistory(
        lottery: SimpleLottery,
        winnerTicket: LotteryTicket,
        winnerName: String,
        winnerEmail: String,
        prizeAmount: Double,
        totalTickets: Int
    ) {
        try {
            val historyId = database.reference.child("lotteryHistory").push().key ?: return

            val history = LotteryHistory(
                id = historyId,
                lotteryId = lottery.id,
                winnerId = winnerTicket.userId,
                winnerName = winnerName,
                winnerEmail = winnerEmail,
                prizeAmount = prizeAmount,
                drawTime = System.currentTimeMillis(),
                ticketCount = totalTickets,
                totalParticipants = totalTickets
            )

            database.reference.child("lotteryHistory").child(historyId).setValue(history).await()
            Log.d(TAG, "✅ История розыгрыша сохранена: $historyId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сохранения истории розыгрыша", e)
        }
    }

    fun getUserTickets(userId: String): Flow<List<LotteryTicket>> = callbackFlow {
        val listener = database.reference.child("lotteryTickets")
            .orderByChild("userId")
            .equalTo(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val tickets = snapshot.children.mapNotNull {
                        val ticket = it.getValue<LotteryTicket>()
                        ticket?.copy(id = it.key ?: "")
                    }
                    trySend(tickets)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки билетов", error.toException())
                    trySend(emptyList())
                }
            })

        awaitClose { database.reference.removeEventListener(listener) }
    }

    suspend fun createPaymentRequest(amount: Double): String? {
        val user = auth.currentUser ?: return null

        return try {
            Log.d(TAG, "Создание запроса на оплату: $amount руб для пользователя ${user.uid}")

            if (amount < 100) {
                Log.w(TAG, "❌ Сумма меньше минимальной: $amount")
                return null
            }

            val paymentId = database.reference.child("manualPayments").push().key ?: return null

            val payment = ManualPayment(
                id = paymentId,
                userId = user.uid,
                userName = user.displayName ?: "Аноним",
                userEmail = user.email ?: "нет email",
                amount = amount,
                status = "PENDING",
                createdAt = System.currentTimeMillis()
            )

            database.reference.child("manualPayments").child(paymentId).setValue(payment).await()

            // Отправляем уведомление админу о новом платеже
            val ticketCount = (amount / 100).toInt()
            sendNewPaymentNotification(paymentId, payment.userName, amount, ticketCount)

            Log.d(TAG, "✅ Запрос на оплату успешно создан: $paymentId")
            paymentId

        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка создания запроса оплаты", e)
            null
        }
    }

    fun getPendingPayments(): Flow<List<ManualPayment>> = callbackFlow {
        val listener = database.reference.child("manualPayments")
            .orderByChild("status")
            .equalTo("PENDING")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val payments = snapshot.children.mapNotNull {
                        val payment = it.getValue<ManualPayment>()
                        payment?.copy(id = it.key ?: "")
                    }.sortedByDescending { it.createdAt }
                    trySend(payments)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "❌ Ошибка загрузки платежей", error.toException())
                    trySend(emptyList())
                }
            })

        awaitClose { database.reference.removeEventListener(listener) }
    }

    // УВЕДОМЛЕНИЕ: Подтверждение платежа пользователю
    private suspend fun sendPaymentConfirmationNotification(paymentId: String, ticketCount: Int, userName: String) {
        try {
            Log.d(TAG, "🔄 Подготовка уведомления о подтверждении платежа для $userName")

            // Получаем OneSignal ID пользователя
            val paymentSnapshot = database.reference.child("manualPayments").child(paymentId).get().await()
            val payment = paymentSnapshot.getValue<ManualPayment>()

            val userOneSignalId = if (payment != null) {
                getOneSignalId(payment.userId)
            } else {
                null
            }

            val notificationData = if (userOneSignalId != null) {
                // Отправляем конкретному пользователю
                Log.d(TAG, "✅ Отправка персонализированного уведомления пользователю $userName")
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "✅ Your payment has been confirmed! $ticketCount tickets have been added to your lottery account.",
                        "ru" to "✅ Ваш платеж подтвержден! Вам добавлено $ticketCount билетов в лотерею."
                    ),
                    "headings" to mapOf(
                        "en" to "🎫 Lottery - Tickets Added",
                        "ru" to "🎫 Лотерея - Билеты добавлены"
                    ),
                    "include_player_ids" to listOf(userOneSignalId),
                    "data" to mapOf(
                        "type" to "payment_confirmed",
                        "ticketCount" to ticketCount,
                        "paymentId" to paymentId,
                        "userName" to userName,
                        "message" to "Ваш платеж подтвержден администратором"
                    )
                )
            } else {
                // Отправляем общее уведомление
                Log.d(TAG, "✅ Отправка общего уведомления о подтверждении платежа")
                mapOf(
                    "app_id" to ONESIGNAL_APP_ID,
                    "contents" to mapOf(
                        "en" to "✅ Your payment has been confirmed! $ticketCount tickets have been added to your lottery account.",
                        "ru" to "✅ Ваш платеж подтвержден! Вам добавлено $ticketCount билетов в лотерею."
                    ),
                    "headings" to mapOf(
                        "en" to "🎫 Lottery - Tickets Added",
                        "ru" to "🎫 Лотерея - Билеты добавлены"
                    ),
                    "included_segments" to listOf("Subscribed Users"),
                    "data" to mapOf(
                        "type" to "payment_confirmed",
                        "ticketCount" to ticketCount,
                        "paymentId" to paymentId,
                        "userName" to userName,
                        "message" to "Ваш платеж подтвержден администратором"
                    )
                )
            }

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Уведомление о подтверждении платежа отправлено пользователю $userName")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки уведомления о подтверждении платежа", e)
        }
    }


    // Метод для принудительной отправки уведомления о подтверждении платежа
    suspend fun resendPaymentConfirmation(paymentId: String) {
        try {
            Log.d(TAG, "🔄 Принудительная отправка уведомления о подтверждении платежа: $paymentId")

            val paymentSnapshot = database.reference.child("manualPayments").child(paymentId).get().await()
            val payment = paymentSnapshot.getValue<ManualPayment>()

            if (payment != null && payment.status == "CONFIRMED") {
                val ticketCount = payment.ticketsAdded
                if (ticketCount > 0) {
                    sendPaymentConfirmationNotification(paymentId, ticketCount, payment.userName)
                    Log.d(TAG, "✅ Уведомление переотправлено для платежа: $paymentId")
                } else {
                    Log.w(TAG, "⚠️ Нет информации о количестве билетов для платежа: $paymentId")
                }
            } else {
                Log.w(TAG, "⚠️ Платеж не подтвержден или не найден: $paymentId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка переотправки уведомления", e)
        }
    }

    // Отправка тестового уведомления
    suspend fun sendTestNotification() {
        try {
            val notificationData = mapOf(
                "app_id" to ONESIGNAL_APP_ID,
                "contents" to mapOf(
                    "en" to "🎉 Тестовое уведомление от лотереи! Система работает отлично!",
                    "ru" to "🎉 Тестовое уведомление от лотереи! Система работает отлично!"
                ),
                "headings" to mapOf(
                    "en" to "🎰 Лотерея - Тест",
                    "ru" to "🎰 Лотерея - Тест"
                ),
                "included_segments" to listOf("Subscribed Users"),
                "data" to mapOf("type" to "test", "screen" to "lottery")
            )

            sendOneSignalNotification(notificationData)
            Log.d(TAG, "✅ Тестовое уведомление отправлено")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки тестового уведомления", e)
        }
    }

    // ИСПРАВЛЕННЫЙ метод forceCreateNewLottery
    suspend fun forceCreateNewLottery(): Boolean {
        return try {
            Log.d(TAG, "🔄 Принудительное создание новой лотереи...")

            // Завершаем текущую активную лотерею если есть
            val activeLotterySnapshot = database.reference.child("simpleLotteries")
                .orderByChild("status")
                .equalTo("ACTIVE")
                .limitToFirst(1)
                .get().await()

            if (activeLotterySnapshot.exists()) {
                val activeLottery = activeLotterySnapshot.children.mapNotNull {
                    it.getValue<SimpleLottery>()?.copy(id = it.key ?: "")
                }.firstOrNull()

                activeLottery?.let {
                    // Завершаем лотерею
                    database.reference.child("simpleLotteries").child(it.id)
                        .child("status").setValue("FINISHED").await()
                    Log.d(TAG, "✅ Завершена текущая лотерея: ${it.id}")
                }
            }

            // Создаем новую лотерею
            val newLotteryId = createNewLottery()

            if (newLotteryId != null) {
                Log.d(TAG, "✅ Новая лотерея принудительно создана: $newLotteryId")
                true
            } else {
                Log.e(TAG, "❌ Не удалось создать новую лотерею")
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка принудительного создания лотереи", e)
            false
        }
    }

    // ИСПРАВЛЕННЫЙ метод для добавления билетов пользователю
    private suspend fun addTicketsToUser(userId: String, userName: String, userEmail: String, ticketCount: Int): Boolean {
        return try {
            Log.d(TAG, "🔄 Добавление $ticketCount билетов для пользователя $userId")

            // Получаем активную лотерею
            val lotterySnapshot = database.reference.child("simpleLotteries")
                .orderByChild("status")
                .equalTo("ACTIVE")
                .limitToFirst(1)
                .get().await()

            if (!lotterySnapshot.exists()) {
                Log.e(TAG, "❌ Активная лотерея не найдена")
                // Создаем новую лотерею
                createNewLottery()
                return false
            }

            val lottery = lotterySnapshot.children.mapNotNull {
                it.getValue<SimpleLottery>()?.copy(id = it.key ?: "")
            }.firstOrNull()

            if (lottery == null) {
                Log.e(TAG, "❌ Лотерея не найдена")
                return false
            }

            Log.d(TAG, "✅ Найдена активная лотерея: ${lottery.id}")

            // Добавляем билеты
            for (i in 1..ticketCount) {
                val ticketId = database.reference.child("lotteryTickets").push().key ?: continue

                val ticket = LotteryTicket(
                    id = ticketId,
                    userId = userId,
                    userName = userName,
                    userEmail = userEmail,
                    purchaseTime = System.currentTimeMillis(),
                    lotteryId = lottery.id,
                    isWinner = false
                )

                database.reference.child("lotteryTickets").child(ticketId).setValue(ticket).await()
                Log.d(TAG, "✅ Добавлен билет $ticketId для лотереи ${lottery.id}")
            }

            // Обновляем призовой фонд
            val ticketPrice = 100.0
            val prizeContribution = ticketCount * ticketPrice * 0.9 // 90% идет в призовой фонд
            val newPrize = lottery.currentPrize + prizeContribution

            database.reference.child("simpleLotteries").child(lottery.id)
                .child("currentPrize").setValue(newPrize).await()

            Log.d(TAG, "✅ Добавлено $ticketCount билетов для пользователя $userId, призовой фонд обновлен: $newPrize")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка добавления билетов", e)
            false
        }
    }

    // Метод для обеспечения активной лотереи
    suspend fun ensureActiveLottery(): Boolean {
        return try {
            val lotterySnapshot = database.reference.child("simpleLotteries")
                .orderByChild("status")
                .equalTo("ACTIVE")
                .limitToFirst(1)
                .get().await()

            if (!lotterySnapshot.exists()) {
                Log.w(TAG, "⚠️ Активная лотерея не найдена, создаем новую...")
                createNewLottery()
                true
            } else {
                Log.d(TAG, "✅ Активная лотерея уже существует")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки активной лотереи", e)
            false
        }
    }
}