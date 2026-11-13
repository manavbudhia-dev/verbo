#!/usr/bin/env python3
"""Synthesizes the key sound effects (no recordings, so nothing to license).
Usage: make_sounds.py <app/src/main/res/raw>"""
import math, random, struct, sys, wave

RATE = 44100
out = sys.argv[1]
rng = random.Random(7)

def write(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    data = b"".join(struct.pack("<h", int(max(-1, min(1, s / peak * 0.89)) * 32767)) for s in samples)
    with wave.open(f"{out}/{name}.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data)

def render(seconds, fn):
    return [fn(i / RATE) for i in range(int(seconds * RATE))]

def noise_burst(t, decay):  # filtered-ish noise: average of a few random values
    return (rng.random() + rng.random() + rng.random() - 1.5) * math.exp(-t * decay)

def tone(t, freq, decay): return math.sin(2 * math.pi * freq * t) * math.exp(-t * decay)

# Typewriter: a sharp strike, a short metallic ring and a lower thunk a few ms later.
write("snd_typewriter", render(0.11, lambda t:
    1.0 * noise_burst(t, 420) + 0.5 * tone(t, 2900, 160) + 0.35 * tone(t, 4300, 220)
    + (0.9 * noise_burst(t - 0.012, 150) + 0.6 * tone(t - 0.012, 190, 70) if t > 0.012 else 0)))
# Typewriter carriage bell, played on enter.
write("snd_typewriter_bell", render(0.45, lambda t:
    0.9 * tone(t, 2093, 9) + 0.45 * tone(t, 4186, 14) + 0.25 * tone(t, 6270, 20) + 0.5 * noise_burst(t, 600)))
# Mechanical switch: bright click, then the softer bottom-out.
write("snd_mechanical", render(0.07, lambda t:
    0.9 * noise_burst(t, 900) + 0.6 * tone(t, 5200, 500)
    + (0.7 * noise_burst(t - 0.018, 380) + 0.4 * tone(t - 0.018, 320, 120) if t > 0.018 else 0)))
# Pop: a quick downward chirp.
write("snd_pop", render(0.07, lambda t: math.sin(2 * math.pi * (260 * t + 520 * (1 - math.exp(-t * 60)) / 60)) * math.exp(-t * 55) * min(1, t * 2000)))
# Water drop: a quick upward chirp.
write("snd_drop", render(0.09, lambda t: math.sin(2 * math.pi * (700 * t + 9000 * t * t)) * math.exp(-t * 48) * min(1, t * 1500)))
# Soft tap: a muted low thud.
write("snd_soft", render(0.06, lambda t: (0.9 * tone(t, 150, 75) + 0.25 * noise_burst(t, 700)) * min(1, t * 3000)))
print("sounds written")
