import numpy as np

from pc_receiver.demo_sender import (
    apply_watermark,
    build_synthetic_frame,
    encode_jpeg,
    render_frame_for_profile,
)
from reward_engine.reward_manager import StreamProfile


def test_build_synthetic_frame_has_expected_shape():
    frame = build_synthetic_frame(640, 480, frame_index=1)
    assert frame.shape == (480, 640, 3)
    assert frame.dtype == np.uint8


def test_watermark_modifies_pixels_without_mutating_input():
    original = np.zeros((480, 640, 3), dtype=np.uint8)
    watermarked = apply_watermark(original.copy())
    assert not np.array_equal(original, watermarked)
    assert original.sum() == 0, "apply_watermark must not mutate its input in place"


def test_encode_jpeg_roundtrip_decodable():
    import cv2

    frame = build_synthetic_frame(320, 240, 1)
    jpeg_bytes = encode_jpeg(frame, quality=85)
    decoded = cv2.imdecode(np.frombuffer(jpeg_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    assert decoded.shape == frame.shape


def test_free_profile_renders_watermarked_1080p():
    frame = build_synthetic_frame(1280, 720, 1)
    profile = StreamProfile(
        quality="1080p60", watermark=True, ads_enabled=True, premium_active=False, balance_seconds=0.0
    )
    rendered, jpeg_quality = render_frame_for_profile(frame, profile)
    assert rendered.shape == (1080, 1920, 3)
    assert jpeg_quality == 80


def test_premium_profile_renders_clean_4k():
    frame = build_synthetic_frame(1280, 720, 1)
    profile = StreamProfile(
        quality="4k60", watermark=False, ads_enabled=False, premium_active=True, balance_seconds=3600.0
    )
    rendered, jpeg_quality = render_frame_for_profile(frame, profile)
    assert rendered.shape == (2160, 3840, 3)
    assert jpeg_quality == 95
