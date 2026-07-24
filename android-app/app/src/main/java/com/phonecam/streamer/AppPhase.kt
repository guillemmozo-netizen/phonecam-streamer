package com.phonecam.streamer

/**
 * Single switch for whether paid Pro (subscription or one-time purchase) is
 * offered at all, versus every Pro feature being unlockable only through
 * rewarded ads.
 *
 * During Alpha/Beta this is `false`: the subscription card's price and
 * "Upgrade to Pro" button stay hidden in favor of the ads-unlock notice
 * (see SettingsActivity's use of this flag), and every "requires Pro"
 * message across the app points at watching ads instead of buying.
 *
 * Flip this to `true` once monetization is ready to launch and those UI
 * paths switch back on their own — nothing else needs to change, since
 * every one of them already branches on this flag rather than assuming
 * one state or the other.
 */
object AppPhase {
    const val MONETIZATION_ENABLED = false
}
