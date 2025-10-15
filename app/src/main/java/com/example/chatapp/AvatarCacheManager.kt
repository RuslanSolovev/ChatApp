package com.example.chatapp

// AvatarCacheManager.kt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AvatarCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "AvatarCacheManager"
        private const val CACHE_DIR_NAME = "avatar_cache"
        private const val PREFS_NAME = "avatar_cache_prefs"
        private const val KEY_LAST_CLEANUP = "last_cleanup_time"
        private const val MAX_CACHE_SIZE_MB = 50L
        private const val MAX_CACHE_AGE_DAYS = 30L
        private const val CLEANUP_INTERVAL_DAYS = 7L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    // Кэш в памяти для быстрого доступа
    private val memoryCache = mutableMapOf<String, Bitmap?>()

    /**
     * Получить аватар из кэша или загрузить и сохранить
     */
    suspend fun getAvatar(userId: String, avatarUrl: String?): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Проверяем кэш в памяти
                memoryCache[userId]?.let { cachedBitmap ->
                    Log.d(TAG, "Avatar from memory cache for user: $userId")
                    return@withContext cachedBitmap
                }

                // 2. Пробуем получить из дискового кэша
                getCachedAvatar(userId)?.let { cachedBitmap ->
                    Log.d(TAG, "Avatar from disk cache for user: $userId")
                    // Сохраняем в память
                    memoryCache[userId] = cachedBitmap
                    return@withContext cachedBitmap
                }

                // 3. Если нет в кэше, загружаем и кэшируем
                avatarUrl?.let { url ->
                    loadAndCacheAvatar(userId, url)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting avatar for user: $userId", e)
                null
            }
        }
    }

    /**
     * Получить аватар из дискового кэша
     */
    private fun getCachedAvatar(userId: String): Bitmap? {
        return try {
            val cacheFile = getCacheFile(userId)
            if (cacheFile.exists() && isCacheValid(cacheFile)) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached avatar", e)
            null
        }
    }

    /**
     * Загрузить и сохранить аватар в кэш
     */
    private suspend fun loadAndCacheAvatar(userId: String, avatarUrl: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = Glide.with(context)
                    .asBitmap()
                    .load(avatarUrl)
                    .submit(150, 150) // Оптимальный размер для маркеров
                    .get()

                // Сохраняем в память
                memoryCache[userId] = bitmap

                // Сохраняем на диск
                saveAvatarToCache(userId, bitmap)

                // Периодическая очистка старых файлов
                cleanupOldCacheIfNeeded()

                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error loading and caching avatar for user: $userId", e)
                // Сохраняем null в память, чтобы не пытаться снова
                memoryCache[userId] = null
                null
            }
        }
    }

    /**
     * Сохранить аватар в кэш
     */
    private fun saveAvatarToCache(userId: String, bitmap: Bitmap): Boolean {
        return try {
            val cacheFile = getCacheFile(userId)
            FileOutputStream(cacheFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, fos)
            }
            Log.d(TAG, "Avatar cached for user: $userId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving avatar to cache", e)
            false
        }
    }

    /**
     * Проверить валидность кэша
     */
    private fun isCacheValid(cacheFile: File): Boolean {
        val fileAge = System.currentTimeMillis() - cacheFile.lastModified()
        return fileAge < TimeUnit.DAYS.toMillis(MAX_CACHE_AGE_DAYS)
    }

    /**
     * Очистка старых файлов кэша
     */
    private fun cleanupOldCacheIfNeeded() {
        try {
            val lastCleanup = prefs.getLong(KEY_LAST_CLEANUP, 0)
            val currentTime = System.currentTimeMillis()
            val cleanupInterval = TimeUnit.DAYS.toMillis(CLEANUP_INTERVAL_DAYS)

            if (currentTime - lastCleanup > cleanupInterval) {
                performCacheCleanup()
                prefs.edit().putLong(KEY_LAST_CLEANUP, currentTime).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in cache cleanup check", e)
        }
    }

    /**
     * Выполнить очистку кэша
     */
    private fun performCacheCleanup() {
        try {
            val files = cacheDir.listFiles() ?: return
            var deletedCount = 0
            var totalSize = 0L

            // Удаляем старые файлы и считаем общий размер
            files.forEach { file ->
                if (!isCacheValid(file)) {
                    if (file.delete()) {
                        deletedCount++
                        // Удаляем из памяти
                        val userId = file.nameWithoutExtension.removePrefix("avatar_")
                        memoryCache.remove(userId)
                    }
                } else {
                    totalSize += file.length()
                }
            }

            // Если размер кэша превышает лимит, удаляем самые старые файлы
            if (totalSize > MAX_CACHE_SIZE_MB * 1024 * 1024) {
                val sortedFiles = files.sortedBy { it.lastModified() }
                val filesToDelete = sortedFiles.take((files.size * 0.3).toInt()) // Удаляем 30% самых старых

                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        deletedCount++
                        val userId = file.nameWithoutExtension.removePrefix("avatar_")
                        memoryCache.remove(userId)
                    }
                }
            }

            Log.d(TAG, "Cache cleanup completed. Deleted $deletedCount files")
        } catch (e: Exception) {
            Log.e(TAG, "Error performing cache cleanup", e)
        }
    }

    /**
     * Получить файл кэша для пользователя
     */
    private fun getCacheFile(userId: String): File {
        val safeFileName = userId.replace("/", "_").replace("\\", "_")
        return File(cacheDir, "avatar_$safeFileName.webp")
    }

    /**
     * Очистить кэш для конкретного пользователя
     */
    fun clearUserCache(userId: String) {
        try {
            val cacheFile = getCacheFile(userId)
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Cache cleared for user: $userId")
                // Удаляем из памяти
                memoryCache.remove(userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for user: $userId", e)
        }
    }

    /**
     * Полностью очистить кэш
     */
    fun clearAllCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "All avatar cache cleared")
            // Очищаем память
            memoryCache.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all cache", e)
        }
    }

    /**
     * Получить статистику кэша
     */
    fun getCacheStats(): String {
        val files = cacheDir.listFiles() ?: emptyArray()
        val totalSize = files.sumOf { it.length() } / (1024 * 1024) // в MB
        val validFiles = files.count { isCacheValid(it) }

        return """
            Avatar Cache Stats:
            Files: ${files.size} (${validFiles} valid)
            Total size: ${totalSize}MB
            Max size: ${MAX_CACHE_SIZE_MB}MB
            Memory cache size: ${memoryCache.size}
        """.trimIndent()
    }
}