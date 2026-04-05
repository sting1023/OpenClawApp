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
    
    suspend fun connect(url: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        currentUrl = url
        currentToken = token
        
        _connectionState.value = ConnectionState.Connecting
        
        val wsUrl = if (url.startsWith("ws://") || url.startsWith("wss://")) url else "ws://$url"
        val request = Request.Builder().url("$wsUrl/").build()
        
        val client = OkHttpClient()
        
        return@withContext try {
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { onConnected() }
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { handleMessage(text) }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                    scope.launch { 
                        _events.emit(GatewayEvent(type = "error", event = "error", payload = null)) 
                    }
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = ConnectionState.Disconnected
                    stopHeartbeat()
                }
            })
            
            webSocket = socket
            
            // Wait for connection to complete (onConnected -> challenge -> connect -> hello-ok)
            try {
                withTimeout(15000) {
                    while (_connectionState.value == ConnectionState.Connecting) {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                // Timeout waiting for connection
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
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
                    startHeartbeat()
                } else {
                    val errorMsg = response.error?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Authentication failed"
                    _connectionState.value = ConnectionState.Error(errorMsg)
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Connection timeout - no response from gateway")
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Failed to send connect request: ${e.message}")
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
                        // Complete pending connect request
                        val connectEntry = pendingRequests.entries.firstOrNull { it.value.isActive }
                        if (connectEntry != null) {
                            connectEntry.value.complete(GatewayResponse(type = "res", id = connectEntry.key, ok = true))
                            pendingRequests.remove(connectEntry.key)
                        }
                    }
                    "error" -> {
                        val errorMsg = event.payload?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                        _connectionState.value = ConnectionState.Error(errorMsg)
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
            // Ignore parse errors
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
