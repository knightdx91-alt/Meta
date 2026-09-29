"""Measure the "Jarvis" wake word against synthetic speech.

Uses the same Vosk model, grammar and acceptance rule as the app
(JarvisDetector.kt), with espeak-ng voices, with and without noise.

    pip install vosk numpy && apt install espeak-ng
    python3 tools/jarvis_eval.py
"""
import json
import pathlib
import subprocess
import tempfile
import wave

import numpy as np
from vosk import KaldiRecognizer, Model, SetLogLevel

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = (ROOT / "app/src/main/java/com/knightdx/glassestunes/JarvisDetector.kt").read_text()
MODEL = ROOT / "app/src/main/assets/model-en-us"

decoys = list(dict.fromkeys(SRC.split('"""')[1].split()))
GRAMMAR = json.dumps(["jarvis"] + decoys + ["[unk]"])
WAKE = {"jarvis", "hey jarvis", "hi jarvis", "okay jarvis", "hello jarvis", "jervis", "hey jervis"}
MIN_CONF = 0.5

POSITIVE = ["Jarvis", "Hey Jarvis", "Jarvis!", "Jarvis?", "Okay Jarvis", "Jarvis."]
NEGATIVE = ["jars of honey", "Charles is nervous", "harvest", "Jarvis Cocker is a singer", "the jar is here",
            "service", "starve us", "carve this", "Marvis", "jazz is nice", "Garvey", "davis", "jars",
            "I talked to Jarvis today", "large", "garage", "car keys", "far away", "nervous", "harvest moon",
            "our service", "yes please", "hey there", "what time is it", "turn left here", "jarred", "journey",
            "jersey", "German", "Harvard", "Marcus", "cardio", "starfish", "garlic", "jobs", "office", "surface",
            "Travis", "Elvis", "Mavis", "Jasmine"]
VOICES = [("en-us", 150), ("en-us+m3", 120), ("en-gb", 180), ("en-us+f3", 150), ("en-gb-x-rp", 130), ("en-us+m7", 165)]


def speak(text, voice, speed):
    with tempfile.NamedTemporaryFile(suffix=".wav") as f:
        subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", f.name, text], check=True)
        w = wave.open(f.name)
        rate = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    y = np.interp(np.linspace(0, len(x) - 1, int(len(x) * 16000 / rate)), np.arange(len(x)), x)
    return np.concatenate([np.zeros(8000), y, np.zeros(16000)])


def is_wake(result):
    if result.get("text") not in WAKE:
        return False
    return max(w["conf"] for w in result["result"] if w["word"] in ("jarvis", "jervis")) >= MIN_CONF


def detect(model, samples):
    rec = KaldiRecognizer(model, 16000, GRAMMAR)
    rec.SetWords(True)
    pcm = samples.astype(np.int16).tobytes()
    results = []
    for i in range(0, len(pcm), 1024):  # 512-sample frames, like the app
        if rec.AcceptWaveform(pcm[i:i + 1024]):
            results.append(json.loads(rec.Result()))
    results.append(json.loads(rec.FinalResult()))
    return any(is_wake(r) for r in results if r.get("text"))


def main():
    SetLogLevel(-1)
    model = Model(str(MODEL))
    rng = np.random.default_rng(1)
    hits = misses = false = quiet = 0
    for text in POSITIVE + NEGATIVE:
        for voice, speed in VOICES:
            clean = speak(text, voice, speed)
            for noise in (0, 500):
                samples = np.clip(clean + rng.normal(0, noise, len(clean)), -32768, 32767)
                woke = detect(model, samples)
                if text in POSITIVE:
                    hits += woke
                    misses += not woke
                    if not woke:
                        print(f"miss   {text!r} {voice} {speed} noise={noise}")
                else:
                    false += woke
                    quiet += not woke
                    if woke:
                        print(f"FALSE  {text!r} {voice} {speed} noise={noise}")
    print(f"detected {hits}/{hits + misses}, false triggers {false}/{false + quiet}")


if __name__ == "__main__":
    main()
