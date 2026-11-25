package com.alexdyakin.lexicon.ui.events

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.data.AddPollOptionRequest
import com.alexdyakin.lexicon.data.ApiResult
import com.alexdyakin.lexicon.data.CreateEventRequest
import com.alexdyakin.lexicon.data.CreatePollRequest
import com.alexdyakin.lexicon.data.EventDetail
import com.alexdyakin.lexicon.data.LexiconEvent
import com.alexdyakin.lexicon.data.Poll
import com.alexdyakin.lexicon.data.PollDetail
import com.alexdyakin.lexicon.data.PollOption
import com.alexdyakin.lexicon.data.VoteRequest
import com.alexdyakin.lexicon.data.api.EventApi
import com.alexdyakin.lexicon.data.safeApiCall
import com.alexdyakin.lexicon.ui.components.EmptyBox
import com.alexdyakin.lexicon.ui.components.ErrorBox
import com.alexdyakin.lexicon.ui.components.LexButton
import com.alexdyakin.lexicon.ui.components.LexTone
import com.alexdyakin.lexicon.ui.components.LoadingBox
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import com.alexdyakin.lexicon.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

data class EventsState(
    val loading: Boolean = true,
    val events: List<LexiconEvent> = emptyList(),
    /** The list itself failed to load. */
    val error: String? = null,
    /** The event being opened, so a late answer for one the user already left is dropped. */
    val openingEventId: Long? = null,
    val detail: EventDetail? = null,
    val openingPollId: Long? = null,
    val poll: PollDetail? = null,
    /** A vote, option or new event/poll is being sent. */
    val busy: Boolean = false,
    /** One-off feedback, shown as a snackbar: "Votes saved." or why it failed. */
    val message: String? = null,
)

/**
 * Events and their polls.
 *
 * Every answer is matched against what the user is still looking at. Opening an event and
 * pressing Back before it loaded used to bring the detail back over the list, and a poll
 * answer arriving after its event was closed left `poll` set with no `detail`, which the
 * screen's `detail!!` turned into a crash.
 */
@HiltViewModel
class EventsViewModel @Inject constructor(
    private val api: EventApi,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("event_guest", Context.MODE_PRIVATE)
    val voterKey: String = prefs.getString("key", null)
        ?: "g:${UUID.randomUUID()}".also { prefs.edit().putString("key", it).apply() }
    var voterName: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", v).apply() }

    private val _state = MutableStateFlow(EventsState())
    val state: StateFlow<EventsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = safeApiCall { api.events() }) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, events = r.data) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
                ApiResult.Unauthorized -> _state.update {
                    it.copy(loading = false, error = "Your session has expired. Sign in again to see events.")
                }
            }
        }
    }

    fun open(id: Long) {
        _state.update { it.copy(openingEventId = id, detail = null, poll = null, openingPollId = null) }
        viewModelScope.launch { loadDetail(id) }
    }

    private suspend fun loadDetail(id: Long) {
        val r = safeApiCall { api.detail(id) }
        _state.update { s ->
            if (s.openingEventId != id) return@update s // left, or opened another meanwhile
            when (r) {
                is ApiResult.Success -> s.copy(detail = r.data)
                else -> s.copy(openingEventId = null, detail = null, message = "Couldn't open that event.")
            }
        }
    }

    fun openPoll(eventId: Long, pollId: Long) {
        _state.update { it.copy(openingPollId = pollId) }
        viewModelScope.launch { loadPoll(eventId, pollId) }
    }

    private suspend fun loadPoll(eventId: Long, pollId: Long) {
        val r = safeApiCall { api.poll(eventId, pollId, voterKey) }
        _state.update { s ->
            if (s.openingPollId != pollId || s.detail?.event?.id != eventId) return@update s
            when (r) {
                is ApiResult.Success -> s.copy(poll = r.data)
                else -> s.copy(openingPollId = null, poll = null, message = "Couldn't open that poll.")
            }
        }
    }

    fun vote(eventId: Long, pollId: Long, ids: List<Long>, name: String) {
        voterName = name
        send(
            success = "Votes saved.",
            failure = "Couldn't save your votes. Please try again.",
            call = { api.vote(eventId, pollId, VoteRequest(voterKey, name, ids)) },
            after = { loadPoll(eventId, pollId) },
        )
    }

    fun addOption(eventId: Long, pollId: Long, text: String, name: String) {
        voterName = name
        send(
            success = "Option added.",
            failure = "Couldn't add that option.",
            call = { api.addOption(eventId, pollId, AddPollOptionRequest(text, voterKey, name)) },
            after = { loadPoll(eventId, pollId) },
        )
    }

    fun create(title: String, description: String, date: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val r = safeApiCall { api.create(CreateEventRequest(title.trim(), description.trim(), date.trim())) }
            _state.update { it.copy(busy = false, message = if (r is ApiResult.Success) null else "Couldn't create the event.") }
            if (r is ApiResult.Success) {
                refresh()
                open(r.data.id)
            }
        }
    }

    fun createPoll(eventId: Long, question: String, options: List<String>, allowAddOptions: Boolean) {
        send(
            success = "Poll added.",
            failure = "Couldn't add the poll.",
            call = { api.createPoll(eventId, CreatePollRequest(question.trim(), allowAddOptions, options)) },
            after = { if (_state.value.openingEventId == eventId) loadDetail(eventId) },
        )
    }

    /** Sends a change and says how it went. Votes used to fail without a word. */
    private fun send(success: String, failure: String, call: suspend () -> Any?, after: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val r = safeApiCall { call() }
            _state.update { it.copy(busy = false, message = if (r is ApiResult.Success) success else failure) }
            if (r is ApiResult.Success) after()
        }
    }

    fun closePoll() = _state.update { it.copy(poll = null, openingPollId = null) }

    fun close() = _state.update { it.copy(detail = null, openingEventId = null, poll = null, openingPollId = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun EventsScreen(onBack: () -> Unit, viewModel: EventsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var name by remember { mutableStateOf(viewModel.voterName) }
    var creatingEvent by remember { mutableStateOf(false) }
    var creatingPoll by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Read once: everything below works on these, so nothing can be nulled mid-frame.
    val detail = state.detail
    val poll = state.poll
    val inEvent = state.openingEventId != null

    val goBack: () -> Unit = when {
        poll != null || state.openingPollId != null -> viewModel::closePoll
        inEvent -> viewModel::close
        else -> onBack
    }
    BackHandler(enabled = inEvent) { goBack() }

    if (creatingEvent) {
        EventDialog(onDismiss = { creatingEvent = false }, onSubmit = viewModel::create)
    }
    if (creatingPoll && detail != null) {
        PollDialog(onDismiss = { creatingPoll = false }) { question, options, allowAdd ->
            viewModel.createPoll(detail.event.id, question, options, allowAdd)
            creatingPoll = false
        }
    }

    ScreenScaffold(
        title = when {
            poll != null -> "Vote"
            detail != null -> detail.event.title
            else -> "Events"
        },
        onBack = goBack,
        snackbarHost = { SnackbarHost(snackbar) },
        gutter = Spacing.gutter,
    ) { padding ->
        when {
            detail != null && poll != null -> PollView(
                padding = padding,
                eventId = detail.event.id,
                detail = poll,
                name = name,
                onName = { name = it },
                busy = state.busy,
                onVote = viewModel::vote,
                onAddOption = viewModel::addOption,
            )
            detail != null -> EventDetailView(
                padding = padding,
                detail = detail,
                onAddPoll = { creatingPoll = true },
                onOpenPoll = { viewModel.openPoll(detail.event.id, it.id) },
            )
            inEvent -> LoadingBox(padding)
            state.loading && state.events.isEmpty() -> LoadingBox(padding)
            state.error != null && state.events.isEmpty() -> ErrorBox(state.error!!, viewModel::refresh, padding)
            else -> EventList(
                padding = padding,
                events = state.events,
                onCreate = { creatingEvent = true },
                onOpen = { viewModel.open(it.id) },
            )
        }
    }
}

@Composable
private fun EventList(
    padding: PaddingValues,
    events: List<LexiconEvent>,
    onCreate: () -> Unit,
    onOpen: (LexiconEvent) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "create") { LexButton("Create event", onClick = onCreate, tone = LexTone.Primary) }
        if (events.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No events yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
        }
        items(events, key = { it.id }) { event ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(event) }) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val date = formatEventDate(event.eventDate)
                    val polls = plural(event.pollCount, "poll", "polls")
                    Text(
                        if (date.isEmpty()) polls else "$date · $polls",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventDetailView(
    padding: PaddingValues,
    detail: EventDetail,
    onAddPoll: () -> Unit,
    onOpenPoll: (Poll) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "about") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    formatEventDate(detail.event.eventDate).takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        detail.event.description.ifBlank { "No description." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item(key = "add") { LexButton("Add poll", onClick = onAddPoll) }
        items(detail.polls, key = { it.id }) { poll ->
            Card(Modifier.fillMaxWidth().clickable { onOpenPoll(poll) }) {
                Text(poll.question, Modifier.padding(Spacing.lg), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PollView(
    padding: PaddingValues,
    eventId: Long,
    detail: PollDetail,
    name: String,
    onName: (String) -> Unit,
    busy: Boolean,
    onVote: (Long, Long, List<Long>, String) -> Unit,
    onAddOption: (Long, Long, String, String) -> Unit,
) {
    var selected by remember(detail) { mutableStateOf(detail.options.filter { it.votedByMe }.map { it.id }.toSet()) }
    var optionText by remember(detail) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(detail.poll.question, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        OutlinedTextField(
            name,
            onName,
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Each option is a Card: on the scrim the default text colours are effectively
        // invisible, and a checkbox alone gives nothing to read the label against.
        detail.options.forEach { option ->
            OptionCard(option, checked = option.id in selected) { selected = selected.toggle(option.id) }
        }
        if (name.isBlank()) {
            Text(
                "Enter your name above to vote.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LexButton(
            "Save votes",
            onClick = { onVote(eventId, detail.poll.id, selected.toList(), name.trim()) },
            tone = LexTone.Primary,
            enabled = name.isNotBlank() && !busy,
            loading = busy,
        )
        if (detail.poll.allowAddOptions) {
            OutlinedTextField(
                optionText,
                { optionText = it },
                label = { Text("Suggest an option") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
            )
            LexButton(
                "Add option",
                onClick = { onAddOption(eventId, detail.poll.id, optionText.trim(), name.trim()); optionText = "" },
                enabled = name.isNotBlank() && optionText.isNotBlank() && !busy,
            )
        }
    }
}

@Composable
private fun OptionCard(option: PollOption, checked: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked, onCheckedChange = null)
            Column(Modifier.padding(vertical = Spacing.sm).weight(1f)) {
                Text(option.text, style = MaterialTheme.typography.bodyLarge)
                val votes = plural(option.voteCount, "vote", "votes")
                Text(
                    if (option.voters.isEmpty()) votes else "$votes · ${option.voters.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventDialog(onDismiss: () -> Unit, onSubmit: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
            }
        },
        confirmButton = {
            LexButton("Create", onClick = { onSubmit(title, description, date); onDismiss() }, tone = LexTone.Primary, enabled = title.isNotBlank())
        },
        dismissButton = { LexButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun PollDialog(onDismiss: () -> Unit, onSubmit: (String, List<String>, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var allowAddOptions by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add poll") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(question, { question = it }, label = { Text("Question") })
                OutlinedTextField(options, { options = it }, label = { Text("Options, one per line") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Switch(allowAddOptions, { allowAddOptions = it })
                    Text("Attendees can add options")
                }
            }
        },
        confirmButton = {
            LexButton(
                "Add",
                onClick = { onSubmit(question, options.lines().map { it.trim() }.filter { it.isNotEmpty() }, allowAddOptions) },
                tone = LexTone.Primary,
                enabled = question.isNotBlank(),
            )
        },
        dismissButton = { LexButton("Cancel", onClick = onDismiss) },
    )
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

internal fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

private val DATE_ONLY = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

/**
 * "2026-11-20" as the phone's own medium date ("Nov 20, 2026"), read as a local calendar
 * date so it cannot slip a day across time zones. Anything else is shown as sent.
 */
internal fun formatEventDate(raw: String): String {
    val m = DATE_ONLY.find(raw.trim()) ?: return raw.trim()
    val (y, mo, d) = m.destructured
    val cal = Calendar.getInstance().apply {
        clear()
        set(y.toInt(), mo.toInt() - 1, d.toInt())
    }
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(cal.time)
}
