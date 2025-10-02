package com.example.chatapp.chess

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chatapp.R
import com.example.chatapp.models.User
import com.example.chatapp.utils.NotificationUtils // Импорт NotificationUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class ChessActivity : AppCompatActivity(), ChessDelegate {
    // Firebase
    private lateinit var database: DatabaseReference
    private lateinit var auth: FirebaseAuth
    private var currentUserId: String? = null
    private var currentGameId: String? = null
    private var gameListener: ValueEventListener? = null
    private var playersListener: ValueEventListener? = null
    private var invitationListener: ChildEventListener? = null
    private var invitationSentListener: ValueEventListener? = null
    private var invitationsListener: ValueEventListener? = null
    private var activeGamesListener: ValueEventListener? = null
    private var processedMoveIds = mutableSetOf<String>()
    // Views
    private lateinit var chessView: ChessView
    private lateinit var resetButton: Button
    private lateinit var exitButton: Button
    private lateinit var playersRecyclerView: RecyclerView
    private lateinit var invitationsRecyclerView: RecyclerView
    private lateinit var activeGamesRecyclerView: RecyclerView
    private lateinit var invitationsTitle: TextView
    private lateinit var activeGamesTitle: TextView
    private lateinit var gameInfoText: TextView
    private lateinit var playerNameEditText: EditText
    private lateinit var refreshButton: Button
    private lateinit var startOfflineButton: Button
    private lateinit var lobbyLayout: LinearLayout
    private lateinit var gameLayout: LinearLayout
    // Adapters
    private lateinit var playersAdapter: ChessPlayerAdapter
    private lateinit var invitationsAdapter: ChessInvitationAdapter
    private lateinit var activeGamesAdapter: ActiveGameAdapter
    // Game state
    private var myPlayerColor: Player = Player.WHITE
    private var isPlaying = false
    private var myPlayerName = "Игрок"
    private var selectedPlayer: ChessPlayer? = null
    private var isWaitingForResponse = false
    private var currentOpponentId: String? = null
    private var currentOpponentName: String? = null
    // Dialogs
    private var currentDialog: AlertDialog? = null
    // Handler
    private val handler = Handler()
    private val TAG = "ChessActivity"
    // Timeout
    private var invitationTimeoutRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chess)
        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        currentUserId = auth.currentUser?.uid ?: run {
            finish()
            return
        }
        database = FirebaseDatabase.getInstance().reference
        // Initialize views
        initViews()
        // Setup game
        setupGame()
    }

    private fun initViews() {
        lobbyLayout = findViewById(R.id.lobbyLayout)
        gameLayout = findViewById(R.id.gameLayout)
        chessView = findViewById(R.id.chess_view)
        resetButton = findViewById(R.id.reset_button)
        exitButton = findViewById(R.id.exit_button)
        playerNameEditText = findViewById(R.id.player_name_edit_text)
        refreshButton = findViewById(R.id.refresh_button)
        startOfflineButton = findViewById(R.id.start_offline_button)
        invitationsTitle = findViewById(R.id.invitations_title)
        activeGamesTitle = findViewById(R.id.active_games_title)
        gameInfoText = findViewById(R.id.game_info_text)
        chessView.chessDelegate = this
        // Players RecyclerView
        playersRecyclerView = findViewById(R.id.players_recycler_view)
        playersRecyclerView.layoutManager = LinearLayoutManager(this)
        playersAdapter = ChessPlayerAdapter { player ->
            if (player.uid != currentUserId && !isPlaying && !isWaitingForResponse) {
                selectedPlayer = player
                showInvitationDialog(player)
            }
        }
        playersRecyclerView.adapter = playersAdapter
        // Invitations RecyclerView
        invitationsRecyclerView = findViewById(R.id.invitations_recycler_view)
        invitationsRecyclerView.layoutManager = LinearLayoutManager(this)
        invitationsAdapter = ChessInvitationAdapter(
            onAccept = { invitation, fromUserId ->
                if (isPlaying) {
                    showToast("Вы уже в игре")
                    return@ChessInvitationAdapter
                }
                currentGameId = invitation.gameId
                currentOpponentId = fromUserId
                currentOpponentName = invitation.fromName
                // Обновляем статус приглашения
                database.child("chess_invitations").child(currentUserId!!)
                    .child(fromUserId).child("status").setValue("accepted")
                    .addOnSuccessListener {
                        startGameAsBlack(fromUserId, invitation.fromName)
                    }
            },
            onDecline = { invitation, fromUserId ->
                database.child("chess_invitations").child(currentUserId!!)
                    .child(fromUserId).child("status").setValue("rejected")
                    .addOnSuccessListener {
                        // Перезагружаем список приглашений
                        loadInvitations()
                    }
            }
        )
        invitationsRecyclerView.adapter = invitationsAdapter
        // Active Games RecyclerView
        activeGamesRecyclerView = findViewById(R.id.active_games_recycler_view)
        activeGamesRecyclerView.layoutManager = LinearLayoutManager(this)
        activeGamesAdapter = ActiveGameAdapter { game ->
            resumeGame(game)
        }
        activeGamesRecyclerView.adapter = activeGamesAdapter
        // Setup button listeners
        resetButton.setOnClickListener { resetGame() }
        exitButton.setOnClickListener { exitToLobby() }
        refreshButton.setOnClickListener {
            loadOnlinePlayers()
            loadInvitations()
            loadActiveGames()
        }
        startOfflineButton.setOnClickListener { startOfflineGame() }
        playerNameEditText.setOnEditorActionListener { _, _, _ ->
            updatePlayerName()
            true
        }
    }

    private fun setupGame() {
        loadCurrentUserName()
        setupPresenceSystem()
        setupPlayersPresenceListener()
        setupInvitationsListener()
        setupActiveGamesListener()
        updatePlayerInfo()
        loadOnlinePlayers()
        loadInvitations()
        loadActiveGames()
        setupInvitationListener()
        showLobby(true)
    }

    private fun loadCurrentUserName() {
        database.child("users").child(currentUserId!!).addListenerForSingleValueEvent(
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)
                    user?.let {
                        myPlayerName = it.name ?: "Игрок"
                        playerNameEditText.setText(myPlayerName)
                        updatePlayerInfo()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    playerNameEditText.setText(myPlayerName)
                }
            })
    }

    private fun updatePlayerName() {
        myPlayerName = playerNameEditText.text.toString().trim()
        if (myPlayerName.isEmpty()) {
            myPlayerName = "Игрок"
            playerNameEditText.setText(myPlayerName)
        }
        updatePlayerInfo()
    }

    private fun setupPresenceSystem() {
        val presenceRef = database.child("chess_players").child(currentUserId!!)
        val playerData = mapOf(
            "name" to myPlayerName,
            "isOnline" to true,
            "isPlaying" to isPlaying,
            "lastActive" to ServerValue.TIMESTAMP
        )
        presenceRef.setValue(playerData)
        presenceRef.child("isOnline").onDisconnect().setValue(false)
        presenceRef.child("lastActive").onDisconnect().setValue(ServerValue.TIMESTAMP)
    }

    private fun setupPlayersPresenceListener() {
        playersListener = database.child("chess_players")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val players = mutableListOf<ChessPlayer>().apply {
                        // Add current user first
                        add(ChessPlayer(
                            uid = currentUserId!!,
                            name = myPlayerName,
                            isOnline = true,
                            isPlaying = isPlaying
                        ))
                        // Add other players
                        snapshot.children.forEach { child ->
                            if (child.key != currentUserId) {
                                child.getValue(ChessPlayer::class.java)?.let {
                                    add(it.copy(uid = child.key ?: ""))
                                }
                            }
                        }
                    }
                    playersAdapter.submitList(players)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить игроков: ${error.message}")
                    showToast("Не удалось загрузить игроков")
                }
            })
    }

    private fun setupInvitationsListener() {
        invitationsListener = database.child("chess_invitations").child(currentUserId!!)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    loadInvitations()
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить приглашения: ${error.message}")
                }
            })
    }

    private fun setupActiveGamesListener() {
        activeGamesListener = database.child("user_active_games").child(currentUserId!!)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    loadActiveGames()
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить активные игры: ${error.message}")
                }
            })
    }

    private fun loadInvitations() {
        database.child("chess_invitations").child(currentUserId!!)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val invitations = mutableListOf<Pair<String, ChessInvitation>>()
                    snapshot.children.forEach { child ->
                        val invitation = child.getValue(ChessInvitation::class.java)
                        if (invitation != null) {
                            invitations.add(Pair(child.key ?: "", invitation))
                        }
                    }
                    invitationsAdapter.submitList(invitations)
                    // Показываем/скрываем заголовок и список приглашений
                    if (invitations.isNotEmpty()) {
                        invitationsTitle.visibility = View.VISIBLE
                        invitationsRecyclerView.visibility = View.VISIBLE
                    } else {
                        invitationsTitle.visibility = View.GONE
                        invitationsRecyclerView.visibility = View.GONE
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить приглашения: ${error.message}")
                }
            })
    }

    private fun loadActiveGames() {
        database.child("user_active_games").child(currentUserId!!)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val activeGames = mutableListOf<ActiveGame>()
                    snapshot.children.forEach { child ->
                        val game = child.getValue(ActiveGame::class.java)
                        if (game != null) {
                            activeGames.add(game)
                        }
                    }
                    activeGamesAdapter.submitList(activeGames)
                    // Показываем/скрываем заголовок и список активных игр
                    if (activeGames.isNotEmpty()) {
                        activeGamesTitle.visibility = View.VISIBLE
                        activeGamesRecyclerView.visibility = View.VISIBLE
                    } else {
                        activeGamesTitle.visibility = View.GONE
                        activeGamesRecyclerView.visibility = View.GONE
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить активные игры: ${error.message}")
                }
            })
    }

    private fun updatePlayerInfo() {
        val playerData = mapOf(
            "name" to myPlayerName,
            "isPlaying" to isPlaying,
            "lastActive" to ServerValue.TIMESTAMP
        )
        database.child("chess_players").child(currentUserId!!).setValue(playerData)
    }

    private fun loadOnlinePlayers() {
        database.child("chess_players")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val players = mutableListOf<ChessPlayer>().apply {
                        add(ChessPlayer(
                            uid = currentUserId!!,
                            name = myPlayerName,
                            isOnline = true,
                            isPlaying = isPlaying
                        ))
                        snapshot.children.forEach { child ->
                            if (child.key != currentUserId) {
                                child.getValue(ChessPlayer::class.java)?.let {
                                    add(it.copy(uid = child.key ?: ""))
                                }
                            }
                        }
                    }
                    playersAdapter.submitList(players)
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить игроков: ${error.message}")
                    showToast("Не удалось загрузить игроков")
                }
            })
    }

    private fun showInvitationDialog(player: ChessPlayer) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Приглашение в игру")
            .setMessage("Пригласить ${player.name} сыграть в шахматы?")
            .setPositiveButton("Отправить") { _, _ ->
                sendInvitation(player)
            }
            .setNegativeButton("Отмена", null)
            .setOnDismissListener { currentDialog = null }
            .show()
    }

    private fun sendInvitation(player: ChessPlayer) {
        if (isPlaying || isWaitingForResponse) {
            showToast("Вы уже в игре")
            return
        }
        isWaitingForResponse = true
        currentOpponentId = player.uid
        currentOpponentName = player.name
        // Create new game first
        currentGameId = database.child("chess_games").push().key
        if (currentGameId == null) {
            showToast("Не удалось создать игру")
            isWaitingForResponse = false
            return
        }
        // Create invitation data
        val invitationData = mapOf(
            "from" to currentUserId,
            "fromName" to myPlayerName,
            "gameId" to currentGameId,
            "status" to "pending",
            "timestamp" to ServerValue.TIMESTAMP
        )
        // Send invitation
        database.child("chess_invitations").child(player.uid).child(currentUserId!!)
            .setValue(invitationData)
            .addOnSuccessListener {
                showToast("Приглашение отправлено ${player.name}")
                // --- Добавлено: Отправка push-уведомления ---
                sendInvitationNotification(player.uid, myPlayerName)
                // ------------------------------------------------
                setupInvitationResponseListener()
                setupInvitationSentListener()
            }
            .addOnFailureListener {
                showToast("Не удалось отправить приглашение")
                isWaitingForResponse = false
                currentGameId = null
                currentOpponentId = null
                currentOpponentName = null
            }
    }

    // --- Добавлено: Новая функция для отправки уведомления о приглашении ---
    /**
     * Отправляет push-уведомление пользователю о приглашении в шахматную игру.
     *
     * @param invitedUserId UserID приглашенного пользователя.
     * @param inviterName Имя пользователя, отправившего приглашение.
     */
    private fun sendInvitationNotification(invitedUserId: String, inviterName: String) {
        // Используем NotificationUtils для отправки уведомления
        // Передаем this (контекст), ID приглашенного пользователя, текст уведомления,
        // имя отправителя (для заголовка или текста), и ID игры (для данных уведомления)
        NotificationUtils.sendChessInvitationNotification(
            context = this,
            userId = invitedUserId,
            messageText = "Вас пригласил $inviterName в шахматную партию!",
            inviterName = inviterName,
            gameId = currentGameId ?: "" // Передаем ID игры для потенциального использования
        )
    }
    // -------------------------------------------------------------------------

    private fun setupInvitationSentListener() {
        if (currentOpponentId == null) return
        val invitationRef = database.child("chess_invitations").child(currentOpponentId!!).child(currentUserId!!)
        invitationSentListener = invitationRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                val gameId = snapshot.child("gameId").getValue(String::class.java)
                if (status == "accepted" && gameId != null) {
                    // ОТМЕНЯЕМ таймаут при успешном принятии приглашения
                    invitationTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    invitationTimeoutRunnable = null
                    currentGameId = gameId
                    try {
                        invitationRef.removeEventListener(this)
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка удаления слушателя приглашения: ${e.message}")
                    }
                    // Обновляем UI для отправителя
                    isPlaying = true
                    myPlayerColor = Player.WHITE
                    ChessGame.reset()
                    ChessGame.currentPlayer = Player.WHITE
                    chessView.isFlipped = true // Белые фигуры снизу
                    updatePlayerInfo()
                    showLobby(false)
                    setupOnlineGameListeners()
                    showToast("Игра началась! Вы играете белыми")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Слушатель приглашения отменен: ${error.message}")
            }
        })
    }

    private fun setupInvitationResponseListener() {
        if (currentOpponentId == null) return
        val invitationRef = database.child("chess_invitations").child(currentUserId!!).child(currentOpponentId!!)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                when (snapshot.child("status").getValue(String::class.java)) {
                    "accepted" -> {
                        // ОТМЕНЯЕМ таймаут при успешном принятии приглашения
                        invitationTimeoutRunnable?.let { handler.removeCallbacks(it) }
                        invitationTimeoutRunnable = null
                        val gameId = snapshot.child("gameId").getValue(String::class.java) ?: return
                        val fromName = snapshot.child("fromName").getValue(String::class.java) ?: "Оппонент"
                        currentGameId = gameId
                        try {
                            invitationRef.removeValue()
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка удаления приглашения: ${e.message}")
                        }
                        startGameAsWhite(currentOpponentId!!, fromName)
                    }
                    "rejected" -> {
                        // ОТМЕНЯЕМ таймаут при отклонении приглашения
                        invitationTimeoutRunnable?.let { handler.removeCallbacks(it) }
                        invitationTimeoutRunnable = null
                        try {
                            invitationRef.removeValue()
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка удаления приглашения: ${e.message}")
                        }
                        isWaitingForResponse = false
                        currentGameId = null
                        currentOpponentId = null
                        currentOpponentName = null
                        showToast("Приглашение отклонено")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Слушатель ответа на приглашение отменен: ${error.message}")
                try {
                    invitationRef.removeEventListener(this)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка удаления слушателя ответа: ${e.message}")
                }
            }
        }
        invitationRef.addValueEventListener(listener)
        // Timeout after 30 seconds
        invitationTimeoutRunnable = Runnable {
            if (isWaitingForResponse) {
                try {
                    invitationRef.removeEventListener(listener)
                    database.child("chess_invitations").child(currentOpponentId!!)
                        .child(currentUserId!!).removeValue()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка очистки приглашения: ${e.message}")
                }
                isWaitingForResponse = false
                currentGameId = null
                currentOpponentId = null
                currentOpponentName = null
                showToast("Нет ответа от игрока")
            }
        }
        handler.postDelayed(invitationTimeoutRunnable!!, 86_400_000)
    }

    private fun setupInvitationListener() {
        invitationListener = database.child("chess_invitations").child(currentUserId!!)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    // Просто обновляем список приглашений, без показа диалога
                    loadInvitations()
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    // Обновляем список при изменении статуса приглашения
                    loadInvitations()
                }
                override fun onChildRemoved(snapshot: DataSnapshot) {
                    // Обновляем список при удалении приглашения
                    loadInvitations()
                }
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Слушатель приглашений отменен: ${error.message}")
                }
            })
    }

    private fun startGameAsWhite(opponentId: String, opponentName: String) {
        isPlaying = true
        myPlayerColor = Player.WHITE
        ChessGame.reset()
        ChessGame.currentPlayer = Player.WHITE
        currentOpponentId = opponentId
        currentOpponentName = opponentName
        chessView.isFlipped = true // Белые фигуры снизу
        updatePlayerInfo()
        // Удаляем все приглашения при начале игры
        try {
            database.child("chess_invitations").child(currentUserId!!).removeValue()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления приглашений: ${e.message}")
        }
        val gameData = ChessGameData(
            playerWhite = currentUserId!!,
            playerBlack = opponentId,
            currentPlayer = Player.WHITE.name,
            moveHistory = mutableListOf()
        )
        database.child("chess_games").child(currentGameId!!).setValue(gameData)
            .addOnSuccessListener {
                // Сохраняем игру в активные для обоих игроков
                saveActiveGame()
                showLobby(false)
                setupOnlineGameListeners()
                updateGameInfoText()
                chessView.invalidate()
                showToast("Вы играете белыми против $opponentName")
            }
            .addOnFailureListener {
                returnToLobby()
                showToast("Не удалось начать игру")
            }
    }

    private fun startGameAsBlack(opponentId: String, opponentName: String) {
        isPlaying = true
        myPlayerColor = Player.BLACK
        ChessGame.reset()
        ChessGame.currentPlayer = Player.WHITE
        currentOpponentId = opponentId
        currentOpponentName = opponentName
        chessView.isFlipped = false // Черные фигуры снизу
        updatePlayerInfo()
        // Удаляем все приглашения при начале игры
        try {
            database.child("chess_invitations").child(currentUserId!!).removeValue()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления приглашений: ${e.message}")
        }
        val gameData = ChessGameData(
            playerWhite = opponentId,
            playerBlack = currentUserId!!,
            currentPlayer = Player.WHITE.name,
            moveHistory = mutableListOf()
        )
        database.child("chess_games").child(currentGameId!!).setValue(gameData)
            .addOnSuccessListener {
                // Сохраняем игру в активные для обоих игроков
                saveActiveGame()
                showLobby(false)
                setupOnlineGameListeners()
                updateGameInfoText()
                chessView.invalidate()
                showToast("Вы играете черными против $opponentName")
            }
            .addOnFailureListener {
                returnToLobby()
                showToast("Не удалось начать игру")
            }
    }

    private fun resumeGame(game: ActiveGame) {
        currentGameId = game.gameId
        currentOpponentId = game.opponentId
        currentOpponentName = game.opponentName
        myPlayerColor = game.myColor
        isPlaying = true
        // Устанавливаем ориентацию доски в зависимости от цвета игрока
        chessView.isFlipped = (myPlayerColor == Player.WHITE)
        // Сначала сбрасываем игру
        ChessGame.reset()
        // Загружаем состояние игры из Firebase
        database.child("chess_games").child(currentGameId!!)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val gameData = snapshot.getValue(ChessGameData::class.java)
                    if (gameData != null) {
                        try {
                            // Восстанавливаем текущего игрока
                            ChessGame.currentPlayer = Player.valueOf(gameData.currentPlayer)
                            // Восстанавливаем все ходы из истории
                            gameData.moveHistory.forEach { move ->
                                if (ChessGame.canMove(move.from, move.to)) {
                                    ChessGame.applyMove(move.from, move.to)
                                }
                            }
                            // Применяем последний ход если он еще не в истории
                            gameData.lastMove?.let { move ->
                                // Проверяем, есть ли этот ход уже в истории
                                val moveExists = gameData.moveHistory.any {
                                    it.from.col == move.from.col &&
                                            it.from.row == move.from.row &&
                                            it.to.col == move.to.col &&
                                            it.to.row == move.to.row
                                }
                                if (!moveExists && ChessGame.canMove(move.from, move.to)) {
                                    ChessGame.applyMove(move.from, move.to)
                                }
                            }
                            // Обновляем состояние активной игры
                            saveActiveGame()
                            showLobby(false)
                            setupOnlineGameListeners()
                            updateGameInfoText()
                            chessView.invalidate()
                            showToast("Игра возобновлена против ${game.opponentName}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Ошибка возобновления игры: ${e.message}")
                            showToast("Ошибка возобновления игры")
                            resetGame()
                        }
                    } else {
                        showToast("Игра не найдена")
                        removeActiveGame(game.gameId)
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Не удалось загрузить игру: ${error.message}")
                    showToast("Не удалось загрузить игру")
                }
            })
    }

    private fun saveActiveGame() {
        if (currentGameId == null || currentOpponentId == null || currentOpponentName == null) return
        try {
            val activeGame = ActiveGame(
                gameId = currentGameId!!,
                opponentId = currentOpponentId!!,
                opponentName = currentOpponentName!!,
                myColor = myPlayerColor,
                currentPlayer = ChessGame.currentPlayer,
                lastMoveTime = System.currentTimeMillis()
            )
            // Сохраняем игру для текущего игрока
            database.child("user_active_games").child(currentUserId!!).child(currentGameId!!)
                .setValue(activeGame)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Не удалось сохранить активную игру для текущего игрока: ${e.message}")
                }
            // Сохраняем игру для оппонента (с противоположным цветом)
            val opponentActiveGame = ActiveGame(
                gameId = currentGameId!!,
                opponentId = currentUserId!!, // ID текущего игрока становится opponentId для оппонента
                opponentName = myPlayerName, // Имя текущего игрока
                myColor = myPlayerColor.opposite(), // Противоположный цвет
                currentPlayer = ChessGame.currentPlayer,
                lastMoveTime = System.currentTimeMillis()
            )
            database.child("user_active_games").child(currentOpponentId!!).child(currentGameId!!)
                .setValue(opponentActiveGame)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Не удалось сохранить активную игру для оппонента: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения активной игры: ${e.message}")
        }
    }

    private fun removeActiveGame(gameId: String) {
        try {
            // Удаляем игру у текущего игрока
            database.child("user_active_games").child(currentUserId!!).child(gameId).removeValue()
            // Удаляем игру у оппонента
            currentOpponentId?.let { opponentId ->
                database.child("user_active_games").child(opponentId).child(gameId).removeValue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления активной игры: ${e.message}")
        }
    }

    private fun updateGameInfoText() {
        val opponentText = currentOpponentName ?: "Оппонент"
        val colorText = if (myPlayerColor == Player.WHITE) "Белые" else "Черные"
        val turnText = if (ChessGame.currentPlayer == myPlayerColor) "Ваш ход" else "Ход оппонента"
        gameInfoText.text = "Противник: $opponentText\nВаш цвет: $colorText\n$turnText"
    }

    private fun setupOnlineGameListeners() {
        if (currentGameId == null) return
        // Сброс обработанных ходов при новой игре
        processedMoveIds.clear()
        // Удаляем старый listener если есть
        gameListener?.let {
            try {
                database.child("chess_games").child(currentGameId!!).removeEventListener(it)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления старого слушателя игры: ${e.message}")
            }
        }
        gameListener = database.child("chess_games").child(currentGameId!!)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d(TAG, "Данные игры изменены: количество детей = ${snapshot.childrenCount}")
                    val game = snapshot.getValue(ChessGameData::class.java)
                    if (game != null) {
                        Log.d(TAG, "Состояние игры: currentPlayer=${game.currentPlayer}, lastMove=${game.lastMove}")
                        handleGameStateUpdate(game)
                    } else {
                        Log.w(TAG, "Данные игры пусты")
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Слушатель игры отменен: ${error.message}")
                    showToast("Ошибка соединения с игрой")
                }
            })
    }

    private fun handleGameStateUpdate(game: ChessGameData) {
        try {
            // Проверяем, если игра закончилась
            game.winner?.let { winner ->
                showGameEndDialog(winner)
                // Удаляем игру из активных при завершении
                currentGameId?.let { removeActiveGame(it) }
                return
            }
            // Обновляем текущего игрока из Firebase
            game.currentPlayer?.let { currentPlayerStr ->
                try {
                    val currentPlayer = Player.valueOf(currentPlayerStr)
                    ChessGame.currentPlayer = currentPlayer
                    Log.d(TAG, "Обновлен текущий игрок из Firebase: $currentPlayer")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка парсинга currentPlayer: ${e.message}")
                }
            }
            // Обновляем информацию об игре
            updateGameInfoText()
            // Обрабатываем историю ходов
            game.moveHistory.forEach { move ->
                val moveId = "${currentGameId}_${move.from.col}${move.from.row}${move.to.col}${move.to.row}${move.player.name}"
                if (!processedMoveIds.contains(moveId) && move.player != myPlayerColor) {
                    Log.d(TAG, "Применяем ход из истории: $move")
                    if (ChessGame.canMove(move.from, move.to)) {
                        ChessGame.applyMove(move.from, move.to)
                        chessView.invalidate()
                        processedMoveIds.add(moveId)
                    }
                }
            }
            // Обрабатываем последний ход
            game.lastMove?.let { move ->
                val moveId = "${currentGameId}_${move.from.col}${move.from.row}${move.to.col}${move.to.row}${move.player.name}_${game.lastMoveTimestamp ?: System.currentTimeMillis()}"
                if (!processedMoveIds.contains(moveId) && move.player != myPlayerColor) {
                    Log.d(TAG, "Применяем последний ход: $move")
                    if (ChessGame.canMove(move.from, move.to)) {
                        ChessGame.applyMove(move.from, move.to)
                        chessView.invalidate()
                        processedMoveIds.add(moveId)
                        // Проверяем победителя после хода
                        if (!ChessGame.kingsExist()) {
                            val winner = ChessGame.currentPlayer.opposite()
                            database.child("chess_games").child(currentGameId!!)
                                .child("winner").setValue(winner.name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка в handleGameStateUpdate: ${e.message}")
        }
    }

    private fun sendMoveToOpponent(from: Square, to: Square) {
        try {
            val move = ChessMove(from, to, myPlayerColor)
            Log.d(TAG, "Отправка хода: $from в $to, игрок: $myPlayerColor")
            // Получаем текущую историю ходов
            database.child("chess_games").child(currentGameId!!).child("moveHistory")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val moveHistory = mutableListOf<ChessMove>()
                        snapshot.children.forEach { child ->
                            child.getValue(ChessMove::class.java)?.let { move ->
                                moveHistory.add(move)
                            }
                        }
                        // Добавляем новый ход в историю
                        moveHistory.add(move)
                        // Обновляем игру с новым ходом и обновленной историей
                        database.child("chess_games").child(currentGameId!!).updateChildren(mapOf(
                            "lastMove" to move,
                            "currentPlayer" to myPlayerColor.opposite().name,
                            "lastMoveTimestamp" to ServerValue.TIMESTAMP,
                            "moveHistory" to moveHistory
                        )).addOnSuccessListener {
                            // Обновляем активную игру после хода
                            saveActiveGame()
                            updateGameInfoText()
                            Log.d(TAG, "Ход успешно отправлен")
                        }.addOnFailureListener { e ->
                            Log.e(TAG, "Не удалось отправить ход оппоненту: ${e.message}")
                            showToast("Не удалось отправить ход")
                            // В случае ошибки возвращаем предыдущего игрока
                            ChessGame.currentPlayer = myPlayerColor
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Ошибка получения истории ходов: ${error.message}")
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания/отправки хода: ${e.message}")
            showToast("Ошибка отправки хода")
        }
    }

    private fun showGameEndDialog(winner: String) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Игра окончена")
            .setMessage(if (winner == myPlayerColor.name) "Вы выиграли!" else "Вы проиграли")
            .setPositiveButton("OK") { _, _ -> returnToLobby() }
            .setCancelable(false)
            .setOnDismissListener { currentDialog = null }
            .show()
    }

    private fun startOfflineGame() {
        isPlaying = true
        myPlayerColor = Player.WHITE
        ChessGame.reset()
        ChessGame.currentPlayer = Player.WHITE
        currentOpponentId = null
        currentOpponentName = "Компьютер"
        chessView.isFlipped = true // Стандартная ориентация для офлайн игры
        showLobby(false)
        updateGameInfoText()
        chessView.invalidate()
        showToast("Офлайн игра началась. Белые ходят первыми.")
    }

    private fun resetGame() {
        ChessGame.reset()
        chessView.invalidate()
        isPlaying = false
        isWaitingForResponse = false
        selectedPlayer = null
        currentGameId?.let { removeActiveGame(it) }
        currentGameId = null
        currentOpponentId = null
        currentOpponentName = null
        processedMoveIds.clear()
        // Очищаем таймаут при сбросе игры
        invitationTimeoutRunnable?.let { handler.removeCallbacks(it) }
        invitationTimeoutRunnable = null
        updatePlayerInfo()
        showLobby(true)
    }

    private fun exitToLobby() {
        // Просто выходим в лобби, не сбрасывая игру
        showLobby(true)
        isPlaying = false
        updatePlayerInfo()
    }

    private fun returnToLobby() {
        currentGameId?.let { gameId ->
            try {
                database.child("chess_games").child(gameId).removeValue()
                gameListener?.let {
                    database.child("chess_games").child(gameId).removeEventListener(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка очистки игры: ${e.message}")
            }
        }
        resetGame()
    }

    private fun showLobby(show: Boolean) {
        lobbyLayout.visibility = if (show) View.VISIBLE else View.GONE
        gameLayout.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            // При показе лобби перезагружаем списки
            loadOnlinePlayers()
            loadInvitations()
            loadActiveGames()
        }
    }

    private fun showToast(message: String) {
        if (!isFinishing) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun pieceAt(square: Square): ChessPiece? = ChessGame.pieceAt(square)
    override fun movePiece(from: Square, to: Square) {
        if (!isPlaying) return
        val piece = ChessGame.pieceAt(from) ?: return
        if (piece.player != myPlayerColor) {
            showToast("Это не ваша фигура!")
            return
        }
        // Проверяем, чей сейчас ход
        if (currentOpponentId != null) {
            // ВАЖНО: Правильная проверка очереди хода
            if (ChessGame.currentPlayer != myPlayerColor) {
                showToast("Не ваш ход! Текущий: ${ChessGame.currentPlayer}, Ваш: $myPlayerColor")
                return
            }
        }
        if (ChessGame.tryMovePiece(from, to)) {
            chessView.invalidate()
            if (currentOpponentId != null) {
                sendMoveToOpponent(from, to)
            } else {
                // Для офлайн игры обновляем текущего игрока
                ChessGame.currentPlayer = ChessGame.currentPlayer.opposite()
                updateGameInfoText()
            }
            // Проверяем победителя
            if (!ChessGame.kingsExist()) {
                val winner = ChessGame.currentPlayer.opposite()
                if (currentOpponentId != null) {
                    database.child("chess_games").child(currentGameId!!)
                        .child("winner").setValue(winner.name)
                } else {
                    showGameEndDialog(winner.name)
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        invitationTimeoutRunnable?.let { handler.removeCallbacks(it) }
        currentDialog?.dismiss()
        try {
            gameListener?.let {
                currentGameId?.let { gameId ->
                    database.child("chess_games").child(gameId).removeEventListener(it)
                }
            }
            playersListener?.let {
                database.child("chess_players").removeEventListener(it)
            }
            invitationListener?.let {
                database.child("chess_invitations").child(currentUserId!!).removeEventListener(it)
            }
            invitationSentListener?.let {
                currentOpponentId?.let { opponentId ->
                    database.child("chess_invitations").child(opponentId).child(currentUserId!!).removeEventListener(it)
                }
            }
            invitationsListener?.let {
                database.child("chess_invitations").child(currentUserId!!).removeEventListener(it)
            }
            activeGamesListener?.let {
                database.child("user_active_games").child(currentUserId!!).removeEventListener(it)
            }
            database.child("chess_players").child(currentUserId!!).child("isOnline").setValue(false)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки в onDestroy: ${e.message}")
        }
    }
}