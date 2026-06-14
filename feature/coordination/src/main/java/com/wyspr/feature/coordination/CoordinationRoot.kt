package com.wyspr.feature.coordination

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wyspr.feature.coordination.screens.CreateEventScreen
import com.wyspr.feature.coordination.screens.EventDetailScreen
import com.wyspr.feature.coordination.screens.EventListScreen

@Composable
fun CoordinationRoot(
    onBack: () -> Unit,
    initialEventIdHex: String? = null,
) {
    val navController = rememberNavController()

    // Deep-link from an event notification: jump straight to the
    // event's detail screen (pushed on top of the list, so Back
    // lands on the Events list as usual).
    LaunchedEffect(initialEventIdHex) {
        val csv = initialEventIdHex?.hexToEventCsv() ?: return@LaunchedEffect
        navController.navigate("detail/$csv")
    }

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            val vm: EventListViewModel = hiltViewModel()
            LaunchedEffect(Unit) { vm.bind() }
            val upcoming by vm.upcoming.collectAsStateWithLifecycle()
            val past by vm.past.collectAsStateWithLifecycle()
            EventListScreen(
                upcoming = upcoming,
                past = past,
                onEventClick = { id ->
                    navController.navigate("detail/${id.joinToString(",") { it.toString() }}")
                },
                onCreateClick = { navController.navigate("create") },
                onBack = onBack,
            )
        }

        composable("detail/{eventIdCsv}") { backStack ->
            val csv = backStack.arguments?.getString("eventIdCsv") ?: ""
            val eventId = csv.split(",").map { it.trim().toByte() }.toByteArray()
            val vm: EventDetailViewModel = hiltViewModel()
            LaunchedEffect(eventId.toList()) { vm.bind(eventId) }
            val event by vm.event.collectAsStateWithLifecycle()
            val rsvps by vm.rsvps.collectAsStateWithLifecycle()
            val ownPub by vm.ownPub.collectAsStateWithLifecycle()
            EventDetailScreen(
                event = event,
                rsvps = rsvps,
                ownPub = ownPub,
                onRsvp = { status -> vm.rsvp(status) },
                onCancel = { vm.cancelEvent() },
                onBack = { navController.popBackStack() },
            )
        }

        composable("create") {
            val vm: CreateEventViewModel = hiltViewModel()
            val done by vm.done.collectAsStateWithLifecycle()
            LaunchedEffect(done) { if (done) navController.popBackStack() }
            CreateEventScreen(
                onCreate = { title, desc, loc, start, end ->
                    vm.create(title, desc, loc, start, end)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Converts a hex event id (from a notification deep-link) into the
 * signed-decimal CSV the inner `detail/{eventIdCsv}` route expects.
 * Returns null on malformed input.
 */
private fun String.hexToEventCsv(): String? {
    if (isEmpty() || length % 2 != 0) return null
    val bytes = ByteArray(length / 2)
    for (i in bytes.indices) {
        val v = substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        bytes[i] = v.toByte()
    }
    return bytes.joinToString(",") { it.toString() }
}
