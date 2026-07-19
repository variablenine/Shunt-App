package app.shunt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shunt.app.plan.PlanViewModel
import app.shunt.app.ui.PlanActions
import app.shunt.app.ui.PlanScreen
import app.shunt.app.ui.theme.ShuntTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ShuntApplication).container

        setContent {
            ShuntTheme {
                val vm: PlanViewModel = viewModel(factory = container.planViewModelFactory())
                val state by vm.state.collectAsStateWithLifecycle()

                // Refresh camera data on open; schedule no background work.
                LaunchedEffect(Unit) { vm.onOpen() }

                PlanScreen(
                    state = state,
                    hereKeyMissing = container.hereKeyMissing,
                    actions = PlanActions(
                        onQueryChange = vm::onQueryChange,
                        onSuggestionSelected = vm::onSuggestionSelected,
                        onFavoriteSelected = vm::onFavoriteSelected,
                        onGo = vm::onGo,
                        onRetryPush = vm::onRetryPush,
                        onDismiss = vm::onDismissResult,
                        onSaveHome = { vm.onSaveFavorite(app.shunt.app.plan.FavoriteSlot.HOME, it) },
                        onSaveWork = { vm.onSaveFavorite(app.shunt.app.plan.FavoriteSlot.WORK, it) },
                    ),
                )
            }
        }
    }
}
