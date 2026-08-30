package com.pancakeworks.fridgegrub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
// import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pancakeworks.fridgegrub.model.AppMode
import com.pancakeworks.fridgegrub.model.Ingredient
import com.pancakeworks.fridgegrub.viewmodel.IngredientViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Units offered by both the add-ingredient form and each fridge row's unit dropdown. */
private val UNIT_OPTIONS = listOf(
    "units", "ounces", "cups", "grams", "kilograms",
    "pounds", "tablespoons", "teaspoons",
    "quarts", "pints", "gallons", "liters", "milliliters",
    "cloves", "cans", "bunches", "slices"
)

/**
 * Root screen composable for the ingredient management UI.
 *
 * Observes state from [IngredientViewModel] via StateFlow and passes events back up to the
 * ViewModel through callbacks. The screen is split into:
 *  - A top app bar with the app title.
 *  - A scrollable [LazyColumn] containing the add form followed by the ingredient list.
 *  - A bottom bar with a "Find Recipes" action.
 *
 * @param onFindRecipes   Called when the user taps "Find Recipes"; receives the names of all
 *                        ingredients currently in the fridge, followed by the subset that are
 *                        starred as prioritized (boosts those recipes' ranking downstream).
 * @param onViewFavorites Called when the user taps the top-bar heart shortcut, to navigate to the
 *                        favorites list. Takes no fridge snapshot -- that screen is intentionally
 *                        fridge-independent (see RecipeViewModel.favoriteRecipes).
 * @param viewModel       Injected automatically by Compose; can be overridden in tests/previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientScreen(
    onFindRecipes: (fridgeIngredients: List<String>, prioritizedIngredients: List<String>, pantryIngredients: List<String>) -> Unit = { _, _, _ -> },
    onViewFavorites: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenPantry: () -> Unit = {},
    viewModel: IngredientViewModel = viewModel()
) {
    // Collect the latest values from each StateFlow; any change triggers recomposition.
    val ingredients by viewModel.ingredients.collectAsState()
    // Display order only -- case-insensitive alphabetical, independent of the backing list's
    // insertion order (which FridgeRepository just persists as-is and nothing else relies on).
    val sortedIngredients = remember(ingredients) { ingredients.sortedBy { it.name.lowercase() } }
    val searchQuery by viewModel.searchQuery.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val isQueryValid by viewModel.isQueryValid.collectAsState()
    val hasNoRecipeMatch by viewModel.hasNoRecipeMatch.collectAsState()
    val mode by viewModel.appMode.collectAsState()
    val pantryItems by viewModel.pantryItems.collectAsState()

    // Local UI state for the expiration date picker — lives here so it resets when the form is
    // submitted and is not part of the permanent ingredient data until the user taps Add.
    var expirationDate by remember { mutableStateOf<Long?>(null) }

    // Shared date formatter used both in the add form chip and on ingredient cards.
    val dateFormat = remember { SimpleDateFormat("MM/yy", Locale.getDefault()) }

    // Used to clear focus (and thus dismiss the keyboard / exit count edit mode) when the
    // user taps anywhere outside an active text field.
    val focusManager = LocalFocusManager.current

    // Hoisted out of AddIngredientCard so clearFocusOnTap's wrapped composables (quick-add row,
    // ingredient cards, bottom bar) can check whether the add form's own field is actually
    // focused before clearing focus -- see clearFocusOnTap's doc for why "always clear" was wrong.
    var isAddFormFieldFocused by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Delete-with-undo safety net, replacing what used to be an instant delete below a count of
    // 5 and a confirm dialog at or above it -- an arbitrary threshold that made "how safe is
    // this delete" depend on quantity. Rendered as a row *inline in the fridge list, at the
    // deleted item's own position* (see PendingDelete/UndoRow below) rather than a Material
    // Snackbar docked to the bottom of the screen -- the whole point of undo is reversing what
    // you just did, and that's a shorter reach right where your finger already is than the
    // opposite corner of the screen. Only one pending delete is tracked at a time: deleting a
    // second item while the first's undo window is still open finalizes the first (matches how
    // a second Snackbar would have dismissed the first one anyway).
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    fun deleteWithUndo(ingredient: Ingredient, index: Int) {
        pendingDelete?.autoDismissJob?.cancel()
        viewModel.removeIngredient(ingredient.id)
        val job = scope.launch {
            delay(4000)
            pendingDelete = null
        }
        pendingDelete = PendingDelete(ingredient, index, job)
    }

    // Explains what tapping the mode-toggle button will switch to, before it actually switches --
    // the icon alone doesn't carry enough meaning to make an instant, silent toggle safe.
    var showModeDialog by remember { mutableStateOf(false) }
    if (showModeDialog) {
        val targetMode = if (mode == AppMode.QUANTITY) AppMode.CHECKLIST else AppMode.QUANTITY
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(if (targetMode == AppMode.CHECKLIST) "Switch to Checklist mode?" else "Switch to Quantity mode?") },
            text = {
                Text(
                    if (targetMode == AppMode.CHECKLIST)
                        "Checklist mode just tracks whether you have an ingredient, with no quantities or units."
                    else
                        "Quantity mode tracks exact quantities and units, and lets you deduct ingredients from your fridge while cooking a recipe."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setAppMode(targetMode); showModeDialog = false }) {
                    Text("Switch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kitchen Assistant") },
                actions = {
                    IconButton(onClick = { showModeDialog = true }) {
                        Icon(
                            // A scale for Quantity mode (weighing/measuring exact amounts) vs a
                            // checklist for Checklist mode (just checking off what's on hand) --
                            // each icon reads as the mode currently active, not the one it'd switch to.
                            imageVector = if (mode == AppMode.QUANTITY) Icons.Default.Scale else Icons.Default.Checklist,
                            contentDescription = if (mode == AppMode.QUANTITY) "Quantity mode -- tap to switch to Checklist mode" else "Checklist mode -- tap to switch to Quantity mode"
                        )
                    }
                    IconButton(onClick = onOpenPantry) {
                        Icon(Icons.Default.Grain, contentDescription = "Pantry & seasonings")
                    }
                    IconButton(onClick = onViewFavorites) {
                        FavoritesShortcutIcon()
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Default.Info, contentDescription = "Recipe sources & licenses")
                    }
                }
            )
        },
        bottomBar = {
            // Elevated surface so the bar stands out from the list content behind it.
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding() // respect the system navigation bar inset
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        // Tapping this bar is "outside the manual add form" too.
                        .clearFocusOnTap(focusManager) { isAddFormFieldFocused },
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Disabled until there's something in the fridge to search with. Starring is
                    // optional — it boosts ranking rather than gating the search.
                    Button(
                        onClick = {
                            // Pantry items are passed to onFindRecipes separately, not merged in
                            // here -- RecipeViewModel needs to know which matches trace back to a
                            // real fridge item vs. a pantry one, to keep pantry-only matches from
                            // outranking real ones (see RecipeMatch.usesRealFridgeItem's doc).
                            onFindRecipes(
                                ingredients.map { it.name },
                                ingredients.filter { it.isPrioritized }.map { it.name },
                                pantryItems.toList()
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = ingredients.isNotEmpty()
                    ) {
//                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
//                        Spacer(Modifier.width(4.dp))
                        Text("Find Recipes")
                    }
                }
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(Unit) {
                    // Clear focus on any tap that isn't consumed by a child composable.
                    // This dismisses the keyboard and exits count-edit mode.
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
        ) {
            // Quick-add row and the manual add form are fixed above the scrollable fridge
            // list below, so they never scroll out of view.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quick-add thumbnails for common vegetables and meats.
                QuickAddRow(
                    onQuickAdd = { name -> viewModel.quickAddIngredient(name) },
                    // Tapping a thumbnail is "outside the manual add form" too.
                    modifier = Modifier.clearFocusOnTap(focusManager) { isAddFormFieldFocused }
                )

                AddIngredientCard(
                    searchQuery = searchQuery,
                    suggestions = suggestions,
                    isQueryValid = isQueryValid,
                    hasNoRecipeMatch = hasNoRecipeMatch,
                    expirationDate = expirationDate,
                    dateFormat = dateFormat,
                    mode = mode,
                    onFieldFocusChange = { isAddFormFieldFocused = it },
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onSuggestionSelect = { name -> viewModel.selectSuggestion(name) },
                    onDismissSuggestions = { viewModel.clearSuggestions() },
                    onDateSelect = { expirationDate = it },
                    onClearDate = { expirationDate = null },
                    onAdd = { count, unit ->
                        viewModel.addIngredient(searchQuery, expirationDate, count, unit)
                        expirationDate = null // reset the date picker for the next entry
                    }
                )
            }

            // The fridge list gets the remaining vertical space and scrolls on its own,
            // independent of the fixed quick-add row and add form above it.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The undo row for [pendingDelete] is spliced into the display list at the index
                // the ingredient was removed from, so it appears right below where that card used
                // to be rather than at a fixed screen position. Computed here, not inside the
                // LazyColumn's content lambda -- that lambda is a LazyListScope DSL builder, not
                // a composable context, so remember() can't be called directly inside it.
                val rows: List<FridgeRow> = remember(sortedIngredients, pendingDelete) {
                    val items = sortedIngredients.map { FridgeRow.Item(it) }
                    val pending = pendingDelete
                    if (pending == null) {
                        items
                    } else {
                        items.toMutableList<FridgeRow>().apply {
                            add(pending.index.coerceIn(0, size), FridgeRow.Undo(pending))
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Only render the section header and list when there is at least one ingredient
                    // -- or a just-deleted one is still showing its undo row (see PendingDelete).
                    if (ingredients.isEmpty() && pendingDelete == null) {
                        item(key = "empty_fridge") {
                            EmptyFridgeMessage()
                        }
                    } else {
                        item(key = "header_ingredients") {
                            Text(
                                "My Fridge",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        // Each row gets a stable key (the ingredient's UUID, prefixed for the undo
                        // variant so it never collides with a real card sharing the same
                        // underlying id) so Compose can animate additions/removals correctly.
                        items(
                            rows,
                            key = { row ->
                                when (row) {
                                    is FridgeRow.Item -> row.ingredient.id
                                    is FridgeRow.Undo -> "undo_${row.pendingDelete.ingredient.id}"
                                }
                            }
                        ) { row ->
                            when (row) {
                                is FridgeRow.Item -> {
                                    val ingredient = row.ingredient
                                    Box(
                                        // Tapping an ingredient card is "outside the manual add form" too.
                                        modifier = Modifier.clearFocusOnTap(focusManager) { isAddFormFieldFocused }
                                    ) {
                                        IngredientItem(
                                            ingredient = ingredient,
                                            dateFormat = dateFormat,
                                            mode = mode,
                                            onDelete = {
                                                deleteWithUndo(ingredient, sortedIngredients.indexOf(ingredient))
                                            },
                                            onToggleStar = { viewModel.togglePrioritized(ingredient.id) },
                                            // onToggleExclude = { viewModel.toggleExclude(ingredient.id) },
                                            onSetExpirationDate = { viewModel.setExpirationDate(ingredient.id, it) },
                                            onIncrement = { viewModel.incrementCount(ingredient.id) },
                                            onDecrement = { viewModel.decrementCount(ingredient.id) },
                                            onSetCount = { viewModel.setCount(ingredient.id, it) },
                                            onSetUnit = { viewModel.setUnit(ingredient.id, it) },
                                            onRename = { viewModel.renameIngredient(ingredient.id, it) },
                                            isValidName = { viewModel.isKnownIngredientName(it) }
                                        )
                                    }
                                }
                                is FridgeRow.Undo -> {
                                    UndoRow(
                                        ingredientName = row.pendingDelete.ingredient.name,
                                        onUndo = {
                                            row.pendingDelete.autoDismissJob.cancel()
                                            viewModel.restoreIngredient(
                                                row.pendingDelete.ingredient,
                                                row.pendingDelete.index
                                            )
                                            pendingDelete = null
                                        }
                                    )
                                }
                            }
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
            }
        }
    }
}

/**
 * Clears the currently focused element the instant a tap begins anywhere within this
 * composable's bounds — even when a descendant (a Button, a Card, etc.) goes on to consume
 * the event itself. Detected at [PointerEventPass.Initial], which runs before any child gets
 * a chance to consume the touch, so it fires reliably for taps on other clickable elements
 * (quick-add thumbnails, ingredient cards, the bottom bar) and not just on empty space.
 *
 * Used to collapse the manual add form's expiration/quantity/Add row whenever the user
 * interacts with anything outside it -- [shouldClear] gates that on the add form's own field
 * actually being focused right now, not "clear absolutely whatever has focus." Without that
 * gate, this fired on *every* tap anywhere in a wrapped composable's bounds, Initial pass
 * meaning before any descendant even gets a look -- including a tap on an ingredient card's own
 * in-progress rename field (e.g. its clear button), which has nothing to do with the add form
 * but would still get its focus yanked and the rename silently closed. Since a tap on something
 * genuinely elsewhere that itself requests focus (starting a different rename, focusing another
 * field) already blurs the add form for free via Android's single-focus-owner model, this was
 * only ever load-bearing for taps that don't request focus themselves (the star icon, the delete
 * icon) -- exactly the case [shouldClear] still covers.
 */
private fun Modifier.clearFocusOnTap(focusManager: FocusManager, shouldClear: () -> Boolean): Modifier =
    pointerInput(focusManager) {
    awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial)
        if (shouldClear()) focusManager.clearFocus()
    }
}

/**
 * Expands the touch target to at least [minWidth]/[minHeight] without changing the composable's
 * own drawn size -- same invisible-padding idea as Material3's `minimumInteractiveComponentSize`,
 * but with a smaller, configurable target. The full 48dp minimum, applied to a small control
 * inside a fridge row's stepper column, grows that column (and so the whole card) taller in a
 * long list where every row's height compounds -- a worse tradeoff than a still-improved but
 * shorter target here.
 */
private fun Modifier.minimumTouchTargetSize(minWidth: Dp = 0.dp, minHeight: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = maxOf(placeable.width, minWidth.roundToPx())
        val height = maxOf(placeable.height, minHeight.roundToPx())
        layout(width, height) {
            placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

/**
 * A just-deleted ingredient still eligible for undo, tracked by [IngredientScreen] so its undo
 * row can be spliced back into the fridge list at [index] -- the position it was removed from --
 * rather than shown as a Material Snackbar docked to the bottom of the screen.
 *
 * @param autoDismissJob Cancels the pending 4s auto-dismiss (see IngredientScreen's
 *   deleteWithUndo) when Undo is tapped, so it can't fire late and null out a *different*,
 *   already-restored ingredient's [IngredientScreen] state.
 */
private data class PendingDelete(
    val ingredient: Ingredient,
    val index: Int,
    val autoDismissJob: Job
)

/** One row of the fridge [LazyColumn]: either a real ingredient card, or the undo row standing
 * in for a [PendingDelete] at its original position. */
private sealed interface FridgeRow {
    data class Item(val ingredient: Ingredient) : FridgeRow
    data class Undo(val pendingDelete: PendingDelete) : FridgeRow
}

/**
 * Inline replacement for the card at [PendingDelete.index] while its ingredient is pending undo.
 * Styled like a Material Snackbar (inverse surface colors) so it still reads as transient,
 * temporary feedback despite living in the list rather than docked to the screen edge.
 */
@Composable
private fun UndoRow(ingredientName: String, onUndo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Removed $ingredientName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndo) {
                Text(
                    "Undo",
                    color = MaterialTheme.colorScheme.inversePrimary
                )
            }
        }
    }
}

/**
 * One selectable option in a quick-add item's sub-type menu (e.g. "Chicken Breast" under
 * "Chicken"). [emoji] is only set where a genuinely distinct emoji exists for that specific
 * cut/variety -- most sub-types fall back to no icon at all (shown as plain text in the dropdown)
 * rather than reusing the parent's emoji, which would misleadingly imply a distinction that
 * isn't actually there.
 */
private data class QuickAddSubType(val name: String, val emoji: String? = null)

/**
 * A single quick-add entry: display name plus an emoji stand-in for a thumbnail image, plus an
 * optional list of more specific [subTypes]. An item with no sub-types adds itself directly on
 * tap (the original one-tap behavior); an item with sub-types opens a small dropdown instead --
 * see [QuickAddThumbnail].
 */
private data class QuickAddItem(val name: String, val emoji: String, val subTypes: List<QuickAddSubType> = emptyList())

/**
 * Primary/defining ingredients, offered as one-tap shortcuts so the user doesn't have to type +
 * select from autocomplete for everyday items. Ordered most-common-first rather than
 * alphabetically or by category, so the ingredients people reach for most often don't require
 * scrolling the row. Names are simple, singular nouns so they head-match broadly under
 * [com.pancakeworks.fridgegrub.data.IngredientMatcher].
 *
 * Items likely to be bought as a specific cut/variety (meats, cheese, rice, etc.) carry
 * [QuickAddItem.subTypes] so the user can be precise without typing -- tapping the thumbnail
 * opens a dropdown of those instead of adding the generic name.
 */
private val QUICK_ADD_ITEMS = listOf(
    QuickAddItem("Egg", "🥚"),
    QuickAddItem("Onion", "🧅"),
    QuickAddItem("Tomato", "🍅"),
    QuickAddItem(
        "Cheese", "🧀", listOf(
            QuickAddSubType("Cheddar Cheese"),
            QuickAddSubType("Mozzarella Cheese"),
            QuickAddSubType("Parmesan Cheese"),
            QuickAddSubType("Cream Cheese"),
            QuickAddSubType("Swiss Cheese"),
        )
    ),
    QuickAddItem(
        "Chicken", "🍗", listOf(
            QuickAddSubType("Whole Chicken"),
            QuickAddSubType("Chicken Breast"),
            QuickAddSubType("Chicken Thigh"),
            QuickAddSubType("Chicken Wing", "🍗"),
            QuickAddSubType("Ground Chicken"),
        )
    ),
    QuickAddItem("Garlic", "🧄"),
    QuickAddItem("Potato", "🥔"),
    QuickAddItem("Carrot", "🥕"),
    QuickAddItem(
        "Beef", "🥩", listOf(
            QuickAddSubType("Ground Beef"),
            QuickAddSubType("Beef Steak", "🥩"),
            QuickAddSubType("Beef Roast"),
            QuickAddSubType("Beef Ribs", "🍖"),
        )
    ),
    QuickAddItem(
        "Rice", "🍚", listOf(
            QuickAddSubType("White Rice"),
            QuickAddSubType("Brown Rice"),
            QuickAddSubType("Jasmine Rice"),
            QuickAddSubType("Basmati Rice"),
        )
    ),
    QuickAddItem(
        "Pork", "🍖", listOf(
            QuickAddSubType("Pork Chop"),
            QuickAddSubType("Ground Pork"),
            QuickAddSubType("Pork Ribs", "🍖"),
            QuickAddSubType("Bacon", "🥓"),
        )
    ),
    QuickAddItem(
        "Mushroom", "🍄", listOf(
            QuickAddSubType("Button Mushroom"),
            QuickAddSubType("Portobello Mushroom"),
            QuickAddSubType("Shiitake Mushroom"),
        )
    ),
    QuickAddItem(
        "Beans", "🫘", listOf(
            QuickAddSubType("Black Beans"),
            QuickAddSubType("Kidney Beans"),
            QuickAddSubType("Pinto Beans"),
            QuickAddSubType("Chickpeas"),
        )
    ),
    QuickAddItem("Shrimp", "🦐"),
    QuickAddItem(
        "Pasta", "🍝", listOf(
            QuickAddSubType("Spaghetti"),
            QuickAddSubType("Penne"),
            QuickAddSubType("Macaroni"),
            QuickAddSubType("Fettuccine"),
        )
    ),
    QuickAddItem("Cabbage", "🥬"),
    QuickAddItem(
        "Bell Pepper", "🫑", listOf(
            QuickAddSubType("Red Bell Pepper", "🫑"),
            QuickAddSubType("Green Bell Pepper", "🫑"),
            QuickAddSubType("Yellow Bell Pepper", "🫑"),
        )
    ),
    QuickAddItem("Spinach", "🍃"),
    QuickAddItem("Corn", "🌽"),
    QuickAddItem("Butter", "🧈"),
    QuickAddItem(
        "Milk", "🥛", listOf(
            QuickAddSubType("Whole Milk"),
            QuickAddSubType("Skim Milk"),
            QuickAddSubType("Almond Milk"),
        )
    ),
    QuickAddItem("Broccoli", "🥦"),
    QuickAddItem("Cucumber", "🥒"),
    QuickAddItem("Salmon", "🐟"),
)

/**
 * Horizontally scrollable row of thumbnail shortcuts for common vegetables and meats.
 * Tapping a thumbnail with no sub-types adds that ingredient directly at a default quantity of 1;
 * tapping one with sub-types opens a dropdown to pick the specific cut/variety first (see
 * [QuickAddThumbnail]).
 *
 * @param onQuickAdd Called with the ingredient name once a final selection is made.
 */
@Composable
private fun QuickAddRow(onQuickAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            "Quick Add",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QUICK_ADD_ITEMS.forEach { item ->
                QuickAddThumbnail(item = item, onSelect = onQuickAdd)
            }
        }
    }
}

/**
 * A single tappable thumbnail (emoji in a rounded square) with its name label below.
 *
 * Items with no [QuickAddItem.subTypes] add themselves directly on tap, same as before. Items
 * with sub-types instead open a compact dropdown anchored to the thumbnail -- kept to a tap-to-
 * open, tap-a-row-to-add flow (no extra confirm step) so specifying a cut/variety stays just as
 * quick as the plain one-tap add, and a small dropdown-arrow badge on the thumbnail signals which
 * kind of tap to expect before the user commits to it.
 *
 * @param onSelect Called with the final ingredient name -- the item's own name for a plain
 *   thumbnail, or the chosen sub-type's name once the dropdown is used.
 */
@Composable
private fun QuickAddThumbnail(item: QuickAddItem, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(64.dp)
                .clickable {
                    if (item.subTypes.isEmpty()) onSelect(item.name) else expanded = true
                }
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(item.emoji, fontSize = 24.sp)
                if (item.subTypes.isNotEmpty()) {
                    // Small badge in the corner, not the same size/weight as the main emoji, so
                    // it reads as "more options" rather than competing with it for attention.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            item.subTypes.forEach { subType ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (subType.emoji != null) {
                                Text(subType.emoji, fontSize = 16.sp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(subType.name)
                        }
                    },
                    onClick = {
                        onSelect(subType.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Card composable that contains the form for adding a new ingredient.
 *
 * Manages its own local [count] state (the quantity stepper), which resets to 1 each time
 * the user taps Add. Everything else (query, suggestions, expiration date) is owned by the
 * caller so it can be persisted across recompositions at the screen level.
 *
 * @param searchQuery        Current text in the ingredient name field.
 * @param suggestions        Autocomplete suggestions to show in the dropdown.
 * @param hasNoRecipeMatch   True when [searchQuery] is an exact, addable match that won't help
 *   find any recipe. Rendered as a hint in the dropdown's spot once [suggestions] is empty.
 * @param expirationDate     Currently selected expiration date in epoch millis, or null.
 * @param dateFormat         Formatter used to display the selected date in the chip label.
 * @param onFieldFocusChange Called whenever the name field's own focus state changes, so the
 *   caller can gate its clearFocusOnTap wrappers on this form specifically being focused.
 * @param onQueryChange      Called on every keystroke with the new query string.
 * @param onSuggestionSelect Called when the user taps a suggestion; receives the suggestion text.
 * @param onDismissSuggestions Called when the dropdown should close (e.g. tap outside).
 * @param onDateSelect       Called with the chosen date in epoch millis when the picker confirms.
 * @param onClearDate        Called when the user taps the date chip to remove the selected date.
 * @param onAdd              Called when the user taps Add; receives the chosen count and unit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIngredientCard(
    searchQuery: String,
    suggestions: List<String>,
    isQueryValid: Boolean,
    hasNoRecipeMatch: Boolean,
    expirationDate: Long?,
    dateFormat: SimpleDateFormat,
    mode: AppMode,
    onFieldFocusChange: (Boolean) -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSuggestionSelect: (String) -> Unit,
    onDismissSuggestions: () -> Unit,
    onDateSelect: (Long) -> Unit,
    onClearDate: () -> Unit,
    onAdd: (count: Int, unit: String) -> Unit
) {
    // Local quantity counter; starts at 1 and resets after each successful add.
    var count by remember { mutableIntStateOf(1) }

    // Used to drop focus from the name field after Add is tapped, which collapses the
    // expiration/quantity/Add row back down.
    val focusManager = LocalFocusManager.current

    var showMonthYearPicker by remember { mutableStateOf(false) }

    if (showMonthYearPicker) {
        MonthYearPickerDialog(
            initialEpochMillis = expirationDate,
            onConfirm = { onDateSelect(it); showMonthYearPicker = false },
            onDismiss = { showMonthYearPicker = false }
        )
    }

    // Selected unit of measurement for the ingredient being added.
    val unitOptions = UNIT_OPTIONS
    var selectedUnit by remember { mutableStateOf("units") }
    var unitDropdownExpanded by remember { mutableStateOf(false) }

    // TextFieldValue lets us control cursor position in addition to the text content.
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }

    // Whether the name field currently has focus. The expiration date, quantity, and Add
    // button are only shown while focused, so the card stays compact until the user starts
    // entering an ingredient.
    var isFieldFocused by remember { mutableStateOf(false) }

    // Sync the local TextFieldValue when searchQuery changes externally (e.g. reset to ""
    // after the user taps Add). Skipped when the text is already in sync to avoid
    // disrupting the cursor while the user is typing.
    LaunchedEffect(searchQuery) {
        if (textFieldValue.text != searchQuery) {
            textFieldValue = TextFieldValue(searchQuery, selection = TextRange(searchQuery.length))
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // BoxWithConstraints lets us read the available width so the dropdown can be
            // sized to exactly match the text field below it.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fieldWidth = maxWidth
                Box {
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                            onQueryChange(newValue.text)
                        },
                        label = { Text("Ingredient name") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isFieldFocused = focusState.isFocused
                                onFieldFocusChange(focusState.isFocused)
                                // Move cursor to end when the field is tapped.
                                if (focusState.isFocused) {
                                    val text = textFieldValue.text
                                    textFieldValue = TextFieldValue(text, selection = TextRange(text.length))
                                }
                            },
                        singleLine = true,
                        trailingIcon = {
                            // Show a clear button only when there is text to clear.
                            if (textFieldValue.text.isNotEmpty()) {
                                IconButton(onClick = {
                                    textFieldValue = TextFieldValue()
                                    onQueryChange("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    )

                    // Autocomplete dropdown, anchored below the text field.
                    // focusable = false keeps the keyboard and text field focus intact while
                    // the dropdown is visible, so the user can keep typing without re-tapping.
                    // A ScrollState is owned here so we can draw a visible scrollbar on top.
                    val dropdownScrollState = rememberScrollState()
                    DropdownMenu(
                        expanded = suggestions.isNotEmpty(),
                        onDismissRequest = onDismissSuggestions,
                        modifier = Modifier.width(fieldWidth),
                        properties = PopupProperties(focusable = false)
                    ) {
                        ScrollableDropdownColumn(scrollState = dropdownScrollState, maxHeight = 200.dp) {
                            suggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion) },
                                    onClick = { onSuggestionSelect(suggestion) }
                                )
                            }
                        }
                    }
                }
            }

            // Shown in the dropdown's spot once it has nothing left to show: an exact, addable
            // match (Add is enabled) that won't help find any recipe in our corpus. Purely
            // informational -- the item can still be added.
            if (isQueryValid && hasNoRecipeMatch && suggestions.isEmpty()) {
                Text(
                    "Not used in any of our recipes — you can still add it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // Bottom row: expiration date selector | quantity stepper | Add button.
            // Hidden until the name field is focused so the card stays compact at rest.
            // AnimatedVisibility softens what used to be an instant layout jump into a reveal --
            // 100ms (not the ~300ms default) so it still reads as snappy, not sluggish, on a
            // field the user is actively typing into.
            AnimatedVisibility(
                visible = isFieldFocused,
                enter = fadeIn(tween(100)) + expandVertically(tween(100)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(100))
            ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (expirationDate != null) {
                    // Once a date is chosen, replace the button with a dismissible chip
                    // that shows the formatted date. Tapping it clears the selection.
                    InputChip(
                        selected = true,
                        onClick = { onClearDate() },
                        label = { Text(dateFormat.format(Date(expirationDate)), maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "Clear date", modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // Show a button that opens the month/year picker dialog.
                    OutlinedButton(
                        onClick = { showMonthYearPicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exp. date", maxLines = 1)
                    }
                }

                // Quantity stepper and unit selector stacked vertically. Hidden entirely in
                // Basic mode -- see AppMode's doc -- but count/selectedUnit still exist as local
                // state at their defaults (1 / "units"), so onAdd below is unaffected.
                if (mode == AppMode.QUANTITY) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The minus button is inert at 1 (count cannot go below 1 here;
                    // decrementing an already-added ingredient to 0 removes it instead).
                    CountStepper(
                        count = count,
                        onDecrement = { if (count > 1) count-- },
                        onIncrement = { count++ },
                        onSetCount = { newCount -> count = newCount }
                    )
                    // Unit dropdown, anchored below the stepper. Owns its own ScrollState (rather
                    // than relying on DropdownMenu's internal, inaccessible one) so a visible
                    // scrollbar thumb can be drawn on top -- same pattern as the ingredient-name
                    // autocomplete dropdown above.
                    val unitScrollState = rememberScrollState()
                    Box {
                        OutlinedButton(
                            onClick = { unitDropdownExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            // A compromise, not the full 48dp: that made this card noticeably
                            // taller (visible padding, not just an invisible hit area, once every
                            // sibling in the stepper column had to grow to match). 40dp height is
                            // still a real improvement over the original 28dp with much less
                            // height cost; widened well past the "units" label's own width too,
                            // so the tap target isn't just barely wider than the text.
                            modifier = Modifier
                                .minimumTouchTargetSize(minWidth = 64.dp, minHeight = 40.dp)
                                .height(28.dp)
                        ) {
                            Text(selectedUnit, style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(
                            expanded = unitDropdownExpanded,
                            onDismissRequest = { unitDropdownExpanded = false }
                        ) {
                            ScrollableDropdownColumn(scrollState = unitScrollState, maxHeight = 170.dp) {
                                unitOptions.forEach { unit ->
                                    DropdownMenuItem(
                                        text = { Text(unit, style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { selectedUnit = unit; unitDropdownExpanded = false },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(40.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                }

                // Only enabled when the typed name exactly matches a database entry.
                Button(
                    onClick = {
                        onAdd(count, selectedUnit)
                        count = 1
                        selectedUnit = "units"
                        focusManager.clearFocus() // collapses this row back down
                    },
                    enabled = isQueryValid
                ) {
                    Text("Add")
                }
            }
            }
        }
    }
}

/**
 * Card composable representing a single ingredient in the fridge list.
 *
 * The card background changes color to reflect the ingredient's current state:
 *  - Primary container  → starred as the main ingredient
 *  - Error container    → excluded / negative
 *  - Surface variant    → normal / included
 *
 * @param ingredient       The data to display.
 * @param dateFormat       Formatter for the expiration date subtitle.
 * @param onDelete         Called when the trash icon is tapped.
 * @param onToggleStar     Called when the star icon is tapped; toggles main-ingredient status.
 * @param onToggleExclude  (commented out) Called when the block icon is tapped; toggles excluded state.
 * @param onIncrement      Called when the + button is tapped; increases count by 1.
 * @param onDecrement      Called when the − button is tapped; decreases count by 1 (removes at 0).
 */
@Composable
private fun IngredientItem(
    ingredient: Ingredient,
    dateFormat: SimpleDateFormat,
    mode: AppMode,
    onDelete: () -> Unit,
    onToggleStar: () -> Unit,
    // onToggleExclude: () -> Unit,
    onSetExpirationDate: (Long?) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSetCount: (Int) -> Unit,
    onSetUnit: (String) -> Unit,
    onRename: (String) -> Unit,
    isValidName: (String) -> Boolean
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        MonthYearPickerDialog(
            initialEpochMillis = ingredient.expirationDate,
            onConfirm = { onSetExpirationDate(it); showDatePicker = false },
            onDismiss = { showDatePicker = false }
        )
    }

    val containerColor = when {
        ingredient.isPrioritized -> MaterialTheme.colorScheme.primaryContainer
//        ingredient.isNegative -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Star button — tapping toggles this ingredient as prioritized.
//            // Hidden for excluded ingredients (replaced by a spacer to keep the layout aligned).
//            if (!ingredient.isNegative) {
            IconButton(onClick = onToggleStar, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (ingredient.isPrioritized) Icons.Default.Star else Icons.Outlined.Star,
                    contentDescription = if (ingredient.isPrioritized) "Remove priority" else "Prioritize",
                    tint = if (ingredient.isPrioritized)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
//            } else {
//                // Placeholder so the name column stays left-aligned whether the star is shown or not.
//                Spacer(Modifier.width(40.dp))
//            }

            // Name and subtitle (main label or expiration date). spacedBy separates the name from
            // the clickable subtitle below it -- with zero gap, a tap aimed at the name can land
            // on "Set expiry"/"Expires ..." instead, since the two lines sit right on top of each
            // other. The subtitle's own vertical padding pads its tap target symmetrically, which
            // combined with the gap above keeps that padding from creeping back up into the name.
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Tap-to-rename, reusing the tap-to-edit pattern from CountStepper: tapping the
                // name swaps it for a BasicTextField pre-filled with the current value. Unlike
                // the count field, a rename is only committed if it's a recognized ingredient
                // (validated against the same database the add-ingredient field checks against)
                // -- the border/background reflect that live as the user types, since there's no
                // autocomplete dropdown here to lean on for feedback.
                var isEditingName by remember { mutableStateOf(false) }
                var nameEditText by remember { mutableStateOf(TextFieldValue()) }
                val nameFocusRequester = remember { FocusRequester() }
                var nameHasFocused by remember { mutableStateOf(false) }

                fun commitName() {
                    onRename(nameEditText.text)
                    isEditingName = false
                    nameHasFocused = false
                }

                if (isEditingName) {
                    val isCurrentValid = isValidName(nameEditText.text)
                    val nameShape = RoundedCornerShape(4.dp)
                    // A Row, not a bare BasicTextField, so a trailing clear button can sit inside
                    // the same bordered/backgrounded pill -- doubles as a hint that this is a live
                    // text input, not just a label, and as a one-tap way to clear a typo without
                    // holding backspace. Clears only the text being typed here, not the fridge
                    // item itself (that's the trash icon at the end of the outer Row).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(nameShape)
                            .background(
                                (if (isCurrentValid) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.errorContainer).copy(alpha = 0.5f),
                                nameShape
                            )
                            .border(
                                2.dp,
                                if (isCurrentValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                nameShape
                            )
                    ) {
                        BasicTextField(
                            value = nameEditText,
                            onValueChange = { nameEditText = it },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { commitName() }),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
                                .focusRequester(nameFocusRequester)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        nameHasFocused = true
                                    } else if (nameHasFocused) {
                                        commitName()
                                    }
                                }
                        )
                        // Plain clear button, same idea as the manual-add form's OutlinedTextField
                        // trailing icon above. If this ends up leaving the field empty when it
                        // loses focus, that's fine as-is: commitName() below already no-ops an
                        // invalid (including blank) name and just closes the editor, so it
                        // collapses back to the original name rather than committing an empty one.
                        if (nameEditText.text.isNotEmpty()) {
                            IconButton(
                                onClick = { nameEditText = TextFieldValue("") },
                                modifier = Modifier.minimumTouchTargetSize(minHeight = 32.dp).size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear name",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }
                } else {
                    Text(
                        text = ingredient.name,
                        style = MaterialTheme.typography.bodyLarge,
                        // textDecoration = if (ingredient.isNegative) TextDecoration.LineThrough else TextDecoration.None
                        textDecoration = TextDecoration.None,
                        // fillMaxWidth before clickable: a short name like "Egg" used to only be
                        // tappable across its own few characters, with the rest of the row's
                        // width (up to the stepper) doing nothing on tap.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val text = ingredient.name
                                nameEditText = TextFieldValue(text, selection = TextRange(text.length))
                                isEditingName = true
                            }
                    )
                }
                if (ingredient.isPrioritized) {
                    Text(
                        text = "Prioritized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (ingredient.expirationDate != null) {
                    Text(
                        text = "Expires ${dateFormat.format(Date(ingredient.expirationDate))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { showDatePicker = true }
                            .padding(vertical = 4.dp)
                    )
                } else {
                    Text(
                        text = "Set expiry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clickable { showDatePicker = true }
                            .padding(vertical = 4.dp)
                    )
                }
            }

            // Quantity stepper: − [count] + with unit label below. Hidden entirely in Basic mode.
            // Decrementing at count == 1 removes the ingredient via [onDecrement].
            if (mode == AppMode.QUANTITY) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountStepper(
                    count = ingredient.count,
                    onDecrement = onDecrement,
                    onIncrement = onIncrement,
                    onSetCount = onSetCount
                )
                // Unit dropdown, same pattern as the add-ingredient form's (own ScrollState so a
                // visible scrollbar thumb can be drawn on top of DropdownMenu's internal one).
                var unitDropdownExpanded by remember { mutableStateOf(false) }
                val unitScrollState = rememberScrollState()
                Box {
                    OutlinedButton(
                        onClick = { unitDropdownExpanded = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        // See the identical minimumTouchTargetSize comment on the add-form's
                        // unit button above -- 36dp here, not 40dp, since this card already
                        // carries more rows on screen at once than the add-form ever does.
                        // OutlinedButton (not TextButton) so this row's unit control gets the
                        // same visible outline as the add form's, signaling it's tappable/changeable.
                        modifier = Modifier
                            .minimumTouchTargetSize(minWidth = 64.dp, minHeight = 36.dp)
                            .height(20.dp)
                    ) {
                        Text(
                            text = ingredient.unit,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = unitDropdownExpanded,
                        onDismissRequest = { unitDropdownExpanded = false }
                    ) {
                        ScrollableDropdownColumn(scrollState = unitScrollState, maxHeight = 170.dp) {
                            UNIT_OPTIONS.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { onSetUnit(unit); unitDropdownExpanded = false },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(40.dp)
                                )
                            }
                        }
                    }
                }
            }
            }

            // Exclude/block button — commented out.
//            IconButton(onClick = onToggleExclude, modifier = Modifier.size(40.dp)) {
//                Icon(
//                    imageVector = Icons.Default.Block,
//                    contentDescription = if (ingredient.isNegative) "Re-include" else "Exclude",
//                    // Red when already excluded to make the active state obvious.
//                    tint = if (ingredient.isNegative)
//                        MaterialTheme.colorScheme.error
//                    else
//                        MaterialTheme.colorScheme.onSurfaceVariant,
//                    modifier = Modifier.size(20.dp)
//                )
//            }

            // Delete button — protected by an undo snackbar (see deleteWithUndo in
            // IngredientScreen), not a confirm dialog, regardless of count.
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * A reusable − / [count] / + stepper where the count label is also directly editable.
 *
 * Tapping the number switches it to a [BasicTextField] pre-filled with the current count.
 * The edit is committed when the user presses the Done key on the keyboard or moves focus
 * away. Non-numeric input and values below 1 are ignored. The +/− buttons remain functional
 * while the field is open so the user doesn't have to dismiss before nudging the value.
 *
 * @param count       The value to display and edit.
 * @param onDecrement Called when the − button is tapped.
 * @param onIncrement Called when the + button is tapped.
 * @param onSetCount  Called with the parsed integer when the user confirms a typed value.
 * @param showIncrement Whether the + button is shown at all. False in cook mode's "Use from
 *   fridge" list (see RecipeDetailScreen) -- cooking only ever consumes stock, so offering a way
 *   to add it back there would be the wrong control for the wrong screen; the tap-to-edit number
 *   still covers correcting an accidental over-decrement.
 */
@Composable
internal fun CountStepper(
    count: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onSetCount: (Int) -> Unit,
    showIncrement: Boolean = true
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    // Tracks whether the field has received focus yet in the current editing session.
    // onFocusChanged fires with isFocused=false on initial composition (before LaunchedEffect
    // requests focus), so without this guard commit() would be called immediately and the
    // field would disappear before the user can type anything.
    var hasFocused by remember { mutableStateOf(false) }

    // Parse the typed text and call onSetCount if valid, then exit editing mode.
    fun commit() {
        val parsed = editText.text.toIntOrNull()
        if (parsed != null && parsed >= 1) onSetCount(parsed)
        isEditing = false
        hasFocused = false
    }

    // Syncs editText to reflect a new count value, keeping the cursor at the end.
    fun updateEditText(newCount: Int) {
        val text = newCount.toString()
        editText = TextFieldValue(text, selection = TextRange(text.length))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                onDecrement()
                // If the field is open, update its text so it stays in sync with the buttons.
                if (isEditing) {
                    val current = editText.text.toIntOrNull() ?: count
                    if (current > 1) updateEditText(current - 1)
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
        }

        if (isEditing) {
            // Inline numeric text field — filled + thick primary border while editing, so it
            // visibly changes state from the plain outlined label it replaces (previously this
            // had no decoration at all, making it easy to miss that a tap had done anything).
            val editingShape = RoundedCornerShape(4.dp)
            BasicTextField(
                value = editText,
                // Strip any non-digit characters so the field only ever holds a valid integer.
                onValueChange = { editText = it.copy(text = it.text.filter { c -> c.isDigit() }) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .width(40.dp)
                    .clip(editingShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), editingShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, editingShape)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hasFocused = true
                        } else if (hasFocused) {
                            commit()
                        }
                    }
            )
            // Request focus as soon as the text field enters composition.
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            // Tappable label — tap to enter editing mode.
            val boxShape = RoundedCornerShape(4.dp)
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(boxShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), boxShape)
                    .clickable {
                        val text = count.toString()
                        // Place cursor at the end so the user can immediately backspace.
                        editText = TextFieldValue(text, selection = TextRange(text.length))
                        isEditing = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (showIncrement) {
            IconButton(
                onClick = {
                    onIncrement()
                    if (isEditing) {
                        val current = editText.text.toIntOrNull() ?: count
                        updateEditText(current + 1)
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * A vertically scrollable [Column], capped at [maxHeight], with a visible scrollbar thumb tracking
 * [scrollState] drawn on top -- the shared shape behind the ingredient-name suggestions dropdown
 * and both unit dropdowns.
 *
 * Sizes the thumb's track from the column's own measured size ([androidx.compose.ui.layout.onSizeChanged],
 * converted back to Dp) rather than `Modifier.matchParentSize()`, which is the idiomatic way to do
 * this but empirically resolves to a **zero-height** Canvas here -- logged
 * `canvas size=16.0x0.0 maxValue=1339` inside a live `DropdownMenu` popup, i.e. the sibling Box's
 * content-driven height isn't visible to a matchParentSize child in that measurement context, so
 * the thumb never drew at all despite the list genuinely being scrollable (nonzero maxValue). The
 * measured-size approach sidesteps that entirely.
 */
@Composable
private fun ScrollableDropdownColumn(
    scrollState: ScrollState,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    var trackHeight by remember { mutableStateOf(0.dp) }
    Box(modifier = modifier.heightIn(max = maxHeight)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .onSizeChanged { trackHeight = with(density) { it.height.toDp() } },
            content = content
        )
        ScrollStateScrollbar(
            state = scrollState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(6.dp)
                .height(trackHeight)
        )
    }
}

/**
 * A thin rounded scrollbar thumb drawn over a vertically scrollable [Column].
 * Sizes and positions the thumb based on [state]'s current scroll offset and max value.
 */
@Composable
private fun ScrollStateScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val maxScroll = state.maxValue
        // size.height now tracks the real (matchParentSize'd) track height rather than always
        // being at least ~200dp, so a short track is a real, reachable case here -- not just a
        // theoretical one. Bail before the track itself is drawable.
        if (maxScroll == 0 || size.height <= 0f) return@Canvas

        val totalContentHeight = size.height + maxScroll
        // Keeping the thumb at least 40px tall (so it stays visible/grabbable on a long list)
        // must never push it past the track's own height -- coerceAtMost after coerceAtLeast
        // keeps that always true, where a bare coerceAtLeast(40f) could previously ask for a
        // thumb taller than a short track and make the thumbTop range below invalid.
        val thumbHeight = ((size.height / totalContentHeight) * size.height)
            .coerceAtLeast(40f)
            .coerceAtMost(size.height)
        val scrollFraction = state.value.toFloat() / maxScroll
        val thumbTop = (scrollFraction * (size.height - thumbHeight)).coerceIn(0f, size.height - thumbHeight)

        drawRoundRect(
            color = Color(0x889E9E9E),
            topLeft = Offset(0f, thumbTop),
            size = GeomSize(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2)
        )
    }
}

/**
 * A dialog that lets the user pick a month and year.
 *
 * @param initialEpochMillis Pre-selects the month/year from this epoch value, or defaults to the
 *                           current month/year if null.
 * @param onConfirm          Called with epoch millis for midnight on the 1st of the chosen month.
 * @param onDismiss          Called when the dialog is cancelled.
 */
@Composable
private fun MonthYearPickerDialog(
    initialEpochMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = remember(initialEpochMillis) {
        Calendar.getInstance().also { if (initialEpochMillis != null) it.timeInMillis = initialEpochMillis }
    }
    val now = remember { Calendar.getInstance() }
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val yearOptions = remember { (now.get(Calendar.YEAR)..(now.get(Calendar.YEAR) + 10)).toList() }
    var pickerMonth by remember { mutableIntStateOf(initial.get(Calendar.MONTH)) }
    var pickerYear by remember { mutableIntStateOf(initial.get(Calendar.YEAR)) }
    var monthExpanded by remember { mutableStateOf(false) }
    var yearExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expiration date") },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Month", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton(onClick = { monthExpanded = true }) {
                            Text(monthNames[pickerMonth])
                        }
                        DropdownMenu(expanded = monthExpanded, onDismissRequest = { monthExpanded = false }) {
                            monthNames.forEachIndexed { index, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { pickerMonth = index; monthExpanded = false }
                                )
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Year", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton(onClick = { yearExpanded = true }) {
                            Text(pickerYear.toString())
                        }
                        DropdownMenu(expanded = yearExpanded, onDismissRequest = { yearExpanded = false }) {
                            yearOptions.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text(year.toString()) },
                                    onClick = { pickerYear = year; yearExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cal = Calendar.getInstance()
                cal.set(pickerYear, pickerMonth, 1, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onConfirm(cal.timeInMillis)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
