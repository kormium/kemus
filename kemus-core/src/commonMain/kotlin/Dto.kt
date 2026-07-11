package io.github.kemus

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the REST protocol, shared by the server (kemus-ktor-plugin) and the remote client
 * (kemus-client) so the two cannot drift apart.
 */

@Serializable
data class SetBody(val value: String, val ttlSeconds: Long? = null)

@Serializable
data class CommandBody(val args: List<String>)

@Serializable
data class PublishBody(val message: String)

@Serializable
data class ValueResponse(val value: String)

@Serializable
data class DeletedResponse(val deleted: Long)

@Serializable
data class SubscribersResponse(val subscribers: Int)

@Serializable
data class ErrorResponse(val error: String)
