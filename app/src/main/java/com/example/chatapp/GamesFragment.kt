package com.example.chatapp.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.example.chatapp.AdditionGameMenuActivity
import com.example.chatapp.GuessNumberMenuActivity
import com.example.chatapp.R
import com.example.chatapp.chess.ChessActivity
import com.example.chatapp.igra_bloki.BlockGameActivity  // Добавьте этот импорт
import com.example.chatapp.igra_strotegiy.StrategyGameMenuActivity
import com.example.chatapp.pamyat.MemoryGameActivity
import com.example.chatapp.pvo.PvoGameActivity

class GamesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.activity_igra, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Находим карточки
        val cardGuessNumber = view.findViewById<CardView>(R.id.card_guess_number)
        val cardAdditionGame = view.findViewById<CardView>(R.id.card_addition_game)
        val cardChess = view.findViewById<CardView>(R.id.card_chess)
        val cardMemoryGame = view.findViewById<CardView>(R.id.card_memory_game)
        val cardStrategyGame = view.findViewById<CardView>(R.id.card_strategy_game)
        val cardPvoGame = view.findViewById<CardView>(R.id.card_pvo_game)
        val cardBlockGame = view.findViewById<CardView>(R.id.card_block_game)  // Новая карточка

        // Устанавливаем обработчики кликов на карточки
        cardGuessNumber.setOnClickListener {
            startActivity(Intent(requireContext(), GuessNumberMenuActivity::class.java))
        }

        cardAdditionGame.setOnClickListener {
            startActivity(Intent(requireContext(), AdditionGameMenuActivity::class.java))
        }

        cardChess.setOnClickListener {
            startActivity(Intent(requireContext(), ChessActivity::class.java))
        }

        cardMemoryGame.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryGameActivity::class.java))
        }

        cardStrategyGame.setOnClickListener {
            startActivity(Intent(requireContext(), StrategyGameMenuActivity::class.java))
        }

        cardPvoGame.setOnClickListener {
            startActivity(Intent(requireContext(), PvoGameActivity::class.java))
        }

        // Новая игра Арканоид
        cardBlockGame.setOnClickListener {
            startActivity(Intent(requireContext(), BlockGameActivity::class.java))
        }
    }
}