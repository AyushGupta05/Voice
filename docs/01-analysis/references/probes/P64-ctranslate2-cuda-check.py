"""P64: does the installed ctranslate2 4.8.3 wheel run Whisper on the local NVIDIA GPU without
extra NVIDIA libraries? Run: .venv-stt/Scripts/python.exe -I <this file> (repository root)."""
import pathlib, time, ctranslate2
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio
print("ctranslate2", ctranslate2.__version__, "cuda_device_count", ctranslate2.get_cuda_device_count())
try:
    print("cuda compute types", ctranslate2.get_supported_compute_types("cuda"))
except Exception as e:
    print("cuda compute types ERROR", type(e).__name__, e)
try:
    t = time.perf_counter()
    m = WhisperModel(str(pathlib.Path("external/models/tiny.en")), device="cuda", compute_type="float16", local_files_only=True)
    load = time.perf_counter() - t
    a = decode_audio("fixtures/invert-command.wav", sampling_rate=16000)
    t = time.perf_counter(); segs, _ = m.transcribe(a, language="en", beam_size=5); text = " ".join(s.text for s in segs)
    print("cuda ok load_s=%.3f transcribe_s=%.3f text=%r" % (load, time.perf_counter() - t, text))
except Exception as e:
    print("cuda run ERROR", type(e).__name__, str(e)[:400])
