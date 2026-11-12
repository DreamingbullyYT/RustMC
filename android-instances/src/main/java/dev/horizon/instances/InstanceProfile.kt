package dev.horizon.instances

data class InstanceProfile(
    val id: String,
    val name: String,
    val directoryPath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean
)
