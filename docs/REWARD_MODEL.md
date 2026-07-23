# Reward model (rewarded ads → premium time)

## Why this shape

The starting point was a fixed "3 ads = 1 hour, split into three separate
1-hour timers (no-ads / no-watermark / max-quality)" design. Two changes
were made before writing any code:

1. **One accumulating balance, not three timers.** Simpler mental model for
   the user ("you have 42 minutes of premium left"), simpler to implement
   and test, and avoids the weird edge case of the three timers drifting
   out of sync.
2. **More generous default ratio: 1 ad = 1 hour**, not 3 ads = 1 hour. A
   streamer doing a 4-6 hour session would otherwise need to sit through
   12-18 ads to stay unlocked the whole time — likely to just bounce to
   DroidCam instead. The ratio is a config value
   (`RewardConfig.seconds_per_ad`), not a hardcoded constant, specifically so
   it can be tuned from real retention data instead of guessed twice.

## The economy

| | Free tier | Premium (balance > 0) |
|---|---|---|
| Quality | 1080p60 | 4K60 |
| Watermark | small, bottom-right | none |
| Ads | shown periodically | none |
| JPEG encode quality | 80% | 95% |

- Watching one rewarded ad to completion credits `seconds_per_ad` (default
  3600s) to a single balance.
- The balance only drains while a stream is actually active
  (`start_ticking()` / `stop_ticking()`) — leaving the app idle doesn't burn
  premium time.
- The balance is capped (`max_balance_seconds`, default 8h) so a user can't
  binge-watch 30 ads in one sitting and then go fully ad-free/watermark-free
  for a week. This wasn't in the original pitch but is a standard anti-abuse
  guard for rewarded-ad economies; the cap is a config value and can be
  raised, lowered, or removed per product decision.
- All-or-nothing gating: as long as balance > 0, *all three* premium
  properties (quality, watermark, ads) are unlocked together. This is a
  product simplification — splitting them into independently-priced unlocks
  is possible later but adds real UI/economy complexity for a benefit that
  wasn't asked for.

## Where the logic lives

- **Reference implementation + test suite:** [`reward_engine/reward_manager.py`](../reward_engine/reward_manager.py)
  and [`reward_engine/tests/test_reward_manager.py`](../reward_engine/tests/test_reward_manager.py).
  This is the version actually run in this environment (15 passing tests) —
  tune the economy here first.
- **Android port:** [`android-app/.../rewards/RewardManager.kt`](../android-app/app/src/main/java/com/phonecam/streamer/rewards/RewardManager.kt),
  a deliberate 1:1 translation, plus a mirrored JUnit suite in
  `RewardManagerTest.kt`. No JDK is available in this sandbox so that suite
  hasn't been executed here — treat any behavioral change as unverified
  until it's run in Android Studio.
- **Ad SDK boundary:** [`AdController.kt`](../android-app/app/src/main/java/com/phonecam/streamer/rewards/AdController.kt)
  is the only thing that talks to an ad network. `RewardManager` never touches
  the ad SDK directly — it only reacts to "reward earned" — so swapping AdMob
  for another network, or unit-testing the economy without any ad SDK at all,
  doesn't touch the credit logic.

## What's stubbed for alpha

- `AdMobAdController` now does the real load/show/reward flow, pointed at
  Google's public test ad unit (see [ADS_SETUP.md](ADS_SETUP.md) for how to
  swap in your own AdMob app once you've registered one — that step
  requires an account only you can create). `ConsentManager` gates all ad
  requests behind GDPR/UMP consent. Ad mediation (multiple ad networks) is
  still outstanding, but is an optimization, not a compliance requirement.
- No server-side validation of ad completions. For an alpha this is fine
  (worst case: a rooted/modified client fakes a reward callback and gets
  free premium time locally); if this ever needs to gate something
  server-side (e.g. a cloud feature, not just local quality/watermark), the
  reward callback needs a server-verified AdMob SSV (server-side
  verification) callback instead of trusting the on-device callback.
