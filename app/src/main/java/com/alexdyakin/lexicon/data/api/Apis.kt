package com.alexdyakin.lexicon.data.api

import com.alexdyakin.lexicon.data.CaughtPokemon
import com.alexdyakin.lexicon.data.ConsumeIngredientRequest
import com.alexdyakin.lexicon.data.ConsumePotionRequest
import com.alexdyakin.lexicon.data.BrewPotionRequest
import com.alexdyakin.lexicon.data.BrewPotionResponse
import com.alexdyakin.lexicon.data.Holdfast
import com.alexdyakin.lexicon.data.HoldfastAdvanceResponse
import com.alexdyakin.lexicon.data.HoldfastMutationResponse
import com.alexdyakin.lexicon.data.HoldfastStatus
import com.alexdyakin.lexicon.data.CreateHoldfastRequest
import com.alexdyakin.lexicon.data.AdvanceHoldfastRequest
import com.alexdyakin.lexicon.data.BuildHoldfastRequest
import com.alexdyakin.lexicon.data.DepositHoldfastRequest
import com.alexdyakin.lexicon.data.ToggleFoodMarketRequest
import com.alexdyakin.lexicon.data.HoldfastEvent
import com.alexdyakin.lexicon.data.HoldfastAssetManifest
import com.alexdyakin.lexicon.data.HoldfastOperationResponse
import com.alexdyakin.lexicon.data.ReplantHoldfastRequest
import com.alexdyakin.lexicon.data.WithdrawHoldfastRequest
import com.alexdyakin.lexicon.data.CurrentUser
import com.alexdyakin.lexicon.data.TorrentListResponse
import com.alexdyakin.lexicon.data.TorrentProgress
import com.alexdyakin.lexicon.data.TorrentStatus
import com.alexdyakin.lexicon.data.TorrentSubmitResponse
import com.alexdyakin.lexicon.data.ForageResponse
import com.alexdyakin.lexicon.data.Inventory
import com.alexdyakin.lexicon.data.KnowledgeEntry
import com.alexdyakin.lexicon.data.LexiconEvent
import com.alexdyakin.lexicon.data.CreateEventRequest
import com.alexdyakin.lexicon.data.EventDetail
import com.alexdyakin.lexicon.data.CreatePollRequest
import com.alexdyakin.lexicon.data.Poll
import com.alexdyakin.lexicon.data.PollDetail
import com.alexdyakin.lexicon.data.VoteRequest
import com.alexdyakin.lexicon.data.AddPollOptionRequest
import com.alexdyakin.lexicon.data.AppVersionInfo
import com.alexdyakin.lexicon.data.LoginRequest
import com.alexdyakin.lexicon.data.LoginResponse
import com.alexdyakin.lexicon.data.LevelUpRequest
import com.alexdyakin.lexicon.data.MediaFile
import com.alexdyakin.lexicon.data.MediaUploadResponse
import com.alexdyakin.lexicon.data.MediaUpdateRequest
import com.alexdyakin.lexicon.data.LiveStreamStateResponse
import com.alexdyakin.lexicon.data.LiveStreamQueueResponse
import com.alexdyakin.lexicon.data.LiveStreamMediaResponse
import com.alexdyakin.lexicon.data.QueueMediaRequest
import com.alexdyakin.lexicon.data.QueuePlaylistRequest
import com.alexdyakin.lexicon.data.SkipStreamRequest
import com.alexdyakin.lexicon.data.StorageInfo
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import com.alexdyakin.lexicon.data.PlaybackPosition
import com.alexdyakin.lexicon.data.PlaybackPositionRequest
import com.alexdyakin.lexicon.data.Playlist
import com.alexdyakin.lexicon.data.PlaylistItemRequest
import com.alexdyakin.lexicon.data.PlaylistReorderRequest
import com.alexdyakin.lexicon.data.PlaylistImportStart
import com.alexdyakin.lexicon.data.PlayerProfile
import com.alexdyakin.lexicon.data.PokemonPlayerStats
import com.alexdyakin.lexicon.data.NotificationPrefs
import com.alexdyakin.lexicon.data.RegisterRequest
import com.alexdyakin.lexicon.data.RegisterResponse
import com.alexdyakin.lexicon.data.SsoTokenResponse
import com.alexdyakin.lexicon.data.AvatarResponse
import com.alexdyakin.lexicon.data.RemoveAvatarRequest
import com.alexdyakin.lexicon.data.AppNotification
import com.alexdyakin.lexicon.data.DeviceTokenRequest
import com.alexdyakin.lexicon.data.UnreadNotificationCount
import com.alexdyakin.lexicon.data.PokemonSpecies
import com.alexdyakin.lexicon.data.PlayerItem
import com.alexdyakin.lexicon.data.ShopItem
import com.alexdyakin.lexicon.data.ShopBuyRequest
import com.alexdyakin.lexicon.data.ShopPurchase
import com.alexdyakin.lexicon.data.PlayerEgg
import com.alexdyakin.lexicon.data.IncubateEggRequest
import com.alexdyakin.lexicon.data.NicknamePokemonRequest
import com.alexdyakin.lexicon.data.FavouritePokemonRequest
import com.alexdyakin.lexicon.data.BuddyPokemonRequest
import com.alexdyakin.lexicon.data.EvolutionOption
import com.alexdyakin.lexicon.data.EvolvePokemonRequest
import com.alexdyakin.lexicon.data.EvolutionResult
import com.alexdyakin.lexicon.data.HealPokemonRequest
import com.alexdyakin.lexicon.data.HealPokemonResult
import com.alexdyakin.lexicon.data.AddGymRequest
import com.alexdyakin.lexicon.data.AddStopRequest
import com.alexdyakin.lexicon.data.CatchRequest
import com.alexdyakin.lexicon.data.CatchResult
import com.alexdyakin.lexicon.data.Gym
import com.alexdyakin.lexicon.data.LureStopRequest
import com.alexdyakin.lexicon.data.LureStopResult
import com.alexdyakin.lexicon.data.Pokestop
import com.alexdyakin.lexicon.data.PokemonSpawn
import com.alexdyakin.lexicon.data.SetTeamRequest
import com.alexdyakin.lexicon.data.SetTeamResult
import com.alexdyakin.lexicon.data.SpinGymRequest
import com.alexdyakin.lexicon.data.SpinGymResult
import com.alexdyakin.lexicon.data.SpinStopRequest
import com.alexdyakin.lexicon.data.SpinStopResult
import com.alexdyakin.lexicon.data.TeamResponse
import com.alexdyakin.lexicon.data.WalkRequest
import com.alexdyakin.lexicon.data.WalkResult
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.PUT
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/auth/me")
    suspend fun me(): CurrentUser

    @POST("api/auth/logout")
    suspend fun logout()

    /**
     * Mints a single-use SSO token for the Mumble bridge. Expires in 60 seconds, so
     * mint it immediately before connecting rather than ahead of any retry wait.
     */
    @POST("api/auth/sso/generate-token")
    suspend fun generateSsoToken(): SsoTokenResponse
}

interface MediaApi {
    @GET("api/media/recent")
    suspend fun recent(@Query("limit") limit: Int = 100): List<MediaFile>

    @GET("api/media/public")
    suspend fun public(): List<MediaFile>

    @GET("api/media/user/{userId}")
    suspend fun byUser(@Path("userId") userId: Int): List<MediaFile>

    @GET("api/media/search")
    suspend fun search(@Query("q") query: String): List<MediaFile>

    @GET("api/media/{id}")
    suspend fun byId(@Path("id") id: Int): MediaFile

    @DELETE("api/media/{id}")
    suspend fun delete(@Path("id") id: Int, @Query("userId") userId: Int)

    @PUT("api/media/{id}")
    suspend fun update(
        @Path("id") id: Int,
        @Query("userId") userId: Int,
        @Body updates: MediaUpdateRequest,
    )

    @Multipart
    @POST("api/media/upload")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("userId") userId: RequestBody,
        @Part("title") title: RequestBody,
        @Part("description") description: RequestBody,
        @Part("isPublic") isPublic: RequestBody,
        @Part("mediaType") mediaType: RequestBody,
    ): MediaUploadResponse

    @FormUrlEncoded
    @POST("api/media/upload-from-url")
    suspend fun uploadFromUrl(
        @Field("url") url: String, @Field("userId") userId: Int, @Field("title") title: String,
        @Field("description") description: String, @Field("isPublic") isPublic: Boolean,
        @Field("mediaType") mediaType: String, @Field("downloadType") downloadType: String,
    ): MediaUploadResponse

    @GET("api/media/storage-info") suspend fun storageInfo(): StorageInfo
}

/**
 * Torrent downloads. The server hands the torrent to qBittorrent on the Lexicon machine,
 * which pulls it over its VPN and adds only the .mp3 and .mp4 files to the library.
 */
interface TorrentApi {
    /** Whether a torrent can be queued right now, and why not when it cannot. */
    @GET("api/torrents/status") suspend fun status(): TorrentStatus

    @Multipart
    @POST("api/torrents")
    suspend fun submitFile(
        @Part file: MultipartBody.Part,
        @Part("userId") userId: RequestBody,
        @Part("title") title: RequestBody,
        @Part("isPublic") isPublic: RequestBody,
    ): TorrentSubmitResponse

    @FormUrlEncoded
    @POST("api/torrents")
    suspend fun submitUrl(
        @Field("url") url: String,
        @Field("userId") userId: Int,
        @Field("title") title: String,
        @Field("isPublic") isPublic: Boolean,
    ): TorrentSubmitResponse

    @GET("api/torrents/active/{userId}")
    suspend fun active(@Path("userId") userId: Int): TorrentListResponse

    /** Cancels a download, or removes a finished torrent along with its data. */
    @DELETE("api/torrents/{infoHash}")
    suspend fun remove(@Path("infoHash") infoHash: String, @Query("userId") userId: Int)
}

interface PlaylistApi {
    @GET("api/playlists/user/{userId}")
    suspend fun byUser(@Path("userId") userId: Int): List<Playlist>

    @GET("api/playlists/public")
    suspend fun public(@Query("mediaType") mediaType: String? = null): List<Playlist>

    @GET("api/playlists/{id}")
    suspend fun byId(@Path("id") id: Int): Playlist

    @POST("api/playlists")
    suspend fun create(@Query("userId") userId: Int, @Body playlist: Playlist): Playlist

    @PUT("api/playlists/{id}")
    suspend fun update(
        @Path("id") id: Int,
        @Query("userId") userId: Int,
        @Body playlist: Playlist,
    ): Playlist

    @DELETE("api/playlists/{id}")
    suspend fun delete(
        @Path("id") id: Int,
        @Query("userId") userId: Int,
        @Query("deleteMediaFiles") deleteMediaFiles: Boolean = false,
    )

    @POST("api/playlists/{id}/items")
    suspend fun addItem(
        @Path("id") id: Int,
        @Query("userId") userId: Int,
        @Body body: PlaylistItemRequest,
    )

    @DELETE("api/playlists/{id}/items/{mediaId}")
    suspend fun removeItem(
        @Path("id") id: Int,
        @Path("mediaId") mediaId: Int,
        @Query("userId") userId: Int,
    )

    @PUT("api/playlists/{id}/reorder")
    suspend fun reorder(
        @Path("id") id: Int,
        @Query("userId") userId: Int,
        @Body body: PlaylistReorderRequest,
    )

    @FormUrlEncoded
    @POST("api/playlists/import-youtube")
    suspend fun importYoutube(
        @Field("url") url: String,
        @Field("userId") userId: Int,
        @Field("playlistName") playlistName: String,
        @Field("isPublic") isPublic: Boolean,
        @Field("mediaIsPublic") mediaIsPublic: Boolean,
        @Field("mediaType") mediaType: String,
        @Field("downloadType") downloadType: String,
    ): PlaylistImportStart
}

interface LiveStreamApi {
    @GET("api/livestream/state") suspend fun state(@Query("channel") channel: String): LiveStreamStateResponse
    @GET("api/livestream/queue") suspend fun queue(@Query("channel") channel: String): LiveStreamQueueResponse
    @GET("api/livestream/eligible-media") suspend fun eligibleMedia(@Query("channel") channel: String): LiveStreamMediaResponse
    @POST("api/livestream/queue") suspend fun add(@Query("channel") channel: String, @Body body: QueueMediaRequest)
    @POST("api/livestream/queue/playlist") suspend fun addPlaylist(@Query("channel") channel: String, @Body body: QueuePlaylistRequest)
    @DELETE("api/livestream/queue/{id}") suspend fun remove(@Path("id") id: Int, @Query("userId") userId: Int, @Query("channel") channel: String)
    @POST("api/livestream/skip") suspend fun skip(@Query("channel") channel: String, @Body body: SkipStreamRequest)

    /** Reported by whoever is playing when a track finishes; the server advances the queue. */
    @POST("api/livestream/media-ended") suspend fun mediaEnded(@Query("channel") channel: String)
}

interface PlaybackApi {
    @GET("api/playback/position/{userId}/{mediaFileId}")
    suspend fun position(
        @Path("userId") userId: Int,
        @Path("mediaFileId") mediaFileId: Int,
    ): PlaybackPosition

    @POST("api/playback/position")
    suspend fun savePosition(@Body body: PlaybackPositionRequest)
}

interface EventApi {
    @GET("api/events")
    suspend fun events(): List<LexiconEvent>

    @POST("api/events") suspend fun create(@Body body: CreateEventRequest): LexiconEvent
    @GET("api/events/{eventId}") suspend fun detail(@Path("eventId") eventId: Long): EventDetail
    @POST("api/events/{eventId}/polls") suspend fun createPoll(@Path("eventId") eventId: Long, @Body body: CreatePollRequest): Poll
    @GET("api/events/{eventId}/polls/{pollId}") suspend fun poll(@Path("eventId") eventId: Long, @Path("pollId") pollId: Long, @Query("voterKey") voterKey: String): PollDetail
    @PUT("api/events/{eventId}/polls/{pollId}/votes") suspend fun vote(@Path("eventId") eventId: Long, @Path("pollId") pollId: Long, @Body body: VoteRequest)
    @POST("api/events/{eventId}/polls/{pollId}/options") suspend fun addOption(@Path("eventId") eventId: Long, @Path("pollId") pollId: Long, @Body body: AddPollOptionRequest)
}

interface ProfileApi {
    @GET("api/players/{id}") suspend fun player(@Path("id") id: Int): PlayerProfile
    @GET("api/avatar/{username}") suspend fun avatar(@Path("username") username: String): AvatarResponse
    @Multipart
    @POST("api/avatar/upload")
    suspend fun uploadAvatar(
        @Part("username") username: RequestBody,
        @Part("userId") userId: RequestBody,
        @Part avatar: MultipartBody.Part,
    ): AvatarResponse
    @POST("api/avatar/remove") suspend fun removeAvatar(@Body body: RemoveAvatarRequest): AvatarResponse
    @GET("api/notifications/prefs") suspend fun notificationPrefs(@Query("userId") userId: Int): NotificationPrefs
    @PUT("api/notifications/prefs") suspend fun updateNotificationPrefs(@Query("userId") userId: Int, @Body prefs: NotificationPrefs)
}

interface AppUpdateApi {
    @GET("api/app/version")
    suspend fun latestVersion(): AppVersionInfo
}

interface NotificationApi {
    @GET("api/notifications") suspend fun history(@Query("userId") userId: Int, @Query("limit") limit: Int = 30): List<AppNotification>
    @GET("api/notifications/unread-count") suspend fun unreadCount(@Query("userId") userId: Int): UnreadNotificationCount
    @POST("api/notifications/read-all") suspend fun markAllRead(@Query("userId") userId: Int)

    // Push device registration. Backend stores one row per (userId, token).
    @POST("api/notifications/device-token") suspend fun registerDeviceToken(@Body body: DeviceTokenRequest)
    @HTTP(method = "DELETE", path = "api/notifications/device-token", hasBody = true)
    suspend fun unregisterDeviceToken(@Body body: DeviceTokenRequest)
}

interface AlchemyApi {
    @GET("api/player/{id}") suspend fun player(@Path("id") id: Int): PlayerProfile
    @GET("api/player/knowledge/{playerId}") suspend fun knowledge(@Path("playerId") playerId: Int): List<KnowledgeEntry>
    @POST("api/player/levelup") suspend fun levelUp(@Body body: LevelUpRequest): PlayerProfile
    @GET("api/player/inventory/{playerId}")
    suspend fun inventory(@Path("playerId") playerId: Int): Inventory

    @GET("api/player/forage/{playerId}")
    suspend fun forage(@Path("playerId") playerId: Int): ForageResponse

    @POST("api/player/ingredient/consume")
    suspend fun consumeIngredient(@Body body: ConsumeIngredientRequest)

    @POST("api/player/potion/consume")
    suspend fun consumePotion(@Body body: ConsumePotionRequest)

    @POST("api/potion/brew")
    suspend fun brewPotion(@Body body: BrewPotionRequest): BrewPotionResponse

    @GET("api/holdfast/all") suspend fun holdfasts(): List<Holdfast>
    @GET("api/holdfast/{groupName}") suspend fun holdfast(@Path("groupName") groupName: String): HoldfastStatus
    @POST("api/holdfast/create") suspend fun createHoldfast(@Body body: CreateHoldfastRequest): Holdfast
    // These three return their own narrow shapes, not a full status — see the doc on
    // HoldfastAdvanceResponse. Callers must re-GET api/holdfast/{groupName} afterwards.
    /** Admin only: [ssoToken] is a fresh Lexicon SSO token; Alchemy checks HOLDFAST_ADVANCE_TIME with it. */
    @POST("api/holdfast/advance") suspend fun advanceHoldfast(
        @Body body: AdvanceHoldfastRequest,
        @Header("X-Lexicon-Sso") ssoToken: String,
    ): HoldfastAdvanceResponse
    @POST("api/holdfast/build") suspend fun buildHoldfast(@Body body: BuildHoldfastRequest): HoldfastMutationResponse
    @POST("api/holdfast/deposit") suspend fun depositHoldfast(@Body body: DepositHoldfastRequest): Holdfast
    @POST("api/holdfast/withdraw") suspend fun withdrawHoldfast(@Body body: WithdrawHoldfastRequest): HoldfastOperationResponse
    @POST("api/holdfast/replant") suspend fun replantHoldfast(@Body body: ReplantHoldfastRequest): HoldfastMutationResponse
    @POST("api/holdfast/toggle-food-market") suspend fun toggleFoodMarket(@Body body: ToggleFoodMarketRequest): Holdfast
    @GET("api/holdfast/{groupName}/events") suspend fun holdfastEvents(@Path("groupName") groupName: String): List<HoldfastEvent>
    /** Which image (or emoji) to show for each building, resource, castle, crop and tab. Public. */
    @GET("api/holdfast/assets/manifest") suspend fun holdfastAssets(): HoldfastAssetManifest
    /** Admin only: Alchemy checks HOLDFAST_DELETE with the SSO token. */
    @DELETE("api/holdfast/{groupName}") suspend fun deleteHoldfast(
        @Path("groupName") groupName: String,
        @Header("X-Lexicon-Sso") ssoToken: String,
    ): HoldfastOperationResponse
}

interface PokemonApi {
    @GET("api/pokemon/player/stats") suspend fun playerStats(): PokemonPlayerStats
    @GET("api/pokemon/collection")
    suspend fun collection(): List<CaughtPokemon>

    @GET("api/pokemon/caught-species")
    suspend fun caughtSpecies(): List<Int>

    @GET("api/pokemon/species") suspend fun species(): List<PokemonSpecies>
    @GET("api/pokemon/items") suspend fun items(): List<PlayerItem>
    @GET("api/pokemon/shop/catalog") suspend fun shopCatalog(): List<ShopItem>
    @POST("api/pokemon/shop/buy") suspend fun buy(@Body body: ShopBuyRequest): ShopPurchase
    @GET("api/pokemon/eggs") suspend fun eggs(): List<PlayerEgg>
    @POST("api/pokemon/eggs/incubate") suspend fun incubate(@Body body: IncubateEggRequest)
    @POST("api/pokemon/nickname") suspend fun nickname(@Body body: NicknamePokemonRequest)
    @POST("api/pokemon/favourite") suspend fun favourite(@Body body: FavouritePokemonRequest)
    @POST("api/pokemon/buddy") suspend fun setBuddy(@Body body: BuddyPokemonRequest)
    @GET("api/pokemon/{id}/evolution") suspend fun evolutionOptions(@Path("id") id: Long): List<EvolutionOption>
    @POST("api/pokemon/{id}/evolve") suspend fun evolve(@Path("id") id: Long, @Body body: EvolvePokemonRequest): EvolutionResult
    @POST("api/pokemon/heal") suspend fun heal(@Body body: HealPokemonRequest): HealPokemonResult

    // ── Map ──────────────────────────────────────────────────────────────────
    //
    // `radius` is metres and the server defaults it to 500. The website asks for
    // 500 and then hides anything past 200m behind a "sightings" treatment, so
    // the radius here is the fetch window, not the interaction range.
    //
    // GET /nearby has a side effect: it records the player's position and spawns
    // more Pokemon when the area is sparse. So it is the call that makes the world
    // populate, and polling it is load-bearing rather than just a refresh.
    @GET("api/pokemon/nearby")
    suspend fun nearbySpawns(
        @Query("lat") lat: Double, @Query("lng") lng: Double,
        @Query("radius") radius: Double = 500.0,
    ): List<PokemonSpawn>

    @GET("api/pokemon/pokestops/nearby")
    suspend fun nearbyStops(
        @Query("lat") lat: Double, @Query("lng") lng: Double,
        @Query("radius") radius: Double = 500.0,
    ): List<Pokestop>

    @GET("api/pokemon/gyms/nearby")
    suspend fun nearbyGyms(
        @Query("lat") lat: Double, @Query("lng") lng: Double,
        @Query("radius") radius: Double = 500.0,
    ): List<Gym>

    @GET("api/pokemon/gym/{id}") suspend fun gym(@Path("id") id: Long): Gym

    @POST("api/pokemon/catch") suspend fun attemptCatch(@Body body: CatchRequest): CatchResult

    @POST("api/pokemon/pokestop/spin") suspend fun spinStop(@Body body: SpinStopRequest): SpinStopResult

    // Answers a bare string ("Pokestop added: ..."), not JSON, so this cannot be
    // given a @Serializable return type.
    @POST("api/pokemon/pokestop/add") suspend fun addStop(@Body body: AddStopRequest): ResponseBody

    @POST("api/pokemon/pokestop/lure") suspend fun lureStop(@Body body: LureStopRequest): LureStopResult

    @POST("api/pokemon/gym/add") suspend fun addGym(@Body body: AddGymRequest): Gym
    @POST("api/pokemon/gym/spin") suspend fun spinGym(@Body body: SpinGymRequest): SpinGymResult

    @GET("api/pokemon/team") suspend fun team(): TeamResponse
    @POST("api/pokemon/team") suspend fun setTeam(@Body body: SetTeamRequest): SetTeamResult

    /** A GPS ping. Advances walked distance, egg incubation and buddy candy. */
    @POST("api/pokemon/walk") suspend fun walk(@Body body: WalkRequest): WalkResult
}
