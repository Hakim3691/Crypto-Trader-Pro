{
  "meta": {
    "repo": "ProactiveMS",
    "project_type": "Android / Kotlin",
    "source_of_truth": [
      "SPECIFICATION.md",
      "COMPLIANCE_REVIEW_06102026.md",
      "AUDIT_06102026_FOLLOWUP.md",
      "metadata.json"
    ],
    "audit_date": "2026-10-07",
    "note": "This JSON is the implementation-audit plan requested for all 7 areas. Findings are consolidated from repo artifacts and prior compliance review; no destructive git operations were performed for this audit."
  },
  "audit_areas": [
    {
      "area": "1. Specification and metadata alignment",
      "files": [
        "SPECIFICATION.md",
        "metadata.json",
        "app/build.gradle.kts"
      ],
      "classes_functions": [
        "metadata.json majorCapabilities declaration",
        "app/build.gradle.kts secrets / BuildConfig wiring"
      ],
      "dependencies": [
        "metadata.json declares MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API",
        "app/build.gradle.kts declares firebase-ai and other Firebase dependencies"
      ],
      "current_behavior": "metadata.json claims a server-side Gemini capability, but the repo is specified as a zero-cloud, fully offline-native Android engine and contains no Gemini/Firebase AI integration wired to trading behavior.",
      "required_behavior": "metadata.json capabilities must match the implemented architecture in SPECIFICATION.md. For a zero-cloud mobile engine, the capability claim should not assert server-side AI that does not exist.",
      "tests_required": [
        "Alignment check between metadata.json majorCapabilities and actual implemented capabilities",
        "Regression guard that metadata edits do not drift from real code"
      ],
      "risks_regressions": [
        "Incorrect capability metadata can mislead reviewers, tooling, or store packaging about server-side dependencies",
        "Capability claims should never be treated as verification that a feature works"
      ]
    },
    {
      "area": "2. Data sourcing and universe screening",
      "files": [
        "app/build.gradle.kts",
        "gradle/libs.versions.toml"
      ],
      "classes_functions": [
        "Already-declared Retrofit / OkHttp / logging-interceptor dependencies",
        "Existing Binance and CoinGlass client scaffolding implied by build config and prior review"
      ],
      "dependencies": [
        "Retrofit",
        "OkHttp",
        "Moshi converter",
        "OkHttp logging interceptor"
      ],
      "current_behavior": "According to the compliance review and follow-up audit, production networking for mandatory feeds was implemented incrementally, but several mandatory data sources and validation rules are still incomplete or fall back to degraded values: liquidation feed and liquidity heatmap endpoints are missing, basis can fall back to 0.0 when CoinGlass is unavailable, diagnostics are sampled once at startup, candle horizon coverage is partial, SimulationHarness still returns canned metrics, and the UI mode/session is reset each launch.",
      "required_behavior": "Per SPECIFICATION.md §4–§6, the engine must use Binance and CoinGlass feeds with UTC normalization and canonical asset mapping, strict cross-platform validation for universe inclusion, staleness-aware polling and WebSocket deltas, a ranked directional screener output, and a real replay/SimulationHarness backed by persisted history rather than literals.",
      "tests_required": [
        "Feed presence/absence fallbacks for funding, OI, basis, liquidation, and heatmap",
        "UTC normalization and canonical symbol mapping",
        "Cross-platform inclusion logic and its degradation behavior",
        "Staleness refresh for heavy HTF data and dormant polling intervals",
        "WebSocket delta lifecycle for active symbols",
        "Screener ranked output shape and Pain Score components",
        "SimulationHarness metric derivation from persisted history",
        "Basis degradation path that uses Binance premium index when CoinGlass is unavailable",
        "Periodic diagnostics re-sampling and feed staleness derivation"
      ],
      "risks_regressions": [
        "Adding feeds without preserving graceful degradation can break the universe in outage conditions",
        "Inconsistent fallback math for basis or funding can silently change regime and invalidation decisions",
        "Candle horizon gaps can distort strategic bias and HTF structure",
        "Canned simulation metrics can hide whether replay is actually correct"
      ]
    },
    {
      "area": "3. Market analysis and modeling",
      "files": [
        "SPECIFICATION.md"
      ],
      "classes_functions": [
        "MarketProfile, LevelMap, CandidatePath, EvidenceFrame models",
        "FeatureEngine concept from SPECIFICATION.md §7–§11",
        "StructureEngine zone detection concept",
        "Regime classification and multi-horizon bias logic concept",
        "CoinCalibration concept"
      ],
      "dependencies": [
        "Binance candles, order book, and trades",
        "CoinGlass funding, OI, basis, liquidations, heatmap",
        "Computed ATR, imbalance, taker pressure, OI deltas, regime and damage scores"
      ],
      "current_behavior": "The spec defines the analytics stack in detail, but the prior compliance review found the implementation’s analytics were partially realized and partially synthetic: zones were synthesized from ATR offsets instead of swing detection/clustering, regime and tactical/strategic bias were static or incomplete, CoinCalibration was dead code, and no computed multi-horizon bias table or scoring weights were wired end-to-end.",
      "required_behavior": "Per SPECIFICATION.md §7–§11 and §15, the system must compute levels from real price action with freshness decay, derive regime and separate tactical/strategic bias scores from real data, support the score-band operational table, and produce CoinCalibration from history so per-asset asymmetry is justified rather than assumed.",
      "tests_required": [
        "Level detection produces HTF support/resistance, LTF pivot/reclaim/breakdown zones, confidence, and freshness",
        "Zone freshness decay behavior over time",
        "Regime classification rules across bullish / bearish / transition / balance",
        "Tactical bias and strategic bias separation and split-bias handling",
        "Bias score band mapping to operational effects",
        "Pain score formula usage and weighting",
        "CoinCalibration derivation and usage",
        "Determinism of analytics given fixed inputs"
      ],
      "risks_regressions": [
        "Heterogeneous analytics maturity means a partial model can still be presented as if complete",
        "Biases computed from incomplete inputs can swing unfairly and change campaign selection",
        "Hardcoded or synthesized zones can hide the difference between a spec-compliant structure engine and a shortcut"
      ]
    },
    {
      "area": "4. Campaign library and asymmetric staging",
      "files": [
        "SPECIFICATION.md"
      ],
      "classes_functions": [
        "CampaignFamily enum",
        "CampaignEngine.determineBoardRoles and calculateSizeBudget concept",
        "CampaignState state machine concept",
        "buildEntryLadder and buildStopLadder concepts",
        "Campaign model with mirrorCampaignId, cooldownUntilTs, priority_score, size_multiplier"
      ],
      "dependencies": [
        "Regime and bias outputs from the analytics layer",
        "Level map and path ranking",
        "Risk layer for exposure and conflict checks",
        "Execution layer for staging and fill reconciliation"
      ],
      "current_behavior": "The campaign taxonomy, board-role assignment, size budgets, and state machine concepts are clearly modeled in the codebase according to the compliance review, but staging was previously unconditional, ExecutionMode behaved identically across modes, mirror campaign activation and time invalidation were not fully wired, and several proactive-monitoring behaviors were only partially present or dead code.",
      "required_behavior": "Per SPECIFICATION.md §12–§18 and §22, the engine must evaluate all six mirrored campaign families, assign primary/secondary/defensive/disabled roles by regime and bias, construct zone-based entry ladders with side-specific aggressiveness, attach invalidation and TTL rules before staging, use ExecutionMode to gate behavior, and support mirror activation, time invalidation, board promotion/degradation/rearm, and explicit conflict resolution so opposite-side or correlated exposure is controlled.",
      "tests_required": [
        "All six campaign families are representable and mirrorable",
        "Regime-based board-role selection and size budgets",
        "Split-bias boards receive different size/target/cancel treatment",
        "Entry ladder slicing, spacing, and order-type selection",
        "Pre-staging preconditions: regime, bias, invalidation rules, TTL, capital routing",
        "ExecutionMode gating: shadow / paper / capped / scaled",
        "Mirror campaign activation on invalidation",
        "Time invalidation and rearm conditions",
        "Board promotion and degradation logic",
        "Conflict resolution for opposing or correlated campaigns",
        "Deterministic campaign planning from fixed inputs"
      ],
      "risks_regressions": [
        "Stale board-role logic can keep the wrong campaign family armed after regime transitions",
        "Missing mirror wiring can prevent the expected mirrored response on invalidation",
        "Weak conflict resolution can allow full-size opposing or correlated exposure to accumulate",
        "Mode gating that is cosmetic rather than functional undermines the safe rollout model"
      ]
    },
    {
      "area": "5. Execution, sizing, and conflict resolution",
      "files": [
        "SPECIFICATION.md"
      ],
      "classes_functions": [
        "RiskEngine.validateCampaignStaging concept",
        "RiskEngine.checkManualOverrideGuardrails concept",
        "ManualOverrideDialog and MainViewModel.applyManualOverride concept",
        "ExecutionEngine staging / paper fill / reconciliation concepts",
        "RiskEnvelope models and daily-loss / gross-exposure concepts"
      ],
      "dependencies": [
        "Session-scoped API keys and Binance signed endpoints for real execution",
        "RiskEnvelope configuration and wallet/risk settings",
        "Order placement, amendment, cancel, and fill reconciliation",
        "Paper fill accumulation and mark-to-market"
      ],
      "current_behavior": "The compliance review and follow-up audit indicate that execution was the largest behavioral gap: real order placement/amendment/cancellation/fill reconciliation were not implemented in the earlier baseline, ExecutionMode was largely cosmetic, paper fills did not accumulate realized/unrealized PnL or exposure, and the risk validator that should gate staging was not wired into the flow at that time.",
      "required_behavior": "Per SPECIFICATION.md §18–§24 and §29, staging must pass account- and symbol-level risk checks before orders go out, ExecutionMode must meaningfully change behavior, paper mode must simulate fills and accumulate PnL/exposure so guardrails can trip, manual overrides must be allowed but must yield persistent contrasting warnings, and capital routing must reserve risk by board role and enforce net exposure limits.",
      "tests_required": [
        "validateCampaignStaging is invoked before staging and blocks when guardrails trip",
        "Kill switch, daily loss stop, gross-exposure cap, and symbol conflict checks",
        "Paper fill accumulation into daily PnL and current gross exposure",
        "Mark-to-market from the active delta stream",
        "ExecutionMode behavioral differences across shadow / paper / capped / scaled",
        "Manual override warnings that contrast engine plan vs override",
        "Capital routing by board role and worst-case net stop-out visibility",
        "Deterministic sizing outputs given fixed envelope and campaign inputs"
      ],
      "risks_regressions": [
        "If staging is not gated by risk, the app can present the illusion of safety while ignoring guardrails",
        "Paper mode without PnL/exposure simulation cannot validate the daily-loss or sizing behavior it is meant to test",
        "Manual override warnings that are not persistent or contrasting fail the spec’s operator-awareness requirement",
        "Live execution without reconciliation can silently drift from the intended campaign state"
      ]
    },
    {
      "area": "6. Monitoring, invalidation, and cancellation",
      "files": [
        "SPECIFICATION.md"
      ],
      "classes_functions": [
        "InvalidationEngine.evaluateCampaign concept",
        "EvidenceFrame and invalidation score concept",
        "Board survival / degradation / promotion / rearm checks concept",
        "Side-agnostic invalidation families: acceptance, rejection, trap, flow, time, conflict",
        "Monitoring loop cadence and live outputs concept"
      ],
      "dependencies": [
        "Real-time order book, trades, OI, funding, basis, liquidation, heatmap",
        "Live order and fill status from the execution layer",
        "Regime, level freshness, and path efficiency signals",
        "Venue health and spread/latency guardrails"
      ],
      "current_behavior": "The invalidation engine and soft/hard thresholds existed and were highlighted as a strong match to spec, but the prior review found that monitoring was a single tick loop without full cadence, fill status, or event-driven semantics; time invalidation and mirror activation were not fully wired; board survival/degradation/rearm were incomplete; and several operational guardrails were not implemented.",
      "required_behavior": "Per SPECIFICATION.md §25–§29 and §33, monitoring must run at the specified cadences with fill status and venue health, invalidation must be explicit and weighted across the six side-agnostic families, soft and hard thresholds must trigger cancel/reduce/flatten/cooldown actions, proactive board checks must cover survival, degradation, promotion, cancellation, and rearm, and operational guardrails must translate feed, reject-rate, spread, latency, daily-loss, and position-mismatch conditions into automatic actions.",
      "tests_required": [
        "Invalidation score computation is deterministic and weighted by family",
        "Soft and hard threshold actions produce the expected cancel/reduce/flatten/cooldown outcomes",
        "Time invalidation and conflict invalidation are evaluated",
        "Mirror campaign activation can be triggered from invalidation",
        "Board survival, degradation, promotion, cancellation, and rearm logic",
        "Monitoring outputs include dominant campaign, active orders, invalidation contributors, risk used vs allowed, and venue health",
        "Operational guardrail automatic actions for stale feed, reject threshold, spread, latency, daily loss, and position mismatch",
        "Graceful degradation when crowding/funding inputs are stale or missing"
      ],
      "risks_regressions": [
        "Invalidation that is not wired into the live loop can exist in code but never fire when it should",
        "Missing time or conflict invalidation leaves two of the six invalidation families underused",
        "Non-event-driven monitoring can miss the fill status and candle-close cadence the spec requires",
        "Guardrails that are defined but not invoked can create silent reliance on manual operator intervention"
      ]
    },
    {
      "area": "7. Mobile architecture, security, and operations",
      "files": [
        "SPECIFICATION.md",
        "app/build.gradle.kts"
      ],
      "classes_functions": [
        "SessionSecurityManager concept",
        "OrphanedOrderWarningDialog and MainViewModel.acknowledgeOrphanedOrderWarning concept",
        "Session-scoped API key handling and wipe concept",
        "Pre-flight diagnostics concept",
        "Lifecycle / OS hook concept for ON_PAUSE / ON_STOP"
      ],
      "dependencies": [
        "Android lifecycle observability for app foreground/background transitions",
        "Hardware diagnostics APIs for power save, battery/thermal, and memory",
        "Android Keystore / TEE concepts for any at-rest need while keeping session keys RAM-only",
        "SharedPreferences or similar for non-secret operational settings such as paper wallet/risk config"
      ],
      "current_behavior": "The compliance review found the session-security and orphaned-order concepts existed but the OS-level intercept and hardware diagnostics were effectively manual or hardcoded, with no real lifecycle hook that automatically warns and wipes on suspension, and no real periodic diagnostics feeding the guardrails. The follow-up audit also noted that execution mode/session defaults reset on restart and only non-secret operational settings were persisted for paper wallet/risk config.",
      "required_behavior": "Per SPECIFICATION.md §2, §3, and Appendix B, the app must run pre-flight diagnostics before unlocking staging, must automatically intercept suspension/minimization when resting orders exist with the exact spec warning and wipe keys on acknowledgment, must keep API keys RAM-only for the session, must re-sample diagnostics periodically for guardrails, and must keep non-secret operational state such as paper wallet/risk config persistent while never persisting secrets.",
      "tests_required": [
        "Pre-flight diagnostics gate the Stage Campaign capability appropriately",
        "Orphaned-order dialog fires automatically on the correct lifecycle transitions when resting orders exist",
        "API key wipe behavior after acknowledgment",
        "No persistence path for API keys",
        "Periodic diagnostics update venue health inputs",
        "Non-secret operational settings persist across restarts while secrets do not",
        "Guardrail warnings reflect current diagnostics and venue health"
      ],
      "risks_regressions": [
        "A manual-only intercept fails the core safety story of §3 because background suspension can still silently kill the monitoring loop",
        "Hardcoded diagnostics can hide the real conditions under which staging is allowed",
        "Persisting anything that should remain RAM-only contradicts the zero-persistence mandate",
        "Not persisting non-secret operational settings can make paper/risk configuration frustrating and error-prone"
      ]
    }
  ],
  "independent_repair_goals": [
    {
      "goal": "G0: Metadata and capability-alignment cleanup",
      "why_independent": "This is a metadata/claim alignment goal that does not depend on engine internals.",
      "audit_areas": [
        "1. Specification and metadata alignment"
      ],
      "primary_files": [
        "metadata.json",
        "app/build.gradle.kts"
      ],
      "exit_criteria": [
        "metadata.json capabilities match the real implemented architecture",
        "No claim exists for a server-side capability that is not actually implemented"
      ]
    },
    {
      "goal": "G1: Real-deterministic data and universe layer",
      "why_independent": "This goal is about inputs: feeds, validation, normalization, staleness, and the screener output. It is upstream of analytics and execution.",
      "audit_areas": [
        "2. Data sourcing and universe screening",
        "3. Market analysis and modeling"
      ],
      "primary_files": [
        "app/build.gradle.kts",
        "gradle/libs.versions.toml"
      ],
      "exit_criteria": [
        "Mandatory Binance and CoinGlass feeds are wired with UTC normalization and canonical asset mapping",
        "Universe inclusion and cross-platform validation behave correctly and degrade gracefully",
        "Staleness, polling, and WebSocket delta lifecycle are implemented",
        "Screener produces the ranked directional output from spec",
        "Analytics consume real data and produce levels, regime, and bias instead of synthesis or static fields"
      ]
    },
    {
      "goal": "G2: Execution, risk gating, and paper realism",
      "why_independent": "This goal is about turning staged campaigns into real or realistic behavior and enforcing risk before orders leave the device.",
      "audit_areas": [
        "4. Campaign library and asymmetric staging",
        "5. Execution, sizing, and conflict resolution"
      ],
      "primary_files": [
        "app/build.gradle.kts"
      ],
      "exit_criteria": [
        "Risk validator is called before staging",
        "ExecutionMode changes real behavior for shadow / paper / capped / scaled",
        "Paper fills accumulate PnL and exposure so guardrails can trip",
        "Manual overrides are allowed but persistently warned",
        "Conflict and net-exposure rules constrain live boards"
      ]
    },
    {
      "goal": "G3: Monitoring, invalidation, and operational guardrails",
      "why_independent": "This goal closes the live-loop and safety behavior around invalidation, board lifecycle, and guardrails.",
      "audit_areas": [
        "6. Monitoring, invalidation, and cancellation",
        "7. Mobile architecture, security, and operations"
      ],
      "primary_files": [
        "SPECIFICATION.md"
      ],
      "exit_criteria": [
        "Monitoring runs at the cadences and outputs required by spec",
        "All relevant invalidation families are evaluated, including time and conflict where applicable",
        "Board survival/degradation/promotion/cancellation/rearm are implemented",
        "Operational guardrails are wired and trigger automatic actions",
        "Lifecycle intercept and periodic diagnostics are real, and session keys remain RAM-only"
      ]
    },
    {
      "goal": "G4: Validation harness and metric integrity",
      "why_independent": "This goal is about proving behavior through replay and backtesting rather than canned outputs.",
      "audit_areas": [
        "2. Data sourcing and universe screening",
        "4. Campaign library and asymmetric staging",
        "6. Monitoring, invalidation, and cancellation"
      ],
      "primary_files": [
        "SPECIFICATION.md"
      ],
      "exit_criteria": [
        "SimulationHarness and replay produce metrics from history or replay execution",
        "Missing replay behaviors and scenario tests are added",
        "Graceful-degradation and stale-input behaviors are testable"
      ]
    }
  ],
  "audit_result_notes": {
    "size_budget_finding": {
      "area_applied": "Risk-envelope and sizing behavior interact with execution, paper simulation, and mobile operational constraints; the size-budget observation is therefore folded into audit areas 5 and 7 rather than treated as a standalone verification success.",
      "status": "audit_result_not_verified_success",
      "reason": "The previously stated $600 size budget is carried here as a finding to be reconciled against the current APK/build configuration and the mobile risk/modeling implementation, not as a confirmed pass.",
      "follow_up_required": [
        "Confirm what $600 refers to in this repo’s build/output context",
        "Reconcile any size constraint with the current dependencies, metadata, and execution/operational behavior",
        "Record the reconciled result as an audit outcome, not an automatic success"
      ]
    }
  }
}
