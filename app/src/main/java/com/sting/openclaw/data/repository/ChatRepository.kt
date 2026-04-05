package com.sting.openclaw.data.repository

import com.sting.openclaw.data.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val json: Json
) {
    private val _chatMessages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val chatMessages: StateFlow<List<MessageUiModel>> = _chatMessages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    
    private var currentSessionKey = "agent:main:main"
    
    // Expose webSocket for sending - set by GatewayClient holder
    var webSocket: com.squareup.okhttp3.WebSocket? = null
    
    fun observeChatEvents() = gatewayClient.events
        .filter { it.event == "chat" }
        .mapNotNull { event ->
            try {
                json.decodeFromString<ChatEvent>(event.payload.toString())
            } catch (e: Exception) { null }
        }
    
    private lateinit var gatewayClient: GatewayClient
    
    fun setGatewayClient(client: GatewayClient) {
        gatewayClient = client
    }
    
    suspend fun sendMessage(content: String, attachments: List<Attachment> = emptyList()): Result<String> {
        _isLoading.value = true
        
        val message = MessageContent(
            role = "user",
            content = content,
            attachments = attachments.ifEmpty { null }
        )
        
        val params = ChatSendParams(
            sessionKey = currentSessionKey,
            message = message,
            idempotencyKey = UUID.randomUUID().toString()
        )
        
        // Add user message to UI
        val userMsg = MessageUiModel(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = content,
            attachments = attachments
        )
        _chatMessages.value = _chatMessages.value + userMsg
        
        // Add empty assistant message
        val assistantMsgId = UUID.randomUUID().toString()
        _chatMessages.value = _chatMessages.value + MessageUiModel(
            id = assistantMsgId,
            role = "assistant",
            content = "",
            isStreaming = true
        )
        
        _isGenerating.value = true
        
        return try {
            val request = GatewayRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.send",
                params = json.encodeToJsonElement(ChatSendParams.serializer(), params)
            )
            
            webSocket?.send(json.encodeToString(GatewayRequest.serializer(), request))
            Result.success(assistantMsgId)
        } catch (e: Exception) {
            _isLoading.value = false
            _isGenerating.value = false
            Result.failure(e)
        }
    }
    
    fun appendToMessage(messageId: String, delta: String) {
        _chatMessages.value = _chatMessages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(content = msg.content + delta)
            } else msg
        }
    }
    
    fun finalizeMessage(messageId: String) {
        _isLoading.value = false
        _isGenerating.value = false
        _chatMessages.value = _chatMessages.value.map { msg ->
            if (msg.id == messageId) {
                msg.copy(isStreaming = false)
            } else msg
        }
    }
    
    suspend fun loadHistory() {
        _isLoading.value = true
        try {
            val params = ChatHistoryParams(sessionKey = currentSessionKey, messageLimit = 50)
            val request = GatewayRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.history",
                params = json.encodeToJsonElement(ChatHistoryParams.serializer(), params)
            )
            
            webSocket?.send(json.encodeToString(GatewayRequest.serializer(), request))
        } catch (e: Exception) {
            // Ignore
        }
        _isLoading.value = false
    }
    
    suspend fun abort(): Result<Unit> {
        val params = ChatAbortParams(sessionKey = currentSessionKey)
        val request = GatewayRequest(
            id = UUID.randomUUID().toString(),
            method = "chat.abort",
            params = json.encodeToJsonElement(ChatAbortParams.serializer(), params)
        )
        
        return try {
            webSocket?.send(json.encodeToString(GatewayRequest.serializer(), request))
            _isGenerating.value = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun clearMessages() {
        _chatMessages.value = emptyList()
    }
}

data class MessageUiModel(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false
)
