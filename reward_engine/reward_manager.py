"""Rewarded-ads credit engine.

Core economy for the app's free tier: users unlock a temporary "premium"
window (no watermark, no ads, max quality) by watching rewarded ads.

Premium time is a wall-clock expiry, not a balance that drains while
streaming: watching a full batch of `ads_per_reward` ads (default 3) adds
one reward's worth of real time on top of whatever expiry is already in
effect (or on top of "now" if premium isn't currently active), so back-to-back
batches stack instead of resetting. This means premium keeps counting down
in the background even when the user isn't actively streaming — the whole
point of "3 ads bought you an hour" is that it's an hour, not "an hour of
streaming spread across however many days you get around to it."

This module has no Android/UI dependencies on purpose: it is the reference
implementation. The Kotlin port in
android-app/app/src/main/java/com/phonecam/streamer/rewards/RewardManager.kt
mirrors this logic 1:1 so the economy can be tuned and tested here first.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class RewardConfig:
    """Tunable knobs for the ad-credit economy.

    Alpha/Beta defaults reflect "3 ads = 12 hours" (was 1 hour): there is no
    paid Pro tier at all while the app is in Alpha/Beta (see the Android
    app's AppPhase.kt), so the ad reward is the only way in and is worth
    making generous. max_balance_seconds is kept at 8x a single reward —
    the same "how many batches can stack" ratio as before the 1h->12h bump
    (was 8x 1h = 8h) — even though each batch is now worth much more.
    """

    seconds_per_reward: float = 12 * 3600.0
    ads_per_reward: int = 3
    max_balance_seconds: float = 8 * (12 * 3600.0)
    free_quality: str = "1080p60"
    premium_quality: str = "4k60"

    def __post_init__(self) -> None:
        if self.seconds_per_reward <= 0:
            raise ValueError("seconds_per_reward must be positive")
        if self.ads_per_reward <= 0:
            raise ValueError("ads_per_reward must be positive")
        if self.max_balance_seconds < self.seconds_per_reward:
            raise ValueError("max_balance_seconds must be >= seconds_per_reward")


@dataclass(frozen=True)
class StreamProfile:
    """The effective capabilities applied to the current/next stream frame."""

    quality: str
    watermark: bool
    ads_enabled: bool
    premium_active: bool
    balance_seconds: float


class RewardManager:
    """Tracks the user's ad-watch batch progress and premium expiry.

    Usage pattern:
        rm = RewardManager(config)
        completed = rm.credit_ad_watch()   # user finished ONE rewarded ad
        if completed:
            ...                            # that ad completed a batch — premium extended
        profile = rm.current_profile()
    """

    def __init__(
        self,
        config: Optional[RewardConfig] = None,
        premium_expires_at: float = 0.0,
        ads_watched_in_batch: int = 0,
    ) -> None:
        self.config = config or RewardConfig()
        if premium_expires_at < 0:
            raise ValueError("premium_expires_at cannot be negative")
        if not (0 <= ads_watched_in_batch < self.config.ads_per_reward):
            raise ValueError("ads_watched_in_batch must be in [0, ads_per_reward)")
        self._premium_expires_at = premium_expires_at
        self._ads_watched_in_batch = ads_watched_in_batch

    @property
    def ads_watched_in_batch(self) -> int:
        return self._ads_watched_in_batch

    @property
    def premium_expires_at(self) -> float:
        return self._premium_expires_at

    def balance_seconds(self, now: Optional[float] = None) -> float:
        now = time.time() if now is None else now
        return max(0.0, self._premium_expires_at - now)

    def credit_ad_watch(self, now: Optional[float] = None) -> bool:
        """Record one completed rewarded ad. Returns True iff this ad completed
        a batch of `ads_per_reward` and extended the premium expiry.

        The extension stacks on whichever is later — "now" or the current
        expiry — so watching another batch while premium is still active adds
        a full extra hour on top instead of overwriting the remaining time.
        Capped so a user can't binge-watch their way to weeks of premium.
        """
        now = time.time() if now is None else now
        self._ads_watched_in_batch += 1
        if self._ads_watched_in_batch < self.config.ads_per_reward:
            return False

        self._ads_watched_in_batch = 0
        base = max(now, self._premium_expires_at)
        extended = base + self.config.seconds_per_reward
        cap = now + self.config.max_balance_seconds
        self._premium_expires_at = min(extended, cap)
        return True

    def is_premium_active(self, now: Optional[float] = None) -> bool:
        now = time.time() if now is None else now
        return self._premium_expires_at > now

    def current_profile(self, now: Optional[float] = None) -> StreamProfile:
        now = time.time() if now is None else now
        premium = self.is_premium_active(now)
        return StreamProfile(
            quality=self.config.premium_quality if premium else self.config.free_quality,
            watermark=not premium,
            ads_enabled=not premium,
            premium_active=premium,
            balance_seconds=self.balance_seconds(now),
        )

    def to_dict(self) -> dict:
        """Serialize for persistence (e.g. SharedPreferences / a JSON file)."""
        return {
            "premium_expires_at": self._premium_expires_at,
            "ads_watched_in_batch": self._ads_watched_in_batch,
            "config": {
                "seconds_per_reward": self.config.seconds_per_reward,
                "ads_per_reward": self.config.ads_per_reward,
                "max_balance_seconds": self.config.max_balance_seconds,
                "free_quality": self.config.free_quality,
                "premium_quality": self.config.premium_quality,
            },
        }

    @classmethod
    def from_dict(cls, data: dict) -> "RewardManager":
        cfg_data = data.get("config", {})
        config = RewardConfig(
            seconds_per_reward=cfg_data.get(
                "seconds_per_reward", RewardConfig.seconds_per_reward
            ),
            ads_per_reward=cfg_data.get("ads_per_reward", RewardConfig.ads_per_reward),
            max_balance_seconds=cfg_data.get(
                "max_balance_seconds", RewardConfig.max_balance_seconds
            ),
            free_quality=cfg_data.get("free_quality", RewardConfig.free_quality),
            premium_quality=cfg_data.get("premium_quality", RewardConfig.premium_quality),
        )
        ads_watched = data.get("ads_watched_in_batch", 0)
        if not (0 <= ads_watched < config.ads_per_reward):
            ads_watched = 0
        return cls(
            config=config,
            premium_expires_at=data.get("premium_expires_at", 0.0),
            ads_watched_in_batch=ads_watched,
        )
