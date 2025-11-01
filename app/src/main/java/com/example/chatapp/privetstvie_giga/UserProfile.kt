package com.example.chatapp.privetstvie_giga

import java.util.*

data class UserProfile(
    val userId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis(),

    // Основная информация
    var gender: String = "",
    var birthYear: Int = 0,

    // Семейное положение
    var relationshipStatus: String = "",

    // Профессия
    var occupation: String = "",
    var jobTitle: String = "",
    var workSchedule: String = "",
    var workStartTime: String = "",
    var workEndTime: String = "",

    // Образ жизни
    var hobbies: String = "", // Изменено на String
    var interests: String = "", // Изменено на String
    var sports: String = "", // Изменено на String

    // Привычки
    var wakeUpTime: String = "",
    var sleepQuality: String = "",
    var workoutFrequency: String = "",
    var readingHabit: String = "",
    var workoutTypes: String = "", // Изменено на String

    // Предпочтения
    var musicPreferences: String = "",
    var movieGenres: String = "",
    var foodPreferences: String = "",
    var favoriteSeasons: String = "",
    var favoriteCuisines: String = "", // Изменено на String

    // Цели
    var currentGoals: String = "", // Изменено на String
    var learningInterests: String = "", // Изменено на String

    // Личностные характеристики
    var personalityType: String = "",
    var communicationStyle: String = "",
    var stressManagement: String = "",
    var socialActivity: String = "",
    var learningStyle: String = "",

    // Контактная информация
    var city: String = "",
    var dailyCommuteTime: Int = 0,

    // Семья и домашние условия
    var hasChildren: Boolean = false,
    var childrenAges: String = "", // Изменено на String
    var hasPets: Boolean = false,
    var petTypes: String = "",

    // Новые поля для умной персонализации
    var fitnessLevel: String = "",
    var travelFrequency: String = "",
    var cookingHabit: String = "",
    var weekendActivities: String = "" // Изменено на String
) {
    fun isProfileComplete(): Boolean {
        return gender.isNotEmpty() &&
                relationshipStatus.isNotEmpty() &&
                occupation.isNotEmpty() &&
                workSchedule.isNotEmpty()
    }

    fun getAge(): Int {
        return if (birthYear > 0) {
            Calendar.getInstance().get(Calendar.YEAR) - birthYear
        } else 0
    }

    // Вспомогательные методы для преобразования строк в списки
    fun getHobbiesList(): List<String> {
        return hobbies.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getInterestsList(): List<String> {
        return interests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getSportsList(): List<String> {
        return sports.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getWorkoutTypesList(): List<String> {
        return workoutTypes.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getFavoriteCuisinesList(): List<String> {
        return favoriteCuisines.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getCurrentGoalsList(): List<String> {
        return currentGoals.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getLearningInterestsList(): List<String> {
        return learningInterests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getChildrenAgesList(): List<Int> {
        return childrenAges.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
    }

    fun getWeekendActivitiesList(): List<String> {
        return weekendActivities.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Персональное приветствие
    fun getPersonalizedGreeting(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..23 -> "Добрый вечер"
            else -> "Доброй ночи"
        }

        return when {
            personalityType.isNotEmpty() && communicationStyle.isNotEmpty() ->
                "$greeting! Как ${getPersonalityBasedQuestion()}"
            hobbies.isNotEmpty() ->
                "$greeting! Как ваши увлечения ${getHobbiesList().firstOrNull()}?"
            occupation.isNotEmpty() ->
                "$greeting! Как продвигается работа в сфере $occupation?"
            else -> "$greeting! Как ваши дела?"
        }
    }

    private fun getPersonalityBasedQuestion(): String {
        return when (personalityType.lowercase()) {
            "интроверт" -> "проходит ваш спокойный день?"
            "экстраверт" -> "ваше общение сегодня?"
            "амбиверт" -> "ваш сбалансированный день?"
            else -> "ваш день?"
        }
    }

    // Рекомендованный стиль общения
    fun getRecommendedCommunicationStyle(): String {
        return when (communicationStyle.lowercase()) {
            "формальный" -> "Уважаемый пользователь, чем могу помочь?"
            "юмористический" -> "Привет! Готов пошутить и помочь! 😄"
            "серьезный" -> "Здравствуйте. Чем могу быть полезен?"
            else -> "Привет! Как дела?"
        }
    }

    fun getTopicsForDiscussion(): List<String> {
        val topics = mutableListOf<String>()

        if (hobbies.isNotEmpty()) topics.addAll(getHobbiesList())
        if (interests.isNotEmpty()) topics.addAll(getInterestsList())
        if (musicPreferences.isNotEmpty()) topics.add("Музыка: $musicPreferences")
        if (movieGenres.isNotEmpty()) topics.add("Кино: $movieGenres")
        if (learningInterests.isNotEmpty()) topics.addAll(getLearningInterestsList())

        return topics.distinct()
    }

    // Умное контекстное приветствие
    fun getContextualGreeting(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

        val greeting = when (hour) {
            in 5..11 -> "Доброе утро"
            in 12..17 -> "Добрый день"
            in 18..23 -> "Добрый вечер"
            else -> "Доброй ночи"
        }

        val contextualPart = when {
            // Утренний контекст
            hour in 5..11 -> when {
                hasChildren && !isWeekend -> "Как настроение перед новым днем? Дети готовы к школе?"
                hasChildren && isWeekend -> "Прекрасное утро выходного дня! Какие планы с семьей?"
                fitnessLevel.isNotEmpty() -> "Готовы к новым достижениям? Планируете тренировку?"
                workStartTime.isNotEmpty() && !isWeekend -> "Собираетесь на работу? Какие планы на день?"
                else -> "Какие планы на сегодня?"
            }
            // Вечерний контекст
            hour in 18..23 -> when {
                hasChildren -> "Как прошел день? Дети уже спят?"
                fitnessLevel.isNotEmpty() -> "Как прошел день? Удалось позаниматься?"
                workEndTime.isNotEmpty() && !isWeekend -> "Отдохнули после работы? Сложный был день?"
                else -> "Как прошел ваш день?"
            }
            // Дневной контекст
            else -> when {
                hasChildren && hour in 15..17 -> "Как день? Дети уже дома из школы?"
                fitnessLevel.isNotEmpty() && hour in 14..16 -> "Есть планы на тренировку? Как энергия?"
                else -> "Как проходит ваш день?"
            }
        }

        return "$greeting! $contextualPart"
    }

    // Утренние вопросы
    fun getMorningQuestions(): List<String> {
        val questions = mutableListOf<String>()
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

        // Базовые утренние вопросы
        questions.addAll(listOf(
            "Как вы спали? Выспались?",
            "Какие планы на сегодня?",
            "Как настроение с утра?",
            "Что на завтрак?",
            "Какая погода за окном?"
        ))

        // Вопросы для родителей
        if (hasChildren) {
            val childQuestions = when {
                getChildrenAgesList().any { it in 0..3 } -> listOf(
                    "Как спал малыш?",
                    "Удалось выспаться с ребенком?",
                    "Какие планы с ребенком на сегодня?",
                    "Во сколько проснулся ребенок?"
                )
                getChildrenAgesList().any { it in 4..12 } -> listOf(
                    "Дети собрались в школу/сад?",
                    "Успели позавтракать с детьми?",
                    "Кто отводит детей сегодня?",
                    "Все ли собрали дети в школу?"
                )
                getChildrenAgesList().any { it in 13..18 } -> listOf(
                    "Подростки проснулись?",
                    "Как успехи детей в школе?",
                    "Планируете что-то с детьми на вечер?",
                    "Есть ли у детей важные занятия сегодня?"
                )
                else -> emptyList()
            }
            questions.addAll(childQuestions)
        }

        // Вопросы для спортсменов
        if (fitnessLevel.isNotEmpty()) {
            val workoutQuestions = when (fitnessLevel.lowercase()) {
                "профессионал", "продвинутый" -> listOf(
                    "Планируете утреннюю тренировку?",
                    "Какие цели на сегодняшнюю тренировку?",
                    "Как самочувствие перед тренировкой?",
                    "Над какими мышцами сегодня работаете?"
                )
                "любитель" -> listOf(
                    "Будете тренироваться сегодня?",
                    "Какой тип тренировки планируете?",
                    "Нужна мотивация для тренировки?",
                    "Какой вес/дистанция сегодня?"
                )
                else -> listOf(
                    "Планируете немного размяться сегодня?",
                    "Может, сделаете утреннюю зарядку?",
                    "Как насчет небольшой прогулки?",
                    "Чувствуете себя бодро с утра?"
                )
            }
            questions.addAll(workoutQuestions)
        }

        // Вопросы о работе
        if (workStartTime.isNotEmpty() && !isWeekend) {
            questions.addAll(listOf(
                "Собираетесь на работу?",
                "Во сколько начинается рабочий день?",
                "Какие задачи планируете на работе?",
                "Предстоят ли важные встречи?"
            ))
        }

        // Вопросы о питомцах
        if (hasPets) {
            questions.addAll(listOf(
                "Как ваш ${if (petTypes.isNotEmpty()) petTypes.split(",").firstOrNull() else "питомец"}?",
                "Успели погулять с питомцем?",
                "Покормили ${if (petTypes.isNotEmpty()) petTypes.split(",").firstOrNull() else "питомца"}?",
                "Как спал ваш питомец?"
            ))
        }

        // Вопросы о готовке
        if (cookingHabit == "часто") {
            questions.addAll(listOf(
                "Что планируете готовить сегодня?",
                "Будете пробовать новые рецепты?",
                "Нужны ли идеи для завтрака?"
            ))
        }

        return questions.distinct()
    }

    // Вечерние вопросы
    fun getEveningQuestions(): List<String> {
        val questions = mutableListOf<String>()
        val isWeekend = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

        // Базовые вечерние вопросы
        questions.addAll(listOf(
            "Как прошел ваш день?",
            "Что интересного сегодня произошло?",
            "Устали после дня?",
            "Какие планы на вечер?",
            "Что будете делать перед сном?"
        ))

        // Вопросы о тренировках вечером
        if (fitnessLevel.isNotEmpty() && workoutTypes.isNotEmpty()) {
            val eveningWorkoutQuestions = when (fitnessLevel.lowercase()) {
                "профессионал", "продвинутый" -> listOf(
                    "Как прошла тренировка? Довольны результатом?",
                    "Удалось достичь поставленных целей?",
                    "Какие мышцы сегодня работали?",
                    "Как самочувствие после тренировки?",
                    "Какие показатели сегодня?"
                )
                "любитель" -> listOf(
                    "Удалось позаниматься сегодня?",
                    "Как прошла тренировка?",
                    "Чувствуете прогресс?",
                    "Планируете завтрашнюю тренировку?",
                    "Насколько интенсивной была тренировка?"
                )
                else -> listOf(
                    "Удалось немного подвигаться сегодня?",
                    "Как насчет легкой вечерней прогулки?",
                    "Чувствуете себя бодрее после активности?",
                    "Получилось сделать зарядку?"
                )
            }
            questions.addAll(eveningWorkoutQuestions)
        }

        // Вопросы для родителей вечером
        if (hasChildren) {
            val eveningChildQuestions = when {
                getChildrenAgesList().any { it in 0..3 } -> listOf(
                    "Как малыш? Уложили спать?",
                    "Удалось отдохнуть пока ребенок спит?",
                    "Каким был день с ребенком?",
                    "Какие новые слова/навыки у ребенка?"
                )
                getChildrenAgesList().any { it in 4..12 } -> listOf(
                    "Дети сделали уроки?",
                    "Во сколько уложили детей спать?",
                    "Как успехи детей сегодня?",
                    "Чем занимались дети после школы?"
                )
                getChildrenAgesList().any { it in 13..18 } -> listOf(
                    "Как подростки? Чем занимались сегодня?",
                    "Удалось пообщаться с детьми?",
                    "Какие у них планы на завтра?",
                    "Есть ли у них важные события?"
                )
                else -> emptyList()
            }
            questions.addAll(eveningChildQuestions)
        }

        // Вопросы о работе вечером
        if (workEndTime.isNotEmpty() && !isWeekend) {
            questions.addAll(listOf(
                "Как прошел рабочий день?",
                "Успели завершить все задачи?",
                "Отдохнули после работы?",
                "Сложный был день?",
                "Что было самым интересным на работе?"
            ))
        }

        // Вопросы о готовке вечером
        if (cookingHabit.isNotEmpty()) {
            questions.addAll(listOf(
                "Что готовили на ужин?",
                "Удалось приготовить что-то вкусное?",
                "Планируете что-то особенное на ужин?",
                "Какой кулинарный эксперимент сегодня?"
            ))
        }

        // Вопросы о выходных
        if (isWeekend) {
            questions.addAll(listOf(
                "Как проходит выходной?",
                "Удалось отдохнуть?",
                "Какие планы на оставшийся выходный?",
                "Что интересного сделали сегодня?"
            ))
        }

        return questions.distinct()
    }

    // Вопросы на основе интересов
    fun getInterestBasedQuestions(): List<String> {
        val questions = mutableListOf<String>()

        // Вопросы на основе хобби
        getHobbiesList().forEach { hobby ->
            when (hobby.lowercase()) {
                "чтение", "книги" -> questions.addAll(listOf(
                    "Что сейчас читаете?",
                    "Открыли для себя новые книги?",
                    "Какой жанр сейчас предпочитаете?",
                    "Какая книга произвела впечатление?"
                ))
                "музыка" -> questions.addAll(listOf(
                    "Какую музыку слушаете в последнее время?",
                    "Открыли новых исполнителей?",
                    "Посещали концерты?",
                    "Есть любимый плейлист?"
                ))
                "путешествия" -> questions.addAll(listOf(
                    "Планируете новые поездки?",
                    "Вспоминаете недавние путешествия?",
                    "Какие места хотите посетить?",
                    "Какой был самый запоминающийся отпуск?"
                ))
                "кино", "фильмы" -> questions.addAll(listOf(
                    "Смотрели что-то интересное?",
                    "Ждете выхода новых фильмов?",
                    "Какой жанр сейчас нравится?",
                    "Какой фильм посоветуете?"
                ))
                "кулинария", "готовка" -> questions.addAll(listOf(
                    "Пробовали новые рецепты?",
                    "Какие блюда сейчас готовите?",
                    "Есть кулинарные эксперименты?",
                    "Что любите готовить больше всего?"
                ))
                "спорт" -> questions.addAll(listOf(
                    "Следите за спортивными событиями?",
                    "Есть любимые команды или спортсмены?",
                    "Планируете посмотреть матчи?",
                    "Какой вид спорта больше нравится?"
                ))
                "рисование", "живопись" -> questions.addAll(listOf(
                    "Рисовали что-то в последнее время?",
                    "Какие техники пробуете?",
                    "Есть любимые художники?",
                    "Что вдохновляет на творчество?"
                ))
                "программирование" -> questions.addAll(listOf(
                    "Над какими проектами работаете?",
                    "Изучаете новые технологии?",
                    "Какой язык программирования нравится?",
                    "Есть интересные задачи?"
                ))
            }
        }

        // Вопросы на основе времени года
        val currentSeason = getCurrentSeason()
        if (currentSeason in favoriteSeasons) {
            questions.addAll(listOf(
                "Нравится $currentSeason?",
                "Чем особенно любите заниматься $currentSeason?",
                "Планируете что-то особенное в этот сезон?",
                "Что больше всего нравится в $currentSeason?"
            ))
        }

        // Вопросы на основе музыкальных предпочтений
        if (musicPreferences.isNotEmpty()) {
            questions.addAll(listOf(
                "Часто слушаете $musicPreferences?",
                "Есть любимые исполнители в этом жанре?",
                "Открыли новую музыку в последнее время?"
            ))
        }

        return questions.distinct()
    }

    // Контекстные вопросы на основе времени
    fun getTimeContextualQuestions(): List<String> {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

        val questions = mutableListOf<String>()

        // Утренние вопросы (5-11)
        if (hour in 5..11) {
            questions.addAll(listOf(
                "Как планируете начать день?",
                "Что на завтрак?",
                "Какие задачи самые важные сегодня?",
                "Удалось выспаться?"
            ))

            if (!isWeekend && workStartTime.isNotEmpty()) {
                questions.add("Собираетесь на работу? Как настроение?")
            }

            if (fitnessLevel.isNotEmpty() && fitnessLevel != "Не занимаюсь спортом") {
                questions.add("Планируете утреннюю тренировку?")
            }
        }

        // Дневные вопросы (12-17)
        if (hour in 12..17) {
            questions.addAll(listOf(
                "Как проходит день?",
                "Успели пообедать?",
                "Есть перерыв в работе?",
                "Какие новости за сегодня?"
            ))

            if (fitnessLevel.isNotEmpty() && hour in 14..16) {
                questions.add("Планируете дневную тренировку?")
            }
        }

        // Вечерние вопросы (18-23)
        if (hour in 18..23) {
            questions.addAll(listOf(
                "Как прошел день?",
                "Устали после работы?",
                "Какие планы на вечер?",
                "Что было самым интересным сегодня?"
            ))

            if (isWeekend) {
                questions.add("Как проводите выходной?")
            }

            if (cookingHabit == "часто") {
                questions.add("Что планируете на ужин?")
            }
        }

        // Ночные вопросы (0-4)
        if (hour in 0..4) {
            questions.addAll(listOf(
                "Поздно засиделись? Все в порядке?",
                "Не можете уснуть?",
                "Работаете или отдыхаете?",
                "Что вас беспокоит?"
            ))
        }

        return questions
    }

    // Случайный контекстный вопрос
    fun getRandomContextualQuestion(): String {
        val allQuestions = mutableListOf<String>()

        // Добавляем вопросы на основе времени
        allQuestions.addAll(getTimeContextualQuestions())

        // Добавляем вопросы на основе интересов
        allQuestions.addAll(getInterestBasedQuestions())

        // Добавляем общие вопросы
        allQuestions.addAll(listOf(
            "Как ваше настроение?",
            "Что нового в вашей жизни?",
            "Есть ли что-то, что вас сейчас вдохновляет?",
            "Какие маленькие радости были сегодня?",
            "О чем мечтаете в последнее время?",
            "Чем планируете заняться в ближайшее время?",
            "Есть ли что-то, что вас беспокоит?"
        ))

        return if (allQuestions.isNotEmpty()) allQuestions.random() else "Как ваши дела?"
    }

    // Промпт для AI с полной информацией о пользователе
    fun getPersonalizedChatPrompt(): String {
        val prompt = StringBuilder()

        prompt.append("Ты - персональный ассистент, который знает пользователя очень хорошо. Используй всю информацию для максимально персонализированного общения.\n\n")

        prompt.append("ИНФОРМАЦИЯ О ПОЛЬЗОВАТЕЛЕ:\n")

        // Основная информация
        if (gender.isNotEmpty()) prompt.append("- Пол: $gender\n")
        if (getAge() > 0) prompt.append("- Возраст: ${getAge()} лет\n")
        if (relationshipStatus.isNotEmpty()) prompt.append("- Семейное положение: $relationshipStatus\n")
        if (city.isNotEmpty()) prompt.append("- Город: $city\n")

        // Профессия
        if (occupation.isNotEmpty()) prompt.append("- Профессия: $occupation\n")
        if (jobTitle.isNotEmpty()) prompt.append("- Должность: $jobTitle\n")
        if (workSchedule.isNotEmpty()) prompt.append("- График работы: $workSchedule\n")

        // Семья
        if (hasChildren) {
            prompt.append("- Есть дети: да\n")
            if (childrenAges.isNotEmpty()) prompt.append("- Возраст детей: $childrenAges\n")
        }
        if (hasPets) prompt.append("- Питомцы: $petTypes\n")

        // Хобби и интересы
        if (hobbies.isNotEmpty()) prompt.append("- Хобби: $hobbies\n")
        if (interests.isNotEmpty()) prompt.append("- Интересы: $interests\n")
        if (sports.isNotEmpty()) prompt.append("- Спорт: $sports\n")

        // Предпочтения
        if (musicPreferences.isNotEmpty()) prompt.append("- Музыка: $musicPreferences\n")
        if (movieGenres.isNotEmpty()) prompt.append("- Фильмы: $movieGenres\n")
        if (foodPreferences.isNotEmpty()) prompt.append("- Еда: $foodPreferences\n")

        // Личностные характеристики
        if (personalityType.isNotEmpty()) prompt.append("- Тип личности: $personalityType\n")
        if (communicationStyle.isNotEmpty()) prompt.append("- Стиль общения: $communicationStyle\n")
        if (stressManagement.isNotEmpty()) prompt.append("- Снятие стресса: $stressManagement\n")

        // Цели
        if (currentGoals.isNotEmpty()) prompt.append("- Цели: $currentGoals\n")
        if (learningInterests.isNotEmpty()) prompt.append("- Интересы в обучении: $learningInterests\n")

        prompt.append("\nИНСТРУКЦИИ ДЛЯ АССИСТЕНТА:\n")
        prompt.append("1. Учитывай ВСЮ информацию о пользователе в каждом ответе\n")
        prompt.append("2. Будь ")

        when (communicationStyle.lowercase()) {
            "формальный" -> prompt.append("уважительным и профессиональным")
            "юмористический" -> prompt.append("дружелюбным и используй уместный юмор")
            "серьезный" -> prompt.append("сосредоточенным и деловым")
            "дружеский" -> prompt.append("дружелюбным и открытым")
            "эмпатичный" -> prompt.append("чутким и поддерживающим")
            else -> prompt.append("дружелюбным и полезным")
        }

        prompt.append("\n3. Задавай уместные вопросы на основе его интересов и образа жизни\n")
        prompt.append("4. Проявляй искренний интерес к его жизни\n")
        prompt.append("5. Учитывай расписание: ")

        if (workStartTime.isNotEmpty()) prompt.append("работа с $workStartTime")
        if (wakeUpTime.isNotEmpty()) prompt.append(", просыпается $wakeUpTime")

        prompt.append("\n6. Поддерживай беседу на темы, которые интересны пользователю\n")

        // Контекстные рекомендации
        if (hasChildren) prompt.append("7. Интересуйся детьми и семейными делами\n")
        if (fitnessLevel.isNotEmpty() && fitnessLevel != "Не занимаюсь спортом") {
            prompt.append("8. Поддерживай спортивные темы и мотивируй к тренировкам\n")
        }
        if (occupation.isNotEmpty()) prompt.append("9. Учитывай профессиональную сферу в советах\n")

        return prompt.toString()
    }

    private fun getCurrentSeason(): String {
        val month = Calendar.getInstance().get(Calendar.MONTH)
        return when (month) {
            in 2..4 -> "весна"
            in 5..7 -> "лето"
            in 8..10 -> "осень"
            else -> "зима"
        }
    }
}