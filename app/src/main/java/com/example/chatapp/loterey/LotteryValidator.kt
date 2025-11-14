package com.example.chatapp.loterey

import android.util.Log
import java.util.*

object LotteryValidator {

    private const val TAG = "LotteryValidator"

    // Валидация суммы платежа
    fun validatePaymentAmount(amount: Double): ValidationResult {
        return try {
            when {
                amount < 100 -> ValidationResult.Error("❌ Минимальная сумма 100 рублей")
                amount > 10000 -> ValidationResult.Error("❌ Максимальная сумма 10,000 рублей")
                amount % 100 != 0.0 -> ValidationResult.Error("❌ Сумма должна быть кратной 100 рублям")
                else -> ValidationResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка валидации суммы", e)
            ValidationResult.Error("❌ Неверный формат суммы")
        }
    }

    // Валидация данных пользователя
    fun validateUserData(userId: String, userName: String, userEmail: String): ValidationResult {
        return try {
            when {
                userId.isBlank() -> ValidationResult.Error("❌ Неверный идентификатор пользователя")
                userName.isBlank() -> ValidationResult.Error("❌ Имя пользователя не может быть пустым")
                userEmail.isBlank() || !isValidEmail(userEmail) -> ValidationResult.Error("❌ Неверный формат email")
                else -> ValidationResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка валидации пользователя", e)
            ValidationResult.Error("❌ Ошибка проверки данных пользователя")
        }
    }

    // Валидация данных лотереи
    fun validateLotteryData(lottery: SimpleLottery): ValidationResult {
        return try {
            when {
                lottery.id.isBlank() -> ValidationResult.Error("❌ Неверный ID лотереи")
                lottery.currentPrize < 0 -> ValidationResult.Error("❌ Призовой фонд не может быть отрицательным")
                lottery.ticketPrice <= 0 -> ValidationResult.Error("❌ Цена билета должна быть положительной")
                lottery.endTime <= System.currentTimeMillis() -> ValidationResult.Error("❌ Время окончания лотереи неверно")
                else -> ValidationResult.Success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка валидации лотереи", e)
            ValidationResult.Error("❌ Ошибка проверки данных лотереи")
        }
    }

    // Проверка email
    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // Проверка прав администратора
    fun validateAdminAccess(currentUserId: String): ValidationResult {
        val adminUserId = "4b3dGWLXHNO5LCeD7R8VAbnmnRg1"
        return if (currentUserId == adminUserId) {
            ValidationResult.Success
        } else {
            ValidationResult.Error("❌ Недостаточно прав для выполнения операции")
        }
    }
}

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()

    val isValid: Boolean
        get() = this is Success
}