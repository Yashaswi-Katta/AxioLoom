package com.offline.inkmath.domain.dag

import com.offline.inkmath.domain.cas.OfflineCasEngine
import com.offline.inkmath.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue
import java.util.UUID

data class ReactiveBlockState(
    val id: UUID,
    val intent: MathIntent,
    val status: BlockStatus = BlockStatus.Dirty,
    val result: CasResult? = null,
    val transform: SymbolicTransform = SymbolicTransform.AUTO
)

data class ReactiveGraphSnapshot(
    val generation: Long = 0,
    val mode: CasMode = CasMode.SYMBOLIC,
    val blocks: Map<UUID, ReactiveBlockState> = emptyMap(),
    val symbolOwners: Map<SymbolKey, List<UUID>> = emptyMap(),
    /** For each block, the unique definition blocks whose symbols it references. */
    val dependencies: Map<UUID, Set<UUID>> = emptyMap(),
    val cyclicBlocks: Set<UUID> = emptySet()
)

/** Serialized transactional graph updates; HMER is never rerun by this class. */
class ReactiveSymbolGraph(
    private val cas: OfflineCasEngine,
    initialMode: CasMode = CasMode.SYMBOLIC
) {
    private val mutex = Mutex()
    private var intents = linkedMapOf<UUID, MathIntent>()
    private var transforms = linkedMapOf<UUID, SymbolicTransform>()
    private var owners: Map<SymbolKey, List<UUID>> = emptyMap()
    private var dependencies: Map<UUID, Set<UUID>> = emptyMap()
    private var globalConstants: Map<String, MathExpr> = emptyMap()
    private var mode = initialMode
    private val mutableSnapshot = MutableStateFlow(ReactiveGraphSnapshot(mode = initialMode))
    val state: StateFlow<ReactiveGraphSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun upsert(
        blockId: UUID,
        intent: MathIntent,
        transform: SymbolicTransform = transforms[blockId] ?: SymbolicTransform.AUTO
    ): ReactiveGraphSnapshot = mutex.withLock {
        transforms[blockId] = transform
        val oldIntents = intents.toMap()
        val oldOwners = owners
        val oldDependencies = dependencies
        val changedKeys = setOfNotNull(oldIntents[blockId]?.definedSymbol, intent.definedSymbol)
        intents[blockId] = intent
        rebuildGraph()
        val seeds = changedRoots(oldIntents, oldOwners, changedKeys, setOf(blockId))
        recompute(oldIntents, oldDependencies, seeds)
    }

    suspend fun setTransform(blockId: UUID, transform: SymbolicTransform): ReactiveGraphSnapshot = mutex.withLock {
        if (transforms[blockId] == transform) return@withLock mutableSnapshot.value
        transforms[blockId] = transform
        val oldIntents = intents.toMap()
        val oldDependencies = dependencies
        recompute(oldIntents, oldDependencies, setOf(blockId))
    }

    suspend fun upsert(intent: MathIntent): UUID {
        val id = UUID.randomUUID()
        upsert(id, intent)
        return id
    }

    suspend fun remove(blockId: UUID): ReactiveGraphSnapshot = mutex.withLock {
        if (blockId !in intents) return@withLock mutableSnapshot.value
        transforms.remove(blockId)
        val oldIntents = intents.toMap()
        val oldOwners = owners
        val oldDependencies = dependencies
        val changedKeys = setOfNotNull(oldIntents[blockId]?.definedSymbol)
        intents.remove(blockId)
        rebuildGraph()
        val seeds = changedRoots(oldIntents, oldOwners, changedKeys, setOf(blockId))
        recompute(oldIntents, oldDependencies, seeds)
    }

    suspend fun setMode(newMode: CasMode): ReactiveGraphSnapshot = mutex.withLock {
        if (mode == newMode) return@withLock mutableSnapshot.value
        mode = newMode
        val oldIntents = intents.toMap()
        val oldDependencies = dependencies
        rebuildGraph()
        recompute(oldIntents, oldDependencies, intents.keys.toSet())
    }

    suspend fun setGlobalConstants(constants: Map<String, MathExpr>): ReactiveGraphSnapshot = mutex.withLock {
        globalConstants = constants
        val oldIntents = intents.toMap()
        val oldDependencies = dependencies
        recompute(oldIntents, oldDependencies, intents.keys.toSet())
    }

    fun getBindingsForBlock(blockId: UUID): BindingSnapshot {
        return makeBindings(blockId, mutableSnapshot.value.blocks)
    }

    private fun changedRoots(
        oldIntents: Map<UUID, MathIntent>,
        oldOwners: Map<SymbolKey, List<UUID>>,
        changedKeys: Set<SymbolKey>,
        direct: Set<UUID>
    ): Set<UUID> {
        if (changedKeys.isEmpty()) return direct
        val affectedRefs = (oldIntents + intents).filterValues { intent -> intent.referencedSymbols.any(changedKeys::contains) }.keys
        // Owner ids are seeds too, including duplicate owners whose edges are intentionally absent.
        return direct + affectedRefs + changedKeys.flatMap { key -> oldOwners[key].orEmpty() + owners[key].orEmpty() }
    }

    private fun rebuildGraph() {
        owners = intents.entries.mapNotNull { (id, intent) -> intent.definedSymbol?.let { it to id } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.sortedBy(UUID::toString) }
        dependencies = intents.mapValues { (_, intent) ->
            intent.referencedSymbols.flatMap { key ->
                val definitions = owners[key].orEmpty()
                if (definitions.size == 1) definitions else emptyList()
            }.toSet()
        }
    }

    private fun recompute(
        oldIntents: Map<UUID, MathIntent>,
        oldDependencies: Map<UUID, Set<UUID>>,
        seeds: Set<UUID>
    ): ReactiveGraphSnapshot {
        val oldDependents = reverse(oldDependencies)
        val newDependents = reverse(dependencies)
        val affected = downstream(oldDependents, seeds) + downstream(newDependents, seeds)
        val activeIds = affected.intersect(intents.keys)
        val oldState = mutableSnapshot.value
        val blocks = oldState.blocks.filterKeys { it in intents }.toMutableMap()
        intents.forEach { (id, intent) ->
            if (id !in blocks) blocks[id] = ReactiveBlockState(id, intent)
            else if (blocks.getValue(id).intent != intent) blocks[id] = blocks.getValue(id).copy(intent = intent)
        }
        activeIds.forEach { id -> blocks[id] = blocks.getValue(id).copy(status = BlockStatus.Dirty, result = null) }

        val cycleNodes = findCycleMembers(newDependents, intents.keys)
        val directErrors = linkedMapOf<UUID, String>()
        owners.filterValues { it.size > 1 }.forEach { (key, ids) ->
            ids.forEach { directErrors[it] = "Duplicate definition of '${key.displayName}'" }
        }
        intents.forEach { (id, intent) ->
            val ambiguous = intent.referencedSymbols.firstOrNull { owners[it].orEmpty().size > 1 }
            if (ambiguous != null) directErrors[id] = "Ambiguous definition of '${ambiguous.displayName}'"
        }
        cycleNodes.forEach { directErrors[it] = "Circular variable dependency" }
        directErrors.forEach { (id, message) ->
            if (id in activeIds) blocks[id] = blocks.getValue(id).copy(status = BlockStatus.Error(message), result = null)
        }

        val evaluable = intents.keys - directErrors.keys
        val order = topologicalOrder(evaluable, dependencies)
        for (id in order) {
            if (id !in activeIds) continue
            val failedDependency = dependencies[id].orEmpty().firstOrNull { blocks[it]?.status is BlockStatus.Error }
            if (failedDependency != null) {
                blocks[id] = blocks.getValue(id).copy(
                    status = BlockStatus.Error("Dependency ${failedDependency} is invalid"), result = null
                )
                continue
            }
            val bindings = makeBindings(id, blocks)
            try {
                val transform = transforms[id] ?: SymbolicTransform.AUTO
                val result = cas.evaluate(intents.getValue(id), bindings, mode, transform)
                blocks[id] = blocks.getValue(id).copy(status = BlockStatus.Ready, result = result, transform = transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                blocks[id] = blocks.getValue(id).copy(
                    status = BlockStatus.Error(failure.message ?: "Offline evaluation failed"), result = null
                )
            }
        }
        mutableSnapshot.value = ReactiveGraphSnapshot(
            generation = oldState.generation + 1,
            mode = mode,
            blocks = blocks.toMap(),
            symbolOwners = owners,
            dependencies = dependencies,
            cyclicBlocks = cycleNodes
        )
        return mutableSnapshot.value
    }

    private fun makeBindings(forBlock: UUID, blocks: Map<UUID, ReactiveBlockState>): BindingSnapshot {
        val values = linkedMapOf<String, MathExpr>()
        values.putAll(globalConstants)
        val functions = linkedMapOf<String, MathIntent.FunctionDef>()
        owners.forEach { (key, ids) ->
            if (ids.size != 1) return@forEach
            val ownerId = ids.single()
            if (ownerId == forBlock || blocks[ownerId]?.status is BlockStatus.Error) return@forEach
            when (val definition = intents[ownerId]) {
                is MathIntent.Assignment -> values[key.name] = definition.rhs
                is MathIntent.FunctionDef -> functions[key.name] = definition
                else -> Unit
            }
        }
        return BindingSnapshot(values, functions)
    }

    private fun findCycleMembers(outgoing: Map<UUID, Set<UUID>>, ids: Set<UUID>): Set<UUID> {
        val colour = mutableMapOf<UUID, Int>() // 0=unvisited, 1=visiting, 2=visited
        val stack = mutableListOf<UUID>()
        val cycles = linkedSetOf<UUID>()
        fun visit(id: UUID) {
            colour[id] = 1
            stack += id
            outgoing[id].orEmpty().sortedBy(UUID::toString).forEach { next ->
                when (colour[next] ?: 0) {
                    0 -> visit(next)
                    1 -> {
                        val start = stack.indexOf(next)
                        if (start >= 0) cycles += stack.subList(start, stack.size)
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            colour[id] = 2
        }
        ids.sortedBy(UUID::toString).forEach { if ((colour[it] ?: 0) == 0) visit(it) }
        return cycles
    }

    private fun topologicalOrder(ids: Set<UUID>, incoming: Map<UUID, Set<UUID>>): List<UUID> {
        val indegree = ids.associateWith { id -> incoming[id].orEmpty().count { it in ids } }.toMutableMap()
        val ready = PriorityQueue<UUID>(compareBy(UUID::toString))
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<UUID>()
        val outgoing = reverse(incoming)
        while (ready.isNotEmpty()) {
            val id = ready.remove()
            order += id
            outgoing[id].orEmpty().filter { it in ids }.sortedBy(UUID::toString).forEach { dependent ->
                indegree[dependent] = indegree.getValue(dependent) - 1
                if (indegree.getValue(dependent) == 0) ready.add(dependent)
            }
        }
        return order
    }

    private fun reverse(incoming: Map<UUID, Set<UUID>>): Map<UUID, Set<UUID>> {
        val outgoing = linkedMapOf<UUID, MutableSet<UUID>>()
        incoming.forEach { (dependent, prerequisites) ->
            prerequisites.forEach { prerequisite -> outgoing.getOrPut(prerequisite) { linkedSetOf() }.add(dependent) }
        }
        return outgoing.mapValues { it.value.toSet() }
    }

    private fun downstream(outgoing: Map<UUID, Set<UUID>>, roots: Set<UUID>): Set<UUID> {
        val seen = linkedSetOf<UUID>()
        val queue = ArrayDeque<UUID>()
        roots.forEach { if (seen.add(it)) queue.addLast(it) }
        while (queue.isNotEmpty()) {
            outgoing[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue.addLast(it) }
        }
        return seen
    }
}
