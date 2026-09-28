package com.nextbench.core.common

enum class NbError(val message: String) {
    Network("No internet connection. Please try again."),
    PermissionDenied("You don't have permission to do that."),
    NotFound("This item no longer exists."),
    RateLimited("Too many requests. Please slow down."),
    Unauthenticated("Please sign in to continue."),
    Unknown("Something went wrong. Please try again.");

    companion object {
        fun fromException(e: Exception): NbError = when {
            e.message?.contains("PERMISSION_DENIED") == true -> PermissionDenied
            e.message?.contains("NOT_FOUND") == true -> NotFound
            e.message?.contains("RESOURCE_EXHAUSTED") == true -> RateLimited
            e.message?.contains("UNAUTHENTICATED") == true -> Unauthenticated
            e.message?.contains("UNAVAILABLE") == true ||
            e.message?.contains("UnknownHostException") == true -> Network
            else -> Unknown
        }
    }
}
