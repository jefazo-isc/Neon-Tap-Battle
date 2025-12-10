package com.example.tap

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

// DataStore para persistencia de victorias
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tap_stats")

class GameRepository(private val context: Context) {
    private val winsKey = intPreferencesKey("neon_wins")
    val totalWins: Flow<Int> = context.dataStore.data.map { it[winsKey] ?: 0 }

    suspend fun incrementWins() {
        context.dataStore.edit { it[winsKey] = (it[winsKey] ?: 0) + 1 }
    }
}

// Modelos de datos
data class Player(val id: String, val name: String, val score: Int)
data class GameTarget(val id: String, val x: Float, val y: Float)

// COLORES TEMA NEON
val NeonBlack = Color(0xFF121212)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFFD500F9)
val NeonDarkPanel = Color(0xFF1E1E1E)

class MainActivity : ComponentActivity() {
    private lateinit var socket: Socket
    private lateinit var repo: GameRepository

    // Estados del juego
    private var gameTarget by mutableStateOf<GameTarget?>(null)
    private var playersList by mutableStateOf<List<Player>>(emptyList())
    private var round by mutableIntStateOf(0)
    private var maxRounds by mutableIntStateOf(15)
    private var winnerMessage by mutableStateOf<String?>(null)

    // Estados de conexión
    private var isConnected by mutableStateOf(false)
    private var mySocketId by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        repo = GameRepository(this)

        // Inicializamos el socket con la URL proporcionada
        setupSocket()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = NeonBlack, primary = NeonCyan)) {
                Surface(
                    color = NeonBlack,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    AppNavigation(
                        repo, isConnected, winnerMessage, playersList, round, maxRounds, gameTarget, mySocketId,
                        onJoin = { socket.emit("player:join", it) },
                        onStartGame = { socket.emit("game:start") },
                        onTargetClick = {
                            socket.emit("game:hit", it)
                            gameTarget = null // Feedback instantáneo local
                        }
                    )
                }
            }
        }
    }

    private fun setupSocket() {
        try {
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                // Permitimos tanto websocket como polling para máxima compatibilidad con Localtunnel
                transports = arrayOf("websocket", "polling")
            }

            // URL actualizada proporcionada
            val serverUrl = "https://tall-words-agree.loca.lt"

            android.util.Log.d("SOCKET_DEBUG", "Iniciando conexión a: $serverUrl")
            socket = IO.socket(serverUrl, opts)

        } catch (e: URISyntaxException) {
            android.util.Log.e("SOCKET_DEBUG", "Error de sintaxis en URL", e)
            return
        }

        // --- Listeners de Conexión ---

        socket.on(Socket.EVENT_CONNECT) {
            val id = socket.id()
            android.util.Log.d("SOCKET_DEBUG", "¡CONECTADO! ID: $id")
            runOnUiThread {
                isConnected = true
                mySocketId = id
            }
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val err = if (args.isNotEmpty()) args[0].toString() else "Error desconocido"
            android.util.Log.e("SOCKET_DEBUG", "ERROR CONEXIÓN: $err")
            runOnUiThread { isConnected = false }
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            android.util.Log.d("SOCKET_DEBUG", "Desconectado del servidor")
            runOnUiThread { isConnected = false }
        }

        // --- Listeners del Juego ---

        socket.on("game:updatePlayers") { args ->
            try {
                val data = args[0] as JSONObject
                val list = mutableListOf<Player>()
                val keys = data.keys()
                while (keys.hasNext()) {
                    // .toString() explícito para evitar error de tipos (Any vs String)
                    val key = keys.next().toString()
                    val obj = data.getJSONObject(key)
                    list.add(Player(key, obj.getString("name"), obj.getInt("score")))
                }
                runOnUiThread { playersList = list.sortedByDescending { it.score } }
            } catch (e: Exception) {
                android.util.Log.e("SOCKET_DEBUG", "Error parseando players", e)
            }
        }

        socket.on("game:spawn") { args ->
            try {
                val data = args[0] as JSONObject
                val tObj = data.getJSONObject("target")
                runOnUiThread {
                    gameTarget = GameTarget(
                        tObj.getString("id"),
                        tObj.getDouble("x").toFloat(),
                        tObj.getDouble("y").toFloat()
                    )
                    round = data.getInt("round")
                    maxRounds = data.getInt("maxRounds")
                    winnerMessage = null
                }
            } catch (e: Exception) {
                android.util.Log.e("SOCKET_DEBUG", "Error en spawn", e)
            }
        }

        socket.on("game:end") { args ->
            try {
                val data = args[0] as JSONObject
                val wId = data.optString("winnerId")
                val wName = data.getString("winnerName")
                runOnUiThread {
                    gameTarget = null
                    winnerMessage = if (wId == mySocketId) {
                        lifecycleScope.launch { repo.incrementWins() }
                        "VICTORY!"
                    } else "WINNER: $wName"
                }
            } catch (e: Exception) {
                android.util.Log.e("SOCKET_DEBUG", "Error en game:end", e)
            }
        }

        socket.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        socket.disconnect()
    }
}

@Composable
fun AppNavigation(
    repo: GameRepository, isConnected: Boolean, winnerMessage: String?, players: List<Player>,
    round: Int, maxRounds: Int, gameTarget: GameTarget?, mySocketId: String,
    onJoin: (String) -> Unit, onStartGame: () -> Unit, onTargetClick: (String) -> Unit
) {
    var hasJoined by remember { mutableStateOf(false) }
    val totalWins by repo.totalWins.collectAsState(initial = 0)

    if (!hasJoined) {
        LoginScreen(isConnected, totalWins) { onJoin(it); hasJoined = true }
    } else {
        GameScreen(players, round, maxRounds, gameTarget, winnerMessage, mySocketId, onStartGame, onTargetClick)
    }
}

@Composable
fun LoginScreen(isConnected: Boolean, totalWins: Int, onJoin: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("NEON TAP", fontSize = 48.sp, fontWeight = FontWeight.Black, color = NeonCyan)
        Spacer(modifier = Modifier.height(20.dp))
        Text("WINS: $totalWins", color = NeonPurple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(40.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("CODENAME", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = Color.Gray,
                focusedTextColor = Color.White
            ),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { if (name.isNotBlank()) onJoin(name) },
            enabled = isConnected && name.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            shape = CutCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(if (isConnected) "CONNECT SYSTEM" else "OFFLINE", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GameScreen(
    players: List<Player>, round: Int, maxRounds: Int, gameTarget: GameTarget?,
    winnerMessage: String?, mySocketId: String, onStartGame: () -> Unit, onTargetClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // HUD Superior
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = NeonDarkPanel),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonPurple)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("ROUND $round / $maxRounds", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(players) { p ->
                        val isMe = p.id == mySocketId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isMe) "> ${p.name}" else p.name,
                                color = if (isMe) NeonCyan else Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                            Text("${p.score}", color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Area de Juego
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val w = maxWidth
            val h = maxHeight

            if (winnerMessage != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        winnerMessage,
                        style = MaterialTheme.typography.headlineMedium,
                        color = NeonPurple,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onStartGame, colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)) {
                        Text("RESTART MISSION", color = Color.Black)
                    }
                }
            } else if (round == 0) {
                Button(
                    onClick = onStartGame,
                    modifier = Modifier.align(Alignment.Center),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("READY?", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                gameTarget?.let { t ->
                    val size = 80.dp
                    val xPos = w * t.x - (size / 2)
                    val yPos = h * t.y - (size / 2)

                    Box(
                        modifier = Modifier
                            .offset(x = xPos, y = yPos)
                            .size(size)
                            .shadow(15.dp, CircleShape, spotColor = NeonCyan)
                            .background(Brush.radialGradient(listOf(NeonCyan, Color.Blue)), CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .clickable { onTargetClick(t.id) }
                    )
                }
            }
        }
    }
}