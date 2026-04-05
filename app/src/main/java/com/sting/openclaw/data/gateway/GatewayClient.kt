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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()
    
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<GatewayResponse>>()
    private var currentUrl = ""
    private var currentToken = ""
    
    // Heartbeat
    private var heartbeatJob: Job? = null
    private var lastTickTs = 0L
    
    fun updateConfig(url: String, token: String) {
        currentUrl = url
        currentToken = token
    }
    
    private val connectDeferred = CompletableDeferred<ConnectionState>()

    suspend fun connect(url: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        currentUrl = url
        currentToken = token
        
        _connectionState.value = ConnectionState.Connecting
        connectDeferred.complete(ConnectionState.Connecting)
        
        val wsUrl = if (url.startsWith("ws://") || url.startsWith("wss://")) url else "ws://$url"
        val request = Request.Builder().url("$wsUrl/").build()
        
        val client = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        
        return@withContext try {
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { 
                        try {
                            onConnected()
                        } catch (e: Exception) {
                            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                            connectDeferred.complete(ConnectionState.Error(e.message ?: "Connection failed"))
                        }
                    }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { 
                        try {
                            handleMessage(text)
                        } catch (e: Exception) {
                            _connectionState.value = ConnectionState.Error("Message handling failed: ${e.message}")
                            connectDeferred.complete(ConnectionState.Error("Message handling failed: ${e.message}"))
                        }
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val errorMsg = when {
                        t.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout"
                        t.message?.contains("refused", ignoreCase = true) == true -> "Connection refused"
                        t.message?.contains("network", ignoreCase = true) == true -> "Network error"
                        t.javaClass.name.contains("UnknownHostException") -> "Invalid host"
                        else -> t.message ?: "Connection failed"
                    }
                    _connectionState.value = ConnectionState.Error(errorMsg)
                    connectDeferred.complete(ConnectionState.Error(errorMsg))
                    scope.launch { 
                        try {
                            _events.emit(GatewayEvent(type = "error", event = "error", payload = null)) 
                        } catch (ignored: Exception) {}
                    }
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = ConnectionState.Disconnected
                    connectDeferred.complete(ConnectionState.Disconnected)
                    stopHeartbeat()
                }
            })
            
            webSocket = socket
            
            // Wait for connection to complete with proper result
            try {
                withTimeout(15000) {
                    val finalState = connectDeferred.await()
                    if (finalState is ConnectionState.Error) {
                        Result.failure(Exception(finalState.message))
                        return@withTimeout
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Connection timeout")
                connectDeferred.complete(ConnectionState.Error("Connection timeout"))
                socket.close(1000, "Timeout")
                return@withContext Result.failure(Exception("Connection timeout"))
            }
            
            when (val state = _connectionState.value) {
                is ConnectionState.Connected -> Result.success(Unit)
                is ConnectionState.Error -> Result.failure(Exception(state.message))
                else -> Result.failure(Exception("Unexpected state"))
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            connectDeferred.complete(ConnectionState.Error(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }
    
    private suspend fun onConnected() {
        // Wait for connect.challenge from Gateway (Protocol v3)
        // App will send connect request when challenge is received in handleMessage()
        try {
            withTimeout(10000) {
                while (_connectionState.value == ConnectionState.Connecting) {
                    delay(100)
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Connection timeout - no challenge received")
            connectDeferred.complete(ConnectionState.Error("Connection timeout - no challenge received"))
        }
    }
    
    private suspend fun sendConnectRequest(nonce: String? = null) {
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
            webSocket?.send(jsonStr)
            
            // Wait for hello-ok
            try {
                val response = withTimeout(10000) {
                    deferred.await()
                }
                if (response.ok == true) {
                    _connectionState.value = ConnectionState.Connected
                    connectDeferred.complete(ConnectionState.Connected)
                    startHeartbeat()
                } else {
                    val errorMsg = response.error?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Authentication failed"
                    _connectionState.value = ConnectionState.Error(errorMsg)
                    connectDeferred.complete(ConnectionState.Error(errorMsg))
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Connection timeout - no response from gateway")
                connectDeferred.complete(ConnectionState.Error("Connection timeout - no response from gateway"))
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to send connect request: ${e.message}")
            connectDeferred.complete(ConnectionState.Error("Failed to send connect request: ${e.message}"))
        }
    }
    
    private suspend fun handleMessage(text: String) {
        try {
            // Try event first
            val event = try {
                json.decodeFromString<GatewayEvent>(text)
            } catch (e: Exception) { null }
            
            if (event != null && event.type == "event") {
                when (event.event) {
                    "connect.challenge" -> {
                        // Protocol v3: Received challenge, send connect request
                        val nonce = event.payload?.jsonObject?.get("nonce")?.jsonPrimitive?.content
                        sendConnectRequest(nonce)
                    }
                    "tick" -> {
                        lastTickTs = System.currentTimeMillis()
                    }
                    "hello-ok" -> {
                        // Complete the connect request if pending
                        val connectEntry = pendingRequests.entries.firstOrNull { it.value.isActive }
                        if (connectEntry != null) {
                            connectEntry.value.complete(GatewayResponse(type = "res", id = connectEntry.key, ok = true))
                            pendingRequests.remove(connectEntry.key)
                        }
                    }
                    "error" -> {
                        val errorMsg = event.payload?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                        _connectionState.value = ConnectionState.Error(errorMsg)
                        connectDeferred.complete(ConnectionState.Error(errorMsg))
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
            // Log but don't crash on parse errors
        }
    }
    
    suspend fun sendRequest(method: String, params: Map<String, Any?>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val paramsElement = json.encodeToJsonElement(JsonObject(params.mapValues { 
                when (val v = it.value) {
                    is String -> JsonPrimitive(v)
                    is Int -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive("")
                }
            }))
            
            val request = GatewayRequest(
                id = UUID.randomUUID().toString(),
                method = method,
                params = paramsElement
            )
            
            val deferred = CompletableDeferred<GatewayResponse>()
            pendingRequests[request.id] = deferred
            
            webSocket?.send(json.encodeToString(GatewayRequest.serializer(), request))
            
            val response = withTimeout(30000) {
                deferred.await()
            }
            
            if (response.ok == true) {
                Result.success(request.id)
            } else {
                Result.failure(Exception("Request failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun disconnect() {
        stopHeartbeat()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15000) // 15s tick
                if (_connectionState.value == ConnectionState.Connected) {
                    // Check for timeout (no tick for 30s)
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
