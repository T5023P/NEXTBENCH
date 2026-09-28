package com.nextbench.core.common

sealed interface NbResult<out T> {
    data class Success<T>(val data: T) : NbResult<T>
    data class Failure(val error: NbError) : NbResult<Nothing>
}

inline fun <T> runCatchingNb(block: () -> T): NbResult<T> = try {
    NbResult.Success(block())
} catch (e: Exception) {
    NbResult.Failure(NbError.fromException(e))
}
