package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.Closeable
import java.util.LinkedHashMap
import kotlin.coroutines.coroutineContext

sealed interface ResourceEntryState<out T> {
    data object Empty : ResourceEntryState<Nothing>
    data object Loading : ResourceEntryState<Nothing>
    data class Ready<T>(val value: T) : ResourceEntryState<T>
    data class Stale<T>(val value: T) : ResourceEntryState<T>
    data class Failed(val message: String) : ResourceEntryState<Nothing>
}

/** One AppRuntime-owned, process-memory GET cache. It never stores canonical Playback/Queue snapshots. */
class ResourceMemoryCache(
    private val ownerScope: CoroutineScope,
    private val maxConcurrentRequests: Int = 3,
    private val maxEntries: Int = 512,
    private val maxWeightBytes: Long = 16L * 1024L * 1024L,
    private val maxArtworkBytesPerImage: Int = 8 * 1024 * 1024,
    private val maxArtworkBytesTotal: Long = 24L * 1024L * 1024L,
    private val historyTtlMillis: Long = 60_000L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private data class Entry(
        val dependency: ResourceDependency,
        var stamp: ResourceDependencyStamp,
        val state: kotlinx.coroutines.flow.MutableStateFlow<ResourceEntryState<Any>>,
        var weight: Long = 0L,
        var cachedAtMillis: Long = 0L,
        var lastAccess: Long = 0L,
    )
    private data class SongEntity(
        val song: SongDto,
        val session: ResourceSessionIdentity,
        val libraryRevision: Long,
        var stale: Boolean,
        var lastAccess: Long,
    )
    private data class Flight(
        val session: ResourceSessionIdentity,
        val dependency: ResourceDependency,
        val stamp: ResourceDependencyStamp,
        val deferred: Deferred<ApiResult<Any>>,
    )
    private data class ArtworkIdentity(
        val session: ResourceSessionIdentity,
        val libraryRevision: Long,
        val artworkId: String,
    )
    private data class ArtworkEntry(
        val response: HttpResult,
        val weight: Long,
        var lastAccess: Long,
        var stale: Boolean = false,
    )
    private data class ArtworkFlight(
        val session: ResourceSessionIdentity,
        val libraryRevision: Long,
        val deferred: Deferred<HttpResult>,
    )

    private val lock = Any()
    private val identityMutableState = kotlinx.coroutines.flow.MutableStateFlow(ResourceCacheIdentityState())
    val identityState: kotlinx.coroutines.flow.StateFlow<ResourceCacheIdentityState> =
        identityMutableState.asStateFlow()
    private val slots = Semaphore(maxConcurrentRequests)
    private val entries = LinkedHashMap<ResourceCacheKey<*>, Entry>(32, 0.75f, true)
    private val stateFlows = LinkedHashMap<ResourceCacheKey<*>, kotlinx.coroutines.flow.MutableStateFlow<ResourceEntryState<Any>>>(32, 0.75f, true)
    private val flights = mutableMapOf<ResourceCacheKey<*>, Flight>()
    private val songEntities = LinkedHashMap<String, SongEntity>(64, 0.75f, true)
    private val artworkEntries = LinkedHashMap<ArtworkIdentity, ArtworkEntry>(32, 0.75f, true)
    private val artworkFlights = mutableMapOf<ArtworkIdentity, ArtworkFlight>()
    private var session: ResourceSessionIdentity? = null
    private var watermarks: ResourceWatermarks? = null
    private var isFresh = false
    private var sequence = 0L
    private var metadataWeight = 0L
    private var songEntityWeight = 0L
    private var artworkWeight = 0L
    private var closed = false

    init {
        require(maxConcurrentRequests > 0 && maxEntries > 0 && maxWeightBytes > 0L)
        require(maxArtworkBytesPerImage > 0 && maxArtworkBytesTotal >= maxArtworkBytesPerImage.toLong())
        require(historyTtlMillis > 0L)
    }

    /** Updates session identity and selective dependency watermarks from the accepted SnapshotStore. */
    fun updateIdentity(
        newSession: ResourceSessionIdentity,
        newWatermarks: ResourceWatermarks,
        isFresh: Boolean,
    ) {
        val cancel = mutableListOf<Deferred<*>>()
        synchronized(lock) {
            if (closed) return
            val previousSession = session
            if (previousSession != null && previousSession != newSession) {
                cancel += flights.values.map { it.deferred }
                cancel += artworkFlights.values.map { it.deferred }
                flights.clear()
                artworkFlights.clear()
                entries.clear()
                stateFlows.values.forEach { it.value = ResourceEntryState.Empty }
                songEntities.clear()
                songEntityWeight = 0L
                artworkEntries.clear()
                metadataWeight = 0L
                artworkWeight = 0L
            } else {
                val previous = watermarks
                if (previous != null && previous.libraryRevision != newWatermarks.libraryRevision) {
                    invalidateLocked(ResourceDependency.LIBRARY)
                    invalidateLocked(ResourceDependency.PLAYLIST)
                    songEntities.clear()
                    songEntityWeight = 0L
                    artworkEntries.clear()
                    artworkWeight = 0L
                    cancel += flights.values.filter {
                        it.dependency == ResourceDependency.LIBRARY ||
                            it.dependency == ResourceDependency.PLAYLIST
                    }.map { it.deferred }
                    flights.entries.removeAll {
                        it.value.dependency == ResourceDependency.LIBRARY ||
                            it.value.dependency == ResourceDependency.PLAYLIST
                    }
                    cancel += artworkFlights.values.map { it.deferred }
                    artworkFlights.clear()
                } else if (previous != null && previous.playlistRevision != newWatermarks.playlistRevision) {
                    invalidateLocked(ResourceDependency.PLAYLIST)
                    cancel += flights.values.filter { it.dependency == ResourceDependency.PLAYLIST }.map { it.deferred }
                    flights.entries.removeAll { it.value.dependency == ResourceDependency.PLAYLIST }
                }
                if (previous != null && previous.historyFingerprint != newWatermarks.historyFingerprint) {
                    invalidateLocked(ResourceDependency.HISTORY)
                    cancel += flights.values.filter { it.dependency == ResourceDependency.HISTORY }.map { it.deferred }
                    flights.entries.removeAll { it.value.dependency == ResourceDependency.HISTORY }
                }
            }
            session = newSession
            watermarks = newWatermarks
            this.isFresh = isFresh
            identityMutableState.value = ResourceCacheIdentityState(
                session = newSession,
                watermarks = newWatermarks,
                isFresh = isFresh,
            )
            if (!isFresh) {
                entries.values.forEach(::markStaleLocked)
                songEntities.values.forEach { it.stale = true }
                artworkEntries.values.forEach { it.stale = true }
                cancel += flights.values.map { it.deferred }
                flights.clear()
                cancel += artworkFlights.values.map { it.deferred }
                artworkFlights.clear()
            }
            trimStateFlowsLocked()
        }
        cancel.distinct().forEach { it.cancel(CancellationException("Resource identity or freshness changed")) }
    }

    /** Dynamic Queue reads can bypass value caching while still sharing the global GET limit. */
    internal suspend fun <T> readUncached(loader: suspend () -> T): T = slots.withPermit { loader() }

    /** Invalidate one cached GET before an explicit post-mutation readback. */
    fun <T : Any> invalidateResource(key: ResourceCacheKey<T>) {
        val pending = synchronized(lock) {
            if (closed) return
            val request = flights.remove(key)?.deferred
            removeEntryLocked(key)
            request
        }
        pending?.cancel(CancellationException("Resource explicitly invalidated for readback"))
    }

    /** A caller may cancel its await without cancelling the runtime-owned shared request. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> readResource(
        key: ResourceCacheKey<T>,
        dependency: ResourceDependency,
        loader: suspend () -> ApiResult<T>,
    ): ApiResult<T> {
        var selected: Deferred<ApiResult<Any>>? = null
        var shouldStart = false
        synchronized(lock) {
            if (closed) return ApiResult.ProtocolError("Resource cache is closed")
            val currentSession = session ?: return ApiResult.ProtocolError("Resource session is not established")
            val currentWatermarks = watermarks ?: return ApiResult.ProtocolError("Resource watermarks are not established")
            val expectedStamp = stamp(dependency, currentWatermarks)

            val existing = entries[key]
            if (existing != null && existing.dependency != dependency) removeEntryLocked(key)
            val entry = entries[key]
            if (entry != null) {
                entry.lastAccess = ++sequence
                if (entry.stamp != expectedStamp) markStaleLocked(entry)
                val ttlExpired = dependency == ResourceDependency.HISTORY &&
                    clockMillis() - entry.cachedAtMillis >= historyTtlMillis
                if (ttlExpired) markStaleLocked(entry)
                val cachedState = entry.state.value
                if (isFresh && !ttlExpired && entry.stamp == expectedStamp &&
                    cachedState is ResourceEntryState.Ready<*>
                ) return ApiResult.Success(cachedState.value as T, 200)
                if (!isFresh) {
                    val cachedValue = when (cachedState) {
                        is ResourceEntryState.Ready<*> -> cachedState.value
                        is ResourceEntryState.Stale<*> -> cachedState.value
                        else -> null
                    }
                    if (cachedValue != null) return ApiResult.Success(cachedValue as T, 200)
                    return ApiResult.ProtocolError("Server resource is unavailable while the snapshot is stale")
                }
            } else if (!isFresh) {
                return ApiResult.ProtocolError("Server resource is unavailable while the snapshot is stale")
            }

            val existingFlight = flights[key]
            if (existingFlight != null && existingFlight.session == currentSession &&
                existingFlight.dependency == dependency && existingFlight.stamp == expectedStamp
            ) {
                selected = existingFlight.deferred
            } else {
                val currentEntry = entries[key]
                if (currentEntry == null) {
                    val state = stateFlowLocked(key)
                    state.value = ResourceEntryState.Loading
                    entries[key] = Entry(
                        dependency,
                        expectedStamp,
                        state,
                        lastAccess = ++sequence,
                    )
                } else if (currentEntry.state.value !is ResourceEntryState.Stale<*>) {
                    currentEntry.state.value = ResourceEntryState.Loading
                }

                lateinit var created: Deferred<ApiResult<Any>>
                created = ownerScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val loaded: ApiResult<T> = try {
                            slots.withPermit { loader() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            ApiResult.ProtocolError(error.message ?: "Resource read failed")
                        }
                        coroutineContext.ensureActive()
                        synchronized(lock) {
                            val flight = flights[key]
                            val stillCurrent = !closed && session == currentSession && isFresh &&
                                watermarks?.let { stamp(dependency, it) == expectedStamp } == true &&
                                flight?.deferred === created
                            if (!stillCurrent) {
                                ApiResult.ProtocolError("Resource response belongs to an obsolete server identity or revision")
                            } else when (loaded) {
                                is ApiResult.Success -> {
                                    val value = loaded.value as Any
                                    putEntryLocked(key, dependency, expectedStamp, value, estimateWeight(value))
                                    ApiResult.Success(value, loaded.statusCode)
                                }
                                else -> {
                                    val failedEntry = entries[key]
                                    val previousState = failedEntry?.state?.value
                                    val previousValue = when (previousState) {
                                        is ResourceEntryState.Ready<*> -> previousState.value
                                        is ResourceEntryState.Stale<*> -> previousState.value
                                        else -> null
                                    }
                                    if (failedEntry != null) {
                                        failedEntry.state.value = if (previousValue != null) {
                                            ResourceEntryState.Stale(previousValue)
                                        } else ResourceEntryState.Failed(loaded.toString())
                                    }
                                    trimEntriesLocked()
                                    loaded as ApiResult<Any>
                                }
                            }
                        }
                    } finally {
                        synchronized(lock) {
                            if (flights[key]?.deferred === created) flights.remove(key)
                        }
                    }
                }
                flights[key] = Flight(currentSession, dependency, expectedStamp, created)
                selected = created
                shouldStart = true
            }
        }
        val request = selected ?: return ApiResult.ProtocolError("Resource request could not be scheduled")
        if (shouldStart) request.start()
        return request.await() as ApiResult<T>
    }

    fun captureLibraryIdentity(): ResourceLibraryIdentity? = synchronized(lock) {
        val activeSession = session ?: return@synchronized null
        val libraryRevision = watermarks?.libraryRevision ?: return@synchronized null
        ResourceLibraryIdentity(activeSession, libraryRevision)
    }

    fun isCurrent(identity: ResourceLibraryIdentity): Boolean = synchronized(lock) {
        !closed && session == identity.session && watermarks?.libraryRevision == identity.libraryRevision
    }

    /** Interns all non-null IDs only if the response still belongs to the captured Library identity. */
    fun internSongs(
        songs: List<SongDto>,
        expectedIdentity: ResourceLibraryIdentity? = null,
    ): List<SongDto> = synchronized(lock) {
        if (closed || session == null || watermarks == null) return@synchronized songs
        if (expectedIdentity != null &&
            (session != expectedIdentity.session || watermarks?.libraryRevision != expectedIdentity.libraryRevision)
        ) return@synchronized songs
        songs.map { song -> internSongLocked(song, stale = !isFresh) }
            .also { trimEntriesLocked() }
    }

    fun peekSong(songId: String): SongDto? = synchronized(lock) {
        songEntities[songId]?.also { it.lastAccess = ++sequence }?.song
    }

    /**
     * Reuses only a fresh entity. Last-known entities remain displayable while offline, then force
     * a read after reconnection before they can be promoted to fresh again.
     */
    suspend fun readSong(songId: String, loader: suspend () -> ApiResult<SongDto>): ApiResult<SongDto> {
        require(songId.isNotBlank())
        val expectedSession: ResourceSessionIdentity
        val expectedLibraryRevision: Long
        synchronized(lock) {
            expectedSession = session
                ?: return ApiResult.ProtocolError("Resource session is not established")
            expectedLibraryRevision = watermarks?.libraryRevision
                ?: return ApiResult.ProtocolError("Resource watermarks are not established")
            val cached = songEntities[songId]?.takeIf {
                it.session == expectedSession && it.libraryRevision == expectedLibraryRevision
            }
            if (cached != null && (!cached.stale || !isFresh)) {
                cached.lastAccess = ++sequence
                return ApiResult.Success(cached.song, 200)
            }
        }

        val key = ResourceCacheKey.get<SongDto>(LibraryApiPaths.song(songId))
        val result = readResource(key, ResourceDependency.LIBRARY) {
            when (val loaded = loader()) {
                is ApiResult.Success -> if (loaded.value.songId != songId) {
                    ApiResult.ProtocolError("Song response ID did not match the requested song")
                } else loaded
                else -> loaded
            }
        }
        return when (result) {
            is ApiResult.Success -> synchronized(lock) {
                if (session != expectedSession || watermarks?.libraryRevision != expectedLibraryRevision) {
                    return@synchronized ApiResult.ProtocolError(
                        "Song response belongs to an obsolete server identity or library revision",
                    )
                }
                if (result.value.songId != songId) {
                    return@synchronized ApiResult.ProtocolError("Song response ID did not match the requested song")
                }
                val canonical = internSongLocked(result.value, stale = !isFresh)
                entries[key]?.takeIf {
                    it.dependency == ResourceDependency.LIBRARY &&
                        it.stamp == stamp(ResourceDependency.LIBRARY, watermarks!!)
                }?.let { entry ->
                    metadataWeight -= entry.weight
                    entry.weight = estimateWeight(canonical)
                    entry.state.value = if (isFresh) {
                        ResourceEntryState.Ready(canonical)
                    } else {
                        ResourceEntryState.Stale(canonical)
                    }
                    metadataWeight += entry.weight
                    trimEntriesLocked()
                }
                ApiResult.Success(canonical, result.statusCode)
            }
            else -> result
        }
    }

    /** Raw compressed artwork is shared by Queue, Player and future Home/Library surfaces. */
    suspend fun readArtwork(
        key: ArtworkCacheKey,
        expectedIdentity: ResourceLibraryIdentity? = null,
        loader: suspend () -> HttpResult,
    ): HttpResult {
        var selected: Deferred<HttpResult>? = null
        var shouldStart = false
        val identity: ArtworkIdentity
        synchronized(lock) {
            if (closed) return HttpResult.Failed(HttpFailure.Protocol("Resource cache is closed"))
            val activeSession = session
                ?: return HttpResult.Failed(HttpFailure.Protocol("Resource session is not established"))
            val currentWatermarks = watermarks
                ?: return HttpResult.Failed(HttpFailure.Protocol("Resource watermarks are not established"))
            if (expectedIdentity != null &&
                (activeSession != expectedIdentity.session ||
                    currentWatermarks.libraryRevision != expectedIdentity.libraryRevision)
            ) {
                return HttpResult.Failed(HttpFailure.Protocol("Artwork identity changed before the resource could be read"))
            }
            identity = ArtworkIdentity(activeSession, currentWatermarks.libraryRevision, key.artworkId)
            artworkEntries[identity]?.let { cached ->
                cached.lastAccess = ++sequence
                // Last-known bytes remain displayable while disconnected. Once the snapshot is
                // fresh again, a stale artwork entry must be revalidated rather than silently
                // promoted to a current resource.
                if (!isFresh || !cached.stale) return cached.response
                artworkEntries.remove(identity)
                artworkWeight -= cached.weight
            }
            if (!isFresh) return HttpResult.Failed(HttpFailure.Protocol("Artwork is unavailable while the snapshot is stale"))

            val oldFlight = artworkFlights[identity]
            if (oldFlight != null && oldFlight.session == activeSession &&
                oldFlight.libraryRevision == currentWatermarks.libraryRevision
            ) {
                selected = oldFlight.deferred
            } else {
                lateinit var created: Deferred<HttpResult>
                created = ownerScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val response = try {
                            slots.withPermit { loader() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            HttpResult.Failed(HttpFailure.Protocol(error.message ?: "Artwork read failed"))
                        }
                        coroutineContext.ensureActive()
                        val processed = validateArtworkResponse(response)
                        synchronized(lock) {
                            val stillCurrent = !closed && session == activeSession && isFresh &&
                                watermarks?.libraryRevision == identity.libraryRevision &&
                                artworkFlights[identity]?.deferred === created
                            if (!stillCurrent) {
                                HttpResult.Failed(HttpFailure.Protocol("Artwork response belongs to an obsolete server identity or revision"))
                            } else {
                                if (processed is HttpResult.Body || processed.isExplicitArtworkMissing()) {
                                    val weight = (processed as? HttpResult.Body)?.bytes?.size?.toLong() ?: 0L
                                    artworkEntries[identity] = ArtworkEntry(processed, weight, ++sequence)
                                    artworkWeight += weight
                                    trimArtworkLocked()
                                }
                                processed
                            }
                        }
                    } finally {
                        synchronized(lock) {
                            if (artworkFlights[identity]?.deferred === created) artworkFlights.remove(identity)
                        }
                    }
                }
                artworkFlights[identity] = ArtworkFlight(activeSession, currentWatermarks.libraryRevision, created)
                selected = created
                shouldStart = true
            }
        }
        val request = selected ?: return HttpResult.Failed(HttpFailure.Protocol("Artwork request could not be scheduled"))
        if (shouldStart) request.start()
        return request.await()
    }

    /**
     * Remaining monotonic freshness window for a successfully cached History resource.
     * Reading this value is observational only; it never refreshes or changes the entry.
     */
    fun remainingTtlMillis(key: ResourceCacheKey<*>): Long? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.dependency != ResourceDependency.HISTORY) return@synchronized null
        if (entry.state.value !is ResourceEntryState.Ready<*> &&
            entry.state.value !is ResourceEntryState.Stale<*>
        ) return@synchronized null
        val age = (clockMillis() - entry.cachedAtMillis).coerceAtLeast(0L)
        (historyTtlMillis - age).coerceAtLeast(0L)
    }

    fun clear() {
        val pending = synchronized(lock) {
            val all = flights.values.map { it.deferred } + artworkFlights.values.map { it.deferred }
            flights.clear()
            artworkFlights.clear()
            entries.clear()
            stateFlows.values.forEach { it.value = ResourceEntryState.Empty }
            songEntities.clear()
            songEntityWeight = 0L
            artworkEntries.clear()
            metadataWeight = 0L
            artworkWeight = 0L
            trimStateFlowsLocked()
            all
        }
        pending.forEach { it.cancel(CancellationException("Resource cache cleared")) }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            identityMutableState.value = ResourceCacheIdentityState(isClosed = true)
        }
        clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> observeResource(key: ResourceCacheKey<T>): kotlinx.coroutines.flow.StateFlow<ResourceEntryState<T>> =
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val flow = (entries[key]?.state ?: stateFlowLocked(key))
                as kotlinx.coroutines.flow.StateFlow<ResourceEntryState<T>>
            trimStateFlowsLocked(protectedKey = key)
            flow
        }

    internal val metadataEntryCountForTest: Int get() = synchronized(lock) { entries.size }
    internal val metadataWeightBytesForTest: Long get() = synchronized(lock) { metadataWeight }
    internal val metadataResourceCountForTest: Int get() = synchronized(lock) { entries.size + songEntities.size }
    internal val metadataTotalWeightBytesForTest: Long get() = synchronized(lock) { metadataWeight + songEntityWeight }
    internal val artworkRetainedBytesForTest: Long get() = synchronized(lock) { artworkWeight }
    internal val songEntityWeightBytesForTest: Long get() = synchronized(lock) { songEntityWeight }
    internal val sessionForTest: ResourceSessionIdentity? get() = synchronized(lock) { session }
    internal val watermarksForTest: ResourceWatermarks? get() = synchronized(lock) { watermarks }

    private fun stamp(
        dependency: ResourceDependency,
        current: ResourceWatermarks,
    ): ResourceDependencyStamp = when (dependency) {
        ResourceDependency.SESSION -> ResourceDependencyStamp.Session
        ResourceDependency.LIBRARY -> ResourceDependencyStamp.Library(current.libraryRevision)
        ResourceDependency.PLAYLIST -> ResourceDependencyStamp.Playlist(
            libraryRevision = current.libraryRevision,
            playlistRevision = current.playlistRevision,
        )
        ResourceDependency.HISTORY -> ResourceDependencyStamp.History(current.historyFingerprint)
    }

    private fun invalidateLocked(dependency: ResourceDependency) {
        entries.values.filter { it.dependency == dependency }.forEach(::markStaleLocked)
    }

    private fun markStaleLocked(entry: Entry) {
        entry.state.value = when (val state = entry.state.value) {
            is ResourceEntryState.Ready -> ResourceEntryState.Stale(state.value)
            is ResourceEntryState.Stale -> state
            ResourceEntryState.Loading -> ResourceEntryState.Empty
            else -> state
        }
    }

    private fun putEntryLocked(
        key: ResourceCacheKey<*>,
        dependency: ResourceDependency,
        expectedStamp: ResourceDependencyStamp,
        value: Any,
        weight: Long,
    ) {
        val entry = entries[key] ?: Entry(
            dependency,
            expectedStamp,
            stateFlowLocked(key),
        )
        metadataWeight -= entry.weight
        entry.stamp = expectedStamp
        entry.weight = weight
        entry.cachedAtMillis = clockMillis()
        entry.lastAccess = ++sequence
        entry.state.value = ResourceEntryState.Ready(value)
        entries[key] = entry
        metadataWeight += weight
        trimEntriesLocked()
    }

    private fun removeEntryLocked(key: ResourceCacheKey<*>) {
        entries.remove(key)?.let { metadataWeight -= it.weight }
        stateFlows[key]?.value = ResourceEntryState.Empty
    }

    private fun stateFlowLocked(key: ResourceCacheKey<*>) = stateFlows.getOrPut(key) {
        kotlinx.coroutines.flow.MutableStateFlow(ResourceEntryState.Empty)
    }

    /**
     * Keep active observers attached across LRU eviction/reload. Unobserved orphan flows are
     * bounded separately; flows belonging to an entry or in-flight request are never discarded.
     */
    private fun trimStateFlowsLocked(protectedKey: ResourceCacheKey<*>? = null) {
        while (stateFlows.size > maxEntries) {
            val candidate = stateFlows.entries.firstOrNull { (key, flow) ->
                key != protectedKey &&
                    key !in entries &&
                    key !in flights &&
                    flow.subscriptionCount.value == 0
            } ?: break
            stateFlows.remove(candidate.key)
        }
    }

    /** One completed-entry LRU budget covers both typed DTOs and canonical Song entities. */
    private fun trimEntriesLocked() {
        while (
            entries.size + songEntities.size > maxEntries ||
            metadataWeight + songEntityWeight > maxWeightBytes
        ) {
            val eldestEntry = entries.entries.firstOrNull {
                it.value.state.value !is ResourceEntryState.Loading
            }
            val eldestSong = songEntities.entries.firstOrNull()
            if (eldestEntry == null && eldestSong == null) break

            val evictSong = eldestSong != null &&
                (eldestEntry == null || eldestSong.value.lastAccess <= eldestEntry.value.lastAccess)
            if (evictSong) {
                val removed = songEntities.remove(eldestSong!!.key) ?: continue
                songEntityWeight -= estimateWeight(removed.song)
            } else {
                metadataWeight -= eldestEntry!!.value.weight
                entries.remove(eldestEntry.key)
                stateFlows[eldestEntry.key]?.value = ResourceEntryState.Empty
            }
        }
        trimStateFlowsLocked()
    }

    private fun internSongLocked(song: SongDto, stale: Boolean): SongDto {
        val id = song.songId?.takeIf(String::isNotBlank) ?: return song
        val activeSession = session ?: return song
        val libraryRevision = watermarks?.libraryRevision ?: return song
        val existing = songEntities[id]?.takeIf {
            it.session == activeSession && it.libraryRevision == libraryRevision
        }
        val selected = if (existing != null && (!existing.stale || stale)) {
            existing
        } else {
            songEntities[id]?.let { songEntityWeight -= estimateWeight(it.song) }
            SongEntity(song, activeSession, libraryRevision, stale, ++sequence).also {
                songEntities[id] = it
                songEntityWeight += estimateWeight(song)
            }
        }
        selected.lastAccess = ++sequence
        synchronizeSongDetailEntryLocked(id, selected)
        trimEntriesLocked()
        return selected.song
    }

    /** Song list and detail routes converge on the same canonical SongDto object. */
    private fun synchronizeSongDetailEntryLocked(songId: String, entity: SongEntity) {
        val currentWatermarks = watermarks ?: return
        val key = ResourceCacheKey.get<SongDto>(LibraryApiPaths.song(songId))
        val entry = entries[key]?.takeIf {
            it.dependency == ResourceDependency.LIBRARY &&
                it.stamp == stamp(ResourceDependency.LIBRARY, currentWatermarks)
        } ?: return
        metadataWeight -= entry.weight
        entry.lastAccess = ++sequence
        entry.weight = estimateWeight(entity.song)
        entry.state.value = if (entity.stale) {
            ResourceEntryState.Stale(entity.song)
        } else {
            ResourceEntryState.Ready(entity.song)
        }
        metadataWeight += entry.weight
        trimEntriesLocked()
    }

    private fun trimArtworkLocked() {
        while (artworkWeight > maxArtworkBytesTotal || artworkEntries.size > maxEntries) {
            val iterator = artworkEntries.entries.iterator()
            if (!iterator.hasNext()) break
            artworkWeight -= iterator.next().value.weight
            iterator.remove()
        }
    }

    private fun estimateWeight(value: Any): Long = when (value) {
        is String -> 48L + value.length * 2L
        is ByteArray -> 32L + value.size
        is List<*> -> 48L + value.sumOf { it?.let(::estimateWeight) ?: 8L }
        is SongDto -> 256L + (
            listOfNotNull(
                value.title, value.fileUri, value.album, value.lyrics, value.lyricsFormat,
                value.lyricsSource, value.lyricsStatus, value.codec, value.metadataStatus,
                value.lastScannedAt, value.date, value.contentHash, value.lastSeenAt,
                value.artwork?.artworkId, value.artwork?.mimeType, value.artwork?.contentSha256,
            ).sumOf { it.length.toLong() } +
                value.artists.sumOf { it.length.toLong() } +
                value.albumArtists.sumOf { it.length.toLong() } +
                value.genres.sumOf { it.length.toLong() } +
                value.tagNames.sumOf { it.length.toLong() }
            ) * 2L
        else -> 64L + value.toString().length * 2L
    }

    private fun validateArtworkResponse(response: HttpResult): HttpResult = when (response) {
        is HttpResult.Body -> when {
            response.bytes.size > maxArtworkBytesPerImage ->
                HttpResult.Failed(HttpFailure.Protocol("Artwork exceeds the configured size limit"))
            !isSupportedImage(response.contentType, response.bytes) ->
                HttpResult.Failed(HttpFailure.Protocol("Artwork response is not a supported image"))
            else -> response
        }
        else -> response
    }

    private fun isSupportedImage(contentType: String?, bytes: ByteArray): Boolean {
        if (!contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true)) return false
        val png = bytes.size >= 8 && bytes.take(8).map { it.toInt() and 0xff } ==
            listOf(137, 80, 78, 71, 13, 10, 26, 10)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        return png || jpeg || gif || webp
    }
}
