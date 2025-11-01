package com.example.chatapp.igra_strotegiy

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.adapters.GameLobbyAdapter
import com.example.chatapp.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MultiplayerLobbyActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var multiplayerLogic: MultiplayerGameLogic
    private lateinit var gamesRecyclerView: RecyclerView
    private lateinit var btnCreateGame: Button
    private lateinit var btnJoinGame: Button
    private lateinit var btnStartGame: Button
    private lateinit var etGameCode: EditText
    private lateinit var gameAdapter: GameLobbyAdapter
    private var currentUser: User? = null
    private val availableGames = mutableListOf<MultiplayerGame>()
    private var currentGameId: String? = null
    private var currentGameListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiplayer_lobby)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        multiplayerLogic = MultiplayerGameLogic(database)
        initViews()
        loadCurrentUser()
        setupRecyclerView()
        loadAvailableGames()
    }

    private fun initViews() {
        gamesRecyclerView = findViewById(R.id.rvAvailableGames)
        btnCreateGame = findViewById(R.id.btnCreateGame)
        btnJoinGame = findViewById(R.id.btnJoinGame)
        btnStartGame = findViewById(R.id.btnStartGame)
        etGameCode = findViewById(R.id.etGameCode)
        btnCreateGame.setOnClickListener { createNewGame() }
        btnJoinGame.setOnClickListener { joinGameByCode() }
        btnStartGame.setOnClickListener { startCurrentGame() }
        btnStartGame.visibility = View.GONE
    }

    private fun loadCurrentUser() {
        val uid = auth.currentUser?.uid ?: return
        database.child("users").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                currentUser = s.getValue(User::class.java)
            }
            override fun onCancelled(e: DatabaseError) {
                Toast.makeText(this@MultiplayerLobbyActivity, "Ошибка загрузки пользователя", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setupRecyclerView() {
        gameAdapter = GameLobbyAdapter(
            onGameJoin = { game ->
                joinGame(game.gameId)
            },
            onGameSpectate = { game ->
                spectateGame(game.gameId)
            }
        )
        gamesRecyclerView.layoutManager = LinearLayoutManager(this)
        gamesRecyclerView.adapter = gameAdapter
    }

    private fun loadAvailableGames() {
        val uid = auth.currentUser?.uid ?: return
        database.child("multiplayer_games")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    availableGames.clear()
                    snapshot.children.forEach { s ->
                        val game = FirebaseGameMapper.safeGetMultiplayerGame(s)
                        game?.let {
                            val isPlayer = it.getSafePlayers().containsKey(uid)
                            val canJoin = it.gameState == GameState.WAITING_FOR_PLAYERS &&
                                    it.getSafePlayers().size < it.maxPlayers
                            if (isPlayer || canJoin) {
                                availableGames.add(it)
                                if (isPlayer && it.gameState == GameState.WAITING_FOR_PLAYERS) {
                                    currentGameId = it.gameId
                                    updateStartButton(it)
                                }
                            }
                        }
                    }
                    gameAdapter.submitList(availableGames.toList())
                }
                override fun onCancelled(e: DatabaseError) {
                    Toast.makeText(this@MultiplayerLobbyActivity, "Ошибка загрузки игр", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun updateStartButton(game: MultiplayerGame) {
        val uid = auth.currentUser?.uid
        val isHost = uid == game.hostUid
        val canStart = game.getSafePlayers().size >= 2 && game.gameState == GameState.WAITING_FOR_PLAYERS
        btnStartGame.visibility = if (isHost && canStart) View.VISIBLE else View.GONE
        btnStartGame.isEnabled = canStart
        if (isHost && canStart) {
            btnStartGame.text = "Начать игру (${game.getSafePlayers().size}/${game.maxPlayers})"
        }
    }

    private fun createNewGame() {
        val user = currentUser ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val id = multiplayerLogic.createGame(user.uid, user.getFullName(), user.profileImageUrl)
                currentGameId = id
                setupGameListener(id)
                Toast.makeText(this@MultiplayerLobbyActivity, "Игра создана!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerLobbyActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCurrentGame() {
        val uid = auth.currentUser?.uid ?: return
        val id = currentGameId ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val success = multiplayerLogic.startGame(id, uid)
                if (success) {
                    startActivity(Intent(this@MultiplayerLobbyActivity, MultiplayerGameActivity::class.java).apply {
                        putExtra("GAME_ID", id)
                        putExtra("IS_SPECTATOR", false)
                    })
                    finish()
                } else {
                    Toast.makeText(this@MultiplayerLobbyActivity, "Не удалось начать игру", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerLobbyActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun joinGameByCode() {
        val code = etGameCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(this, "Введите код игры", Toast.LENGTH_SHORT).show()
            return
        }
        joinGame(code)
    }

    private fun joinGame(gameId: String) {
        val user = currentUser ?: return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val success = multiplayerLogic.joinGame(gameId, user)
                if (success) {
                    currentGameId = gameId
                    setupGameListener(gameId)
                    Toast.makeText(this@MultiplayerLobbyActivity, "Вы присоединились!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MultiplayerLobbyActivity, "Не удалось присоединиться", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MultiplayerLobbyActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun spectateGame(gameId: String) {
        startActivity(Intent(this, MultiplayerGameActivity::class.java).apply {
            putExtra("GAME_ID", gameId)
            putExtra("IS_SPECTATOR", true)
        })
    }

    private fun setupGameListener(gameId: String) {
        // Удаляем старый слушатель
        currentGameListener?.let { listener ->
            currentGameId?.let { oldId ->
                database.child("multiplayer_games").child(oldId).removeEventListener(listener)
            }
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val game = FirebaseGameMapper.safeGetMultiplayerGame(snapshot)
                if (game?.gameState == GameState.IN_PROGRESS) {
                    // Автоматически входим в игру
                    startActivity(Intent(this@MultiplayerLobbyActivity, MultiplayerGameActivity::class.java).apply {
                        putExtra("GAME_ID", gameId)
                        putExtra("IS_SPECTATOR", false)
                    })
                    finish()
                } else if (game?.gameState == GameState.WAITING_FOR_PLAYERS) {
                    updateStartButton(game)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                // ignore
            }
        }

        currentGameListener = listener
        currentGameId = gameId
        database.child("multiplayer_games").child(gameId).addValueEventListener(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentGameListener?.let { listener ->
            currentGameId?.let { id ->
                database.child("multiplayer_games").child(id).removeEventListener(listener)
            }
        }
    }
}