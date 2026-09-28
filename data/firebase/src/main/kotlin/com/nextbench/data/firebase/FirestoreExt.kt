package com.nextbench.data.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun DocumentReference.snapshotFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val listener = addSnapshotListener { snap, err ->
        if (err != null) { close(err); return@addSnapshotListener }
        if (snap != null) trySend(snap)
    }
    awaitClose { listener.remove() }
}

fun Query.snapshotFlow(): Flow<QuerySnapshot> = callbackFlow {
    val listener = addSnapshotListener { snap, err ->
        if (err != null) { close(err); return@addSnapshotListener }
        if (snap != null) trySend(snap)
    }
    awaitClose { listener.remove() }
}
