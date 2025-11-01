package com.example.chatapp.privetstvie_giga

import android.content.Context
import android.util.Log
import com.example.chatapp.utils.MessageStorage
import java.util.*
import kotlin.math.min
import kotlinx.coroutines.*
import kotlin.collections.LinkedHashMap

class SmartContextAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "SmartContextAnalyzer"
        private const val MAX_MESSAGES_TO_ANALYZE = 20
        private const val RECENT_MESSAGES_THRESHOLD = 5

        // Константы для кэширования
        private const val CACHE_SIZE = 10
        private const val DEEP_CONTEXT_CACHE_TTL = 30000L // 30 секунд
        private const val GREETING_CONTEXT_CACHE_TTL = 60000L // 1 минута
    }

    // Кэши для оптимизации
    private val deepContextCache = LinkedHashMap<String, CachedDeepContext>(CACHE_SIZE, 0.75f, true)
    private val greetingContextCache = LinkedHashMap<String, CachedGreetingContext>(CACHE_SIZE, 0.75f, true)

    // Кэш для результатов анализа
    private data class CachedDeepContext(
        val context: DeepConversationContext,
        val timestamp: Long
    )

    private data class CachedGreetingContext(
        val context: GreetingContext,
        val timestamp: Long
    )

    /**
     * Анализирует глубокий контекст с кэшированием (асинхронно)
     */
    suspend fun analyzeDeepContext(): DeepConversationContext = withContext(Dispatchers.Default) {
        val cacheKey = generateCacheKey("deep_context")

        // Проверяем кэш
        deepContextCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < DEEP_CONTEXT_CACHE_TTL) {
                Log.d(TAG, "Using cached deep context")
                return@withContext cached.context
            }
        }

        try {
            Log.d(TAG, "Analyzing deep context in background")
            val messages = withContext(Dispatchers.IO) {
                MessageStorage.loadMessages(context)
            }
            val analyzedContext = analyzeMessagesWithIntelligence(messages)

            // Сохраняем в кэш
            deepContextCache[cacheKey] = CachedDeepContext(analyzedContext, System.currentTimeMillis())
            cleanupCache(deepContextCache, CACHE_SIZE)

            return@withContext analyzedContext
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing deep context", e)
            return@withContext DeepConversationContext()
        }
    }

    /**
     * Анализирует контекст для генерации приветствия с кэшированием (асинхронно)
     */
    suspend fun analyzeGreetingContext(): GreetingContext = withContext(Dispatchers.Default) {
        val cacheKey = generateCacheKey("greeting_context")

        // Проверяем кэш
        greetingContextCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < GREETING_CONTEXT_CACHE_TTL) {
                Log.d(TAG, "Using cached greeting context")
                return@withContext cached.context
            }
        }

        return@withContext try {
            Log.d(TAG, "Analyzing greeting context in background")
            val messages = withContext(Dispatchers.IO) {
                MessageStorage.loadMessages(context)
            }
            val recentMessages = messages.takeLast(RECENT_MESSAGES_THRESHOLD)

            val greetingContext = GreetingContext(
                lastInteractionTime = getLastInteractionTime(messages),
                conversationFrequency = calculateConversationFrequency(messages),
                userMood = analyzeUserMood(recentMessages),
                activeInterests = extractCurrentInterests(messages),
                timeContext = analyzeTimeContext(),
                hasRecentConversation = hasRecentConversation(messages)
            )

            // Сохраняем в кэш
            greetingContextCache[cacheKey] = CachedGreetingContext(greetingContext, System.currentTimeMillis())
            cleanupCache(greetingContextCache, CACHE_SIZE)

            greetingContext
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing greeting context", e)
            GreetingContext()
        }
    }

    /**
     * Быстрый анализ для немедленного использования (ограниченный набор данных)
     */
    suspend fun analyzeQuickContext(): QuickContext = withContext(Dispatchers.Default) {
        return@withContext try {
            val messages = withContext(Dispatchers.IO) {
                MessageStorage.loadMessages(context)
            }

            QuickContext(
                hasRecentActivity = hasRecentConversation(messages),
                lastInteractionTime = getLastInteractionTime(messages),
                messageCount = messages.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in quick context analysis", e)
            QuickContext()
        }
    }

    /**
     * Предварительная загрузка и кэширование данных (для ускорения последующих операций)
     */
    suspend fun preloadContextData() = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Preloading context data")

            // Параллельная предзагрузка разных типов контекста
            val deepContextDeferred = async { analyzeDeepContext() }
            val greetingContextDeferred = async { analyzeGreetingContext() }

            // Ожидаем завершения
            deepContextDeferred.await()
            greetingContextDeferred.await()

            Log.d(TAG, "Context data preloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading context data", e)
        }
    }

    /**
     * Очистка кэша (например, при выходе пользователя)
     */
    fun clearCache() {
        deepContextCache.clear()
        greetingContextCache.clear()
        Log.d(TAG, "Context analyzer cache cleared")
    }

    /**
     * Основной анализ сообщений с интеллектом (в фоне)
     */
    private fun analyzeMessagesWithIntelligence(messages: List<GigaMessage>): DeepConversationContext {
        val context = DeepConversationContext()

        if (messages.isEmpty()) {
            context.conversationDepth = "new_user"
            return context
        }

        try {
            // Ограничиваем количество анализируемых сообщений для производительности
            val recentMessages = messages.takeLast(min(messages.size, MAX_MESSAGES_TO_ANALYZE))

            // 1. Анализ тем с приоритетами
            context.activeTopics = extractWeightedTopics(recentMessages)

            // 2. Эмоциональный анализ
            context.emotionalState = analyzeEmotionalContext(recentMessages)

            // 3. Временной контекст
            context.timeContext = analyzeBehavioralTimeContext(messages)

            // 4. Глубина разговора
            context.conversationDepth = analyzeConversationDepth(messages)

            // 5. Незавершенные темы
            context.pendingDiscussions = findNaturalContinuations(recentMessages)

            // 6. Интересы пользователя
            context.userInterests = extractDynamicInterests(recentMessages)

            // 7. Активность и вовлеченность
            context.engagementLevel = analyzeEngagementLevel(messages)

            // 8. Стиль общения
            context.communicationStyle = analyzeCommunicationStyle(recentMessages)

        } catch (e: Exception) {
            Log.e(TAG, "Error in context analysis", e)
            // Возвращаем базовый контекст в случае ошибки
            context.conversationDepth = "new"
            context.emotionalState = EmotionalState()
            context.timeContext = TimeContext()
        }

        return context
    }

    private fun extractWeightedTopics(messages: List<GigaMessage>): List<ActiveTopic> {
        val topicWeights = mutableMapOf<String, Double>()
        val userMessages = messages.filter { it.isUser }

        // РАСШИРЕННАЯ система тем с весами
        val topicPatterns = mapOf(
            "работа" to listOf("работа", "проект", "задача", "карьера", "начальник", "коллеги", "офис", "встреча", "презентация",
                "должность", "профессия", "труд", "занятость", "вакансия", "интервью", "компания", "бизнес"),
            "семья" to listOf("семья", "дети", "муж", "жена", "родители", "супруг", "ребёнок", "мама", "папа"),
            "хобби" to listOf("хобби", "увлечение", "творчество", "рукоделие", "коллекционирование", "рисую", "фотография", "садоводство"),
            "спорт" to listOf("спорт", "тренировка", "фитнес", "йога", "плавание", "бег", "зал", "упражнения", "активность"),
            "путешествия" to listOf("путешествие", "отпуск", "поездка", "отель", "билет", "тур", "страна", "город", "маршрут"),
            "технологии" to listOf("технологии", "компьютер", "смартфон", "интернет", "программирование", "код", "AI", "робот"),
            "образование" to listOf("учеба", "курсы", "книги", "обучение", "знания", "университет", "школа", "экзамен"),
            "кулинария" to listOf("кулинария", "готовка", "рецепт", "еда", "блюдо", "кухня", "ингредиенты", "печь", "варить"),
            "здоровье" to listOf("здоровье", "болезнь", "врач", "самочувствие", "лечение", "аптека", "сон", "энергия", "усталость"),
            "финансы" to listOf("деньги", "зарплата", "покупка", "бюджет", "инвестиции", "кредит", "сбережения", "доход")
        )

        userMessages.forEachIndexed { index, message ->
            val text = message.text.lowercase()
            val recencyWeight = 1.0 + (index.toDouble() / userMessages.size.coerceAtLeast(1))
            val lengthWeight = if (text.length > 20) 1.2 else 1.0

            topicPatterns.forEach { (topic, keywords) ->
                keywords.forEach { keyword ->
                    if (text.contains(keyword)) {
                        val currentWeight = topicWeights.getOrDefault(topic, 0.0)
                        topicWeights[topic] = currentWeight + (1.0 * recencyWeight * lengthWeight)
                    }
                }
            }

            // Дополнительный анализ: вопросы увеличивают вес темы
            if (text.contains("?")) {
                topicWeights.forEach { (topic, weight) ->
                    if (text.contains(topic)) {
                        topicWeights[topic] = weight + 0.5
                    }
                }
            }
        }

        return topicWeights.entries
            .filter { it.value >= 0.5 }
            .sortedByDescending { it.value }
            .take(5)
            .map {
                ActiveTopic(
                    name = it.key,
                    weight = it.value,
                    lastMention = System.currentTimeMillis(),
                    context = extractTopicContext(it.key, userMessages),
                    isRecent = it.value > 1.0
                )
            }
    }

    private fun analyzeEmotionalContext(messages: List<GigaMessage>): EmotionalState {
        val userMessages = messages.filter { it.isUser }
        if (userMessages.isEmpty()) return EmotionalState()

        var positiveScore = 0.0
        var negativeScore = 0.0
        var questionScore = 0.0
        var energyLevel = 0.0
        var messageLengthTotal = 0

        // Индикаторы эмоций
        val positiveIndicators = listOf("рад", "счастлив", "хорошо", "отлично", "спасибо", "прекрасно", "замечательно", "восхитительно", "улыбка", "люблю")
        val negativeIndicators = listOf("плохо", "грустно", "устал", "проблема", "сложно", "ужасно", "кошмар", "тяжело", "разочарован", "злюсь")

        userMessages.takeLast(8).forEach { message ->
            val text = message.text.lowercase()
            messageLengthTotal += text.length

            // Эмоциональная окраска
            positiveIndicators.forEach { word ->
                if (text.contains(word)) positiveScore += 1.0
            }

            negativeIndicators.forEach { word ->
                if (text.contains(word)) negativeScore += 1.0
            }

            // Энергия сообщения
            if (text.contains("!")) energyLevel += 0.3
            if (text.contains("!!")) energyLevel += 0.5
            if (text.contains("???")) energyLevel += 0.4
            if (text.contains("😊") || text.contains("😄") || text.contains("😍") || text.contains("🥰")) energyLevel += 0.6
            if (text.contains("😢") || text.contains("😞") || text.contains("😩")) energyLevel -= 0.3

            // Вопросы и восклицания
            if (text.contains("?")) questionScore += 1.0
            if (text.contains("!")) energyLevel += 0.2

            // Длина сообщения как показатель вовлеченности
            if (text.length > 50) energyLevel += 0.2
        }

        val avgMessageLength = messageLengthTotal.toDouble() / userMessages.size.coerceAtLeast(1)
        val emotionalBalance = positiveScore - negativeScore

        return EmotionalState(
            mood = when {
                emotionalBalance > 1.0 -> "positive"
                emotionalBalance < -1.0 -> "negative"
                else -> "neutral"
            },
            energyLevel = when {
                energyLevel > 2.0 -> "high"
                energyLevel > 1.0 -> "medium"
                else -> "low"
            },
            isInquisitive = questionScore > userMessages.size * 0.3,
            emotionalScore = emotionalBalance,
            engagementLevel = when {
                avgMessageLength > 80 -> "high"
                avgMessageLength > 40 -> "medium"
                else -> "low"
            }
        )
    }

    private fun analyzeBehavioralTimeContext(messages: List<GigaMessage>): TimeContext {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        // Анализ времени активности пользователя
        val userActiveHours = mutableListOf<Int>()
        val userActiveDays = mutableListOf<Int>()

        messages.takeLast(30).forEach { message ->
            val msgCalendar = Calendar.getInstance().apply { timeInMillis = message.timestamp }
            userActiveHours.add(msgCalendar.get(Calendar.HOUR_OF_DAY))
            userActiveDays.add(msgCalendar.get(Calendar.DAY_OF_WEEK))
        }

        val preferredTime = calculatePreferredTime(userActiveHours)
        val preferredDay = calculatePreferredDay(userActiveDays)

        return TimeContext(
            timeOfDay = when (hour) {
                in 5..11 -> "morning"
                in 12..17 -> "day"
                in 18..22 -> "evening"
                else -> "night"
            },
            isWeekend = isWeekend,
            userPreferredTime = preferredTime,
            season = getCurrentSeason(),
            isUserActiveNow = userActiveHours.contains(hour),
            preferredDayType = if (preferredDay in listOf(Calendar.SATURDAY, Calendar.SUNDAY)) "weekend" else "weekday"
        )
    }

    private fun calculatePreferredTime(hours: List<Int>): String {
        if (hours.isEmpty()) return "day"
        val hourCount = IntArray(24)
        hours.forEach { hour -> hourCount[hour]++ }
        val mostFrequentHour = hourCount.indices.maxByOrNull { hourCount[it] } ?: 12
        return when (mostFrequentHour) {
            in 5..11 -> "morning"
            in 12..17 -> "day"
            in 18..22 -> "evening"
            else -> "night"
        }
    }

    private fun calculatePreferredDay(days: List<Int>): Int {
        if (days.isEmpty()) return Calendar.MONDAY
        val dayCount = IntArray(7)
        days.forEach { day -> dayCount[day - 1]++ }
        return dayCount.indices.maxByOrNull { dayCount[it] }?.plus(1) ?: Calendar.MONDAY
    }

    private fun analyzeConversationDepth(messages: List<GigaMessage>): String {
        if (messages.size < 3) return "new"
        val topicChanges = countTopicChanges(messages)
        val averageLength = messages.map { it.text.length }.average()
        val responseTime = calculateAverageResponseTime(messages)

        return when {
            messages.size > 20 && averageLength > 80 && responseTime < 120000 -> "deep"
            messages.size > 10 && topicChanges < messages.size / 4 -> "focused"
            messages.size > 5 -> "developing"
            else -> "new"
        }
    }

    private fun calculateAverageResponseTime(messages: List<GigaMessage>): Long {
        var totalTime = 0L
        var responseCount = 0

        for (i in 1 until messages.size) {
            if (messages[i].isUser != messages[i-1].isUser) {
                totalTime += messages[i].timestamp - messages[i-1].timestamp
                responseCount++
            }
        }

        return if (responseCount > 0) totalTime / responseCount else 0
    }

    private fun findNaturalContinuations(messages: List<GigaMessage>): List<PendingDiscussion> {
        val continuations = mutableListOf<PendingDiscussion>()
        if (messages.size < 2) return continuations

        val recentPairs = messages.takeLast(5).windowed(2, 1)
        for ((prev, curr) in recentPairs) {
            if (!prev.isUser && curr.isUser && curr.text.contains("?")) {
                // Пользователь задал вопрос после ответа — возможно, не получил полного ответа
                val topic = extractMainTopic(curr.text)
                continuations.add(PendingDiscussion(
                    type = "unanswered_question",
                    topic = topic,
                    context = curr.text.take(100),
                    timestamp = curr.timestamp,
                    priority = "high"
                ))
            } else if (prev.isUser && !curr.isUser && !prev.text.endsWith(".")) {
                // Пользователь оборвал мысль — можно продолжить
                val topic = extractMainTopic(prev.text)
                continuations.add(PendingDiscussion(
                    type = "natural_continuation",
                    topic = topic,
                    context = prev.text.take(100),
                    timestamp = prev.timestamp,
                    priority = "medium"
                ))
            }
        }

        return continuations.sortedByDescending { it.priority }.take(3)
    }

    private fun extractMainTopic(text: String): String {
        val topics = listOf("работа", "семья", "здоровье", "хобби", "путешествия", "спорт", "финансы", "образование")
        return topics.firstOrNull { text.lowercase().contains(it) } ?: "общее"
    }

    private fun extractDynamicInterests(messages: List<GigaMessage>): List<UserInterest> {
        val interestScores = mutableMapOf<String, Double>()
        val interestKeywords = mapOf(
            "работа" to listOf("работа", "проект", "карьера"),
            "семья" to listOf("семья", "дети", "муж", "жена"),
            "хобби" to listOf("хобби", "увлечение", "творчество"),
            "спорт" to listOf("спорт", "тренировка", "фитнес"),
            "путешествия" to listOf("путешествие", "отпуск", "поездка"),
            "технологии" to listOf("технологии", "компьютер", "AI"),
            "образование" to listOf("учеба", "курсы", "книги"),
            "кулинария" to listOf("готовка", "рецепт", "еда"),
            "здоровье" to listOf("здоровье", "врач", "самочувствие"),
            "финансы" to listOf("деньги", "бюджет", "инвестиции")
        )

        messages.filter { it.isUser }.takeLast(15).forEach { msg ->
            val text = msg.text.lowercase()
            interestKeywords.forEach { (interest, words) ->
                words.forEach { word ->
                    if (text.contains(word)) {
                        interestScores[interest] = interestScores.getOrDefault(interest, 0.0) + 1.0
                    }
                }
            }
        }

        return interestScores.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { UserInterest(it.key, it.value.toInt(), getInterestEngagementLevel(it.value)) }
    }

    private fun analyzeEngagementLevel(messages: List<GigaMessage>): EngagementLevel {
        if (messages.isEmpty()) return EngagementLevel()
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
        val todayMessages = messages.filter { it.timestamp >= today }

        val responseTimes = mutableListOf<Long>()
        for (i in 1 until messages.size.coerceAtMost(15)) {
            if (messages[i].isUser != messages[i-1].isUser) {
                responseTimes.add(messages[i].timestamp - messages[i-1].timestamp)
            }
        }

        val avgResponseTime = if (responseTimes.isNotEmpty()) {
            responseTimes.average().toLong().coerceAtMost(300000)
        } else 0

        val conversationLength = messages.size
        val activeDays = calculateActiveDays(messages)

        return EngagementLevel(
            messagesToday = todayMessages.size,
            avgResponseTime = avgResponseTime,
            isActiveToday = todayMessages.isNotEmpty(),
            conversationLength = conversationLength,
            activeDays = activeDays,
            engagementScore = calculateEngagementScore(todayMessages.size, avgResponseTime, conversationLength)
        )
    }

    private fun analyzeCommunicationStyle(messages: List<GigaMessage>): CommunicationStyle {
        val userMessages = messages.filter { it.isUser }
        if (userMessages.isEmpty()) return CommunicationStyle()

        val avgLength = userMessages.map { it.text.length }.average()
        val questionCount = userMessages.count { it.text.contains("?") }
        val detailCount = userMessages.count { it.text.length > 50 }
        val emotionalCount = userMessages.count {
            it.text.contains(Regex("""!\s*$|😊|😢|😂|❤️|🔥|🎉|👍|👏|💖|💯"""))
        }

        return CommunicationStyle(
            asksQuestions = questionCount > userMessages.size * 0.3,
            providesDetails = detailCount > userMessages.size * 0.2,
            emotional = emotionalCount > userMessages.size * 0.15,
            messageLength = avgLength.toInt(),
            communicationFrequency = when (userMessages.size) {
                in 0..5 -> "new"
                in 6..20 -> "occasional"
                else -> "frequent"
            }
        )
    }

    // Методы для анализа приветственного контекста
    private fun getLastInteractionTime(messages: List<GigaMessage>): Long {
        return messages.lastOrNull()?.timestamp ?: 0L
    }

    private fun calculateConversationFrequency(messages: List<GigaMessage>): String {
        if (messages.size < 3) return "new"
        val recentMessages = messages.filter {
            it.timestamp > System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        }
        return when (recentMessages.size) {
            in 0..2 -> "rare"
            in 3..10 -> "occasional"
            else -> "frequent"
        }
    }

    private fun analyzeUserMood(messages: List<GigaMessage>): String {
        val userMessages = messages.filter { it.isUser }
        if (userMessages.isEmpty()) return "neutral"

        var positive = 0
        var negative = 0

        userMessages.forEach { message ->
            val text = message.text.lowercase()
            if (text.contains(Regex("""рад|счастлив|хорошо|отлично|спасибо|прекрасно|замечательно|восхитительно"""))) positive++
            if (text.contains(Regex("""плохо|грустно|устал|проблема|сложно|ужасно|кошмар|тяжело"""))) negative++
        }

        return when {
            positive > negative -> "positive"
            negative > positive -> "negative"
            else -> "neutral"
        }
    }

    private fun extractCurrentInterests(messages: List<GigaMessage>): List<String> {
        val interests = mutableSetOf<String>()
        val recentMessages = messages.takeLast(10)

        val interestKeywords = mapOf(
            "работа" to listOf("работа", "проект", "задача", "карьера", "должность"),
            "семья" to listOf("семья", "дети", "муж", "жена", "родители", "супруг"),
            "хобби" to listOf("хобби", "увлечение", "творчество", "рукоделие", "коллекционирование"),
            "спорт" to listOf("спорт", "тренировка", "фитнес", "йога", "плавание", "бег"),
            "путешествия" to listOf("путешествие", "отпуск", "поездка", "отель", "билет", "тур"),
            "технологии" to listOf("технологии", "компьютер", "смартфон", "интернет", "программирование"),
            "образование" to listOf("учеба", "курсы", "книги", "обучение", "знания"),
            "кулинария" to listOf("кулинария", "готовка", "рецепт", "еда", "блюдо"),
            "здоровье" to listOf("здоровье", "болезнь", "врач", "самочувствие", "лечение"),
            "финансы" to listOf("деньги", "зарплата", "покупка", "бюджет", "инвестиции")
        )

        recentMessages.forEach { message ->
            val text = message.text.lowercase()
            interestKeywords.forEach { (interest, keywords) ->
                if (keywords.any { text.contains(it) }) {
                    interests.add(interest)
                }
            }
        }

        return interests.toList()
    }

    private fun analyzeTimeContext(): TimeContext {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        return TimeContext(
            timeOfDay = when (hour) {
                in 5..11 -> "morning"
                in 12..17 -> "day"
                in 18..22 -> "evening"
                else -> "night"
            },
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            userPreferredTime = "day",
            season = getCurrentSeason(),
            isUserActiveNow = true
        )
    }

    private fun hasRecentConversation(messages: List<GigaMessage>): Boolean {
        val lastMessageTime = messages.lastOrNull()?.timestamp ?: 0L
        return System.currentTimeMillis() - lastMessageTime < 2 * 60 * 60 * 1000 // 2 часа
    }

    // Вспомогательные методы
    private fun countTopicChanges(messages: List<GigaMessage>): Int {
        var changes = 0
        val topics = listOf("работа", "семья", "хобби", "спорт", "здоровье", "путешествия", "технологии", "образование", "финансы")

        for (i in 1 until messages.size.coerceAtMost(15)) {
            if (messages[i].isUser && messages[i-1].isUser) {
                val prevTopic = topics.firstOrNull { messages[i-1].text.contains(it) }
                val currTopic = topics.firstOrNull { messages[i].text.contains(it) }
                if (prevTopic != null && currTopic != null && prevTopic != currTopic) {
                    changes++
                }
            }
        }
        return changes
    }

    private fun extractTopicContext(topic: String, messages: List<GigaMessage>): String {
        val relevantMessages = messages.filter { it.text.lowercase().contains(topic) }
        return if (relevantMessages.isNotEmpty()) {
            relevantMessages.last().text.take(100)
        } else {
            ""
        }
    }

    private fun getInterestEngagementLevel(score: Double): String {
        return when {
            score > 3 -> "high"
            score > 1 -> "medium"
            else -> "low"
        }
    }

    private fun calculateActiveDays(messages: List<GigaMessage>): Int {
        val days = mutableSetOf<Long>()
        messages.forEach { message ->
            val calendar = Calendar.getInstance().apply { timeInMillis = message.timestamp }
            val day = calendar.get(Calendar.DAY_OF_YEAR) + calendar.get(Calendar.YEAR) * 1000
            days.add(day.toLong())
        }
        return days.size
    }

    private fun calculateEngagementScore(messagesToday: Int, avgResponseTime: Long, totalMessages: Int): Int {
        var score = 0
        if (messagesToday > 5) score += 3
        else if (messagesToday > 2) score += 1

        if (avgResponseTime < 60000) score += 2
        else if (avgResponseTime < 180000) score += 1

        if (totalMessages > 20) score += 2
        else if (totalMessages > 10) score += 1

        return score.coerceIn(0, 7)
    }

    private fun getCurrentSeason(): String {
        val month = Calendar.getInstance().get(Calendar.MONTH)
        return when (month) {
            in 2..4 -> "spring"
            in 5..7 -> "summer"
            in 8..10 -> "autumn"
            else -> "winter"
        }
    }

    // Методы для управления кэшем
    private fun generateCacheKey(type: String): String {
        return "${type}_${System.currentTimeMillis() / 60000}" // Ключ обновляется каждую минуту
    }

    private fun <K, V> cleanupCache(cache: LinkedHashMap<K, V>, maxSize: Int) {
        while (cache.size > maxSize) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
        }
    }

    // Вспомогательные методы для отладки
    suspend fun debugContextAnalysis(messages: List<GigaMessage>): String = withContext(Dispatchers.Default) {
        val context = analyzeMessagesWithIntelligence(messages)

        return@withContext """
            === CONTEXT ANALYSIS DEBUG ===
            Active topics: ${context.activeTopics.size}
            ${context.activeTopics.joinToString { "${it.name} (${it.weight})" }}
            Emotional state: ${context.emotionalState.mood}, energy: ${context.emotionalState.energyLevel}
            Time context: ${context.timeContext.timeOfDay}, weekend: ${context.timeContext.isWeekend}
            Conversation depth: ${context.conversationDepth}
            Pending discussions: ${context.pendingDiscussions.size}
            Engagement: today=${context.engagementLevel.messagesToday}, total=${context.engagementLevel.conversationLength}
            Communication style: questions=${context.communicationStyle.asksQuestions}, details=${context.communicationStyle.providesDetails}
            ==============================
        """.trimIndent()
    }
}

// Data classes

data class QuickContext(
    val hasRecentActivity: Boolean = false,
    val lastInteractionTime: Long = 0L,
    val messageCount: Int = 0
)

data class GreetingContext(
    val lastInteractionTime: Long = 0L,
    val conversationFrequency: String = "new",
    val userMood: String = "neutral",
    val activeInterests: List<String> = emptyList(),
    val timeContext: TimeContext = TimeContext(),
    val hasRecentConversation: Boolean = false
)

data class DeepConversationContext(
    var activeTopics: List<ActiveTopic> = emptyList(),
    var emotionalState: EmotionalState = EmotionalState(),
    var communicationStyle: CommunicationStyle = CommunicationStyle(),
    var timeContext: TimeContext = TimeContext(),
    var conversationDepth: String = "new",
    var pendingDiscussions: List<PendingDiscussion> = emptyList(),
    var userInterests: List<UserInterest> = emptyList(),
    var engagementLevel: EngagementLevel = EngagementLevel()
)

data class ActiveTopic(
    val name: String,
    val weight: Double,
    val lastMention: Long,
    val context: String = "",
    val isRecent: Boolean = false
)

data class EmotionalState(
    val mood: String = "neutral",
    val energyLevel: String = "medium",
    val isInquisitive: Boolean = false,
    val emotionalScore: Double = 0.0,
    val engagementLevel: String = "medium"
)

data class CommunicationStyle(
    val asksQuestions: Boolean = false,
    val providesDetails: Boolean = false,
    val emotional: Boolean = false,
    val messageLength: Int = 0,
    val communicationFrequency: String = "new"
)

data class TimeContext(
    val timeOfDay: String = "day",
    val isWeekend: Boolean = false,
    val userPreferredTime: String = "day",
    val season: String = "unknown",
    val isUserActiveNow: Boolean = false,
    val preferredDayType: String = "weekday"
)

data class PendingDiscussion(
    val type: String,
    val topic: String,
    val context: String,
    val timestamp: Long,
    val priority: String = "normal"
)

data class UserInterest(
    val name: String,
    val frequency: Int,
    val engagement: String = "medium"
)

data class EngagementLevel(
    val messagesToday: Int = 0,
    val avgResponseTime: Long = 0,
    val isActiveToday: Boolean = false,
    val conversationLength: Int = 0,
    val activeDays: Int = 0,
    val engagementScore: Int = 0
)