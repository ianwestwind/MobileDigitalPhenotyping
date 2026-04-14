"""
Must stay in sync with ScreenshotSentimentTextEncoding.kt (INPUT_DIM and encode logic).
"""
from __future__ import annotations

import numpy as np

INPUT_DIM = 128


def encode_text(ocr_text: str) -> np.ndarray:
    """Same float vector as Kotlin ScreenshotSentimentTextEncoding.encode."""
    text = ocr_text or ""
    length = len(text)
    span = length if length > 0 else 1
    out = np.zeros(INPUT_DIM, dtype=np.float32)
    for i in range(INPUT_DIM):
        idx = i % span
        c = 0 if length == 0 else ord(text[idx])
        out[i] = float((c + i * 31) % 1025) / 1024.0
    return out
