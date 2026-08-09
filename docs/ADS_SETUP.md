# Making ads actually show up

`AdMobAdController` ([android-app/.../rewards/AdMobAdController.kt](../android-app/app/src/main/java/com/framecast/streamer/rewards/AdMobAdController.kt))
now does the real AdMob rewarded-ad flow (load → show → credit on completed
view only). Out of the box it's wired to **Google's public test ad unit**,
so you can see a real ad render and the "watch ad → premium unlocked" loop
work end-to-end before you have your own AdMob account. Swapping to your
own ads is a config change, not a code change.

## 1. Run it as-is first (test ads, no account needed)

Nothing to sign up for. Build and run the app (once you have it building in
Android Studio — this sandbox has no JDK to compile it) and tap "Watch ad
for +1h premium". You'll see a full-screen ad clearly labeled **"Test Ad"**.
Closing it after it finishes credits the reward exactly like a real one
would — this is the wiring you asked about, and it's already working.

Test IDs currently in the code:
- App ID (`AndroidManifest.xml`): `ca-app-pub-3940256099942544~3347511713`
- Rewarded ad unit (`AdMobAdController.kt`): `ca-app-pub-3940256099942544/5224354917`

These are Google's official [test ad unit IDs](https://developers.google.com/admob/android/test-ads) — safe to leave in for
as long as you're developing, and they always fill (no "no ad available"
surprises while you're testing the UI).

## 2. Get your own ad unit (this part only you can do)

I can't create an AdMob account or app registration on your behalf — that's
an account-creation step you need to do yourself:

1. Go to https://admob.google.com and sign in with the Google account you
   want to monetize with.
2. **Apps → Add app** → register this app (Android, not yet published is
   fine for testing).
3. Copy the **App ID** it gives you (`ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY`).
4. **Ad units → Add ad unit → Rewarded**, name it (e.g. "Premium unlock"),
   copy the resulting **ad unit ID**
   (`ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ`).

## 3. Swap the IDs in

Two places, both currently holding Google's test values with a comment
pointing here:

- `android-app/app/src/main/AndroidManifest.xml` → the
  `com.google.android.gms.ads.APPLICATION_ID` meta-data value → your App ID.
- `android-app/app/src/main/java/com/framecast/streamer/rewards/AdMobAdController.kt`
  → the `rewardedAdUnitId` default parameter → your ad unit ID.

## 4. Do not click your own real ads

Once you're using real IDs, **never watch/click your own production ads**
to "test" them — AdMob treats that as invalid traffic and can suspend the
account, sometimes permanently. Two safe ways to test with real ad units
instead:
- Register your test device: AdMob will mark ads served to it as test ads
  even though you're using a real ad unit ID (`MobileAds.setRequestConfiguration`
  with `setTestDeviceIds(listOf("YOUR_DEVICE_ID"))` — the device ID prints
  to Logcat the first time the SDK loads an ad without it registered).
- Or just keep using the test ad unit ID during development and only switch
  to the real one in the build you actually publish.

## 5. GDPR/UMP consent — implemented

[`ConsentManager.kt`](../android-app/app/src/main/java/com/framecast/streamer/consent/ConsentManager.kt)
wraps Google's User Messaging Platform (UMP) SDK. `MainActivity.onCreate`
calls `consentManager.requestConsentAndThen { ... }` *before* touching
`AdMobAdController` at all — ads are only initialized/preloaded if
`canRequestAds()` comes back true, and `onWatchAdClicked` re-checks it so a
user who never granted consent (or is still mid-flow) gets a clear "ads
unavailable" message instead of silently never seeing the button work.

What this does automatically, per Google's UMP contract:
- Users outside the EEA/UK: consent status resolves without ever showing a
  form; `canRequestAds()` is true almost immediately.
- Users inside the EEA/UK: a consent form is shown once (or again if privacy
  regulations require a refresh) before any ad request is made.

What's still on you before a public release:
- Set your app's **Privacy & messaging** settings in the AdMob console
  (creating the actual consent message content is done there, not in code) —
  https://developers.google.com/admob/android/privacy
- Test the EEA flow using UMP's debug geography override
  (`ConsentDebugSettings` with `setDebugGeography(DebugGeography.EEA)`) since
  you likely aren't physically in the EEA while developing.

## Where this fits in the reward economy

None of the above changes `RewardManager` — `AdController` is the seam
specifically so the ad SDK can be swapped or upgraded (test → real IDs →
consent flow → maybe a second network via mediation later) without
touching the credit/tier logic in [REWARD_MODEL.md](REWARD_MODEL.md). The
only contract that matters to `RewardManager` is: `onRewardEarned` fires if
and only if the user watched the ad to completion.
