# Turns promo/voiceover.json into one WAV per line with the open Kokoro voice model.
import json, sys, os
import soundfile as sf
from kokoro_onnx import Kokoro
cfg = json.load(open(sys.argv[1])); out = sys.argv[2]; os.makedirs(out, exist_ok=True)
k = Kokoro("kokoro-v1.0.onnx", "voices-v1.0.bin")
for i, line in enumerate(cfg["lines"]):
    samples, sr = k.create(line, voice=cfg["voice"], speed=cfg.get("speed", 1.0), lang="en-us")
    sf.write(f"{out}/line{i:02d}.wav", samples, sr)
    print(i, len(samples) / sr)
