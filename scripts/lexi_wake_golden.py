"""Regenerate the wake-word golden fixtures for WakeWordDetectorTest.

The Kotlin wake-word detector is a hand port of openWakeWord's streaming pipeline, so
it is checked against the real thing: this script synthesizes clips with Lexi's Piper
voice, runs them through openWakeWord exactly as Lexi does on the Pi, and records the
score for every 80 ms chunk. The Kotlin test must reproduce those scores.

Run with Lexi's virtualenv, from the LexiconAndroid directory:

    ../Lexi/.venv/Scripts/python.exe scripts/lexi_wake_golden.py

Writes app/src/test/resources/lexi/{hey_jarvis,negative}.wav and golden_scores.json.
"""
from __future__ import annotations

import glob
import json
import pathlib
import wave

import numpy as np
from openwakeword.model import Model
from piper import PiperVoice
from scipy.signal import resample_poly

HERE = pathlib.Path(__file__).resolve().parent
LEXI = HERE.parent.parent / "Lexi"
OUT = HERE.parent / "app" / "src" / "test" / "resources" / "lexi"
CHUNK = 1280  # openWakeWord's 80 ms frame at 16 kHz

CLIPS = {
    "hey_jarvis": "Hey Jarvis.",
    # Speech that is not the wake word, so the test also pins the absence of false fires.
    "negative": "Hey, what is the weather like today?",
}


def synthesize(voice: PiperVoice, text: str) -> np.ndarray:
    pcm = b"".join(chunk.audio_int16_bytes for chunk in voice.synthesize(text))
    audio = np.frombuffer(pcm, np.int16).astype(np.float32)
    resampled = resample_poly(audio, 16000, voice.config.sample_rate)
    clip = np.clip(resampled, -32768, 32767).astype(np.int16)
    # 1.5 s of leading silence lets the detector warm up before anything is said.
    silence = lambda seconds: np.zeros(int(16000 * seconds), np.int16)  # noqa: E731
    return np.concatenate([silence(1.5), clip, silence(1.0)])


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    voice_path = glob.glob(str(LEXI / "models" / "piper" / "**" / "*.onnx"), recursive=True)[0]
    voice = PiperVoice.load(voice_path)
    model = Model(wakeword_models=["hey_jarvis"], inference_framework="onnx")

    golden = {}
    for name, text in CLIPS.items():
        clip = synthesize(voice, text)
        with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(16000)
            w.writeframes(clip.tobytes())

        model.reset()
        scores = [
            float(model.predict(clip[i:i + CHUNK])["hey_jarvis"])
            for i in range(0, len(clip) - len(clip) % CHUNK, CHUNK)
        ]
        golden[name] = scores
        print(f"{name}: {len(scores)} chunks, peak {max(scores):.4f}")

    (OUT / "golden_scores.json").write_text(json.dumps(golden, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
