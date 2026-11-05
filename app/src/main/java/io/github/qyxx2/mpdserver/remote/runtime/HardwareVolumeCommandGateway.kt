package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID

/** Transient arbitration status; none of these fields is canonical hardware-volume state. */
enum class HardwareVolumeCommandPhase { IDLE, QUEUED, IN_FLIGHT, UNKNOWN, FAILED }
enum class HardwareVolumeEnqueueDisposition { STARTED, QUEUED, NO_CHANGE, REJECTED }

data class HardwareVolumeEnqueueResult(
    val disposition: HardwareVolumeEnqueueDisposition,
    val message: String? = null,
)

data class HardwareVolumeCommandState(
    val phase: HardwareVolumeCommandPhase = HardwareVolumeCommandPhase.IDLE,
    val activeIntentId: String? = null,
    val activeTargetPercent: Int? = null,
    /** Adjacent same-direction key presses are represented by one pending delta segment. */
    val queuedInputCount: Int = 0,
    val retryableIntentId: String? = null,
    val message: String? = null,
    /** Confirmed canonical value plus transient FIFO inputs; never written to SnapshotStore. */
    val previewVolumePercent: Int? = null,
)

/**
 * The single hardware-volume input arbitration seam. Slider absolute targets and key deltas are
 * submitted through the existing MutationCoordinator; each next target is derived only after an
 * accepted full-state readback in SnapshotStore. This class owns no network or canonical state.
 */
class HardwareVolumeCommandGateway(
    private val stateProvider: () -> SnapshotStoreState,
    private val snapshots: StateFlow<SnapshotStoreState>,
    private val activitiesProvider: StateFlow<List<OperationActivity>>,
    private val originProvider: () -> ServerOrigin,
    private val submitter: (MutationIntent) -> MutationOutcome,
    private val retryUnknownSubmitter: (String) -> MutationOutcome,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val commandEligibilityProvider: () -> Boolean = { true },
    /** Shared with AppRuntime origin switching so check+submit cannot cross an origin boundary. */
    private val commandLock: Any = Any(),
) : Closeable {
    private val operationJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val operationScope = CoroutineScope(parentScope.coroutineContext + operationJob)
    private val queue = ArrayDeque<PendingInput>()
    private var session: HardwareVolumeSession? = null
    private var sessionAborted = false
    private var active: ActiveCommand? = null
    private var unknownRetry: UnknownRetry? = null
    private var worker: Job? = null
    private val _state = MutableStateFlow(HardwareVolumeCommandState())
    val state: StateFlow<HardwareVolumeCommandState> = _state.asStateFlow()

    private val snapshotObserver = operationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        snapshots.collect { observed ->
            synchronized(commandLock) {
                val expected = session ?: return@synchronized
                if (!sessionMatches(expected, observed, originProvider())) {
                    abortQueuedInputsLocked("Hardware volume state/session is no longer fresh")
                } else {
                    // Keep the short-lived overlay preview aligned with canonical readback while
                    // preserving queued inputs and without changing the hide deadline.
                    publishLocked(phase = _state.value.phase)
                }
            }
        }
    }

    /** Submit one Slider release target. The UI must keep its draft local until server readback. */
    fun setAbsolute(volumePercent: Int): HardwareVolumeEnqueueResult {
        if (volumePercent !in 0..100) {
            return HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "Volume target must be between 0 and 100")
        }
        return enqueue(PendingInput.Absolute(volumePercent))
    }

    /** One valid Volume Up/Down DOWN or repeat event is exactly one ±1 step. */
    fun step(direction: Int): HardwareVolumeEnqueueResult {
        if (direction != -1 && direction != 1) {
            return HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "Key step must be -1 or +1")
        }
        return enqueue(PendingInput.KeyDelta(direction, 1))
    }

    /** Explicit recovery only; MutationCoordinator resends the original method/path/payload/key. */
    fun retryUnknown(): MutationOutcome = synchronized(commandLock) {
        val retained = unknownRetry ?: return@synchronized MutationOutcome.NotRetryable
        if (worker?.isActive == true || active != null || queue.isNotEmpty()) {
            return@synchronized MutationOutcome.DuplicatePending
        }
        if (!commandEligibilityProvider()) return@synchronized MutationOutcome.NotRetryable
        if (originProvider() != retained.origin) {
            unknownRetry = null
            publishLocked(
                phase = HardwareVolumeCommandPhase.IDLE,
                activeIntentId = null,
                activeTargetPercent = null,
                queuedInputCount = 0,
                retryableIntentId = null,
                message = "Unknown intent belongs to the previous server origin and cannot be retried here",
            )
            return@synchronized MutationOutcome.BlockedOriginChange
        }

        val snapshot = stateProvider()
        if (!HardwareVolumeCapabilityPolicy.evaluate(snapshot).canWrite) {
            return@synchronized MutationOutcome.NotRetryable
        }
        val retrySession = sessionFor(snapshot, retained.origin)
            ?: return@synchronized MutationOutcome.NotRetryable
        val retryWork = ActiveCommand(
            intent = retained.intent,
            targetPercent = retained.targetPercent,
            session = retrySession,
            baseSequence = snapshot.snapshot?.sequence ?: return@synchronized MutationOutcome.NotRetryable,
            isExplicitRetry = true,
        )

        // retryUnknown updates the existing activity to PENDING synchronously and schedules the
        // exact retained MutationRequest. It never rebuilds a key or serializes a new payload.
        val result = retryUnknownSubmitter(retained.intent.id)
        if (result != MutationOutcome.Submitted) return@synchronized result

        unknownRetry = null
        session = retrySession
        sessionAborted = false
        active = retryWork
        publishLocked(
            phase = HardwareVolumeCommandPhase.IN_FLIGHT,
            activeIntentId = retryWork.intent.id,
            activeTargetPercent = retryWork.targetPercent,
            message = "Retrying the original hardware-volume intent",
        )
        startWorkerLocked(retryWork)
        result
    }

    /** Called while AppRuntime holds the shared origin-switch lock. */
    fun onOriginChanging(newOrigin: ServerOrigin) = synchronized(commandLock) {
        if (session != null && session?.origin != newOrigin) {
            abortQueuedInputsLocked("Server origin changed; unsent volume inputs were discarded")
        }
        if (unknownRetry?.origin != null && unknownRetry?.origin != newOrigin) {
            unknownRetry = null
            publishLocked(
                phase = HardwareVolumeCommandPhase.IDLE,
                activeIntentId = null,
                activeTargetPercent = null,
                queuedInputCount = 0,
                retryableIntentId = null,
                message = "Unknown intent belongs to the previous server origin and cannot be retried here",
            )
        }
    }

    override fun close() {
        snapshotObserver.cancel()
        operationScope.cancel()
    }

    private fun enqueue(input: PendingInput): HardwareVolumeEnqueueResult = synchronized(commandLock) {
        if (!commandEligibilityProvider()) {
            return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "Server origin is changing")
        }
        val retainedUnknown = unknownRetry
        if (retainedUnknown != null && retainedUnknown.origin != originProvider()) {
            unknownRetry = null
            publishLocked(
                phase = HardwareVolumeCommandPhase.IDLE,
                activeIntentId = null,
                activeTargetPercent = null,
                queuedInputCount = 0,
                retryableIntentId = null,
                message = "Previous-server UNKNOWN intent is no longer eligible for retry",
            )
        } else if (retainedUnknown != null) {
            return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "An UNKNOWN intent requires explicit retry before new volume input")
        }

        val snapshot = stateProvider()
        val capability = HardwareVolumeCapabilityPolicy.evaluate(snapshot)
        val confirmedVolume = capability.controllableVolumePercent
        if (!capability.canWrite || confirmedVolume == null) {
            return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "Hardware volume is not writable from the current fresh server snapshot")
        }

        val hasWork = worker?.isActive == true || active != null || queue.isNotEmpty()
        if (!hasWork && targetFor(input, confirmedVolume) == confirmedVolume) {
            return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.NO_CHANGE)
        }
        if (!hasWork) {
            session = sessionFor(snapshot, originProvider())
                ?: return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "No current server snapshot is available")
            sessionAborted = false
        } else {
            val expected = session
            if (expected == null || sessionAborted || !sessionMatches(expected, snapshot, originProvider())) {
                abortQueuedInputsLocked("Hardware volume state/session changed; queued input was discarded")
                return@synchronized HardwareVolumeEnqueueResult(HardwareVolumeEnqueueDisposition.REJECTED, "Hardware volume state/session changed")
            }
        }

        appendInputLocked(input)
        val shouldStart = worker?.isActive != true
        publishLocked(
            phase = if (active == null) HardwareVolumeCommandPhase.QUEUED else HardwareVolumeCommandPhase.IN_FLIGHT,
            activeIntentId = active?.intent?.id,
            activeTargetPercent = active?.targetPercent,
            message = null,
        )
        if (shouldStart) startWorkerLocked()
        HardwareVolumeEnqueueResult(
            if (shouldStart) HardwareVolumeEnqueueDisposition.STARTED else HardwareVolumeEnqueueDisposition.QUEUED,
        )
    }

    private fun appendInputLocked(input: PendingInput) {
        val last = queue.peekLast()
        if (input is PendingInput.KeyDelta && last is PendingInput.KeyDelta && last.direction == input.direction) {
            queue.removeLast()
            queue.addLast(last.copy(steps = (last.steps + input.steps).coerceAtMost(Int.MAX_VALUE)))
        } else {
            queue.addLast(input)
        }
    }

    private fun startWorkerLocked(firstWork: ActiveCommand? = null) {
        if (worker?.isActive == true) return
        val job = operationScope.launch(dispatcher, start = CoroutineStart.LAZY) { drain(firstWork) }
        worker = job
        job.start()
    }

    private suspend fun drain(firstWork: ActiveCommand?) {
        val currentJob = currentCoroutineContext()[Job] ?: return
        try {
            var first = firstWork
            while (currentCoroutineContext().isActive) {
                val work = first ?: synchronized(commandLock) {
                    val expected = session
                    if (expected == null || sessionAborted ||
                        !sessionMatches(expected, stateProvider(), originProvider())
                    ) {
                        abortQueuedInputsLocked("Hardware volume state/session changed before the next command")
                        finishIdleLocked(currentJob)
                        return
                    }
                    val nextInput = queue.pollFirst()
                    if (nextInput == null) {
                        finishIdleLocked(currentJob)
                        return
                    }
                    val currentSnapshot = stateProvider()
                    val currentVolume = HardwareVolumeCapabilityPolicy.evaluate(currentSnapshot).controllableVolumePercent
                    if (currentVolume == null) {
                        abortQueuedInputsLocked("Fresh server-confirmed hardware volume is unavailable")
                        finishIdleLocked(currentJob)
                        return
                    }
                    val target = targetFor(nextInput, currentVolume)
                    if (target == currentVolume) {
                        null // boundary-clamped key step or absolute target already achieved
                    } else {
                        val intent = MutationIntent(
                            id = "hardware-volume:${idFactory()}",
                            method = "PUT",
                            path = HARDWARE_VOLUME_PATH,
                            payload = buildJsonObject { put("volumePercent", target) }.toString(),
                            label = "DAC · 设置硬件音量",
                        )
                        ActiveCommand(
                            intent = intent,
                            targetPercent = target,
                            session = expected,
                            baseSequence = currentSnapshot.snapshot?.sequence ?: 0L,
                        ).also {
                            active = it
                            publishLocked(
                                phase = HardwareVolumeCommandPhase.IN_FLIGHT,
                                activeIntentId = intent.id,
                                activeTargetPercent = target,
                                message = null,
                            )
                        }
                    }
                }
                first = null
                if (work == null) continue

                val submission = synchronized(commandLock) {
                    if (sessionAborted || !sessionMatches(work.session, stateProvider(), originProvider()) ||
                        !commandEligibilityProvider()
                    ) {
                        abortQueuedInputsLocked("Hardware volume session changed before request submission")
                        null
                    } else if (work.isExplicitRetry) {
                        MutationOutcome.Submitted // the original intent was submitted by retryUnknown above
                    } else {
                        submitter(work.intent)
                    }
                }
                if (submission != MutationOutcome.Submitted) {
                    val message = when (submission) {
                        is MutationOutcome.Failed -> submission.message
                        MutationOutcome.BlockedOriginChange -> "Server origin changed before request submission"
                        MutationOutcome.DuplicatePending -> "Duplicate hardware-volume mutation is already pending"
                        else -> "Hardware-volume mutation was not accepted by MutationCoordinator"
                    }
                    finishWithFailure(currentJob, work, message)
                    return
                }

                val terminal = activitiesProvider.first { entries ->
                    entries.any { it.intentId == work.intent.id && it.status in TERMINAL_MUTATION_STATUSES }
                }.first { it.intentId == work.intent.id }
                if (terminal.status == MutationStatus.SUCCESS && terminal.syncError == null && confirmsReadback(work)) {
                    synchronized(commandLock) {
                        if (active?.intent?.id == work.intent.id) active = null
                        publishLocked(
                            phase = if (queue.isEmpty()) HardwareVolumeCommandPhase.QUEUED else HardwareVolumeCommandPhase.QUEUED,
                            activeIntentId = null,
                            activeTargetPercent = null,
                            message = null,
                        )
                    }
                    continue
                }
                finishFromTerminal(currentJob, work, terminal)
                return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(commandLock) {
                if (worker === currentJob) {
                    worker = null
                    if (active != null) {
                        active = null
                        queue.clear()
                        session = null
                        sessionAborted = false
                        publishLocked(
                            phase = if (unknownRetry != null) HardwareVolumeCommandPhase.UNKNOWN else HardwareVolumeCommandPhase.IDLE,
                            retryableIntentId = unknownRetry?.intent?.id,
                        )
                    }
                }
            }
        }
    }

    private fun confirmsReadback(work: ActiveCommand): Boolean {
        val current = stateProvider()
        return synchronized(commandLock) {
            val snapshot = current.snapshot
            val expectedSequence = if (work.isExplicitRetry) {
                snapshot?.sequence ?: return@synchronized false
            } else work.baseSequence + 1
            session != null && !sessionAborted && session == work.session &&
                sessionMatches(work.session, current, originProvider()) &&
                snapshot != null && snapshot.epoch == work.session.epoch &&
                snapshot.sequence >= expectedSequence &&
                HardwareVolumeCapabilityPolicy.evaluate(current).controllableVolumePercent == work.targetPercent
        }
    }

    private fun finishFromTerminal(currentJob: Job, work: ActiveCommand, activity: OperationActivity) = synchronized(commandLock) {
        if (active?.intent?.id == work.intent.id) active = null
        queue.clear()
        session = null
        sessionAborted = false
        // Publish terminal state only after the worker slot is available to a new user intent.
        if (worker === currentJob) worker = null
        when (activity.status) {
            MutationStatus.UNKNOWN -> {
                val originStillCurrent = originProvider() == work.session.origin
                unknownRetry = if (originStillCurrent) UnknownRetry(work.intent, work.targetPercent, work.session.origin) else null
                publishLocked(
                    phase = if (originStillCurrent) HardwareVolumeCommandPhase.UNKNOWN else HardwareVolumeCommandPhase.FAILED,
                    activeIntentId = null,
                    activeTargetPercent = null,
                    queuedInputCount = 0,
                    retryableIntentId = unknownRetry?.intent?.id,
                    message = if (originStillCurrent) {
                        activity.message ?: "Write outcome is unknown; explicitly retry the same intent to recover"
                    } else {
                        "Write outcome belongs to the previous server origin and cannot be retried here"
                    },
                )
            }
            MutationStatus.FAILED -> {
                unknownRetry = null
                publishLocked(
                    phase = HardwareVolumeCommandPhase.FAILED,
                    activeIntentId = null,
                    activeTargetPercent = null,
                    queuedInputCount = 0,
                    retryableIntentId = null,
                    message = activity.message ?: "Hardware-volume write failed",
                )
            }
            else -> {
                unknownRetry = null
                publishLocked(
                    phase = HardwareVolumeCommandPhase.FAILED,
                    activeIntentId = null,
                    activeTargetPercent = null,
                    queuedInputCount = 0,
                    retryableIntentId = null,
                    message = activity.syncError ?: "Authoritative readback did not confirm the requested volume",
                )
            }
        }
    }

    private fun finishWithFailure(currentJob: Job, work: ActiveCommand, message: String) = synchronized(commandLock) {
        if (active?.intent?.id == work.intent.id) active = null
        queue.clear()
        session = null
        sessionAborted = false
        unknownRetry = null
        if (worker === currentJob) worker = null
        publishLocked(
            phase = HardwareVolumeCommandPhase.FAILED,
            activeIntentId = null,
            activeTargetPercent = null,
            queuedInputCount = 0,
            retryableIntentId = null,
            message = message,
        )
    }

    private fun finishIdleLocked(currentJob: Job) {
        if (worker === currentJob) worker = null
        active = null
        session = null
        sessionAborted = false
        publishLocked(
            phase = if (unknownRetry != null) HardwareVolumeCommandPhase.UNKNOWN else HardwareVolumeCommandPhase.IDLE,
            activeIntentId = null,
            activeTargetPercent = null,
            queuedInputCount = queue.size,
            retryableIntentId = unknownRetry?.intent?.id,
            message = null,
        )
    }

    private fun abortQueuedInputsLocked(message: String) {
        queue.clear()
        sessionAborted = true
        publishLocked(
            phase = when {
                unknownRetry != null -> HardwareVolumeCommandPhase.UNKNOWN
                active != null -> HardwareVolumeCommandPhase.IN_FLIGHT
                else -> HardwareVolumeCommandPhase.QUEUED
            },
            activeIntentId = active?.intent?.id,
            activeTargetPercent = active?.targetPercent,
            queuedInputCount = 0,
            retryableIntentId = unknownRetry?.intent?.id,
            message = message,
        )
    }

    private fun publishLocked(
        phase: HardwareVolumeCommandPhase,
        activeIntentId: String? = active?.intent?.id,
        activeTargetPercent: Int? = active?.targetPercent,
        queuedInputCount: Int = queue.size,
        retryableIntentId: String? = unknownRetry?.intent?.id,
        message: String? = _state.value.message,
    ) {
        _state.value = HardwareVolumeCommandState(
            phase = phase,
            activeIntentId = activeIntentId,
            activeTargetPercent = activeTargetPercent,
            queuedInputCount = queuedInputCount,
            retryableIntentId = retryableIntentId,
            message = message,
            previewVolumePercent = projectedVolumePreviewLocked(),
        )
    }

    /** Compute the display-only target without duplicating a value already confirmed by readback. */
    private fun projectedVolumePreviewLocked(): Int? {
        val currentState = stateProvider()
        val capability = HardwareVolumeCapabilityPolicy.evaluate(currentState)
        var projected = capability.controllableVolumePercent ?: return null
        val work = active
        if (work != null) {
            val snapshot = currentState.snapshot
            val alreadyConfirmed = snapshot != null &&
                snapshot.epoch == work.session.epoch &&
                snapshot.sequence > work.baseSequence &&
                projected == work.targetPercent
            if (!alreadyConfirmed) projected = work.targetPercent
        }
        queue.forEach { input -> projected = targetFor(input, projected) }
        return projected.coerceIn(0, 100)
    }

    private fun targetFor(input: PendingInput, current: Int): Int = when (input) {
        is PendingInput.Absolute -> input.target
        is PendingInput.KeyDelta -> (current.toLong() + input.direction.toLong() * input.steps.toLong())
            .coerceIn(0L, 100L).toInt()
    }

    private fun sessionFor(state: SnapshotStoreState, origin: ServerOrigin): HardwareVolumeSession? {
        val snapshot = state.snapshot ?: return null
        if (!HardwareVolumeCapabilityPolicy.evaluate(state).canWrite) return null
        return HardwareVolumeSession(origin, snapshot.epoch, state.connectionGeneration)
    }

    private fun sessionMatches(expected: HardwareVolumeSession, state: SnapshotStoreState, currentOrigin: ServerOrigin): Boolean {
        val snapshot = state.snapshot ?: return false
        return currentOrigin == expected.origin &&
            snapshot.epoch == expected.epoch &&
            state.connectionGeneration == expected.connectionGeneration &&
            state.snapshotConnectionGeneration == expected.connectionGeneration &&
            state.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            state.freshness == SnapshotFreshness.FRESH &&
            HardwareVolumeCapabilityPolicy.evaluate(state).canWrite
    }

    private sealed interface PendingInput {
        data class Absolute(val target: Int) : PendingInput
        data class KeyDelta(val direction: Int, val steps: Int) : PendingInput
    }

    private data class HardwareVolumeSession(
        val origin: ServerOrigin,
        val epoch: String,
        val connectionGeneration: Long,
    )

    private data class ActiveCommand(
        val intent: MutationIntent,
        val targetPercent: Int,
        val session: HardwareVolumeSession,
        val baseSequence: Long,
        val isExplicitRetry: Boolean = false,
    )

    private data class UnknownRetry(
        val intent: MutationIntent,
        val targetPercent: Int,
        val origin: ServerOrigin,
    )

    companion object {
        const val HARDWARE_VOLUME_PATH = "/api/system/hardware-volume"
        private val TERMINAL_MUTATION_STATUSES = setOf(MutationStatus.SUCCESS, MutationStatus.FAILED, MutationStatus.UNKNOWN)
    }
}
