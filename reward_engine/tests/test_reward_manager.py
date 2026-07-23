import pytest

from reward_engine.reward_manager import RewardConfig, RewardManager


def test_default_state_is_free_tier():
    rm = RewardManager()
    profile = rm.current_profile(now=1000.0)
    assert profile.premium_active is False
    assert profile.watermark is True
    assert profile.ads_enabled is True
    assert profile.quality == "1080p60"
    assert profile.balance_seconds == 0.0


def test_single_ad_does_not_grant_premium():
    rm = RewardManager(RewardConfig(ads_per_reward=3))
    completed = rm.credit_ad_watch(now=1000.0)
    assert completed is False
    assert rm.ads_watched_in_batch == 1
    assert rm.is_premium_active(now=1000.0) is False


def test_third_ad_completes_batch_and_grants_configured_seconds():
    rm = RewardManager(RewardConfig(ads_per_reward=3, seconds_per_reward=3600.0))
    assert rm.credit_ad_watch(now=1000.0) is False
    assert rm.credit_ad_watch(now=1000.0) is False
    assert rm.credit_ad_watch(now=1000.0) is True
    assert rm.ads_watched_in_batch == 0
    assert rm.balance_seconds(now=1000.0) == 3600.0
    assert rm.is_premium_active(now=1000.0) is True


def test_batch_counter_resets_after_completing_a_reward():
    rm = RewardManager(RewardConfig(ads_per_reward=3))
    for _ in range(3):
        rm.credit_ad_watch(now=1000.0)
    assert rm.ads_watched_in_batch == 0
    # a fresh batch starts counting from zero again
    assert rm.credit_ad_watch(now=1000.0) is False
    assert rm.ads_watched_in_batch == 1


def test_second_batch_stacks_on_top_of_still_active_premium():
    rm = RewardManager(RewardConfig(ads_per_reward=3, seconds_per_reward=3600.0))
    for _ in range(3):
        rm.credit_ad_watch(now=1000.0)
    assert rm.balance_seconds(now=1000.0) == 3600.0

    # watch a second batch 10 minutes later, while premium is still active
    later = 1000.0 + 600.0
    for _ in range(3):
        rm.credit_ad_watch(now=later)

    # extended from the still-active expiry, not from "later" — so the
    # second hour stacks fully on top of the remaining 50 minutes
    assert rm.balance_seconds(now=later) == pytest.approx(3600.0 + 3000.0)


def test_second_batch_after_premium_expired_starts_from_now():
    rm = RewardManager(RewardConfig(ads_per_reward=3, seconds_per_reward=3600.0))
    for _ in range(3):
        rm.credit_ad_watch(now=1000.0)

    # premium has long since expired by the time the next batch finishes
    much_later = 1000.0 + 10_000.0
    for _ in range(3):
        rm.credit_ad_watch(now=much_later)

    assert rm.balance_seconds(now=much_later) == 3600.0


def test_balance_caps_at_max():
    rm = RewardManager(
        RewardConfig(ads_per_reward=1, seconds_per_reward=3600.0, max_balance_seconds=7200.0)
    )
    for _ in range(5):
        rm.credit_ad_watch(now=1000.0)
    assert rm.balance_seconds(now=1000.0) == 7200.0


def test_balance_counts_down_in_real_time_without_ticking():
    rm = RewardManager(RewardConfig(ads_per_reward=1, seconds_per_reward=3600.0))
    rm.credit_ad_watch(now=1000.0)
    assert rm.balance_seconds(now=1000.0 + 100.0) == 3500.0
    assert rm.balance_seconds(now=1000.0 + 3600.0) == 0.0
    assert rm.is_premium_active(now=1000.0 + 3600.0) is False


def test_balance_floors_at_zero_and_reverts_to_free_profile():
    rm = RewardManager(RewardConfig(ads_per_reward=1, seconds_per_reward=10.0))
    rm.credit_ad_watch(now=1000.0)
    profile = rm.current_profile(now=1000.0 + 100.0)
    assert profile.premium_active is False
    assert profile.quality == "1080p60"
    assert profile.watermark is True
    assert profile.ads_enabled is True
    assert profile.balance_seconds == 0.0


def test_premium_profile_while_balance_positive():
    rm = RewardManager(
        RewardConfig(ads_per_reward=1, seconds_per_reward=3600.0, premium_quality="4k60")
    )
    rm.credit_ad_watch(now=1000.0)
    profile = rm.current_profile(now=1000.0)
    assert profile.premium_active is True
    assert profile.quality == "4k60"
    assert profile.watermark is False
    assert profile.ads_enabled is False


def test_premium_expires_at_cannot_be_negative():
    with pytest.raises(ValueError):
        RewardManager(premium_expires_at=-1.0)


def test_ads_watched_in_batch_must_be_within_range():
    with pytest.raises(ValueError):
        RewardManager(ads_watched_in_batch=3)  # equal to default ads_per_reward
    with pytest.raises(ValueError):
        RewardManager(ads_watched_in_batch=-1)


def test_config_validates_seconds_per_reward():
    with pytest.raises(ValueError):
        RewardConfig(seconds_per_reward=0)


def test_config_validates_ads_per_reward():
    with pytest.raises(ValueError):
        RewardConfig(ads_per_reward=0)


def test_config_validates_max_balance_at_least_one_reward():
    with pytest.raises(ValueError):
        RewardConfig(seconds_per_reward=3600.0, max_balance_seconds=1800.0)


def test_serialization_round_trip_preserves_state_and_config():
    rm = RewardManager(RewardConfig(ads_per_reward=2, seconds_per_reward=1800.0))
    rm.credit_ad_watch(now=1000.0)
    data = rm.to_dict()

    restored = RewardManager.from_dict(data)
    assert restored.premium_expires_at == rm.premium_expires_at
    assert restored.ads_watched_in_batch == rm.ads_watched_in_batch
    assert restored.config == rm.config


def test_serialization_missing_fields_falls_back_to_defaults():
    restored = RewardManager.from_dict({"premium_expires_at": 120.0})
    assert restored.premium_expires_at == 120.0
    assert restored.ads_watched_in_batch == 0
    assert restored.config == RewardConfig()


def test_serialization_rejects_out_of_range_batch_count_by_resetting():
    # a corrupted/edited save file shouldn't crash the app on load
    restored = RewardManager.from_dict({"ads_watched_in_batch": 99, "config": {"ads_per_reward": 3}})
    assert restored.ads_watched_in_batch == 0
