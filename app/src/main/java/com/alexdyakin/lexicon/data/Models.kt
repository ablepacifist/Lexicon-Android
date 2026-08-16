package com.alexdyakin.lexicon.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Auth (LexiconServer) ─────────────────────────────────────────────────────

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** Asks AuthController for a bearer token alongside the session. */
    val platform: String = "mobile",
    val rememberMe: Boolean = true,
)

@Serializable
data class LoginResponse(
    val id: Int = 0,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val level: Int = 0,
    val mobileToken: String? = null,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val confirmPassword: String,
    val email: String = "",
    val displayName: String = "",
)

@Serializable
data class RegisterResponse(
    val success: Boolean = false,
    val playerId: Int = 0,
    val username: String = "",
    val message: String = "",
)

@Serializable
data class CurrentUser(
    val id: Int = 0,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val level: Int = 0,
)

@Serializable
data class PlayerProfile(
    val id: Int = 0, val username: String = "", val email: String = "", val displayName: String = "",
    val level: Int = 0, val createdAt: String = "", val lastLogin: String = "",
)

@Serializable
data class PokemonPlayerStats(
    val username: String = "", val level: Int = 0, val xp: Int = 0, val xpProgress: Int = 0,
    val xpRequired: Int = 0, val coins: Int = 0, val totalCaught: Int = 0, val totalKm: Double = 0.0,
    val team: String? = null,
)

@Serializable
data class NotificationPrefs(
    val userId: Int = 0, val enableMessage: Boolean = true, val enableVoiceJoin: Boolean = true,
    val enableMention: Boolean = true, val enableMusic: Boolean = false, val enablePush: Boolean = true,
)

@Serializable
data class AppNotification(
    val id: Long = 0,
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val source: String = "",
    val fromUsername: String = "",
    val link: String = "",
    val createdAt: String = "",
)

@Serializable
data class UnreadNotificationCount(val count: Int = 0)

@Serializable
data class LevelUpRequest(val playerId: Int, val secretPassword: String)

@Serializable
data class AvatarResponse(val avatarUrl: String = "", val success: Boolean = true, val message: String = "")

@Serializable
data class RemoveAvatarRequest(val username: String, val userId: Int)

@Serializable
data class AppVersionInfo(
    val versionCode: Long = 0,
    val versionName: String = "",
    val downloadUrl: String = "",
    val critical: Boolean = false,
    val changelog: String = "",
    val sha256: String? = null,
)

// ── Alchemy ──────────────────────────────────────────────────────────────────

@Serializable
data class ForageResponse(
    @SerialName("forage") val ingredient: String = "",
)

@Serializable
data class Effect(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
)

@Serializable
data class Ingredient(
    val id: Int = 0,
    val name: String = "",
    val quantity: Int = 0,
    val effects: List<Effect> = emptyList(),
)

@Serializable
data class Potion(
    val id: Int = 0,
    val name: String = "",
    val quantity: Int = 0,
    val description: String = "",
    val duration: Double = 0.0,
    val brewLevel: Int = 0,
    val dice: String = "None",
    val effects: List<Effect> = emptyList(),
)

@Serializable
data class Inventory(
    val ingredients: List<Ingredient> = emptyList(),
    val potions: List<Potion> = emptyList(),
)

@Serializable
data class KnowledgeEntry(
    val ingredientId: Int = 0,
    val ingredientName: String = "",
    val effects: List<Effect> = emptyList(),
)

@Serializable
data class ConsumeIngredientRequest(
    val playerId: Int,
    val ingredientId: Int,
)

@Serializable
data class ConsumePotionRequest(val playerId: Int, val potionId: Int)

@Serializable
data class BrewPotionRequest(val playerId: Int, val ingredientId1: Int, val ingredientId2: Int)

@Serializable
data class BrewPotionResponse(val message: String = "")

@Serializable data class Holdfast(
    val id: Int = 0,
    val groupName: String = "",
    val holdfastName: String = "",
    val baseGoldPerDay: Double = 40.0,
    val population: Int = 0,
    val castleType: String = "",
    val gold: Double = 0.0,
    val silver: Int = 0,
    val happiness: Double = 0.0,
    val targetHappiness: Double = 0.0,
    val daysElapsed: Int = 0,
    val beer: Int = 0,
    val grain: Int = 0,
    val wine: Int = 0,
    val tools: Int = 0,
    val raidsSurvived: Int = 0,
    val food: Int = 0,
    val wood: Int = 0,
    val stone: Int = 0,
    val iron: Int = 0,
    val buildings: Map<String, Int> = emptyMap(),
    val wheatFieldPlantDays: List<Int> = emptyList(),
    val vegetableGardenPlantDays: List<Int> = emptyList(),
    val orchardPlantDays: List<Int> = emptyList(),
    val vineyardPlantDays: List<Int> = emptyList(),
    val populationGrowthHistory: List<Int> = emptyList(),
    val ryeFieldPlantDays: List<Int> = emptyList(),
    val berryPatchPlantDays: List<Int> = emptyList(),
    val mushroomCavePlantDays: List<Int> = emptyList(),
    val foodBatchDays: List<Int> = emptyList(),
    val foodBatchAmounts: List<Int> = emptyList(),
    val foodMarketEnabled: Boolean = false,
)
@Serializable data class CreateHoldfastRequest(val groupName: String, val holdfastName: String)
@Serializable data class AdvanceHoldfastRequest(val groupName: String, val days: Int)
@Serializable data class BuildHoldfastRequest(val groupName: String, val buildingType: String)
@Serializable data class DepositHoldfastRequest(val groupName: String, val gold: Double)
@Serializable data class WithdrawHoldfastRequest(val groupName: String, val gold: Double = 0.0, val beer: Int = 0, val wine: Int = 0, val grain: Int = 0, val tools: Int = 0)
@Serializable data class ReplantHoldfastRequest(val groupName: String, val fieldType: String)
@Serializable data class ToggleFoodMarketRequest(val groupName: String)
@Serializable data class HoldfastBuildingMenuItem(
    val type: String = "",
    val name: String = "",
    val description: String = "",
    val current: Int = 0,
    val max: Int? = null,
    val status: String = "available",
    val lockReason: String? = null,
    val cost: Int = 0,
    val baseCost: Int = 0,
    val dailySilver: Double = 0.0,
    val dailyUpkeep: Double = 0.0,
    val happiness: Double = 0.0,
    val resourceCost: Map<String, Int>? = null,
    val harvestFood: Int? = null,
    val harvestGold: Double? = null,
    val harvestDays: Int? = null,
    val productionItem: String? = null,
    val productionAmount: Int? = null,
    val productionDays: Int? = null,
)
@Serializable data class HoldfastEvent(val id: Long = 0, val day: Int = 0, val message: String = "")
@Serializable data class HoldfastStatus(
    val holdfast: Holdfast = Holdfast(),
    val message: String = "",
    val events: List<String> = emptyList(),
    val dailyIncome: Double = 0.0,
    val dailyUpkeep: Double = 0.0,
    val netDailyGold: Double = 0.0,
    val protection: Double = 0.0,
    val raidChance: Double = 0.0,
    val targetHappiness: Double = 0.0,
    val buildingMenu: List<HoldfastBuildingMenuItem> = emptyList(),
    val daysOfFood: Int = 0,
    val nextSpoilIn: Int = -1,
    val foodShelfLife: Int = 0,
    val foodMarketEnabled: Boolean = false,
    val populationChange: Int = 0,
    val avgDailyGrowth: Double = 0.0,
    val populationHistory: List<Int> = emptyList(),
)
@Serializable data class HoldfastOperationResponse(val success: Boolean = false, val message: String = "")

// ── Lexicon media ────────────────────────────────────────────────────────────

@Serializable
data class MediaFile(
    val id: Int = 0,
    val filename: String = "",
    val originalFilename: String = "",
    val contentType: String = "",
    val fileSize: Long = 0,
    val title: String = "",
    val description: String = "",
    val mediaType: String = "",
    val sourceUrl: String = "",
    val isPublic: Boolean = true,
    val uploadDate: String = "",
) {
    val displayTitle: String get() = title.ifBlank { originalFilename.ifBlank { filename } }

    val kind: MediaKind get() = when {
        mediaType.equals("VIDEO", true) -> MediaKind.VIDEO
        mediaType.contains("AUDIOBOOK", true) -> MediaKind.AUDIOBOOK
        mediaType.equals("IMAGE", true) -> MediaKind.IMAGE
        mediaType.isNotBlank() -> MediaKind.AUDIO
        contentType.startsWith("video/") -> MediaKind.VIDEO
        contentType.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }

    val isPlayable: Boolean get() = kind in setOf(MediaKind.AUDIO, MediaKind.AUDIOBOOK, MediaKind.VIDEO)
}

@Serializable data class MediaUploadResponse(val success: Boolean = false, val message: String = "", val mediaFile: MediaFile = MediaFile())
@Serializable data class LiveStreamState(
    val channel: String = "",
    val currentMediaId: Int = 0,
    val currentPositionMs: Long = 0,
    // Arrives as an ISO date STRING, not a number — see FlexibleEpochMillisSerializer.
    @Serializable(with = FlexibleEpochMillisSerializer::class) val currentStartTime: Long = 0,
    val totalSkipVotes: Int = 0,
    val requiredSkipVotes: Int = 1,
    val currentMedia: MediaFile? = null,
)
@Serializable data class LiveStreamQueueItem(val id: Int = 0, val channel: String = "", val mediaFileId: Int = 0, val addedBy: Int = 0, val position: Int = 0, val status: String = "QUEUED", val skipVotes: List<Int> = emptyList(), val mediaFile: MediaFile? = null)
@Serializable data class LiveStreamStateResponse(val success: Boolean = false, val state: LiveStreamState = LiveStreamState(), val message: String = "")
@Serializable data class LiveStreamQueueResponse(val success: Boolean = false, val queue: List<LiveStreamQueueItem> = emptyList(), val count: Int = 0, val message: String = "")
@Serializable data class LiveStreamMediaResponse(val success: Boolean = false, val media: List<MediaFile> = emptyList(), val count: Int = 0, val message: String = "")
@Serializable data class QueueMediaRequest(val userId: Int, val mediaFileId: Int)
@Serializable data class QueuePlaylistRequest(val userId: Int, val playlistId: Int)
@Serializable data class SkipStreamRequest(val userId: Int)
@Serializable data class StorageVolume(val label: String = "", val totalBytes: Long = 0, val usedBytes: Long = 0, val freeBytes: Long = 0)
@Serializable data class StorageInfo(val volumes: List<StorageVolume> = emptyList(), val totalBytes: Long = 0, val usedBytes: Long = 0, val freeBytes: Long = 0)

enum class MediaKind { AUDIO, AUDIOBOOK, VIDEO, IMAGE, OTHER }

@Serializable
data class Playlist(
    val id: Int = 0,
    val name: String = "",
    val description: String = "",
    val mediaType: String = "",
    val isPublic: Boolean = false,
    val createdBy: Int = 0,
    val createdDate: String = "",
    val itemCount: Int = 0,
    val items: List<PlaylistItem> = emptyList(),
    val mediaFileIds: List<Int> = emptyList(),
)

@Serializable
data class PlaylistItem(
    val playlistId: Int = 0,
    val mediaFileId: Int = 0,
    val position: Int = 0,
    val mediaFile: MediaFile = MediaFile(),
)

@Serializable
data class PlaylistItemRequest(val mediaFileId: Int)

@Serializable
data class PlaylistReorderRequest(val mediaFileIds: List<Int>)
@Serializable data class PlaylistImportStart(val status: String = "", val message: String = "", val importId: String = "")
@Serializable data class PlaylistImportProgress(val message: String = "", val total: Int = 0, val successful: Int = 0, val failed: Int = 0, val processed: Int = 0, val percentage: Int = 0)
@Serializable data class PlaylistImportCompleted(val playlistId: Int = 0, val totalTracks: Int = 0, val successfulTracks: Int = 0, val failedTracks: Int = 0, val message: String = "")

@Serializable data class MediaUpdateRequest(val title: String? = null, val description: String? = null, val isPublic: Boolean? = null, val mediaType: String? = null)

@Serializable
data class PlaybackPosition(
    val found: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val completed: Boolean = false,
)

@Serializable
data class PlaybackPositionRequest(
    val userId: Int,
    val mediaFileId: Int,
    val position: Double,
    val duration: Double = 0.0,
    val completed: Boolean = false,
)

// ── Events & polls ───────────────────────────────────────────────────────────

@Serializable
data class LexiconEvent(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val eventDate: String = "",
    val createdByUserId: Int? = null,
    val createdAt: String = "",
    val pollCount: Int = 0,
)

@Serializable data class CreateEventRequest(val title: String, val description: String = "", val eventDate: String = "", val userId: Int? = null)
@Serializable data class Poll(val id: Long = 0, val eventId: Long = 0, val question: String = "", val allowAddOptions: Boolean = true, val displayOrder: Int = 0)
@Serializable data class PollOption(val id: Long = 0, val pollId: Long = 0, val text: String = "", val addedByName: String? = null, val voteCount: Int = 0, val voters: List<String> = emptyList(), val votedByMe: Boolean = false)
@Serializable data class CreatePollRequest(val question: String, val allowAddOptions: Boolean = true, val seedOptions: List<String> = emptyList())
@Serializable data class VoteRequest(val voterKey: String, val voterName: String, val optionIds: List<Long>)
@Serializable data class AddPollOptionRequest(val text: String, val voterKey: String, val voterName: String)
@Serializable data class PollDetail(val poll: Poll = Poll(), val options: List<PollOption> = emptyList())
@Serializable data class EventDetail(val event: LexiconEvent = LexiconEvent(), val polls: List<Poll> = emptyList())

// ── Pokemon ──────────────────────────────────────────────────────────────────

@Serializable
data class CaughtPokemon(
    val id: Long = 0,
    val speciesId: Int = 0,
    val speciesName: String = "",
    val spriteKey: String = "",
    val type1: String = "",
    val type2: String = "",
    val pokemonLevel: Int = 0,
    val hp: Int = 0,
    val currentHp: Int = 0,
    val attack: Int = 0,
    val defense: Int = 0,
    val speed: Int = 0,
    val nickname: String = "",
) {
    val displayName: String get() = nickname.ifBlank { speciesName }
    val fainted: Boolean get() = currentHp <= 0
}

@Serializable
data class PokemonSpecies(
    val id: Int = 0,
    val name: String = "",
    val type1: String = "",
    val type2: String = "",
    val rarity: Int = 0,
    val spriteKey: String = "",
)

@Serializable data class PlayerItem(val itemType: String = "", val quantity: Int = 0)
@Serializable data class ShopItem(val itemType: String = "", val label: String = "", val price: Int = 0, val sprite: String = "")
@Serializable data class ShopBuyRequest(val itemType: String, val quantity: Int = 1)
@Serializable data class ShopPurchase(val success: Boolean = false, val item: String = "", val quantity: Int = 0, val coinsSpent: Int = 0, val coinsRemaining: Int = 0, val message: String = "")
@Serializable data class PlayerEgg(val id: Long = 0, val distanceKm: Double = 0.0, val progressKm: Double = 0.0, val incubating: Boolean = false, val obtainedAt: String = "")
@Serializable data class IncubateEggRequest(val eggId: Long)
@Serializable data class NicknamePokemonRequest(val caughtId: Long, val nickname: String)
@Serializable data class FavouritePokemonRequest(val caughtId: Long, val favourite: Boolean)
@Serializable data class BuddyPokemonRequest(val caughtId: Long)
@Serializable data class EvolutionOption(val evolvesToId: Int = 0, val evolvesToName: String = "", val minLevel: Int = 0, val itemRequired: String? = null, val eligible: Boolean = false, val reason: String = "")
@Serializable data class EvolvePokemonRequest(val targetSpeciesId: Int)
@Serializable data class EvolutionResult(val success: Boolean = false, val oldName: String = "", val newName: String = "", val newSpeciesId: Int = 0, val newSpriteKey: String = "")
@Serializable data class HealPokemonRequest(val caughtId: Long, val item: String)
@Serializable data class HealPokemonResult(val success: Boolean = false, val caughtId: Long = 0, val currentHp: Int = 0, val maxHp: Int = 0, val restored: Int = 0, val message: String = "")
