package com.example.kitchenassistant.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import com.example.kitchenassistant.ui.theme.FavoriteHeart
import com.example.kitchenassistant.ui.theme.FridgeMatchGreen
import com.example.kitchenassistant.ui.theme.FridgeMissingRed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kitchenassistant.data.IngredientMatcher
import com.example.kitchenassistant.model.Ingredient
import com.example.kitchenassistant.model.Recipe
import com.example.kitchenassistant.viewmodel.RecipeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    fridgeIngredients: List<Ingredient> = emptyList(),
    onBack: () -> Unit,
    // Keyed by Ingredient.id, not name: two fridge entries can share a name prefix, and the old
    // name-based lookup resolved "eggplant" to "egg" and decremented the wrong one.
    onDeductIngredient: (String) -> Unit = {},
    onSetCount: (String, Int) -> Unit = { _, _ -> },
    viewModel: RecipeViewModel = viewModel()
) {
    val ingredients by viewModel.detailIngredients.collectAsState()
    val directions by viewModel.detailDirections.collectAsState()
    val isLoading by viewModel.isLoadingDetail.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isFavorite = favoriteIds.contains(recipe.id)

    // Toggles a highlight over the missing (X) rows in the Ingredients checklist above -- always
    // enabled (no disabled/greyed state), since "what am I missing" is meaningful to ask even
    // with nothing at all in the fridge. missingHighlightAlpha animates a brighter flash down to
    // a steady tint on turn-on ("blink once"), and fades out on turn-off.
    var highlightMissing by remember { mutableStateOf(false) }
    val missingHighlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlightMissing) {
        if (highlightMissing) {
            missingHighlightAlpha.snapTo(0.7f)
            missingHighlightAlpha.animateTo(0.25f, animationSpec = tween(600))
        } else {
            missingHighlightAlpha.animateTo(0f, animationSpec = tween(200))
        }
    }

    // Which fridge ingredient covers each recipe line, parallel to [ingredients]. Computed once
    // and used for both the checkmarks and the cook-mode rows, through the same IngredientMatcher
    // the search scored with — so this screen can no longer disagree with the card's "X/Y".
    val coveredBy: List<Ingredient?> = remember(ingredients, fridgeIngredients) {
        val fridgeTerms = fridgeIngredients.map { it to IngredientMatcher.parseFridge(it.name) }
        ingredients.map { detail ->
            val canonical = detail.canonical ?: return@map null
            val recipeTerm = IngredientMatcher.parseRecipe(canonical)
            fridgeTerms.firstOrNull { (_, fridgeTerm) ->
                IngredientMatcher.matches(fridgeTerm, recipeTerm)
            }?.first
        }
    }
    val cookIngredients = remember(coveredBy) { coveredBy.filterNotNull().distinctBy { it.id } }

    // Reads Directions aloud via the platform TTS engine -- no bundled voice data, no network,
    // consistent with the rest of the app. Android's TTS API has no true pause (only stop, which
    // can't resume mid-sentence), so Stop always leaves off at a step boundary, never mid-sentence
    // -- Play then resumes from that same step (currentStepIndex) rather than restarting from the
    // very first one, which is as close to "pause/resume" as the platform API actually allows.
    val context = LocalContext.current
    val mainScope = rememberCoroutineScope()
    var isSpeaking by remember { mutableStateOf(false) }
    // Which direction is currently playing (or, once playback finishes or before it ever starts,
    // which one would play next / just finished) -- drives both the highlighted row below and the
    // Previous/Next bounds. Starts at 0 so the first step is highlighted as "up next" even before
    // any playback, rather than showing nothing until the first tap.
    var currentStepIndex by remember { mutableStateOf(0) }
    // Guards the auto-scroll effect below from firing on initial composition (currentStepIndex
    // starts at 0 to highlight the first step, but the screen shouldn't jump straight to
    // Directions before the user has actually started playback or tapped Next/Previous).
    var hasStartedReading by remember { mutableStateOf(false) }
    val textToSpeech = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { }
        engine.language = Locale.getDefault()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // Fires on the TTS service's own callback thread, not the UI thread -- Compose state
            // must only be written from the main thread, hence the explicit dispatch throughout.
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.removePrefix("step_")?.toIntOrNull() ?: return
                mainScope.launch(Dispatchers.Main) { currentStepIndex = index }
            }
            override fun onDone(utteranceId: String?) {
                // Reads the live direction count (not one captured when this listener was
                // created) since DisposableEffect(Unit) only runs once, before the recipe's
                // directions have necessarily finished loading.
                val lastStepId = "step_${viewModel.detailDirections.value.size - 1}"
                if (utteranceId == lastStepId) {
                    mainScope.launch(Dispatchers.Main) { isSpeaking = false }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainScope.launch(Dispatchers.Main) { isSpeaking = false }
            }
        })
        textToSpeech.value = engine
        onDispose {
            engine.stop()
            engine.shutdown()
        }
    }

    // Queues every direction from [fromIndex] onward, each with its own utteranceId so
    // onStart/onDone above can track which one is currently playing.
    fun speakFrom(directions: List<String>, fromIndex: Int) {
        val engine = textToSpeech.value ?: return
        directions.forEachIndexed { index, step ->
            if (index < fromIndex) return@forEachIndexed
            engine.speak(
                step,
                if (index == fromIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "step_$index"
            )
        }
        currentStepIndex = fromIndex
        isSpeaking = true
        hasStartedReading = true
    }

    LaunchedEffect(recipe.id, fridgeIngredients) {
        viewModel.loadRecipeDetail(recipe.id, fridgeIngredients.map { it.name })
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(recipe.title) },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(recipe.id) }) {
                        Icon(
                            // A heart, not a star -- the star is already used for prioritized
                            // fridge ingredients (see IngredientScreen), a different concept
                            // (boosts ranking dynamically) from favoriting a specific recipe
                            // (a static pin). Same icon for both read as "the same feature."
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (isFavorite) FavoriteHeart else LocalContentColor.current,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            val listState = rememberLazyListState()
            // Where the Directions section's step rows start in the LazyColumn's flat item
            // index, so the currently-playing step can be scrolled into view. Recomputed
            // whenever the sections above Directions could change size.
            val directionsStartIndex = remember(ingredients, directions) {
                var idx = 1 // metadata row
                if (ingredients.isNotEmpty()) idx += 1 + ingredients.size // header + rows
                idx += 1 // cook button / read-aloud row
                if (directions.isNotEmpty()) idx += 2 // Previous/Next row + "Directions" header
                idx
            }
            LaunchedEffect(currentStepIndex) {
                if (hasStartedReading && directions.isNotEmpty()) {
                    listState.animateScrollToItem(directionsStartIndex + currentStepIndex)
                }
            }
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metadata row
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (recipe.servings != null) {
                            Text("Serves ${recipe.servings}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (recipe.categories.isNotEmpty()) {
                            Text(
                                recipe.categories.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Ingredients section
                if (ingredients.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Ingredients", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(ingredients) { index, detail ->
                        // detail.matched is the new corpus's category-aware verdict (see its doc);
                        // the legacy corpus leaves it null and falls back to the direct-match-only
                        // coveredBy computed above, exactly as before.
                        val inFridge = detail.matched ?: (coveredBy.getOrNull(index) != null)
                        val isMissing = detail.canonical != null && !inFridge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isMissing) FridgeMissingRed.copy(alpha = missingHighlightAlpha.value)
                                    else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(4.dp)
                        ) {
                            if (detail.canonical == null) {
                                // A section heading or a line the corpus couldn't parse. It can't
                                // be matched either way, so claiming it's missing would be a lie.
                                Spacer(Modifier.size(16.dp))
                            } else {
                                Icon(
                                    imageVector = if (inFridge) Icons.Filled.Check else Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = if (inFridge) FridgeMatchGreen else FridgeMissingRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(detail.line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Highlight-missing toggle + read-aloud control, sitting right below the
                // ingredient checklist above so the effect (a flash on the X rows right there) is
                // immediately visible without scrolling -- the previous cook-mode toggle here
                // revealed a section far below Directions, so tapping it looked like it did
                // nothing from up here.
                item {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { highlightMissing = !highlightMissing },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (highlightMissing) "Hide what's missing" else "Highlight what's missing")
                        }
                        Spacer(Modifier.width(8.dp))
                        // A labeled button, not a bare icon -- an icon-only control next to a
                        // full-width text button read as decoration rather than something
                        // tappable.
                        OutlinedButton(
                            enabled = directions.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            onClick = {
                                if (isSpeaking) {
                                    textToSpeech.value?.stop()
                                    isSpeaking = false
                                } else {
                                    // Resumes from currentStepIndex, not always 0 -- after a Stop
                                    // this continues from the step that was playing, the closest
                                    // this platform API gets to real pause/resume.
                                    speakFrom(directions, currentStepIndex)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isSpeaking) "Stop" else "Play")
                        }
                    }
                }

                // Previous/Next step controls -- jump to (and read) a specific step directly,
                // rather than only ever moving linearly through Play. Shown once there's
                // something to navigate; the step counter doubles as feedback for what Play will
                // read next before you've tapped anything.
                if (directions.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                enabled = currentStepIndex > 0,
                                onClick = { speakFrom(directions, currentStepIndex - 1) }
                            ) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous step")
                            }
                            Text(
                                "Step ${currentStepIndex + 1} of ${directions.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                enabled = currentStepIndex < directions.lastIndex,
                                onClick = { speakFrom(directions, currentStepIndex + 1) }
                            ) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "Next step")
                            }
                        }
                    }
                }

                // Directions section
                if (directions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Directions", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(directions) { index, step ->
                        val isCurrent = index == currentStepIndex
                        val highlightShape = RoundedCornerShape(6.dp)
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    highlightShape
                                )
                                .padding(6.dp)
                        )
                    }
                }

                // Cook section — ingredient rows with editable count and minus button. Always
                // shown once there's something to deduct, rather than gated behind a toggle: it's
                // a natural continuation of reading through Directions, and a remote toggle up
                // near Ingredients had the same "looks like nothing happened" problem the
                // highlight button above just got fixed for.
                if (cookIngredients.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("Use from fridge", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                    itemsIndexed(cookIngredients, key = { _, ing -> ing.id }) { _, ingredient ->
                        CookIngredientRow(
                            ingredient = ingredient,
                            onDeduct = { onDeductIngredient(ingredient.id) },
                            onSetCount = { onSetCount(ingredient.id, it) }
                        )
                    }
                }

                if (ingredients.isEmpty() && directions.isEmpty()) {
                    item {
                        Text(
                            "No details available for this recipe.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            LazyListScrollbar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(6.dp)
            )
            } // end Box
        }
    }
}

@Composable
private fun CookIngredientRow(
    ingredient: Ingredient,
    onDeduct: () -> Unit,
    onSetCount: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${ingredient.name} (${ingredient.unit})",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        CountStepper(
            count = ingredient.count,
            onDecrement = onDeduct,
            onIncrement = { onSetCount(ingredient.count + 1) },
            onSetCount = onSetCount
        )
    }
}
