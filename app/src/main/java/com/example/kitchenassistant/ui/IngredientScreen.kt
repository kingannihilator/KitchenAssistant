package com.example.kitchenassistant.ui

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
// import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kitchenassistant.model.Ingredient
import com.example.kitchenassistant.viewmodel.IngredientViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Root screen composable for the ingredient management UI.
 *
 * Observes state from [IngredientViewModel] via StateFlow and passes events back up to the
 * ViewModel through callbacks. The screen is split into:
 *  - A top app bar with the app title.
 *  - A scrollable [LazyColumn] containing the add form followed by the ingredient list.
 *  - A bottom bar with a "Find Recipes" action.
 *
 * @param onFindRecipes Called when the user taps "Find Recipes"; receives the names of all
 *                      ingredients currently in the fridge, followed by the subset that are
 *                      starred as prioritized (boosts those recipes' ranking downstream).
 * @param viewModel     Injected automatically by Compose; can be overridden in tests/previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientScreen(
    onFindRecipes: (fridgeIngredients: List<String>, prioritizedIngredients: List<String>) -> Unit = { _, _ -> },
    viewModel: IngredientViewModel = viewModel()
) {
    // Collect the latest values from each StateFlow; any change triggers recomposition.
    val ingredients by viewModel.ingredients.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val isQueryValid by viewModel.isQueryValid.collectAsState()
    val hasNoRecipeMatch by viewModel.hasNoRecipeMatch.collectAsState()

    // Local UI state for the expiration date picker — lives here so it resets when the form is
    // submitted and is not part of the permanent ingredient data until the user taps Add.
    var expirationDate by remember { mutableStateOf<Long?>(null) }

    // Shared date formatter used both in the add form chip and on ingredient cards.
    val dateFormat = remember { SimpleDateFormat("MM/yy", Locale.getDefault()) }

    // Used to clear focus (and thus dismiss the keyboard / exit count edit mode) when the
    // user taps anywhere outside an active text field.
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Kitchen Assistant") })
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
                        .clearFocusOnTap(focusManager),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Disabled until there's something in the fridge to search with. Starring is
                    // optional — it boosts ranking rather than gating the search.
                    Button(
                        onClick = {
                            onFindRecipes(
                                ingredients.map { it.name },
                                ingredients.filter { it.isPrioritized }.map { it.name }
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
                    modifier = Modifier.clearFocusOnTap(focusManager)
                )

                AddIngredientCard(
                    searchQuery = searchQuery,
                    suggestions = suggestions,
                    isQueryValid = isQueryValid,
                    hasNoRecipeMatch = hasNoRecipeMatch,
                    expirationDate = expirationDate,
                    dateFormat = dateFormat,
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onSuggestionSelect = { name ->
                        // Fill the text field with the tapped suggestion and close the dropdown.
                        viewModel.onSearchQueryChange(name)
                        viewModel.clearSuggestions()
                    },
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Only render the section header and list when there is at least one ingredient.
                    if (ingredients.isNotEmpty()) {
                        item(key = "header_ingredients") {
                            Text(
                                "My Fridge",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        // Each ingredient gets a stable key (its UUID) so Compose can animate
                        // additions/removals correctly and avoid unnecessary recomposition.
                        items(ingredients, key = { it.id }) { ingredient ->
                            Box(
                                // Tapping an ingredient card is "outside the manual add form" too.
                                modifier = Modifier.clearFocusOnTap(focusManager)
                            ) {
                                IngredientItem(
                                    ingredient = ingredient,
                                    dateFormat = dateFormat,
                                    onDelete = { viewModel.removeIngredient(ingredient.id) },
                                    onToggleStar = { viewModel.togglePrioritized(ingredient.id) },
                                    // onToggleExclude = { viewModel.toggleExclude(ingredient.id) },
                                    onSetExpirationDate = { viewModel.setExpirationDate(ingredient.id, it) },
                                    onIncrement = { viewModel.incrementCount(ingredient.id) },
                                    onDecrement = { viewModel.decrementCount(ingredient.id) },
                                    onSetCount = { viewModel.setCount(ingredient.id, it) },
                                    onRename = { viewModel.renameIngredient(ingredient.id, it) },
                                    isValidName = { viewModel.isKnownIngredientName(it) }
                                )
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
 * interacts with anything outside it.
 */
private fun Modifier.clearFocusOnTap(focusManager: FocusManager): Modifier = pointerInput(focusManager) {
    awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial)
        focusManager.clearFocus()
    }
}

/** A single quick-add entry: display name plus an emoji stand-in for a thumbnail image. */
private data class QuickAddItem(val name: String, val emoji: String)

/**
 * Primary/defining ingredients, offered as one-tap shortcuts so the user doesn't have to type +
 * select from autocomplete for everyday items. Ordered most-common-first rather than
 * alphabetically or by category, so the ingredients people reach for most often don't require
 * scrolling the row. Names are simple, singular nouns so they head-match broadly under
 * [com.example.kitchenassistant.data.IngredientMatcher].
 */
private val QUICK_ADD_ITEMS = listOf(
    QuickAddItem("Egg", "🥚"),
    QuickAddItem("Onion", "🧅"),
    QuickAddItem("Tomato", "🍅"),
    QuickAddItem("Cheese", "🧀"),
    QuickAddItem("Chicken", "🍗"),
    QuickAddItem("Garlic", "🧄"),
    QuickAddItem("Potato", "🥔"),
    QuickAddItem("Carrot", "🥕"),
    QuickAddItem("Beef", "🥩"),
    QuickAddItem("Rice", "🍚"),
    QuickAddItem("Pork", "🍖"),
    QuickAddItem("Mushroom", "🍄"),
    QuickAddItem("Beans", "🫘"),
    QuickAddItem("Lemon", "🍋"),
    QuickAddItem("Shrimp", "🦐"),
    QuickAddItem("Pasta", "🍝"),
    QuickAddItem("Cabbage", "🥬"),
    QuickAddItem("Bell Pepper", "🫑"),
    QuickAddItem("Spinach", "🍃"),
    QuickAddItem("Corn", "🌽"),
)

/**
 * Horizontally scrollable row of thumbnail shortcuts for common vegetables and meats.
 * Tapping a thumbnail adds that ingredient to the fridge at a default quantity of 1.
 *
 * @param onQuickAdd Called with the ingredient name when a thumbnail is tapped.
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
                QuickAddThumbnail(item = item, onClick = { onQuickAdd(item.name) })
            }
        }
    }
}

/** A single tappable thumbnail (emoji in a rounded square) with its name label below. */
@Composable
private fun QuickAddThumbnail(item: QuickAddItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick)
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
    val unitOptions = listOf(
        "units", "pounds", "ounces", "grams", "kilograms",
        "cups", "tablespoons", "teaspoons",
        "quarts", "pints", "gallons", "liters", "milliliters",
        "cloves", "cans", "bunches", "slices"
    )
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
                        Box(modifier = Modifier.heightIn(max = 200.dp)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(dropdownScrollState)
                            ) {
                                suggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                        text = { Text(suggestion) },
                                        onClick = { onSuggestionSelect(suggestion) }
                                    )
                                }
                            }
                            ScrollStateScrollbar(
                                state = dropdownScrollState,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(6.dp)
                            )
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
            if (isFieldFocused) {
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

                // Quantity stepper and unit selector stacked vertically.
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
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(selectedUnit, style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(
                            expanded = unitDropdownExpanded,
                            onDismissRequest = { unitDropdownExpanded = false }
                        ) {
                            Box(modifier = Modifier.heightIn(max = 170.dp)) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(unitScrollState)
                                ) {
                                    unitOptions.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text(unit, style = MaterialTheme.typography.bodyMedium) },
                                            onClick = { selectedUnit = unit; unitDropdownExpanded = false },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(40.dp)
                                        )
                                    }
                                }
                                ScrollStateScrollbar(
                                    state = unitScrollState,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(6.dp)
                                )
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
    onDelete: () -> Unit,
    onToggleStar: () -> Unit,
    // onToggleExclude: () -> Unit,
    onSetExpirationDate: (Long?) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSetCount: (Int) -> Unit,
    onRename: (String) -> Unit,
    isValidName: (String) -> Boolean
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ingredient?") },
            text = { Text("You still have ${ingredient.count} ${ingredient.unit} of ${ingredient.name}. Are you sure you want to remove it?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

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
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .focusRequester(nameFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    nameHasFocused = true
                                } else if (nameHasFocused) {
                                    commitName()
                                }
                            }
                    )
                    LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }
                } else {
                    Text(
                        text = ingredient.name,
                        style = MaterialTheme.typography.bodyLarge,
                        // textDecoration = if (ingredient.isNegative) TextDecoration.LineThrough else TextDecoration.None
                        textDecoration = TextDecoration.None,
                        modifier = Modifier.clickable {
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

            // Quantity stepper: − [count] + with unit label below.
            // Decrementing at count == 1 removes the ingredient via [onDecrement].
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountStepper(
                    count = ingredient.count,
                    onDecrement = onDecrement,
                    onIncrement = onIncrement,
                    onSetCount = onSetCount
                )
                Text(
                    text = ingredient.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            // Delete button — asks for confirmation when count >= 5.
            IconButton(
                onClick = { if (ingredient.count >= 5) showDeleteConfirm = true else onDelete() },
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
 */
@Composable
internal fun CountStepper(
    count: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onSetCount: (Int) -> Unit
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

/**
 * A thin rounded scrollbar thumb drawn over a vertically scrollable [Column].
 * Sizes and positions the thumb based on [state]'s current scroll offset and max value.
 */
@Composable
private fun ScrollStateScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val maxScroll = state.maxValue
        if (maxScroll == 0) return@Canvas

        val totalContentHeight = size.height + maxScroll
        val thumbHeight = ((size.height / totalContentHeight) * size.height).coerceAtLeast(40f)
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
