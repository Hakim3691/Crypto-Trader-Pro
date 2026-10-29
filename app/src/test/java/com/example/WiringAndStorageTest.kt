package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.CandleDao
import com.example.data.local.CandleEntity
import com.example.data.model.ExecutionMode
import com.example.engine.UniverseScreener
import com.example.ui.ExecutionModeStore
import com.example.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature: Spec §36 execution-mode restoration on the real ViewModel surface.
 *
 * Scenario: the operator changes mode, the process dies, and the app relaunches. The
 * audited defect was that `MainViewModel` reset to PAPER every launch. Store-level
 * round-trips are not enough here — this drives the actual ViewModel so removing either
 * the restore read in `init` or the save call in `setExecutionMode` breaks the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionModeRestoreTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        modePrefs().edit().clear().commit()
    }

    private fun modePrefs() = app.getSharedPreferences(
        ExecutionModeStore.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Test
    fun `should restore the selected mode into a restarted view model`() {
        // given a view model that starts in the safe paper default
        val first = MainViewModel(app)
        assertEquals(ExecutionMode.PAPER, first.uiState.value.executionMode)

        // when the operator selects shadow mode
        first.setExecutionMode(ExecutionMode.SHADOW)

        // then the state changes immediately and the choice is written to storage
        assertEquals(ExecutionMode.SHADOW, first.uiState.value.executionMode)
        assertEquals(
            ExecutionMode.SHADOW.name,
            modePrefs().getString(ExecutionModeStore.KEY_EXECUTION_MODE, null)
        )

        // and a fresh view model (process restart) restores shadow instead of paper
        val restarted = MainViewModel(app)
        assertEquals(ExecutionMode.SHADOW, restarted.uiState.value.executionMode)
    }

    @Test
    fun `should not resurrect a live mode without an unlocked session`() {
        // given a live mode persisted by a previous process
        ExecutionModeStore.save(modePrefs(), ExecutionMode.SCALED_LIVE)

        // when a fresh view model starts with no unlocked session
        val restarted = MainViewModel(app)

        // then it downgrades to paper rather than re-arming live routing
        assertEquals(ExecutionMode.PAPER, restarted.uiState.value.executionMode)
    }
}

/**
 * Feature: Spec §4 candle horizons through the real persistence path.
 *
 * Scenario: `loadTimeframes` must be able to serve 1m/15m/1d, not just 5m/1h/4h. These
 * scenarios use the production DAO and Room schema (in-memory) with freshly-fetched
 * candles, so the Room-first cache answers without touching the network. If the schema,
 * DAO query or interval mapping ever drops a horizon, the stored rows stop coming back.
 *
 * The live HTTP transport behind a cache miss is NOT exercised here (no network in a
 * unit test) and stays labelled unverified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandleHorizonStorageTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CandleDao

    private val closeFor = mapOf(
        "1m" to 1.0,
        "5m" to 5.0,
        "15m" to 15.0,
        "1h" to 60.0,
        "4h" to 240.0,
        "1d" to 1440.0
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.candleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seeds one fresh candle per required horizon, as a successful sync would. */
    private fun seedFreshCandles() {
        val now = System.currentTimeMillis()
        val rows = UniverseScreener.REQUIRED_TIMEFRAMES.map { interval ->
            val close = closeFor.getValue(interval)
            CandleEntity(
                id = "SOLUSDT|$interval|0",
                symbol = "SOLUSDT",
                interval = interval,
                openTimeUtcMs = 0L,
                closeTimeUtcMs = 1L,
                open = close,
                high = close,
                low = close,
                close = close,
                volume = 1.0,
                quoteVolume = 1.0,
                fetchedAtMs = now
            )
        }
        runBlocking { dao.insertCandles(rows) }
    }

    @Test
    fun `should round-trip every §4 horizon through the candle dao`() {
        // given freshly synced candles for all six horizons
        seedFreshCandles()

        // when each horizon is read back through the production DAO
        // then every one is stored and addressable — none is silently dropped
        for (interval in UniverseScreener.REQUIRED_TIMEFRAMES) {
            val rows = runBlocking { dao.getCandles("SOLUSDT", interval) }
            assertEquals("no rows stored for $interval", 1, rows.size)
            assertEquals(closeFor.getValue(interval), rows.single().close, 1e-9)
            assertEquals(interval, rows.single().interval)
            assertEquals(
                "fetch timestamp missing for $interval",
                true,
                runBlocking { dao.getLatestFetchedAtMs("SOLUSDT", interval) } != null
            )
        }
    }

    @Test
    fun `should serve every §4 horizon from the fresh candle cache`() {
        // given a fresh cache populated by the sync path
        seedFreshCandles()

        // when the production loader assembles its timeframe set
        val frames = runBlocking { UniverseScreener(dao).loadTimeframes("SOLUSDT") }

        // then each horizon is served from the store with its own series
        assertEquals(1.0, frames.candles1m.single().close, 1e-9)
        assertEquals(5.0, frames.candles5m.single().close, 1e-9)
        assertEquals(15.0, frames.candles15m.single().close, 1e-9)
        assertEquals(60.0, frames.candles1h.single().close, 1e-9)
        assertEquals(240.0, frames.candles4h.single().close, 1e-9)
        assertEquals(1440.0, frames.candles1d.single().close, 1e-9)
    }
}
