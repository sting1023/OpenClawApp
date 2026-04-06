package com.sting.openclaw.data.gateway

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.*
import javax.inject.Singleton

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Singleton
class GatewayClient @Inject constructor(
    private val json: Json
) {
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
        t.printStackTrace()
    })
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()
    
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<GatewayResponse>>()
    private var currentUrl = ""
    private var currentToken = ""
    
    private var heartbeatJob: Job? = null
    private var lastTickTs = 0L
    
    fun updateConfig(url: String, token: String) {
        currentUrl = url
        currentToken = token
    }
    
    suspend fun connect(url: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        currentUrl = url
        currentToken = token
        _connectionState.value = ConnectionState.Connecting
        
        val wsUrl = if (url.startsWith("ws://") || url.startsWith("wss://")) url else "ws://$url"
        val request = Request.Builder().url("$wsUrl/").build()
        
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var connectionResult: Result<Unit> = Result.failure(Exception("Connection not completed"))
            
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        onConnected(webSocket)
                        latch.countDown()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _connectionState.value = ConnectionState.Error("Setup failed: ${e.message}")
                        connectionResult = Result.failure(e)
                        latch.countDown()
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        try {
                            handleMessage(text, webSocket)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try {
                        val msg = t.message ?: "Connection failed"
                        _connectionState.value = ConnectionState.Error(msg)
                        connectionResult = Result.failure(Exception(msg))
                        scope.launch { _events.emit(GatewayEvent(type="error", event="error", payload=null)) }
                        latch.countDown()
                    } catch (ignored: Exception) {}
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    try {
                        _connectionState.value = ConnectionState.Disconnected
                        stopHeartbeat()
                    } catch (ignored: Exception) {}
                }
            }
            
            webSocket = client.newWebSocket(request, listener)
            
            // Wait for connection with timeout
            val waitOk = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            
            if (!waitOk) {
                // Timeout
                _connectionState.value = ConnectionState.Error("Connection timeout - check IP and port")
                return@withContext Result.failure(Exception("Connection timeout"))
            }
            
            // Check final state
            when (val state = _connectionState.value) {
                is ConnectionState.Connected -> Result.success(Unit)
                is ConnectionState.Error -> Result.failure(Exception(state.message))
                else -> Result.failure(Exception("Connection failed"))
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }
    
    private fun onConnected(webSocket: WebSocket) {
        // Wait for connect.challenge from Gateway (Protocol v3)
        // The challenge will arrive via onMessage -> handleMessage
    }
    
    private suspend fun handleMessage(text: String, webSocket: WebSocket) {
        try {
            // Try event first
            val event = try {
                json.decodeFromString<GatewayEvent>(text)
            } catch (e: Exception) { null }
            
            if (event != null && event.type == "event") {
                when (event.event) {
                    "connect.challenge" -> {
                        val nonce = event.payload?.jsonObject?.get("nonce")?.jsonPrimitive?.content
                        sendConnectRequest(nonce, webSocket)
                    }
                    "tick" -> {
                        lastTickTs = System.currentTimeMillis()
                    }
                    "hello-ok" -> {
                        _connectionState.value = ConnectionState.Connected
                        startHeartbeat()
                        // Complete any pending connect request
                        pendingRequests.entries.firstOrNull { it.value.isActive }?.let {
                            it.value.complete(GatewayResponse(type="res", id=it.key, ok=true))
                            pendingRequests.remove(it.key)
                        }
                    }
                    "error" -> {
                        val msg = event.payload?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                        _connectionState.value = ConnectionState.Error(msg)
                    }
                }
                _events.emit(event)
                return
            }
            
            // Try response
            val response = try {
                json.decodeFromString<GatewayResponse>(text)
            } catch (e: Exception) { null }
            
            if (response != null && response.id != null) {
                pendingRequests[response.id]?.complete(response)
                pendingRequests.remove(response.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun sendConnectRequest(nonce: String?, webSocket: WebSocket) {
        try {
            val connectParams = ConnectParams(
                auth = AuthToken(token = currentToken),
                role = "operator",
                scopes = listOf("operator.read", "operator.write"),
                minProtocol = 3,
                maxProtocol = 3
            )
            
            val paramsJson = json.encodeToJsonElement(ConnectParams.serializer(), connectParams)
            val requestId = UUID.randomUUID().toString()
            val request = GatewayRequest(
                id = requestId,
                method = "connect",
                params = paramsJson
            )
            
            val deferred = CompletableDeferred<GatewayResponse>()
            pendingRequests[requestId] = deferred
            
            val jsonStr = json.encodeToString(GatewayRequest.serializer(), request)
            webSocket.send(jsonStr)
            
            // Wait for hello-ok with timeout
            scope.launch {
                try {
                    val response = withTimeoutOrNull(10000L) {
                        deferred.await()
                    }
                    if (response?.ok == true) {
                        _connectionState.value = ConnectionState.Connected
                        startHeartbeat()
                    } else {
                        val msg = response?.error?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Authentication failed"
                        _connectionState.value = ConnectionState.Error(msg)
                    }
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Error("No response from gateway - check token")
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to send connect request: ${e.message}")
        }
    }
    

    fun sendRawMessage(jsonStr: String) {
        webSocket?.send(jsonStr)
    }
    suspend fun disconnect() {
        stopHeartbeat()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15000)
                if (_connectionState.value == ConnectionState.Connected) {
                    if (lastTickTs > 0 && System.currentTimeMillis() - lastTickTs > 30000) {
                        _connectionState.value = ConnectionState.Error("Heartbeat timeout")
                        disconnect()
                    }
                }
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
