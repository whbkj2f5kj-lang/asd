"""
Trainiert den kleinen Schild-Klassifikator der App (GTSRB, 43 Klassen + 1 "kein Schild").

Ausgabe:
  app/src/main/assets/signnet.bin          Gewichte (eigenes, einfaches Format, little endian)
  app/src/test/resources/signnet_samples.bin  Testbilder + erwartete Ausgaben (Paritätstest in Kotlin)

Netz (bewusst klein, läuft in reinem Kotlin in wenigen ms):
  32x32x3 -> Conv3x3(16) ReLU MaxPool2 -> Conv3x3(32) ReLU MaxPool2 -> Conv3x3(64) ReLU MaxPool2
          -> Flatten(1024) -> Dense(128) ReLU -> Dense(44)
"""
import csv
import glob
import hashlib
import os
import struct
import sys
import urllib.request
import zipfile

import numpy as np
from PIL import Image

BASE = "https://sid.erda.dk/public/archives/daaeac0d7ce1152aea9b61d9f1e19370/"
FILES = {
    "GTSRB-Training_fixed.zip": "513f3c79a4c5141765e10e952eaa2478",
    "GTSRB_Final_Test_Images.zip": "c7e4e6327067d32654124b0fe9e82185",
    "GTSRB_Final_Test_GT.zip": "fe31e9c9270bbcd7b84b7f21a9d9d9e5",
}
S = 32
NEG = 43
CLASSES = 44
WORK = os.environ.get("GTSRB_DIR", "gtsrb_data")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_MODEL = os.path.join(ROOT, "app/src/main/assets/signnet.bin")
OUT_SAMPLES = os.path.join(ROOT, "app/src/test/resources/signnet_samples.bin")


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch():
    os.makedirs(WORK, exist_ok=True)
    for name, sum_ in FILES.items():
        p = os.path.join(WORK, name)
        if not os.path.exists(p) or md5(p) != sum_:
            print("lade", name, flush=True)
            urllib.request.urlretrieve(BASE + name, p)
            if md5(p) != sum_:
                sys.exit(f"Prüfsumme falsch: {name}")
        marker = p + ".done"
        if not os.path.exists(marker):
            with zipfile.ZipFile(p) as z:
                z.extractall(WORK)
            open(marker, "w").close()


def img(path, box=None):
    im = Image.open(path).convert("RGB")
    if box is not None:
        im = im.crop(box)
    return np.asarray(im.resize((S, S), Image.BILINEAR), dtype=np.uint8)


def load_train():
    xs, ys = [], []
    for d in sorted(glob.glob(os.path.join(WORK, "GTSRB", "Training", "*"))):
        if not os.path.isdir(d):
            continue
        cls = int(os.path.basename(d))
        for f in glob.glob(os.path.join(d, "*.ppm")):
            xs.append(img(f))
            ys.append(cls)
    return np.stack(xs), np.array(ys, dtype=np.int64)


def load_test():
    gt = glob.glob(os.path.join(WORK, "**", "GT-final_test.csv"), recursive=True)[0]
    folder = os.path.join(WORK, "GTSRB", "Final_Test", "Images")
    xs, ys = [], []
    with open(gt) as f:
        for row in csv.DictReader(f, delimiter=";"):
            xs.append(img(os.path.join(folder, row["Filename"])))
            ys.append(int(row["ClassId"]))
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
    fetch()
    x_tr, y_tr = load_train()
    x_te, y_te = load_test()
    nx, ny = negatives(x_tr, 4000, rng)
    nxt, nyt = negatives(x_te, 600, rng)
    x_tr = np.concatenate([x_tr, nx]); y_tr = np.concatenate([y_tr, ny])
    x_te2 = np.concatenate([x_te, nxt]); y_te2 = np.concatenate([y_te, nyt])
    print("Training:", x_tr.shape, "Test:", x_te.shape, flush=True)

    aug = keras.Sequential([
        layers.RandomRotation(0.04, fill_mode="nearest"),
        layers.RandomTranslation(0.08, 0.08, fill_mode="nearest"),
        layers.RandomZoom((-0.12, 0.12), fill_mode="nearest"),
        layers.RandomContrast(0.35),
        layers.RandomBrightness(0.35, value_range=(0.0, 1.0)),
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

    inp = keras.Input((S, S, 3))
    x = layers.Conv2D(16, 3, padding="same", activation="relu", name="c1")(inp)
    x = layers.MaxPooling2D()(x)
    x = layers.Conv2D(32, 3, padding="same", activation="relu", name="c2")(x)
    x = layers.MaxPooling2D()(x)
    x = layers.Conv2D(64, 3, padding="same", activation="relu", name="c3")(x)
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
    model.fit(ds, epochs=22, validation_data=ds_te, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=2, factor=0.4, verbose=1)])

    logits_te = model.predict(x_te.astype(np.float32) / 255.0, batch_size=512, verbose=0)
    acc = float((logits_te.argmax(1) == y_te).mean())
    print(f"GTSRB-Testgenauigkeit: {acc:.4f}", flush=True)
    if acc < 0.95:
        sys.exit("Genauigkeit zu gering")

    # ---- Gewichte schreiben
    os.makedirs(os.path.dirname(OUT_MODEL), exist_ok=True)
    tensors = []
    for name in ["c1", "c2", "c3", "d1", "d2"]:
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
