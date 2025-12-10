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
import androidx.lifecycle.lifecycleScope // Importante para corrutinas seguras
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URISyntaxException

// DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tap_stats")

class GameRepository(private val context: Context) {
    // CORRECCIÓN: Nombre en camelCase para propiedad privada
    private val winsKey = intPreferencesKey("neon_wins")

    val totalWins: Flow<Int> = context.dataStore.data.map { it[winsKey] ?: 0 }

    suspend fun incrementWins() {
        context.dataStore.edit { it[winsKey] = (it[winsKey] ?: 0) + 1 }
    }
}

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

    private var gameTarget by mutableStateOf<GameTarget?>(null)
    private var playersList by mutableStateOf<List<Player>>(emptyList())

    // CORRECCIÓN: Optimización usando mutableIntStateOf para primitivos (evita el warning de rendimiento)
    private var round by mutableIntStateOf(0)
    private var maxRounds by mutableIntStateOf(15)

    private var winnerMessage by mutableStateOf<String?>(null)
    private var isConnected by mutableStateOf(false)
    private var mySocketId by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        repo = GameRepository(this)
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
                    AppNavigation(repo, isConnected, winnerMessage, playersList, round, maxRounds, gameTarget, mySocketId,
                        onJoin = { socket.emit("player:join", it) },
                        onStartGame = { socket.emit("game:start") },
                        onTargetClick = {
                            socket.emit("game:hit", it)
                            gameTarget = null
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
                transports = arrayOf("websocket", "polling")
            }
            // --- PEGAR URL DE NGROK AQUÍ (Reemplazar con tu URL real) ---
            socket = IO.socket("https://TU-URL-NGROK.ngrok-free.dev", opts)

        } catch (_: URISyntaxException) {
            // CORRECCIÓN: Usamos "_" para ignorar explícitamente la variable 'e' no usada
            return
        }

        socket.on(Socket.EVENT_CONNECT) {
            isConnected = true
            mySocketId = socket.id()
        }
        socket.on("game:updatePlayers") { args ->
            val data = args[0] as JSONObject
            val list = mutableListOf<Player>()
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = data.getJSONObject(key)
                list.add(Player(key, obj.getString("name"), obj.getInt("score")))
            }
            runOnUiThread { playersList = list.sortedByDescending { it.score } }
        }
        socket.on("game:spawn") { args ->
            val data = args[0] as JSONObject
            val tObj = data.getJSONObject("target")
            runOnUiThread {
                gameTarget = GameTarget(tObj.getString("id"), tObj.getDouble("x").toFloat(), tObj.getDouble("y").toFloat())
                round = data.getInt("round")
                maxRounds = data.getInt("maxRounds")
                winnerMessage = null
            }
        }
        socket.on("game:end") { args ->
            val data = args[0] as JSONObject
            val wId = data.optString("winnerId")
            val wName = data.getString("winnerName")
            runOnUiThread {
                gameTarget = null
                winnerMessage = if (wId == mySocketId) {
                    // CORRECCIÓN: Usamos lifecycleScope en lugar de GlobalScope (Delicate API fix)
                    lifecycleScope.launch { repo.incrementWins() }
                    "VICTORY!"
                } else "WINNER: $wName"
            }
        }
        socket.connect()
    }

    override fun onDestroy() { super.onDestroy(); socket.disconnect() }
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
            modifier = Modifier.fillMaxWidth().height(50.dp)
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
            modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (isMe) "> ${p.name}" else p.name, color = if (isMe) NeonCyan else Color.Gray, fontWeight = FontWeight.Bold)
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
                    Text(winnerMessage, style = MaterialTheme.typography.headlineMedium, color = NeonPurple, fontWeight = FontWeight.Black)
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
                ) { Text("READY?", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
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