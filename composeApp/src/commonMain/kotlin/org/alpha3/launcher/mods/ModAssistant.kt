package org.alpha3.launcher.mods

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.ThumbVisibility
import com.composables.core.VerticalScrollbar
import com.composables.core.rememberScrollAreaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.alpha3.launcher.files.readTextFile
import org.alpha3.launcher.files.writeTextFile
import org.alpha3.launcher.paths.OpenMWPaths
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.time.Duration.Companion.seconds

data class ModValue(
    var id: Int,
    val category: String,
    val value: String,
    val isChecked: Boolean,
    var originalIndex: Int
) {
    companion object {
        fun updateUIFromModValues(categories: List<String>): List<List<ModValue>> {
            val newModValues = readModValues()
            return newModValues.categorizeModValues(categories)
        }
    }
}

fun List<ModValue>.categorizeModValues(categories: List<String>): List<List<ModValue>> {
    return categories.map { category ->
        this.filter { it.category == category }
    }
}

fun readModValues(): List<ModValue> {
    val values = mutableListOf<ModValue>()
    val validCategories = setOf("data", "content", "groundcover")

    val text = readTextFile(OpenMWPaths.USER_OPENMW_CFG) ?: return values

    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if ("=" in trimmed) {
            val isChecked = !trimmed.startsWith("#")
            val (category, value) =
                trimmed.removePrefix("#").split("=", limit = 2).map { it.trim() }

            if (category in validCategories) {
                values.add(
                    ModValue(
                        values.size, category, value, isChecked, values.size + 1
                    )
                )
            }
        }
    }
    return values
}

fun searchMods(query: String, modValues: List<ModValue>, category: String?): List<ModValue> {
    return modValues.filter {
        (category == null || it.category == category) && it.value.contains(query, ignoreCase = true)
    }
}

fun navigateToMod(
    modValue: ModValue,
    categorizedModValues: List<List<ModValue>>,
    setSelectedTabIndex: (Int) -> Unit,
    lazyListState: LazyListState,
    coroutineScope: CoroutineScope
) {
    val tabIndex = categorizedModValues.indexOfFirst { categoryList -> categoryList.any { it.id == modValue.id } }
    if (tabIndex != -1) {
        setSelectedTabIndex(tabIndex)
        val itemIndex = categorizedModValues[tabIndex].indexOfFirst { it.id == modValue.id }
        if (itemIndex != -1) {
            coroutineScope.launch {
                try {

                    lazyListState.scrollToItem(itemIndex)  // Use scrollToItem for testing
                } catch (e: Exception) {
                    // nothing here
                }
            }
        }
    }
}

@InternalCoroutinesApi
@OptIn(ExperimentalMaterial3Api::class)
@DelicateCoroutinesApi
@ExperimentalFoundationApi
@Composable
fun ModValuesList(modValues: List<ModValue>) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val categories = listOf("data", "content", "groundcover")
    var categorizedModValues by remember {
        mutableStateOf(categories.map { category ->
            modValues.filter { it.category == category }
        })
    }
    val lazyListState = rememberLazyListState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showDialogPC by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<ModValue>()) }
    val newModValues = readModValues()
    val stateSB = rememberScrollAreaState(lazyListState)

    // Manage whats in the tabs here
    var showMods by remember { mutableStateOf(true) }
    var showDialogSettings by remember { mutableStateOf(false) }

    fun resetStates() {
        showMods = false
        showDialogSettings = false
        showSearchDialog = false
    }

    val coroutineScope = rememberCoroutineScope()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val currentList = categorizedModValues[selectedTabIndex].toMutableList()
        val movedItem = currentList.removeAt(from.index)
        currentList.add(to.index, movedItem)

        categorizedModValues = categorizedModValues.toMutableList().apply {
            this[selectedTabIndex] = currentList
        }
    }

    // Reload the mod values and update the UI
    categorizedModValues = ModValue.updateUIFromModValues(categories)

    val infiniteTransition = rememberInfiniteTransition(label = "")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 500
            ),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )

    Column {
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = Color(0xFF4CAF50) // indicator color
                )
            },
        ) { // , edgePadding = 1.dp,
            categories.forEachIndexed { index, category ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = {
                        resetStates()
                        selectedTabIndex = index

                    },
                    text = { Text(category.replaceFirstChar { it.titlecase() }) }
                )
            }
            Tab(
                selected = false,
                onClick = {
                    resetStates()
                    showSearchDialog = true
                },
                icon = { Text("Search Mods") }
            )
        }
        fun handleSearchResult(modValue: ModValue) {
            navigateToMod(
                modValue,
                categorizedModValues,
                setSelectedTabIndex = { selectedTabIndex = it },
                lazyListState = lazyListState,
                coroutineScope = coroutineScope  // Pass the CoroutineScope
            )
            showSearchDialog = false
            showMods = true
        }
        if (showSearchDialog) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),  // Increased height for filter options
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Search Mods", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Category Filter Checkboxes
                    categories.forEach { category ->
                        var isChecked by remember { mutableStateOf(false) }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                isChecked = checked
                                selectedCategory = if (checked) category else null
                                searchResults = if (selectedCategory != null) {
                                    searchMods(searchQuery, modValues, selectedCategory)
                                } else {
                                    searchMods(searchQuery, modValues, null)
                                }
                            }
                        )
                        Text(category)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        searchResults = searchMods(it, modValues, selectedCategory)
                    },
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(color = Color.Black)
                ) {
                    items(searchResults) { result ->
                        Text(
                            text = result.value,
                            modifier = Modifier
                                .padding(8.dp)
                                .clickable {
                                    handleSearchResult(result)
                                }
                        )
                    }
                }
            }
        }

            ScrollArea(state = stateSB) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                        items(
                            categorizedModValues[selectedTabIndex],
                            key = { it.originalIndex }) { modValue ->
                            ReorderableItem(reorderableLazyListState, key = modValue.originalIndex) {
                                var isDragging by remember { mutableStateOf(false) }
                                var showPopup by remember { mutableStateOf(false) }
                                var showDialog2 by remember { mutableStateOf(false) }
                                var isChecked by remember { mutableStateOf(modValue.isChecked) }
                                var showDeleteConfirmation by remember { mutableStateOf(false) }
                                var showMoveDialog by remember { mutableStateOf(false) }

                                val backgroundColor by animateColorAsState(
                                    when {
                                        isDragging -> Color(0xFF8BC34A) // Color during drag
                                        modValue.isChecked -> Color.DarkGray
                                        else -> MaterialTheme.colorScheme.surface
                                    }, label = ""
                                )
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = backgroundColor,
                                    ),
                                    modifier = Modifier.then(
                                        if (isDragging) Modifier.graphicsLayer(
                                            scaleX = pulse,
                                            scaleY = pulse
                                        ) else Modifier
                                    ),
                                    elevation = CardDefaults.cardElevation(4.dp),
                                    onClick = { showPopup = true },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Start,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                isChecked = checked
                                                val currentList =
                                                    categorizedModValues[selectedTabIndex].toMutableList()
                                                val index =
                                                    currentList.indexOfFirst { it.originalIndex == modValue.originalIndex }
                                                if (index != -1) {
                                                    currentList[index] =
                                                        currentList[index].copy(isChecked = checked)
                                                }
                                                categorizedModValues =
                                                    categorizedModValues.toMutableList().apply {
                                                        this[selectedTabIndex] = currentList
                                                    }

                                                // Update file
                                                val existingLines = mutableListOf<String>()

                                                val text = readTextFile(OpenMWPaths.USER_OPENMW_CFG)
                                                if (text != null) {
                                                    text.lineSequence().forEach { line ->
                                                        val trimmed = line.trim()
                                                        if (trimmed.isNotEmpty()) {
                                                            existingLines.add(trimmed)
                                                        }
                                                    }
                                                }

                                                val updatedLines = existingLines.map { line ->
                                                    if (line.contains(modValue.value)) {
                                                        if (checked) {
                                                            modValue.category + "=" + modValue.value
                                                        } else {
                                                            "#" + modValue.category + "=" + modValue.value
                                                        }
                                                    } else {
                                                        line
                                                    }
                                                }
                                                writeTextFile(
                                                    OpenMWPaths.USER_OPENMW_CFG,
                                                    updatedLines.joinToString("\n") + "\n"
                                                )

                                                // Reload the mod values and update the UI
                                                categorizedModValues = ModValue.updateUIFromModValues(categories)

                                            }
                                        )
                                        Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                                            Text(
                                                text = modValue.value,
                                                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                            )
                                            Text(
                                                text = "Load Order: ${modValue.originalIndex}",
                                                style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier,
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            IconButton(
                                                modifier = Modifier.draggableHandle(
                                                    onDragStarted = {
                                                        isDragging = true
                                                    },
                                                    onDragStopped = {
                                                        isDragging = false

                                                        val currentList =
                                                            categorizedModValues[selectedTabIndex].toMutableList()

                                                        // Update the original indexes based on current list order
                                                        currentList.forEachIndexed { index, item ->
                                                            currentList[index] =
                                                                item.copy(originalIndex = index + 1)
                                                        }

                                                        categorizedModValues =
                                                            categorizedModValues.toMutableList().apply {
                                                                this[selectedTabIndex] = currentList
                                                            }

                                                        // Update the file with the new order
                                                        val finalLines = categorizedModValues.flatten()
                                                            .sortedWith(
                                                                compareBy(
                                                                    { categories.indexOf(it.category) },
                                                                    { it.originalIndex })
                                                            )
                                                            .map { modValue ->
                                                                if (modValue.isChecked) {
                                                                    "${modValue.category}=${modValue.value}"
                                                                } else {
                                                                    "#${modValue.category}=${modValue.value}"
                                                                }
                                                            }
                                                        writeTextFile(
                                                            OpenMWPaths.USER_OPENMW_CFG,
                                                            finalLines.joinToString("\n") + "\n"
                                                        )


                                                        // Reload the mod values and update the UI
                                                        categorizedModValues = ModValue.updateUIFromModValues(categories)
                                                    }
                                                ),
                                                onClick = { showDialog2 = true },
                                            ) {
                                                Text(
                                                    text = "\u2630",   // Unicode "hamburger" menu
                                                    fontSize = 20.sp,
                                                    color = Color.White
                                                )
                                            }

                                        }
                                    }
                                    if (showPopup) {
                                        Popup(
                                            alignment = Alignment.Center,
                                            onDismissRequest = {
                                                showPopup = false
                                            } // Hide the popup when dismissed
                                        ) {
                                            Surface(
                                                modifier = Modifier.padding(16.dp),
                                                shape = MaterialTheme.shapes.medium,
                                                color = MaterialTheme.colorScheme.background
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = "Choose an action for the selected mod.",
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = "Name: ${modValue.value}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                    Text(
                                                        text = "Category: ${modValue.category}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Button(
                                                        onClick = {
                                                            // Handle the switch category action
                                                            val updatedList =
                                                                categorizedModValues[selectedTabIndex].toMutableList()
                                                            val index =
                                                                updatedList.indexOfFirst { it.originalIndex == modValue.originalIndex }
                                                            if (index != -1) {
                                                                val newCategory =
                                                                    if (modValue.category == "content") "groundcover" else "content"
                                                                updatedList[index] =
                                                                    updatedList[index].copy(category = newCategory)
                                                                categorizedModValues =
                                                                    categorizedModValues.toMutableList()
                                                                        .apply {
                                                                            this[selectedTabIndex] =
                                                                                updatedList
                                                                        }

                                                                val existingLines = mutableListOf<String>()

                                                                val text = readTextFile(OpenMWPaths.USER_OPENMW_CFG)
                                                                if (text != null) {
                                                                    text.lineSequence().forEach { line ->
                                                                        val trimmed = line.trim()
                                                                        if (trimmed.isNotEmpty()) {
                                                                            existingLines.add(trimmed)
                                                                        }
                                                                    }
                                                                }

                                                                // Update file with new categories
                                                                val finalLines =
                                                                    categorizedModValues.flatten()
                                                                        .map { modValue ->
                                                                            "${modValue.category}=${modValue.value}"
                                                                        }
                                                                writeTextFile(
                                                                    OpenMWPaths.USER_OPENMW_CFG,
                                                                    finalLines.joinToString("\n") + "\n"
                                                                )


                                                                // Reload the mod values and update the UI
                                                                categorizedModValues = ModValue.updateUIFromModValues(categories)
                                                            }
                                                            showPopup = false
                                                        }
                                                    ) {
                                                        Text(
                                                            text = "Switch to ${if (modValue.category == "content") "groundcover" else "content"}",
                                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Button(
                                                        onClick = {
                                                            // Show the delete confirmation dialog
                                                            showDeleteConfirmation = true
                                                        }
                                                    ) {
                                                        Text(
                                                            text = "Delete",
                                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Button(
                                                        onClick = {
                                                            // Show the move dialog
                                                            showMoveDialog = true
                                                        }
                                                    ) {
                                                        Text(
                                                            text = "Move",
                                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(8.dp))

                                                    Button(onClick = { showPopup = false }) {
                                                        Text(
                                                            text = "Cancel",
                                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (showDeleteConfirmation) {
                                        AlertDialog(
                                            onDismissRequest = { showDeleteConfirmation = false },
                                            title = { Text("Confirm Deletion") },
                                            text = { Text("Are you sure you want to delete this mod?") },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        // Handle the delete action
                                                        val updatedList =
                                                            categorizedModValues[selectedTabIndex].toMutableList()
                                                        val index =
                                                            updatedList.indexOfFirst { it.originalIndex == modValue.originalIndex }
                                                        if (index != -1) {
                                                            updatedList.removeAt(index)
                                                            categorizedModValues =
                                                                categorizedModValues.toMutableList().apply {
                                                                    this[selectedTabIndex] = updatedList
                                                                }

                                                            // Update the file after deletion
                                                            val finalLines =
                                                                categorizedModValues.flatten()
                                                                    .map { modValue ->
                                                                        "${modValue.category}=${modValue.value}"
                                                                    }
                                                            writeTextFile(
                                                                OpenMWPaths.USER_OPENMW_CFG,
                                                                finalLines.joinToString("\n") + "\n"
                                                            )

                                                            // Reload the mod values and update the UI
                                                            categorizedModValues = ModValue.updateUIFromModValues(categories)
                                                        }
                                                        showDeleteConfirmation = false
                                                        showPopup = false
                                                    }
                                                ) {
                                                    Text("Yes")
                                                }
                                            },
                                            dismissButton = {
                                                Button(onClick = { showDeleteConfirmation = false }) {
                                                    Text("No")
                                                }
                                            }
                                        )
                                    }

                                    if (showDialog2) {
                                        AlertDialog(
                                            onDismissRequest = { showDialog2 = false },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        val updatedList =
                                                            categorizedModValues[selectedTabIndex].toMutableList()
                                                        val index =
                                                            updatedList.indexOfFirst { it.originalIndex == modValue.originalIndex }
                                                        if (index != -1) {
                                                            val newCategory =
                                                                if (modValue.category == "content") "groundcover" else "content"
                                                            updatedList[index] =
                                                                updatedList[index].copy(category = newCategory)
                                                            categorizedModValues =
                                                                categorizedModValues.toMutableList().apply {
                                                                    this[selectedTabIndex] = updatedList
                                                                }

                                                            val existingLines = mutableListOf<String>()

                                                            val text = readTextFile(OpenMWPaths.USER_OPENMW_CFG)
                                                            if (text != null) {
                                                                text.lineSequence().forEach { line ->
                                                                    val trimmed = line.trim()
                                                                    if (trimmed.isNotEmpty()) {
                                                                        existingLines.add(trimmed)
                                                                    }
                                                                }
                                                            }

                                                            // Update file with new categories
                                                            val finalLines =
                                                                categorizedModValues.flatten()
                                                                    .map { modValue ->
                                                                        "${modValue.category}=${modValue.value}"
                                                                    }
                                                            writeTextFile(
                                                                OpenMWPaths.USER_OPENMW_CFG,
                                                                finalLines.joinToString("\n") + "\n"
                                                            )
                                                            // Reload the mod values and update the UI
                                                            categorizedModValues = ModValue.updateUIFromModValues(categories)
                                                        }
                                                        showDialog2 = false
                                                    }
                                                ) {
                                                    Text("Yes")
                                                }
                                            },
                                            dismissButton = {
                                                Button(onClick = { showDialog2 = false }) {
                                                    Text("No")
                                                }
                                            },
                                            title = { Text("Confirm Action") },
                                            text = { Text("Are you sure you want to switch the category to ${if (modValue.category == "content") "groundcover" else "content"}?") }
                                        )
                                    }
                                }
                            }
                        }
                }
                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(10.dp)
                ) {
                    Thumb(
                        modifier = Modifier.background(Color.Black.copy(0.3f), RoundedCornerShape(100)),
                        thumbVisibility = ThumbVisibility.HideWhileIdle(
                            enter = fadeIn(),
                            exit = fadeOut(),
                            hideDelay = 0.5.seconds
                        )
                    )
                }
            }
        }
    }


