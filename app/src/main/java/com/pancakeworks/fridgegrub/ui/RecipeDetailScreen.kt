package com.pancakeworks.fridgegrub.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import com.pancakeworks.fridgegrub.ui.theme.FavoriteHeart
import com.pancakeworks.fridgegrub.ui.theme.FridgeMatchGreen
import com.pancakeworks.fridgegrub.ui.theme.FridgeMissingRed
import com.pancakeworks.fridgegrub.ui.theme.IngredientAvailableBgDark
import com.pancakeworks.fridgegrub.ui.theme.IngredientAvailableBgLight
import com.pancakeworks.fridgegrub.ui.theme.IngredientMissingBgDark
import com.pancakeworks.fridgegrub.ui.theme.IngredientMissingBgLight
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
import com.pancakeworks.fridgegrub.data.IngredientMatcher
import com.pancakeworks.fridgegrub.model.AppMode
import com.pancakeworks.fridgegrub.model.Ingredient
import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel
import com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel
import com.pancakeworks.fridgegrub.viewmodel.difficultyLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** The read-aloud control's 3-state cycle -- see the doc where it's declared in the composable. */
private enum class ReadAloudState { IDLE, SPEAKING, PAUSED }

// Master switch for the read-aloud (TTS) feature, off while other features are under test so
// audio playback doesn't get exercised incidentally -- flip back to true to re-enable. When
// false, the TTS engine is never created and the button/Previous-Next row/step highlight don't
// render, rather than rendering disabled -- consistent with how the rest of the app treats
// data-quality mitigations (e.g. RecipeViewModel.SUPPRESS_BLOB_RECIPES_NEW): a named, reversible
// flag rather than deleting the feature.
private const val READ_ALOUD_ENABLED = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    fridgeIngredients: List<Ingredient> = emptyList(),
    onBack: () -> Unit,
    // Swipe left/right to move to the next/previous recipe in whatever list the user opened this
    // one from (search results or favorites) -- see Screen.RecipeDetail in MainActivity, which
    // owns the actual list/index and recomposes this screen with a new `recipe` on navigate.
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onNavigate: (Int) -> Unit = {},
    // Lets a user comparing this recipe's ingredients against their pantry checklist (e.g. "is
    // scallion actually one of my checked pantry items?") check or edit it without backing all
    // the way out to the fridge screen first -- same motivation as RecipeScreen's own pantry icon.
    onOpenPantry: () -> Unit = {},
    viewModel: RecipeViewModel = viewModel(),
    ingredientViewModel: IngredientViewModel = viewModel()
) {
    val ingredients by viewModel.detailIngredients.collectAsState()
    val directions by viewModel.detailDirections.collectAsState()
    val isLoading by viewModel.isLoadingDetail.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isFavorite = favoriteIds.contains(recipe.id)
    val isDark = isSystemInDarkTheme()
    val mode by ingredientViewModel.appMode.collectAsState()
    val pantryItems by ingredientViewModel.pantryItems.collectAsState()

    // Which fridge ingredient covers each recipe line, parallel to [ingredients]. Computed once
    // and used for the checkmarks, through the same IngredientMatcher the search scored with —
    // so this screen can no longer disagree with the card's "X/Y".
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

    // The fridge ingredients cook mode can deduct from -- deliberately built from [coveredBy]'s
    // specific per-line match rather than DetailIngredient.matched's category-only verdict (see
    // its doc): cook mode needs to know *which* fridge row to decrement, not just that some
    // sibling in the same category exists.
    val cookIngredients: List<Ingredient> = remember(coveredBy) {
        coveredBy.filterNotNull().distinctBy { it.id }
    }
    var isCooking by remember(recipe.id) { mutableStateOf(false) }

    // Reads Directions aloud via the platform TTS engine -- no bundled voice data, no network,
    // consistent with the rest of the app. Android's TTS API has no true pause (only stop, which
    // can't resume mid-sentence), so this is a 3-state cycle rather than a plain toggle: IDLE
    // ("Read to me") -> tap -> SPEAKING ("Pause") -> tap (or natural completion) -> PAUSED
    // ("Stop") -> tap -> back to IDLE. PAUSED and SPEAKING both stop at the same step boundary
    // either way -- the distinction is what happens next: from PAUSED, resuming (Previous/Next,
    // or IDLE's own Play) continues from currentStepIndex, while explicitly tapping "Stop"
    // resets currentStepIndex to 0, the one place this cycle offers a real "start over."
    val context = LocalContext.current
    val mainScope = rememberCoroutineScope()
    var readAloudState by remember(recipe.id) { mutableStateOf(ReadAloudState.IDLE) }
    // Which direction is currently playing (or, once playback finishes or before it ever starts,
    // which one would play next / just finished) -- drives both the highlighted row below and the
    // Previous/Next bounds. Starts at 0 so the first step is highlighted as "up next" even before
    // any playback, rather than showing nothing until the first tap.
    var currentStepIndex by remember(recipe.id) { mutableStateOf(0) }
    // Guards the auto-scroll effect below from firing on initial composition (currentStepIndex
    // starts at 0 to highlight the first step, but the screen shouldn't jump straight to
    // Directions before the user has actually started playback or tapped Next/Previous).
    var hasStartedReading by remember(recipe.id) { mutableStateOf(false) }
    val textToSpeech = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        if (!READ_ALOUD_ENABLED) return@DisposableEffect onDispose {}
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
                // directions have necessarily finished loading. Finishing naturally lands on
                // PAUSED, same as an explicit Pause tap -- either way, currentStepIndex is where
                // playback left off, and only an explicit Stop resets it.
                val lastStepId = "step_${viewModel.detailDirections.value.size - 1}"
                if (utteranceId == lastStepId) {
                    mainScope.launch(Dispatchers.Main) { readAloudState = ReadAloudState.PAUSED }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainScope.launch(Dispatchers.Main) { readAloudState = ReadAloudState.IDLE }
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
        if (!READ_ALOUD_ENABLED) return
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
        readAloudState = ReadAloudState.SPEAKING
        hasStartedReading = true
    }

    LaunchedEffect(recipe.id, fridgeIngredients, pantryItems) {
        // Pantry items factor into matching the same way they do for search (see
        // IngredientViewModel.pantryItems' doc) so a card's checkmarks agree with its match ratio
        // -- passed separately, not pre-merged, so a pantry item can't seed category expansion
        // (see RecipeViewModel.loadRecipeDetailNew's doc). The "Cook this recipe" deduction below
        // still operates on the real fridgeIngredients list, untouched -- pantry items have no
        // quantity to deduct.
        viewModel.loadRecipeDetail(recipe.id, fridgeIngredients.map { it.name }, pantryItems.toList())
    }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.pointerInput(recipe.id, hasPrevious, hasNext) {
            // A left swipe (negative delta) advances to the next recipe; a right swipe goes back
            // to the previous one -- the natural "pages flip leftward" direction. Accumulated over
            // the whole gesture rather than acting on every delta, so a single swipe fires at most
            // one navigation regardless of how far the finger travels past the threshold.
            var totalDrag = 0f
            val threshold = 120f
            detectHorizontalDragGestures(
                onDragEnd = { totalDrag = 0f },
                onDragCancel = { totalDrag = 0f }
            ) { change, dragAmount ->
                totalDrag += dragAmount
                if (totalDrag > threshold && hasPrevious) {
                    onNavigate(-1)
                    totalDrag = 0f
                } else if (totalDrag < -threshold && hasNext) {
                    onNavigate(1)
                    totalDrag = 0f
                }
                change.consume()
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(recipe.title) },
                actions = {
                    IconButton(onClick = onOpenPantry) {
                        Icon(Icons.Default.Grain, contentDescription = "Pantry & seasonings")
                    }
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
            val listState = remember(recipe.id) { LazyListState() }
            // Where the Directions section's step rows start in the LazyColumn's flat item
            // index, so the currently-playing step can be scrolled into view. Recomputed
            // whenever the sections above Directions could change size.
            val directionsStartIndex = remember(ingredients, directions, mode, isCooking, cookIngredients) {
                var idx = 1 // metadata row
                if (ingredients.isNotEmpty()) idx += 1 + ingredients.size // header + rows
                if (mode == AppMode.QUANTITY) {
                    idx += 1 // "Cook this recipe" button row
                    if (isCooking) idx += 1 + cookIngredients.size // "Use from fridge" header + rows
                }
                if (READ_ALOUD_ENABLED) idx += 1 // read-aloud row
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
                // Extra bottom padding when the working-mascot cameo is showing (see below), so
                // the list can keep scrolling past its natural end -- otherwise the last
                // Directions row would hit the bottom of the screen and get stuck sitting right
                // behind her forever, with no way to scroll it clear of her head.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (directions.isNotEmpty()) 160.dp else 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metadata row -- servings/difficulty/time are absent for the portion of the
                // corpus that doesn't rate them (see RecipeEntity's doc), so each piece just
                // doesn't render rather than showing a placeholder; ingredient count is always
                // available (it's just the size of the ingredient list below).
                item {
                    val metadataParts = listOfNotNull(
                        recipe.servings?.let { "Serves $it" },
                        "${recipe.ingredientCount} ingredients",
                        difficultyLabel(recipe.difficulty),
                        recipe.timeText
                    )
                    Text(
                        metadataParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (recipe.categories.isNotEmpty()) {
                        Text(
                            recipe.categories.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        val isAvailable = detail.canonical != null && inFridge
                        val baseColor = when {
                            isAvailable -> if (isDark) IngredientAvailableBgDark else IngredientAvailableBgLight
                            isMissing -> if (isDark) IngredientMissingBgDark else IngredientMissingBgLight
                            else -> Color.Transparent
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(baseColor, RoundedCornerShape(4.dp))
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

                // "Cook this recipe" -- only in Full mode, since Basic mode tracks no quantities
                // for it to deduct from. Toggles an inline "Use from fridge" section rather than
                // navigating to a separate screen.
                if (mode == AppMode.QUANTITY) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { isCooking = !isCooking }) {
                            Text(if (isCooking) "Done cooking" else "Cook this recipe")
                        }
                    }
                    if (isCooking) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text("Use from fridge", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                        }
                        if (cookIngredients.isEmpty()) {
                            item {
                                Text(
                                    "None of this recipe's ingredients are specifically matched in your fridge.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(cookIngredients, key = { "cook_${it.id}" }) { fridgeIngredient ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "${fridgeIngredient.name} (${fridgeIngredient.unit})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    CountStepper(
                                        count = fridgeIngredient.count,
                                        onDecrement = { ingredientViewModel.decrementCount(fridgeIngredient.id) },
                                        onIncrement = {},
                                        onSetCount = { ingredientViewModel.setCount(fridgeIngredient.id, it) },
                                        // Cook mode only consumes stock -- no + button here (see
                                        // CountStepper's showIncrement doc). Manually typing a
                                        // higher number via tap-to-edit is still allowed.
                                        showIncrement = false
                                    )
                                }
                            }
                        }
                    }
                }

                // Read-aloud control, sitting right below the ingredient checklist above.
                if (READ_ALOUD_ENABLED) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // A labeled button, not a bare icon -- an icon-only control next to
                        // another text button read as decoration rather than something tappable.
                        OutlinedButton(
                            enabled = directions.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            onClick = {
                                when (readAloudState) {
                                    ReadAloudState.IDLE -> speakFrom(directions, currentStepIndex)
                                    ReadAloudState.SPEAKING -> {
                                        textToSpeech.value?.stop()
                                        readAloudState = ReadAloudState.PAUSED
                                    }
                                    ReadAloudState.PAUSED -> {
                                        currentStepIndex = 0
                                        readAloudState = ReadAloudState.IDLE
                                    }
                                }
                            }
                        ) {
                            val icon = when (readAloudState) {
                                ReadAloudState.IDLE -> Icons.Filled.PlayArrow
                                ReadAloudState.SPEAKING -> Icons.Filled.Pause
                                ReadAloudState.PAUSED -> Icons.Filled.Stop
                            }
                            val label = when (readAloudState) {
                                ReadAloudState.IDLE -> "Read to me"
                                ReadAloudState.SPEAKING -> "Pause"
                                ReadAloudState.PAUSED -> "Stop"
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label)
                        }
                    }
                }
                }

                // Previous/Next step controls -- jump to (and read) a specific step directly,
                // rather than only ever moving linearly through Play. Shown once there's
                // something to navigate; the step counter doubles as feedback for what Play will
                // read next before you've tapped anything.
                if (READ_ALOUD_ENABLED && directions.isNotEmpty()) {
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
                        val isCurrent = READ_ALOUD_ENABLED && index == currentStepIndex
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
            // The mascot, hard at work rolling dough, anchored to the screen (not the list) so
            // she stays put at the bottom-center as you scroll -- deliberately overlapping the
            // end of the Directions section below her, the same "she's on top, just scroll past
            // her" tradeoff as the corner cameos on the results/favorites screens.
            if (directions.isNotEmpty()) {
                WorkingMascotScene(modifier = Modifier.align(Alignment.BottomCenter))
            }
            } // end Box
        }
    }
}
