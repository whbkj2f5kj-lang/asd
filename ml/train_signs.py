"""
Trainiert den kleinen Schild-Klassifikator der App (GTSRB, 43 Klassen + 1 "kein Schild").

Ausgabe:
  app/src/main/assets/signnet.bin          Gewichte (eigenes, einfaches Format, little endian)
  app/src/test/resources/signnet_samples.bin  Testbilder + erwartete Ausgaben (Paritätstest in Kotlin)

Netz (bewusst klein, läuft in reinem Kotlin in wenigen ms):
  32x32x3 -> Conv3x3(16) ReLU MaxPool2 -> Conv3x3(32) ReLU MaxPool2 -> Conv3x3(64) ReLU MaxPool2
          -> Flatten(1024) -> Dense(128) ReLU -> Dense(44)
"""
import glob
import os
import struct
import sys

import numpy as np
from PIL import Image

# Quelle: Kopie des GTSRB-Datensatzes auf Hugging Face (gleiche Klassen-Reihenfolge 0..42),
# der Originalserver (sid.erda.dk) ist vom GitHub-Build aus nicht erreichbar.
HF_REPO = "tanganke/gtsrb"
S = 32
NEG = 43
CLASSES = 44
WORK = os.environ.get("GTSRB_DIR", "gtsrb_data")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_MODEL = os.path.join(ROOT, "app/src/main/assets/signnet.bin")
OUT_SAMPLES = os.path.join(ROOT, "app/src/test/resources/signnet_samples.bin")


def fetch():
    from huggingface_hub import snapshot_download
    return snapshot_download(repo_id=HF_REPO, repo_type="dataset", local_dir=WORK,
                             allow_patterns=["data/train-*", "data/test-*"])


def load_split(root, split):
    import io
    import pyarrow.parquet as pq
    xs, ys = [], []
    for f in sorted(glob.glob(os.path.join(root, "data", f"{split}-*.parquet"))):
        t = pq.read_table(f).to_pydict()
        for im, lab in zip(t["image"], t["label"]):
            data = im["bytes"] if isinstance(im, dict) else im
            pil = Image.open(io.BytesIO(data)).convert("RGB")
            xs.append(np.asarray(pil.resize((S, S), Image.BILINEAR), dtype=np.uint8))
            ys.append(int(lab))
    if not xs:
        sys.exit(f"keine Daten für {split}")
    return np.stack(xs), np.array(ys, dtype=np.int64)


def negatives(train_x, n, rng):
    """Synthetische "kein Schild"-Bilder: Hintergrundecken, stark verschobene Schilder,
    Flächen mit roten Flecken (Rücklichter!), Rauschen."""
    out = []
    for i in range(n):
        kind = i % 4
        if kind == 0:   # Ecke eines Trainingsbilds stark vergrößert (meist Hintergrund)
            src = Image.fromarray(train_x[rng.integers(len(train_x))])
            c = rng.integers(0, 4)
            box = [(0, 0, 12, 12), (20, 0, 32, 12), (0, 20, 12, 32), (20, 20, 32, 32)][c]
            out.append(np.asarray(src.crop(box).resize((S, S), Image.BILINEAR)))
        elif kind == 1:  # Schild nur angeschnitten
            src = train_x[rng.integers(len(train_x))]
            dx, dy = rng.integers(14, 24) * rng.choice([-1, 1]), rng.integers(14, 24) * rng.choice([-1, 1])
            canvas = np.full((S, S, 3), rng.integers(30, 200), dtype=np.uint8)
            xs0, ys0 = max(0, dx), max(0, dy)
            xs1, ys1 = min(S, S + dx), min(S, S + dy)
            canvas[ys0:ys1, xs0:xs1] = src[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
            out.append(canvas)
        elif kind == 2:  # Fläche + rote Ellipsen (Rück-/Bremslichter, Werbung)
            base = rng.integers(0, 255, size=3)
            a = np.ones((S, S, 3)) * base + rng.normal(0, 18, (S, S, 3))
            yy, xx = np.mgrid[0:S, 0:S]
            for _ in range(rng.integers(1, 3)):
                cx, cy = rng.integers(4, 28, size=2)
                rx, ry = rng.integers(3, 12, size=2)
                m = ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2 < 1
                a[m] = [rng.integers(170, 255), rng.integers(0, 60), rng.integers(0, 60)]
            out.append(np.clip(a, 0, 255).astype(np.uint8))
        else:            # Struktur/Rauschen (Laub, Fassaden)
            small = rng.integers(0, 255, size=(rng.integers(3, 9), rng.integers(3, 9), 3)).astype(np.uint8)
            out.append(np.asarray(Image.fromarray(small).resize((S, S), Image.BILINEAR)))
    return np.stack(out), np.full(n, NEG, dtype=np.int64)


def main():
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers

    tf.keras.utils.set_random_seed(204)
    rng = np.random.default_rng(204)
    root = fetch()
    x_tr, y_tr = load_split(root, "train")
    x_te, y_te = load_split(root, "test")
    nx, ny = negatives(x_tr, 4000, rng)
    nxt, nyt = negatives(x_te, 600, rng)
    x_tr = np.concatenate([x_tr, nx]); y_tr = np.concatenate([y_tr, ny])
    x_te2 = np.concatenate([x_te, nxt]); y_te2 = np.concatenate([y_te, nyt])
    print("Training:", x_tr.shape, "Test:", x_te.shape, flush=True)

    aug = keras.Sequential([
        layers.RandomRotation(0.03, fill_mode="nearest"),
        layers.RandomTranslation(0.06, 0.06, fill_mode="nearest"),
        layers.RandomZoom((-0.1, 0.1), fill_mode="nearest"),
        layers.RandomContrast(0.25),
        layers.RandomBrightness(0.25, value_range=(0.0, 1.0)),
    ])

    def prep(x, y, train):
        x = tf.cast(x, tf.float32) / 255.0
        if train:
            x = aug(x, training=True)
            x = tf.clip_by_value(x, 0.0, 1.0)
        return x, y

    ds = (tf.data.Dataset.from_tensor_slices((x_tr, y_tr)).shuffle(len(x_tr), seed=204).batch(128)
          .map(lambda a, b: prep(a, b, True), num_parallel_calls=tf.data.AUTOTUNE).prefetch(4))
    ds_te = tf.data.Dataset.from_tensor_slices((x_te2, y_te2)).batch(512).map(lambda a, b: prep(a, b, False))

    # Conv -> BatchNorm -> ReLU; BatchNorm wird beim Export in die Faltung eingerechnet
    inp = keras.Input((S, S, 3))
    x = inp
    for i, n in enumerate([16, 32, 64], start=1):
        x = layers.Conv2D(n, 3, padding="same", name=f"c{i}")(x)
        x = layers.BatchNormalization(name=f"bn{i}")(x)
        x = layers.ReLU()(x)
        x = layers.MaxPooling2D()(x)
    x = layers.Flatten()(x)
    x = layers.Dropout(0.3)(x)
    x = layers.Dense(128, activation="relu", name="d1")(x)
    x = layers.Dropout(0.3)(x)
    out = layers.Dense(CLASSES, name="d2")(x)
    model = keras.Model(inp, out)
    model.compile(
        optimizer=keras.optimizers.Adam(1e-3),
        loss=keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["accuracy"],
    )
    model.fit(ds, epochs=30, validation_data=ds_te, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(monitor="val_loss", patience=2, factor=0.4, verbose=1)])

    logits_te = model.predict(x_te.astype(np.float32) / 255.0, batch_size=512, verbose=0)
    acc = float((logits_te.argmax(1) == y_te).mean())
    print(f"GTSRB-Testgenauigkeit: {acc:.4f}", flush=True)
    if acc < 0.95:
        sys.exit("Genauigkeit zu gering")

    # ---- Gewichte schreiben
    os.makedirs(os.path.dirname(OUT_MODEL), exist_ok=True)
    tensors = []
    for i in (1, 2, 3):
        k, b = model.get_layer(f"c{i}").get_weights()
        gamma, beta, mean, var = model.get_layer(f"bn{i}").get_weights()
        eps = model.get_layer(f"bn{i}").epsilon
        scale = gamma / np.sqrt(var + eps)
        tensors += [k * scale, (b - mean) * scale + beta]
    for name in ["d1", "d2"]:
        k, b = model.get_layer(name).get_weights()
        tensors += [k, b]
    with open(OUT_MODEL, "wb") as f:
        f.write(b"GTS1")
        f.write(struct.pack("<i", len(tensors)))
        for t in tensors:
            t = t.astype("<f4")
            f.write(struct.pack("<i", t.ndim))
            f.write(struct.pack("<" + "i" * t.ndim, *t.shape))
            f.write(t.tobytes(order="C"))
    print("geschrieben:", OUT_MODEL, os.path.getsize(OUT_MODEL), "Bytes")

    # ---- Paritäts-Stichprobe für den Kotlin-Test
    idx = rng.choice(len(x_te), 64, replace=False)
    xs = x_te[idx]
    lg = model.predict(xs.astype(np.float32) / 255.0, verbose=0)
    os.makedirs(os.path.dirname(OUT_SAMPLES), exist_ok=True)
    with open(OUT_SAMPLES, "wb") as f:
        f.write(struct.pack("<iii", len(xs), S, CLASSES))
        for i in range(len(xs)):
            f.write(struct.pack("<i", int(y_te[idx[i]])))
            f.write(xs[i].astype(np.uint8).tobytes(order="C"))
            f.write(lg[i].astype("<f4").tobytes())
    print("geschrieben:", OUT_SAMPLES)


if __name__ == "__main__":
    main()
