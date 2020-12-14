package ru.ifmo.setgame

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.CoroutineContext

const val IN_GAME_BROADCAST = "ru.ifmo.setgame.IN_GAME"

class Connector @VisibleForTesting constructor(
        private val socket: Socket,
        private val localBroadcastManager: LocalBroadcastManager
        ) : AutoCloseable, CoroutineScope {
    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val mutex = Mutex()
    private lateinit var reader: BufferedReader // = BufferedReader(InputStreamReader(socket.getInputStream()))
    private lateinit var writer: BufferedWriter // = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"))
    private val mapper = jacksonObjectMapper()

    var gameNavigation: GameNavigation? = null

    private val lobbiesListLiveData = MutableLiveData<String>()
    private val lobbyInfoLiveData = MutableLiveData<String>()

    private var playerId = -1
    private var lobbyId = -1
    private var gameId = -1
    private var status = "NEW"

    fun getLobbiesListLiveData(): LiveData<String> = lobbiesListLiveData
    fun getLobbyInfoLiveData(): LiveData<String> = lobbyInfoLiveData

    fun requestLobbies() = launch {
        mutex.withLock {
            val request = """{"status": "$status",
            |"player_id": $playerId,
            |"action": "refresh_list"}""".trimMargin()

            writer.write(request)
            writer.flush()

            val response = mapper.readTree(reader.readLine())
            status = response.get("status").asText()
            val lobbiesListJson = mapper.writeValueAsString(response.get("lobbies_list"))
            lobbiesListLiveData.postValue(lobbiesListJson)
        }
    }

    fun createLobby(maxPlayers: Int) = launch {
        mutex.withLock {
            val request = """{"status": "$status",
            |"player_id": $playerId,
            |"action": "create_lobby",
            |"max_players": $maxPlayers}""".trimMargin()

            writer.write(request)
            writer.flush()

            val response = mapper.readTree(reader.readLine())
            status = response.get("status").asText()
            lobbyId = response.get("lobby_id").asInt()
            val lobbyInfoJson = mapper.writeValueAsString(response.get("lobby"))
            lobbyInfoLiveData.postValue(lobbyInfoJson)
        }
    }

    fun joinLobby(mLobbyId: Int) = launch {
        mutex.withLock {
            val request = """{"status": "$status",
            |"player_id": $playerId,
            |"action": "join_lobby",
            |"lobby_id": $mLobbyId}""".trimMargin()

            writer.write(request)
            writer.flush()

            val response = mapper.readTree(reader.readLine())
            status = response.get("status").asText()

            // something went wrong, return to lobbies list
            if (status != "IN_LOBBY") {
                gameNavigation?.showLobbiesList()
                return@launch
            }

            lobbyId = response.get("lobby_id").asInt()

            val lobbyInfoJson = mapper.writeValueAsString(response.get("lobby"))
            lobbyInfoLiveData.postValue(lobbyInfoJson)
        }
    }

    fun leaveLobby() = launch {
        mutex.withLock {
            val request = """{"status": "$status",
            |"player_id": $playerId,
            |"action": "leave_lobby",
            |"lobby_id": $lobbyId}""".trimMargin()

            writer.write(request)
            writer.flush()

            val response = mapper.readTree(reader.readLine())
            status = response.get("status").asText()
            lobbyId = -1

            assert(status == "SELECTING_LOBBY")

            val lobbiesListJson = mapper.writeValueAsString(response.get("lobbies_list"))
            lobbiesListLiveData.postValue(lobbiesListJson)
        }
    }

    fun make_move(positions: IntArray) = launch {
        mutex.withLock {
            val request = """{"status": "$status",
            |"player_id": $playerId,
            |"action": "make_move",
            |"lobby_id": $lobbyId,
            |"game_id": $gameId,
            |"move_positions": ${mapper.writeValueAsString(positions)}}""".trimMargin()

            writer.write(request)
            writer.flush()
            // we get no response after this
        }
    }

    // send default handshake and get player id and list of lobbies
    private suspend fun init() = mutex.withLock {
        val request = """{"status": "$status"}"""

        writer.write(request)
        writer.flush()

        val response = mapper.readTree(reader.readLine())
        playerId = response.get("player_id").asInt()
        status = response.get("status").asText()
        val lobbiesListJson = mapper.writeValueAsString(response.get("lobbies_list"))
        lobbiesListLiveData.postValue(lobbiesListJson)
    }

    fun connect() = launch {
        socket.connect(InetSocketAddress(HOST_ADDRESS, HOST_PORT))
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"))

        init()

        while (socket.isConnected) {
            if (reader.ready()) {
                if (status == "SELECTING_LOBBY") {
                    assert(false)
                } else if (status == "IN_LOBBY") {
                    val tmp_str = reader.readLine()
                    Log.d("TG_connect_loop", tmp_str)

                    val update = mapper.readTree(tmp_str)

                    status = update.get("status").asText()

                    if (status == "IN_GAME") {

                        gameId = update.get("game_id").asInt()
                        val gameJson = mapper.writeValueAsString(update.get("game"))
                        gameNavigation?.startMultiplayerGame(gameJson)
                    } else {
                        val lobbyInfoJson = mapper.writeValueAsString(update.get("lobby"))
                        lobbyInfoLiveData.postValue(lobbyInfoJson)
                    }
                } else if (status == "IN_GAME") {
                    val tmp_str = reader.readLine()
                    Log.d("TG_connect_loop", tmp_str)

                    val update = mapper.readTree(tmp_str)

                    status = update.get("status").asText()

                    if (status == "GAME_ENDED") {
                        val trscores = update.get("score")

                        val players = mutableListOf<String>()
                        val scores = mutableListOf<Int>()

                        for (pr in trscores.fields()) {
                            if (pr.key == playerId.toString()) {
                                players.add("You")
                            } else {
                                players.add("Player #${pr.key}")
                            }
                            scores.add(pr.value.asInt())
                        }

                        gameNavigation?.showScore(
                                "Game #$lobbyId results",
                                update.get("time").asLong(),
                                players.toTypedArray(),
                                scores.toIntArray()
                        )
                        break
                    } else {
                        val gameStr = mapper.writeValueAsString(update.get("game"))
                        localBroadcastManager.sendBroadcast(Intent(IN_GAME_BROADCAST).apply { putExtra("game", gameStr) })
                    }
                }
            }
        }
    }

    fun ready() = reader.ready()

    override fun close() {
        socket.close()
        job.cancelChildren()
    }

    companion object {
        private const val HOST_ADDRESS = "rsbat.dev"
        private const val HOST_PORT = 3691

        fun createConnector(context: Context): Connector {
            val socket = Socket()
            val localBroadcastManager = LocalBroadcastManager.getInstance(context)

            return Connector(socket, localBroadcastManager)
        }
    }
}
