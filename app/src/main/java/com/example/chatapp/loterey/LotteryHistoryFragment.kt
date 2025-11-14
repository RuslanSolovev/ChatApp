package com.example.chatapp.loterey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chatapp.databinding.FragmentLotteryHistoryBinding
import kotlinx.coroutines.launch

class LotteryHistoryFragment : Fragment() {

    private var _binding: FragmentLotteryHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LotteryViewModel by viewModels()
    private lateinit var adapter: LotteryHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLotteryHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()
        loadHistory()

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressed()
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadHistory()
        }
    }

    private fun setupRecyclerView() {
        adapter = LotteryHistoryAdapter()
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.lotteryHistory.collect { history ->
                adapter.submitList(history)
                binding.tvEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                if (isLoading) {
                    binding.progressBar.visibility = View.VISIBLE
                } else {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun loadHistory() {
        viewModel.loadLotteryHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}