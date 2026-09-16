package dev.scenenote.core.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import dev.scenenote.core.egress.Destination
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.db.SceneNoteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

interface DbDriverFactory { fun create(): SqlDriver }

fun createDatabase(factory: DbDriverFactory): SceneNoteDb = SceneNoteDb(factory.create())

/** 去向账本的 SQLDelight 实现。 */
class SqlLedger(private val db: SceneNoteDb) : Ledger {
    override suspend fun record(entry: LedgerEntry): Unit = withContext(Dispatchers.Default) {
        db.egressQueries.insertEntry(
            ts = entry.ts, provider_id = entry.providerId, host = entry.host, kind = entry.kind, transport = entry.transport,
            bytes = entry.bytes, segment_id = entry.segmentId, est_cost = entry.estCost, destination_class = entry.destinationClass,
        )
        Unit
    }

    override fun since(ts: Long): Flow<List<LedgerEntry>> =
        db.egressQueries.selectSince(ts).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { LedgerEntry(it.ts, it.provider_id, it.host, it.kind, it.transport, it.bytes, it.segment_id, it.est_cost, it.destination_class) }
        }

    override suspend fun monthlyEstCost(): Double = withContext(Dispatchers.Default) {
        db.egressQueries.sumCostSince(monthStart()).executeAsOne()
    }

    override suspend fun bytesSince(ts: Long, destination: Destination): Long = withContext(Dispatchers.Default) {
        db.egressQueries.sumBytesSince(ts, destination.id).executeAsOne()
    }

    private fun monthStart(): Long = Clock.System.now().toEpochMilliseconds() - 30L * 24 * 3600 * 1000  // 滚动 30 天；自然月在 I7 与账本 UI 一起做
}
