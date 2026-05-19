package com.keystone.feature.messaging

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.keystone.core.identity.PublicKey
import com.keystone.feature.messaging.screens.ConversationListScreen
import com.keystone.feature.messaging.screens.ConversationScreen

/**
 * Messaging entry composable with its own internal NavController.
 * The host (KeystoneNavHost) mounts this at a single top-level
 * route; we manage list ↔ thread navigation internally.
 *
 * Peer pubkeys are passed through the back stack as a 64-char hex
 * string in the route — small enough to fit, opaque to the URL
 * parser, and decode failures are caught at the Compose boundary
 * with a graceful pop.
 */
@Composable
fun MessagingRoot(
    onBack: () -> Unit = {},
    /**
     * Set by the app shell when the user tapped a message
     * notification — we deep-link straight into that chat instead
     * of stopping at the conversation list.
     */
    initialChatPeerHex: String? = null,
) {
    val nav = rememberNavController()

    androidx.compose.runtime.LaunchedEffect(initialChatPeerHex) {
        if (initialChatPeerHex != null) {
            nav.navigate("messages_thread/$initialChatPeerHex") {
                popUpTo(MessagingRoutes.List) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = nav, startDestination = MessagingRoutes.List) {
        composable(MessagingRoutes.List) {
            ConversationListScreen(
                onOpenThread = { peer ->
                    nav.navigate(MessagingRoutes.threadRoute(peer))
                },
                onBack = onBack,
            )
        }
        composable(
            MessagingRoutes.ThreadPattern,
            arguments = listOf(navArgument("peerHex") { type = NavType.StringType }),
        ) { entry ->
            val peerHex = entry.arguments?.getString("peerHex")
            val peer = peerHex?.let { runCatching { PublicKey(it.hexToBytes()) }.getOrNull() }
            if (peer == null) {
                // Bad arg — pop back to the list instead of rendering
                // a broken screen.
                nav.popBackStack(MessagingRoutes.List, inclusive = false)
                return@composable
            }
            ConversationScreen(
                peer = peer,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

private object MessagingRoutes {
    const val List = "messages_list"
    const val ThreadPattern = "messages_thread/{peerHex}"
    fun threadRoute(peer: PublicKey): String =
        "messages_thread/${peer.bytes.toHex()}"
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex must be even-length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        require(hi != -1 && lo != -1) { "invalid hex char at $i" }
        ((hi shl 4) or lo).toByte()
    }
}
