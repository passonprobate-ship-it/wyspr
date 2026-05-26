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
) {
    val navController = rememberNavController()

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
