package com.nextbench.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nextbench.app.BuildConfig

/** Uses the platform credential picker, falling back cleanly when Firebase config is absent. */
class GoogleCredentialHelper(
    private val credentialManager: CredentialManager,
) {
    suspend fun getIdToken(context: Context): String {
        require(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            "Google sign-in is not configured for this build."
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val response = credentialManager.getCredential(
            context,
            GetCredentialRequest(listOf(option)),
        )
        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Choose a Google account to continue."
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
