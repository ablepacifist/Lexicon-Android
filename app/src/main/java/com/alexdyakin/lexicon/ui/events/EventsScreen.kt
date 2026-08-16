package com.alexdyakin.lexicon.ui.events

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdyakin.lexicon.R
import com.alexdyakin.lexicon.data.*
import com.alexdyakin.lexicon.data.api.EventApi
import com.alexdyakin.lexicon.ui.components.ScreenScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class EventsState(val loading: Boolean = true, val events: List<LexiconEvent> = emptyList(), val detail: EventDetail? = null, val poll: PollDetail? = null, val error: String? = null)
@HiltViewModel class EventsViewModel @Inject constructor(private val api: EventApi, @ApplicationContext context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("event_guest", Context.MODE_PRIVATE)
    val voterKey = prefs.getString("key", null) ?: "g:${UUID.randomUUID()}".also { prefs.edit().putString("key", it).apply() }
    var voterName: String get() = prefs.getString("name", "") ?: ""; set(v) { prefs.edit().putString("name", v).apply() }
    private val _state = MutableStateFlow(EventsState()); val state = _state.asStateFlow(); init { refresh() }
    fun refresh() = viewModelScope.launch { val r = safeApiCall { api.events() }; _state.value = EventsState(false, r.successOrNull.orEmpty(), error = (r as? ApiResult.Failure)?.message) }
    fun open(id: Long) = viewModelScope.launch { val r = safeApiCall { api.detail(id) }; if (r is ApiResult.Success) _state.value = _state.value.copy(detail = r.data, poll = null) else _state.value = _state.value.copy(error = "Could not load event.") }
    fun openPoll(eventId: Long, pollId: Long) = viewModelScope.launch { val r = safeApiCall { api.poll(eventId, pollId, voterKey) }; if (r is ApiResult.Success) _state.value = _state.value.copy(poll = r.data) }
    fun vote(eventId: Long, pollId: Long, ids: List<Long>, name: String) = viewModelScope.launch { voterName = name; safeApiCall { api.vote(eventId, pollId, VoteRequest(voterKey, name, ids)) }; openPoll(eventId, pollId) }
    fun create(title: String, description: String, date: String) = viewModelScope.launch { val r = safeApiCall { api.create(CreateEventRequest(title, description, date)) }; if (r is ApiResult.Success) { refresh(); open(r.data.id) } }
    fun createPoll(eventId: Long, question: String, options: List<String>, allowAddOptions: Boolean) = viewModelScope.launch { safeApiCall { api.createPoll(eventId, CreatePollRequest(question, allowAddOptions, options)) }; open(eventId) }
    fun addOption(eventId: Long, pollId: Long, text: String, name: String) = viewModelScope.launch { voterName = name; safeApiCall { api.addOption(eventId, pollId, AddPollOptionRequest(text, voterKey, name)) }; openPoll(eventId, pollId) }
    fun close() { _state.value = _state.value.copy(detail = null, poll = null) }
}
@Composable fun EventsScreen(onBack: () -> Unit, viewModel: EventsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState(); var name by remember { mutableStateOf(viewModel.voterName) }; var createEvent by remember { mutableStateOf(false) }; var createPoll by remember { mutableStateOf(false) }
    if (createEvent) EventDialog({ createEvent = false }, viewModel::create)
    if (createPoll && state.detail != null) PollDialog({ createPoll = false }) { q, options, allowAddOptions -> viewModel.createPoll(state.detail!!.event.id, q, options, allowAddOptions); createPoll = false }
    ScreenScaffold(if (state.poll != null) "Vote" else if (state.detail != null) state.detail!!.event.title else "Events", { if (state.detail != null) viewModel.close() else onBack() }, R.drawable.bg_dashboard) { padding ->
        when { state.poll != null -> PollView(state.detail!!.event.id, state.poll!!, name, { name = it }, viewModel::vote, viewModel::addOption)
            state.detail != null -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) { item { Text(state.detail!!.event.description); Button({ createPoll = true }, Modifier.padding(top = 12.dp)) { Text("Add poll") } }; items(state.detail!!.polls) { poll -> Card(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { viewModel.openPoll(state.detail!!.event.id, poll.id) }) { Text(poll.question, Modifier.padding(16.dp)) } } }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Button({ createEvent = true }) { Text("Create event") } }; items(state.events) { event -> Card(Modifier.fillMaxWidth().clickable { viewModel.open(event.id) }) { Column(Modifier.padding(16.dp)) { Text(event.title, style = MaterialTheme.typography.titleMedium); Text(event.eventDate); Text("${event.pollCount} polls") } } } }
        }
    }
}
@Composable private fun PollView(eventId: Long, detail: PollDetail, name: String, setName: (String) -> Unit, vote: (Long, Long, List<Long>, String) -> Unit, addOption: (Long, Long, String, String) -> Unit) { var selected by remember(detail) { mutableStateOf(detail.options.filter { it.votedByMe }.map { it.id }.toSet()) }; var optionText by remember(detail) { mutableStateOf("") }; Column(Modifier.padding(16.dp)) { Text(detail.poll.question, style = MaterialTheme.typography.titleLarge); OutlinedTextField(name, setName, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)); detail.options.forEach { option -> Row(Modifier.fillMaxWidth().clickable { selected = selected.toggle(option.id) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(option.id in selected, null); Column { Text(option.text); Text("${option.voteCount} votes · ${option.voters.joinToString()}", style = MaterialTheme.typography.labelSmall) } } }; Button({ vote(eventId, detail.poll.id, selected.toList(), name) }, enabled = name.isNotBlank(), modifier = Modifier.padding(top = 12.dp)) { Text("Save votes") }; if (detail.poll.allowAddOptions) { OutlinedTextField(optionText, { optionText = it }, label = { Text("Suggest an option") }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)); Button({ addOption(eventId, detail.poll.id, optionText.trim(), name); optionText = "" }, enabled = name.isNotBlank() && optionText.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Add option") } } } }
@Composable private fun EventDialog(dismiss: () -> Unit, submit: (String, String, String) -> Unit) { var title by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var date by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = dismiss, title = { Text("Create event") }, text = { Column { OutlinedTextField(title, { title = it }, label = { Text("Title") }); OutlinedTextField(desc, { desc = it }, label = { Text("Description") }); OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }) } }, confirmButton = { Button({ submit(title, desc, date); dismiss() }, enabled = title.isNotBlank()) { Text("Create") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) }
@Composable private fun PollDialog(dismiss: () -> Unit, submit: (String, List<String>, Boolean) -> Unit) { var question by remember { mutableStateOf("") }; var options by remember { mutableStateOf("") }; var allowAddOptions by remember { mutableStateOf(true) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Add poll") }, text = { Column { OutlinedTextField(question, { question = it }, label = { Text("Question") }); OutlinedTextField(options, { options = it }, label = { Text("Options, one per line") }); Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(allowAddOptions, { allowAddOptions = it }); Text(" Attendees can add options") } } }, confirmButton = { Button({ submit(question, options.lines().filter { it.isNotBlank() }, allowAddOptions) }, enabled = question.isNotBlank()) { Text("Add") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } }) }
private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id