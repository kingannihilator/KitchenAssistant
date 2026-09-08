package com.pancakeworks.fridgegrub.ui

import android.media.AudioAttributes
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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.data.IngredientMatcher
import com.pancakeworks.fridgegrub.data.ReadAloudRepository
import com.pancakeworks.fridgegrub.model.AppMode
import com.pancakeworks.fridgegrub.model.Ingredient
import com.pancakeworks.fridgegrub.model.ReadAloudPlaybackMode
import com.pancakeworks.fridgegrub.model.Recipe
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel
import com.pancakeworks.fridgegrub.viewmodel.RecipeViewModel
import com.pancakeworks.fridgegrub.viewmodel.difficultyLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** The read-aloud control's 3-state cycle -- see the doc where it's declared in the composable. */
private enum class ReadAloudState { IDLE, SPEAKING, PAUSED }

// Master switch for the read-aloud (TTS) feature. When false, the TTS engine is never created
// and the button/Previous-Next row/step highlight don't render, rather than rendering disabled --
// consistent with how the rest of the app treats data-quality mitigations (e.g.
// RecipeViewModel.SUPPRESS_BLOB_RECIPES_NEW): a named, reversible flag rather than deleting the
// feature. Re-enabled once AndroidManifest.xml gained the <queries> declaration TextToSpeech
// needs for reliable engine discovery on Android 11+ (see the manifest's comment).
private const val READ_ALOUD_ENABLED = true

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
    // ("Read to me", or "Play next step" once currentStepIndex > 0 -- see the label logic below)
    // -> tap -> SPEAKING ("Pause") -> tap -> PAUSED ("Stop") -> tap -> back to IDLE at step 0.
    // Only an explicit Pause tap reaches PAUSED; a step finishing on its own goes straight back to
    // IDLE instead (see onDone below), whether that means "ready for the next step"
    // ([ReadAloudPlaybackMode.STEP_BY_STEP]) or "finished, ready to start over"
    // ([ReadAloudPlaybackMode.THROUGH], last step). Tapping "Stop" is the one place this cycle
    // resets currentStepIndex to 0 as a deliberate, explicit action.
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

    // Read-aloud speed and playback mode are user preferences, not per-recipe state -- loaded
    // once (no recipe.id key) and persisted via ReadAloudRepository, same mechanism as AppMode.
    // Deliberately NOT keyed on recipe.id: keeping them tied to the same "once per screen
    // instance" lifetime as the DisposableEffect(Unit) below is what lets that effect's listener
    // read the current value directly, with no staleness across a recipe swipe (see
    // speakFromRef/onStepDoneRef's doc for why currentStepIndex/readAloudState need a different
    // fix for the same underlying issue).
    val readAloudRepository = remember { ReadAloudRepository(context) }
    var playbackMode by remember { mutableStateOf(readAloudRepository.loadMode()) }
    var speechRate by remember { mutableStateOf(readAloudRepository.loadSpeed()) }

    // Queues a single direction step, tagged with its own utteranceId so onStart/onDone below can
    // track which one is currently playing. Deliberately queues only one at a time (unlike the
    // old "queue everything up front" approach) so ReadAloudPlaybackMode.STEP_BY_STEP can stop
    // between steps -- see onStepDone's doc for how THROUGH mode still chains automatically.
    fun speakFrom(directions: List<String>, fromIndex: Int) {
        if (!READ_ALOUD_ENABLED) return
        if (fromIndex !in directions.indices) return
        val engine = textToSpeech.value ?: return
        engine.setSpeechRate(speechRate)
        engine.speak(directions[fromIndex], TextToSpeech.QUEUE_FLUSH, null, "step_$fromIndex")
        currentStepIndex = fromIndex
        readAloudState = ReadAloudState.SPEAKING
        hasStartedReading = true
    }

    // DisposableEffect(Unit) registers its listener exactly once for this screen's whole
    // lifetime, including across a recipe swipe (recipe.id changes but this effect doesn't
    // re-run) -- so a closure inside it can only safely read state that lives just as long
    // (playbackMode/speechRate above, or viewModel.detailDirections.value). currentStepIndex/
    // readAloudState are deliberately re-created per recipe.id instead (so switching recipes
    // resets them), which means writing to them -- or calling speakFrom, which also writes them
    // -- from inside that one-time closure would silently target the PREVIOUS recipe's now-
    // detached state once the user has swiped. rememberUpdatedState re-points this callback at a
    // freshly-recomposed lambda every time, so it always closes over the CURRENT recipe's
    // speakFrom/setters rather than whichever recipe was showing when the listener was created.
    val onStepDoneRef = rememberUpdatedState { doneIndex: Int, allDirections: List<String> ->
        val isLastStep = doneIndex >= allDirections.lastIndex
        when {
            isLastStep -> {
                // Reset the controls once the whole recipe has been read, in either mode --
                // back to "Read to me" at step 0, not left sitting on "Stop".
                readAloudState = ReadAloudState.IDLE
                currentStepIndex = 0
                hasStartedReading = false
            }
            playbackMode == ReadAloudPlaybackMode.THROUGH -> speakFrom(allDirections, doneIndex + 1)
            else -> {
                // STEP_BY_STEP: advance the "up next" pointer and highlight, but wait for another
                // tap instead of auto-continuing. Landing on IDLE (not PAUSED) is what makes that
                // next tap play from here rather than the explicit-Stop behavior PAUSED implies.
                currentStepIndex = doneIndex + 1
                readAloudState = ReadAloudState.IDLE
            }
        }
    }

    DisposableEffect(Unit) {
        if (!READ_ALOUD_ENABLED) return@DisposableEffect onDispose {}
        val engine = TextToSpeech(context) { }
        engine.language = Locale.getDefault()
        // Explicit media/music routing -- reported as sounding quiet by default, which usually
        // means the engine fell back to a quieter stream (e.g. accessibility/notification)
        // instead of the media stream most users have turned up. USAGE_MEDIA + STREAM_MUSIC is
        // the loudest commonly-boosted stream on a typical device.
        //
        // CONTENT_TYPE_MUSIC rather than the more obviously-matching CONTENT_TYPE_SPEECH --
        // reported clicking artifacts mid-utterance after this was first added; some engines
        // apply extra speech-specific audio processing (noise suppression, dynamics) under
        // CONTENT_TYPE_SPEECH that can introduce exactly this kind of glitch. Revert to
        // CONTENT_TYPE_SPEECH if this doesn't actually fix it -- it's the more semantically
        // correct value for what this is.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // Fires on the TTS service's own callback thread, not the UI thread -- Compose state
            // must only be written from the main thread, hence the explicit dispatch throughout.
            override fun onStart(utteranceId: String?) {
                val index = utteranceId?.removePrefix("step_")?.toIntOrNull() ?: return
                mainScope.launch(Dispatchers.Main) { currentStepIndex = index }
            }
            override fun onDone(utteranceId: String?) {
                val index = utteranceId?.removePrefix("step_")?.toIntOrNull() ?: return
                // Reads the live direction list (not one captured when this listener was
                // created) since DisposableEffect(Unit) only runs once, before the recipe's
                // directions have necessarily finished loading.
                mainScope.launch(Dispatchers.Main) {
                    onStepDoneRef.value(index, viewModel.detailDirections.value)
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
                if (directions.isNotEmpty()) {
                    idx += 1 // "Directions" header (includes the Read to me button when enabled)
                    if (READ_ALOUD_ENABLED) idx += 1 // Previous/Next row
                }
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

                // Directions section, with the read-aloud control inline in its header (rather
                // than a separate row above) -- it's directions-specific, so it belongs to that
                // section, not floating between Ingredients/Cook and Directions.
                if (directions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Directions", style = MaterialTheme.typography.titleMedium)
                            if (READ_ALOUD_ENABLED) {
                                // A labeled button, not a bare icon -- an icon-only control next
                                // to a text title read as decoration rather than something
                                // tappable.
                                OutlinedButton(
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    onClick = {
                                        when (readAloudState) {
                                            // currentStepIndex is 0 the first time, but sits at
                                            // whatever step just finished otherwise (natural
                                            // completion in STEP_BY_STEP mode lands back on IDLE
                                            // without resetting it -- see onStepDoneRef), so this
                                            // one branch serves both "start reading" and "play the
                                            // next step".
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
                                        ReadAloudState.IDLE ->
                                            if (currentStepIndex > 0) "Play next step" else "Read to me"
                                        ReadAloudState.SPEAKING -> "Pause"
                                        ReadAloudState.PAUSED -> "Stop"
                                    }
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(label)
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }

                    // Previous/Next step controls -- jump to (and read) a specific step directly,
                    // rather than only ever moving linearly through Play. The step counter
                    // doubles as feedback for what Play will read next before you've tapped
                    // anything. Playback-mode and speed controls flank this row rather than
                    // crowding the "Directions" header above.
                    if (READ_ALOUD_ENABLED) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        playbackMode = if (playbackMode == ReadAloudPlaybackMode.THROUGH) {
                                            ReadAloudPlaybackMode.STEP_BY_STEP
                                        } else {
                                            ReadAloudPlaybackMode.THROUGH
                                        }
                                        readAloudRepository.saveMode(playbackMode)
                                    }
                                ) {
                                    Icon(
                                        if (playbackMode == ReadAloudPlaybackMode.THROUGH) {
                                            Icons.AutoMirrored.Filled.PlaylistPlay
                                        } else {
                                            Icons.Filled.RepeatOne
                                        },
                                        contentDescription = if (playbackMode == ReadAloudPlaybackMode.THROUGH) {
                                            "Reads through every step -- tap to switch to one step at a time"
                                        } else {
                                            "Pauses after each step -- tap to switch to reading straight through"
                                        }
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                // Cycles slowest -> fastest, wrapping back to slowest -- a tap
                                // target labeled with the resulting speed reads clearer than a
                                // bare icon for a control whose whole point is a numeric value.
                                TextButton(
                                    onClick = {
                                        val steps = ReadAloudRepository.SPEED_STEPS
                                        val currentIndex = steps.indexOf(speechRate).let { if (it < 0) 0 else it }
                                        speechRate = steps[(currentIndex + 1) % steps.size]
                                        readAloudRepository.saveSpeed(speechRate)
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Speed,
                                        contentDescription = "Read-aloud speed, tap to change",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("${speechRate}x")
                                }
                            }
                        }
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
