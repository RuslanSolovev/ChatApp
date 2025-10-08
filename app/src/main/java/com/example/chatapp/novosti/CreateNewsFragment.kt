package com.example.chatapp.novosti

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.chatapp.activities.MainActivity
import com.example.chatapp.databinding.FragmentCreateNewsBinding
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import java.util.UUID

class CreateNewsFragment : Fragment() {
    private lateinit var binding: FragmentCreateNewsBinding
    private lateinit var storageHelper: YandexStorageHelper
    private var selectedImageUri: Uri? = null
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    private lateinit var currentUser: User
    private val newsRepository = NewsRepository()
    private var editingNewsItem: NewsItem? = null

    companion object {
        private const val ARG_NEWS_ITEM = "news_item"

        fun newInstance(newsItem: NewsItem): CreateNewsFragment {
            return CreateNewsFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_NEWS_ITEM, newsItem)
                }
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                binding.ivSelectedImage.setImageURI(uri)
                binding.ivSelectedImage.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCreateNewsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storageHelper = YandexStorageHelper(requireContext())
        loadCurrentUser()

        arguments?.getParcelable<NewsItem>(ARG_NEWS_ITEM)?.let { newsItem ->
            editingNewsItem = newsItem
            binding.etContent.setText(newsItem.content)
            binding.btnPublish.text = "Обновить"

            newsItem.imageUrl?.let { imageUrl ->
                Glide.with(requireContext())
                    .load(imageUrl)
                    .into(binding.ivSelectedImage)
                binding.ivSelectedImage.visibility = View.VISIBLE
            }
        }

        binding.btnAddImage.setOnClickListener {
            openImagePicker()
        }

        binding.btnPublish.setOnClickListener {
            if (editingNewsItem != null) {
                updateNews()
            } else {
                publishNews()
            }
        }
    }

    private fun loadCurrentUser() {
        val userId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    currentUser = snapshot.getValue(User::class.java) ?: User()
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.tvError.text = "Ошибка загрузки пользователя"
                    binding.tvError.visibility = View.VISIBLE
                }
            })
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun publishNews() {
        val content = binding.etContent.text.toString().trim()
        if (content.isEmpty()) {
            binding.etContent.error = "Введите текст новости"
            return
        }

        lifecycleScope.launch {
            try {
                binding.btnPublish.isEnabled = false
                binding.btnPublish.text = "Публикация..."

                val imageUrl = selectedImageUri?.let { uri ->
                    storageHelper.uploadNewsImage(uri)
                }

                val newsItem = NewsItem(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    timestamp = System.currentTimeMillis(),
                    authorId = auth.currentUser?.uid,
                    authorName = currentUser.name ?: "Аноним",
                    authorAvatarUrl = currentUser.profileImageUrl,
                    imageUrl = imageUrl
                )

                newsRepository.addNews(newsItem)
                (activity as? MainActivity)?.onNewsCreated()
                parentFragmentManager.popBackStack()

            } catch (e: Exception) {
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = "Опубликовать"
                binding.tvError.text = "Ошибка публикации: ${e.message}"
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun updateNews() {
        val content = binding.etContent.text.toString().trim()
        if (content.isEmpty()) {
            binding.etContent.error = "Введите текст новости"
            return
        }

        lifecycleScope.launch {
            try {
                binding.btnPublish.isEnabled = false
                binding.btnPublish.text = "Обновление..."

                val imageUrl = if (selectedImageUri != null) {
                    storageHelper.uploadNewsImage(selectedImageUri!!)
                } else {
                    editingNewsItem?.imageUrl
                }

                val updatedNews = editingNewsItem!!.copy(
                    content = content,
                    imageUrl = imageUrl,
                    timestamp = System.currentTimeMillis()
                )

                newsRepository.updateNews(updatedNews)
                (activity as? MainActivity)?.onNewsCreated()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = "Обновить"
                binding.tvError.text = "Ошибка обновления: ${e.message}"
                binding.tvError.visibility = View.VISIBLE
            }
        }
    }
}