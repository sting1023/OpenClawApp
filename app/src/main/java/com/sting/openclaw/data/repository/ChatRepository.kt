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
    private val gatewayClient: GatewayClient,
    private val json: Json
) {
    private val _chatMessages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val chatMessages: StateFlow<List<MessageUiModel>> = _chatMessages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    
    private var currentSessionKey = "agent:main:main"
    private var pendingAssistantMsgId: String? = null
    
    // Expose chat events from gatewayClient
    val chatEvents = gatewayClient.events
        .filter { it.event == "chat" }
    
    suspend fun sendMessage(content: String): Result<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.success("")
        
        _isLoading.value = true
        
        val message = MessageContent(
            role = "user",
            content = trimmed,
            attachments = null
        )
        
        val params = ChatSendParams(
            sessionKey = currentSessionKey,
            message = message,
            idempotencyKey = UUID.randomUUID().toString()
        )
        
        // Add user message to UI
        val userMsgId = UUID.randomUUID().toString()
        _chatMessages.value = _chatMessages.value + MessageUiModel(
            id = userMsgId,
            role = "user",
            content = trimmed,
            attachments = emptyList()
        )
        
        // Add empty assistant message placeholder
        val assistantMsgId = UUID.randomUUID().toString()
        pendingAssistantMsgId = assistantMsgId
        _chatMessages.value = _chatMessages.value + MessageUiModel(
            id = assistantMsgId,
            role = "assistant",
            content = "",
            isStreaming = true
        )
        
        _isGenerating.value = true
        
        return try {
            val requestId = UUID.randomUUID().toString()
            val request = GatewayRequest(
                id = requestId,
                method = "chat.send",
                params = json.encodeToJsonElement(ChatSendParams.serializer(), params)
            )
            
            gatewayClient.sendRawMessage(json.encodeToString(GatewayRequest.serializer(), request))
            Result.success(assistantMsgId)
        } catch (e: Exception) {
            e.printStackTrace()
            _isLoading.value = false
            _isGenerating.value = false
            pendingAssistantMsgId = null
            // Remove placeholder on failure
            _chatMessages.value = _chatMessages.value.filter { it.id != assistantMsgId }
            Result.failure(e)
        }
    }
    
    fun appendToMessage(delta: String) {
        pendingAssistantMsgId?.let { id ->
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == id) {
                    msg.copy(content = msg.content + delta)
                } else msg
            }
        }
    }
    
    fun finalizeMessage() {
        _isLoading.value = false
        _isGenerating.value = false
        pendingAssistantMsgId?.let { id ->
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.id == id) {
                    msg.copy(isStreaming = false)
                } else msg
            }
        }
        pendingAssistantMsgId = null
    }
    
    fun appendErrorMessage(content: String) {
        val id = UUID.randomUUID().toString()
        _chatMessages.value = _chatMessages.value + MessageUiModel(
            id = id,
            role = "assistant",
            content = "Error: $content",
            attachments = emptyList(),
            isStreaming = false
        )
        _isLoading.value = false
        _isGenerating.value = false
    }
    
    suspend fun loadHistory() {
        try {
            val params = ChatHistoryParams(sessionKey = currentSessionKey, messageLimit = 50)
            val request = GatewayRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.history",
                params = json.encodeToJsonElement(ChatHistoryParams.serializer(), params)
            )
            gatewayClient.sendRawMessage(json.encodeToString(GatewayRequest.serializer(), request))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun abort() {
        try {
            val params = ChatAbortParams(sessionKey = currentSessionKey)
            val request = GatewayRequest(
                id = UUID.randomUUID().toString(),
                method = "chat.abort",
                params = json.encodeToJsonElement(ChatAbortParams.serializer(), params)
            )
            gatewayClient.sendRawMessage(json.encodeToString(GatewayRequest.serializer(), request))
            _isGenerating.value = false
            finalizeMessage()
        } catch (e: Exception) {
            e.printStackTrace()
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
