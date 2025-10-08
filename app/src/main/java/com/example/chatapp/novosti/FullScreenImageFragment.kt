// FullScreenImageFragment.kt
package com.example.chatapp.novosti

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.chatapp.databinding.FragmentFullScreenImageBinding

class FullScreenImageFragment : Fragment() {
    private lateinit var binding: FragmentFullScreenImageBinding

    companion object {
        private const val ARG_IMAGE_URL = "image_url"
        fun newInstance(imageUrl: String) = FullScreenImageFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_IMAGE_URL, imageUrl)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentFullScreenImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val imageUrl = arguments?.getString(ARG_IMAGE_URL)
        imageUrl?.let {
            Glide.with(this)
                .load(it)
                .into(binding.ivFullScreen)
        }

        binding.ivFullScreen.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }
}