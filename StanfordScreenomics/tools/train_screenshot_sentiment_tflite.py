#!/usr/bin/env python3
"""
Train a small on-device sentiment regressor (1–10, higher = more positive) and export
app/src/main/assets/screenshot_sentiment.tflite.

Encoding must match screenshot_sentiment_encoding.py / ScreenshotSentimentTextEncoding.kt.
"""
from __future__ import annotations

import os
import random
import sys

import numpy as np

try:
    import tensorflow as tf
except ImportError:
    print("Install TensorFlow: pip install 'tensorflow>=2.14,<2.19'", file=sys.stderr)
    sys.exit(1)

from screenshot_sentiment_encoding import INPUT_DIM, encode_text

_POS = {
    "good", "great", "excellent", "amazing", "love", "happy", "best", "thanks", "thank", "yes",
    "wonderful", "fantastic", "awesome", "nice", "perfect", "beautiful", "glad", "enjoy", "fun",
    "success", "win", "won", "like", "hope", "better", "well", "fine", "ok", "okay", "congrats",
    "congratulations", "pleased", "delighted", "sweet", "cute", "adorable", "brilliant", "super",
}
_NEG = {
    "bad", "hate", "terrible", "awful", "worst", "sad", "angry", "no", "never", "fail", "failed",
    "error", "wrong", "sorry", "pain", "hurt", "ugly", "horrible", "disgusting", "annoying", "stupid",
    "trash", "garbage", "scam", "fraud", "cancel", "refund", "complaint", "unfortunately", "problem",
    "issue", "bug", "crash", "lost", "miss", "missed", "late", "wait", "waiting", "blocked", "denied",
}

_NEUTRAL_FILLERS = (
    "the", "and", "for", "with", "this", "that", "from", "your", "home", "page", "menu", "settings",
    "tap", "open", "close", "time", "date", "email", "search", "loading",
)


def _lexicon_neg1_to_1(text: str) -> float:
    if not text.strip():
        return 0.0
    tokens = [t.lower() for t in "".join(c if c.isalpha() or c == "'" else " " for c in text).split()]
    tokens = [t for t in tokens if t]
    if not tokens:
        return 0.0
    pos = sum(1 for t in tokens if t in _POS)
    neg = sum(1 for t in tokens if t in _NEG)
    total = pos + neg
    if total == 0:
        return 0.0
    return max(-1.0, min(1.0, (pos - neg) / total))


def _label_1_to_10_from_lex(text: str) -> float:
    s = _lexicon_neg1_to_1(text)
    if s == 0.0:
        return 5.5
    return 1.0 + (s + 1.0) * 4.5


def _random_sentence(rng: random.Random) -> str:
    kind = rng.randint(0, 4)
    parts: list[str] = []
    if kind == 0:
        for _ in range(rng.randint(2, 8)):
            parts.append(rng.choice(list(_POS)))
        parts += [rng.choice(_NEUTRAL_FILLERS) for _ in range(rng.randint(0, 4))]
    elif kind == 1:
        for _ in range(rng.randint(2, 8)):
            parts.append(rng.choice(list(_NEG)))
        parts += [rng.choice(_NEUTRAL_FILLERS) for _ in range(rng.randint(0, 4))]
    elif kind == 2:
        parts = [rng.choice(list(_POS)), rng.choice(list(_NEG)), rng.choice(_NEUTRAL_FILLERS)]
    else:
        parts = [rng.choice(_NEUTRAL_FILLERS) for _ in range(rng.randint(3, 12))]
    rng.shuffle(parts)
    return " ".join(parts)


def main() -> None:
    root = os.path.dirname(os.path.abspath(__file__))
    repo_app_assets = os.path.normpath(os.path.join(root, "..", "app", "src", "main", "assets"))
    os.makedirs(repo_app_assets, exist_ok=True)
    out_path = os.path.join(repo_app_assets, "screenshot_sentiment.tflite")

    rng = random.Random(42)
    n_samples = 24_000
    x = np.zeros((n_samples, INPUT_DIM), dtype=np.float32)
    y = np.zeros((n_samples, 1), dtype=np.float32)

    for i in range(n_samples):
        text = _random_sentence(rng)
        if i < 2000:
            text = ""  # empty OCR cases
        elif i < 3500:
            text = " ".join([rng.choice(_NEUTRAL_FILLERS) for _ in range(6)])
        x[i] = encode_text(text)
        label = _label_1_to_10_from_lex(text)
        jitter = rng.uniform(-0.35, 0.35)
        label = max(1.0, min(10.0, label + jitter))
        y[i, 0] = (label - 1.0) / 9.0  # train sigmoid output 0..1

    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(INPUT_DIM,)),
            tf.keras.layers.Dense(96, activation="relu"),
            tf.keras.layers.Dense(48, activation="relu"),
            tf.keras.layers.Dense(1, activation="sigmoid"),
        ]
    )
    model.compile(optimizer=tf.keras.optimizers.Adam(0.001), loss="mse", metrics=["mae"])

    model.fit(x, y, epochs=40, batch_size=64, validation_split=0.1, verbose=1)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    # Float32 I/O to match org.tensorflow.lite.Interpreter without quant calibration.
    tflite_model = converter.convert()

    with open(out_path, "wb") as f:
        f.write(tflite_model)

    print(f"Wrote {out_path} ({len(tflite_model)} bytes)")


if __name__ == "__main__":
    main()
