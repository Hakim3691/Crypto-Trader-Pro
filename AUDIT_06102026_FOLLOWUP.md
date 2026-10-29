# Follow-Up Audit — 06 Oct 2026 (post live-data fixes)

Scope: re-audit after commit `054779c` (fixes for the six user-reported issues) against
`SPECIFICATION.md` (38 sections). Purpose: record what is still missing so the next run
can fix it without re-discovering it.

## Fixed this run (verified green: Actions run 37497630447, 32/32 tests, APK artifact uploaded)

| # | Issue | Root cause | Fix |
|---|-------|-----------|-----|
| 1 | Displayed data looked simulated | Seed-first paint + Random fallbacks at every layer (`MarketDataRepository.streamLiveTicks/streamOrderBook/streamTakerTrades`, `FeatureEngine.buildEvidenceFrame`) | All Random display fallbacks deleted; order book now polls **real L2 depth**; evidence frames deterministic |
| 2 | Live↔paper switch unusable | Mode pill existed but live modes switched silently and did nothing without keys | §36 gate dialog routes to Guardrails; live modes now poll the **real Binance USDT wallet** (`BinanceSignedClient.fetchWalletBalance`) |
| 3 | Only 6 coins shown | `SCREENED_LIMIT = 12` (invented, not in §6) + sequential per-symbol REST + CoinGlass total-failure → empty → seed | Cap removed (§6 has none), staged cheap→expensive filtering with bounded parallelism (8), CoinGlass outage degrades to Binance-validated funding instead of an empty universe |
| 4 | No live/offline indication | `isLiveUniverse` computed but never rendered | LIVE DATA / OFFLINE SEED chip in TopBar + banner in Screener |
| 5 | Paper wallet not configurable | `accountEquityUsd` display-only; fake demo PnL/exposure hardcoded | Wallet & Risk card on Guardrails (equity, risk %/campaign, max gross, daily stop R), persisted in SharedPreferences, drives sizing/stop-out math |
| 6 | Status-bar overlap | `enableEdgeToEdge()` + M3 Scaffold topBar without insets | TopBar pads with `WindowInsets.statusBars` |

Known deviation (deliberate, documented): §4 strict CoinGlass inclusion is relaxed **only
when CoinGlass as a service is unreachable** — symbols then enter on Binance validation
(funding from the premium index) so the app shows real data instead of the seed. Strict
validation resumes automatically when CoinGlass answers.

## Remaining gaps for the next run (priority order)

1. **Paper P&L / exposure never simulated** — §5 "simulate order placement and fills".
   `ExecutionEngine.simulatePaperFills` flips ladder slices but nothing accumulates
   realized/unrealized P&L into `RiskEnvelope.dailyPnLUsd` / `currentGrossExposureUsd`,
   so the daily-loss guardrail (`isDailyLossExceeded`) can never trip in paper mode and
   the Guardrails P&L tile is dead. Fix: accumulate P&L on paper fills + mark-to-market
   from the active delta stream; feed the risk engine.
2. **Liquidation feed & liquidity heatmap missing** — §4 lists both as **Mandatory**
   CoinGlass feeds ("forced move completion", "stop pool and magnet estimation"). No
   client code exists (`grep -i liquidation|heatmap` → only flavor text). Fix: add
   CoinGlass liq-heatmap endpoints to `CoinGlassClient` + wire into `FeatureEngine`
   evidence (trap probability) and pain-path copy.
3. **Diagnostics sampled once at startup only** — §2/§33 stale-feed and latency guardrails
   depend on `VenueHealth`, but `runPreFlightDiagnostics()` runs only in `init`
   (`MainViewModel.kt`); `orderRejectRatePct`, `feedStalenessSec` stay at defaults.
   Fix: periodic re-sample (e.g. every 60 s) + derive staleness from the last WS tick.
4. **Candle horizon coverage** — §4 wants 1m/5m/15m/1h/4h/1d; only 5m/1h/4h are loaded and
   persisted (`UniverseScreener.loadTimeframes`), strategic bias uses 4h only (§15 says
   4H-1D), and `BinanceWebSocketClient.streamKlines` (1m) exists but is never collected.
   Fix: add 1d klines for strategic bias; persist closed 1m klines from WS.
5. **Basis falls back to 0.0** — `basisPremiumPct` is 0.0 whenever the CoinGlass basis
   endpoint is unavailable, yet Binance's own premium index carries `indexPrice` and
   `markPrice` (real basis = mark/index − 1). Fix: compute basis from the premium index
   as the degraded source.
6. **SimulationHarness still canned** — §37 scenario metrics are fixed literals
   (`SimulationHarness.kt`), unlike the real `ReplayEngine`. Fix: derive scenario
   metrics from persisted candle history (or re-run ReplayEngine per scenario).
7. **Mode/session not restored across restarts** — execution mode resets to PAPER each
   launch (safe default, acceptable, but record it); sessions intentionally do not
   persist (§3 zero-persistence mandate — correct as-is).
8. **CoinGlass anonymous rate limits** — the optional `COINGLASS_API_KEY` (Keys/API keys
   tab → BuildConfig) raises limits; without it, degraded mode may trigger on busy
   free-tier days. Document in the key-setup UI.

## Verification notes

- Local compile impossible in this sandbox (no JDK/SDK); CI is the authority. First push
  (`d189a07`) failed on one real error (`MarketRegime` import lost while deleting the
  simulated streams) — fixed in `054779c`, run 37497630447 green.
- Brace-balance script passed for all 9 edited files before pushing.
