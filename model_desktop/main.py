"""Webcam facial landmarks and a translation/scale-normalized face encoder."""

import argparse
from contextlib import closing
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
from urllib.request import urlopen

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python
from mediapipe.tasks.python import vision


ROOT = Path(__file__).resolve().parent
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/"
    "face_landmarker/face_landmarker/float16/1/face_landmarker.task"
)
WINDOW = "Face encoder | Q: sair | N: indices | S: salvar | clique: ponto"
DOT_OFFSETS = np.array(((0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)), np.int32)


def ensure_model(path: Path) -> Path:
    """Download the fixed model version once, replacing only complete files."""
    if path.is_file():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    print("Baixando modelo oficial do MediaPipe...", flush=True)
    try:
        with urlopen(MODEL_URL, timeout=30) as response:
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as file:
                temporary = Path(file.name)
                shutil.copyfileobj(response, file)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return path


def encode_landmarks(landmarks: np.ndarray, width: int, height: int) -> np.ndarray:
    """Return float32 XYZ, nose-centered and scaled by projected eye distance.

    Input: MediaPipe coordinates, shape (N, 3). Output: flat vector (N * 3,).
    Y is aspect-corrected; Z uses the same relative scale as X, not meters.
    Head rotation and expression remain represented in the vector.
    """
    points = np.asarray(landmarks, dtype=np.float32)
    if points.ndim != 2 or points.shape[1] != 3 or len(points) < 264:
        raise ValueError("Landmarks devem ter formato (N, 3), com N >= 264.")
    if width <= 0 or height <= 0 or not np.isfinite(points).all():
        raise ValueError("Dimensoes e coordenadas devem ser validas.")
    encoded = points.copy()
    encoded[:, 1] *= np.float32(height / width)
    eye_distance = np.linalg.norm(encoded[33, :2] - encoded[263, :2])
    if eye_distance < 1e-6:
        raise ValueError("Distancia entre os olhos insuficiente para normalizar.")
    origin = encoded[1].copy()
    encoded -= origin
    encoded /= eye_distance
    return encoded.reshape(-1)


class LatestFrame:
    """One capture thread and one replaceable slot; never queue old frames."""

    def __init__(self, camera: int, width: int, height: int, fps: int):
        self._condition = threading.Condition()
        self._stop = threading.Event()
        self._latest = None
        self._sequence = 0
        self._error = None
        self._thread = threading.Thread(
            target=self._capture, args=(camera, width, height, fps), daemon=True
        )
        self._thread.start()

    def _capture(self, camera: int, width: int, height: int, fps: int):
        capture = None
        try:
            backend = cv2.CAP_V4L2 if sys.platform.startswith("linux") else cv2.CAP_ANY
            capture = cv2.VideoCapture(camera, backend)
            if not capture.isOpened():
                raise RuntimeError(f"Nao foi possivel abrir a camera {camera}.")
            capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
            capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
            capture.set(cv2.CAP_PROP_FPS, fps)
            capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            while not self._stop.is_set():
                ok, frame = capture.read()
                timestamp_ns = time.monotonic_ns()
                if not ok:
                    raise RuntimeError("A camera parou de fornecer imagens.")
                with self._condition:
                    self._sequence += 1
                    self._latest = (self._sequence, timestamp_ns, frame)
                    self._condition.notify()
        except Exception as error:
            with self._condition:
                self._error = error
                self._condition.notify()
        finally:
            if capture is not None:
                capture.release()

    def read(self, previous_sequence: int, timeout: float = 0.01):
        with self._condition:
            self._condition.wait_for(
                lambda: self._sequence != previous_sequence or self._error is not None,
                timeout=timeout,
            )
            if self._error is not None:
                raise RuntimeError(str(self._error)) from self._error
            if self._sequence == previous_sequence:
                return None
            return self._latest

    def close(self):
        self._stop.set()
        self._thread.join(timeout=2)


def draw_landmarks(frame, landmarks, selected, show_indices):
    """Stamp all markers with one NumPy assignment instead of 478 draw calls."""
    height, width = frame.shape[:2]
    xy = np.rint(landmarks[:, :2] * (width, height)).astype(np.int32)
    pixels = (xy[:, None, :] + DOT_OFFSETS).reshape(-1, 2)
    visible = (
        (pixels[:, 0] >= 0) & (pixels[:, 0] < width)
        & (pixels[:, 1] >= 0) & (pixels[:, 1] < height)
    )
    pixels = pixels[visible]
    frame[pixels[:, 1], pixels[:, 0]] = (80, 245, 120)
    if show_indices:
        for index, (x, y) in enumerate(xy):
            cv2.putText(frame, str(index), (int(x), int(y)),
                        cv2.FONT_HERSHEY_PLAIN, 0.65, (255, 255, 255), 1)
    center = tuple(int(value) for value in xy[selected])
    cv2.circle(frame, center, 5, (0, 220, 255), 1, cv2.LINE_AA)
    return xy


def draw_hud(frame, lines):
    cv2.rectangle(frame, (0, 0), (frame.shape[1], 12 + 22 * len(lines)), (20, 20, 20), -1)
    for row, line in enumerate(lines):
        cv2.putText(frame, line, (10, 24 + row * 22), cv2.FONT_HERSHEY_SIMPLEX,
                    0.48, (240, 240, 240), 1, cv2.LINE_AA)


def save_snapshot(frame, landmarks, encoding, timestamp_ns, mirrored):
    directory = ROOT / "captures"
    directory.mkdir(exist_ok=True)
    stem = directory / f"face_{time.time_ns()}"
    if not cv2.imwrite(str(stem.with_suffix(".png")), frame):
        raise OSError("Nao foi possivel salvar a imagem.")
    np.savez_compressed(
        stem.with_suffix(".npz"), landmarks=landmarks, encoding=encoding,
        image_size=np.array((frame.shape[1], frame.shape[0]), np.int32),
        timestamp_ns=np.int64(timestamp_ns), mirrored=np.bool_(mirrored),
    )
    print(f"Imagem e dados salvos: {stem}", flush=True)


def run(args):
    cv2.setNumThreads(1)  # Avoid competing with the model's inference threads.
    model_path = ensure_model(args.model)
    options = vision.FaceLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=vision.RunningMode.VIDEO,
        num_faces=1,  # Enables built-in temporal smoothing.
        output_face_blendshapes=False,
        output_facial_transformation_matrixes=False,
    )
    sequence = last_timestamp_ms = 0
    rgb = xy = None
    selected = 1
    show_indices = False
    fps = 0.0
    previous_time = None

    def select_point(event, x, y, _flags, _data):
        nonlocal selected
        if event == cv2.EVENT_LBUTTONDOWN and xy is not None:
            selected = int(np.argmin(np.sum((xy - (x, y)) ** 2, axis=1)))

    with vision.FaceLandmarker.create_from_options(options) as landmarker:
        with closing(LatestFrame(args.camera, args.width, args.height, args.fps)) as camera:
            deadline = time.monotonic() + 10
            try:
                if not args.check:
                    cv2.namedWindow(WINDOW, cv2.WINDOW_AUTOSIZE)
                    cv2.setMouseCallback(WINDOW, select_point)
                while True:
                    item = camera.read(sequence)
                    if item is None:
                        if time.monotonic() > deadline:
                            raise RuntimeError("Camera sem imagens por 10 segundos.")
                        if not args.check:
                            if cv2.waitKey(1) & 0xFF in (27, ord("q")):
                                break
                            if cv2.getWindowProperty(WINDOW, cv2.WND_PROP_VISIBLE) < 1:
                                break
                        continue
                    sequence, timestamp_ns, frame = item
                    deadline = time.monotonic() + 10
                    height, width = frame.shape[:2]
                    if width > args.width:
                        frame = cv2.resize(frame, (args.width, max(1, round(height * args.width / width))),
                                           interpolation=cv2.INTER_AREA)
                    if not args.no_mirror:
                        frame = cv2.flip(frame, 1)
                    height, width = frame.shape[:2]
                    if rgb is None or rgb.shape != frame.shape:
                        rgb = np.empty_like(frame)
                    cv2.cvtColor(frame, cv2.COLOR_BGR2RGB, dst=rgb)
                    image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                    timestamp_ms = max(timestamp_ns // 1_000_000, last_timestamp_ms + 1)
                    last_timestamp_ms = timestamp_ms
                    started = time.perf_counter()
                    # Synchronous inference keeps landmarks aligned with the exact displayed frame.
                    result = landmarker.detect_for_video(image, timestamp_ms)
                    inference_ms = (time.perf_counter() - started) * 1000
                    now = time.perf_counter()
                    if previous_time is not None:
                        measured_fps = 1 / max(now - previous_time, 1e-6)
                        fps = measured_fps if fps == 0 else 0.9 * fps + 0.1 * measured_fps
                    previous_time = now
                    landmarks = encoding = xy = None
                    lines = [f"{fps:.1f} FPS | IA: {inference_ms:.1f} ms | {width}x{height}"]
                    if result.face_landmarks:
                        landmarks = np.array([(p.x, p.y, p.z) for p in result.face_landmarks[0]],
                                             dtype=np.float32)
                        encoding = encode_landmarks(landmarks, width, height)
                        if not args.check:
                            xy = draw_landmarks(frame, landmarks, selected, show_indices)
                        x, y, z = landmarks[selected]
                        lines.extend((f"{len(landmarks)} pontos | encoder: {encoding.size} float32",
                                      f"Ponto {selected}: x={x:.3f}  y={y:.3f}  z={z:+.3f}"))
                    else:
                        lines.append("Nenhum rosto detectado. Olhe para a camera.")
                    if args.check:
                        print(f"Camera OK: {width}x{height} | IA: {inference_ms:.1f} ms")
                        print(f"Pontos: {0 if landmarks is None else len(landmarks)} | "
                              f"Encoder: {0 if encoding is None else encoding.size}")
                        return
                    draw_hud(frame, lines)
                    cv2.imshow(WINDOW, frame)
                    key = cv2.waitKey(1) & 0xFF
                    if key in (27, ord("q")):
                        break
                    if cv2.getWindowProperty(WINDOW, cv2.WND_PROP_VISIBLE) < 1:
                        break
                    if key == ord("n"):
                        show_indices = not show_indices
                    elif key == ord("s") and landmarks is not None:
                        try:
                            save_snapshot(frame, landmarks, encoding, timestamp_ns, not args.no_mirror)
                        except OSError as error:
                            print(f"Falha ao salvar: {error}", file=sys.stderr)
            finally:
                if not args.check:
                    cv2.destroyAllWindows()


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("O valor deve ser maior que zero.")
    return number


def parse_args():
    parser = argparse.ArgumentParser(description="Encoder facial em tempo real com a webcam.")
    parser.add_argument("--camera", type=int, default=0, help="Indice da webcam (padrao: 0).")
    parser.add_argument("--width", type=positive_int, default=640, help="Largura maxima analisada.")
    parser.add_argument("--height", type=positive_int, default=480, help="Altura solicitada a camera.")
    parser.add_argument("--fps", type=positive_int, default=30, help="FPS solicitado a camera.")
    parser.add_argument("--model", type=Path, default=ROOT / "models" / "face_landmarker.task")
    parser.add_argument("--no-mirror", action="store_true", help="Desativar imagem espelhada.")
    parser.add_argument("--check", action="store_true", help="Analisar um frame e sair, sem janela.")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        run(parse_args())
    except KeyboardInterrupt:
        pass
    except (OSError, RuntimeError, ValueError, cv2.error) as error:
        print(f"Erro: {error}", file=sys.stderr)
        sys.exit(1)
