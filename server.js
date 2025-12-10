const express = require("express");
const http = require("http");
const { Server } = require("socket.io");
const cors = require("cors");
const { v4: uuidv4 } = require("uuid");

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: "*", methods: ["GET", "POST"] },
  transports: ['websocket', 'polling']
});

// --- CONFIGURACIÓN DIFERENTE ---
let gameState = {
  isRunning: false,
  round: 0,
  maxRounds: 15, // CAMBIO: Más rondas que el tuyo
  players: {},
  currentTarget: null
};

function spawnTarget() {
  if (!gameState.isRunning) return;

  if (gameState.round >= gameState.maxRounds) {
    endGame();
    return;
  }

  gameState.round++;
  // CAMBIO: Fórmula matemática ligeramente distinta para coordenadas
  const x = 0.05 + Math.random() * 0.9; // Más cerca de los bordes (5% a 95%)
  const y = 0.15 + Math.random() * 0.7; // Más centrado verticalmente

  gameState.currentTarget = { id: uuidv4(), x, y };

  io.emit("game:spawn", {
    target: gameState.currentTarget,
    round: gameState.round,
    maxRounds: gameState.maxRounds
  });
}

function endGame() {
  gameState.isRunning = false;
  gameState.currentTarget = null;

  let winnerName = "Empate";
  let maxScore = -1;
  let winnerId = null;

  for (const [id, player] of Object.entries(gameState.players)) {
    if (player.score > maxScore) {
      maxScore = player.score;
      winnerName = player.name;
      winnerId = id;
    }
  }

  io.emit("game:end", { winnerName, winnerId });
}

io.on("connection", (socket) => {
  console.log("Nuevo Rival:", socket.id); // Log diferente

  gameState.players[socket.id] = { name: "Anon-" + socket.id.substr(0,3), score: 0 };
  io.emit("game:updatePlayers", gameState.players);

  socket.on("player:join", (name) => {
      if(gameState.players[socket.id]) {
          gameState.players[socket.id].name = name;
          io.emit("game:updatePlayers", gameState.players);
      }
  });

  socket.on("game:start", () => {
    gameState.isRunning = true;
    gameState.round = 0;
    for (let id in gameState.players) gameState.players[id].score = 0;

    io.emit("game:updatePlayers", gameState.players);
    spawnTarget();
  });

  socket.on("game:hit", (targetId) => {
    if (gameState.isRunning && gameState.currentTarget && gameState.currentTarget.id === targetId) {
      if(gameState.players[socket.id]) {
          gameState.players[socket.id].score++;
      }
      gameState.currentTarget = null;
      io.emit("game:updatePlayers", gameState.players);

      // CAMBIO: Juego más rápido (400ms en vez de 500ms)
      setTimeout(spawnTarget, 400);
    }
  });

  socket.on("disconnect", () => {
    delete gameState.players[socket.id];
    io.emit("game:updatePlayers", gameState.players);
  });
});

server.listen(3000, () => console.log('>> Server NEON TAP listo en puerto 3000 <<'));