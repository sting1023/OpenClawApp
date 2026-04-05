package com.sting.openclaw.data.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ============== Gateway Protocol Frames ==============

@Serializable
data class GatewayRequest(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class GatewayResponse(
    val type: String,
    val id: String? = null,
    val ok: Boolean? = null,
    val payload: JsonElement? = null,
    val error: JsonElement? = null
)

@Serializable
data class GatewayEvent(
    val type: String,
    val event: String? = null,
    val payload: JsonElement? = null,
    val seq: Int? = null,
    val stateVersion: Int? = null
)

// ============== Connect ==============

@Serializable
data class ConnectParams(
    val auth: AuthToken,
    val role: String = "operator",
    val scopes: List<String> = listOf("operator.read", "operator.write"),
    val minProtocol: Int = 3,
    val maxProtocol: Int = 3
)

@Serializable
data class AuthToken(
    val token: String
)

@Serializable
data class ConnectChallenge(
    val nonce: String,
    val ts: Long
)

@Serializable
data class HelloOk(
    val auth: HelloOkAuth? = null
)

@Serializable
data class HelloOkAuth(
    val deviceToken: String? = null
)

// ============== Chat ==============

@Serializable
data class ChatSendParams(
    val sessionKey: String,
    val message: MessageContent,
    val idempotencyKey: String
)

@Serializable
data class MessageContent(
    val role: String = "user",
    val content: String,
    val attachments: List<Attachment>? = null
)

@Serializable
data class Attachment(
    val mimeType: String? = null,
    val content: String? = null,
    val url: String? = null
)

@Serializable
data class ChatSendResponse(
    val runId: String,
    val status: String
)

@Serializable
data class ChatHistoryParams(
    val sessionKey: String,
    val messageLimit: Int = 50
)

@Serializable
data class ChatHistoryResponse(
    val messages: List<Message>
)

@Serializable
data class Message(
    val role: String,
    val content: String,
    val attachments: List<Attachment>? = null
)

@Serializable
data class ChatAbortParams(
    val sessionKey: String
)

@Serializable
data class ChatEvent(
    val runId: String? = null,
    val status: String? = null,
    val delta: ChatDelta? = null,
    val type: String? = null,
    val tool: String? = null,
    val input: JsonElement? = null,
    val attachments: List<Attachment>? = null
)

@Serializable
data class ChatDelta(
    val content: String? = null
)

// ============== Models ==============

@Serializable
data class ModelsListResponse(
    val models: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val name: String,
    val provider: String? = null
)

// ============== Tick ==============

@Serializable
data class TickEvent(
    val ts: Long
)
