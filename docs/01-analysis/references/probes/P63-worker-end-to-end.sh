#!/usr/bin/env bash
# P63: end-to-end wall time of the unmodified prototype speech worker (one subprocess per request,
# as backend/src/fidelis/adapters/speech.py does), plus the cost of its per-call SHA-256 model check.
# Run from the repository root with Git Bash.
set -u
for i in 1 2 3 4 5; do
  s=$(date +%s%N); out=$(.venv-stt/Scripts/python.exe speech/worker.py fixtures/invert-command.wav); e=$(date +%s%N)
  echo "run $i wall_ms=$(( (e - s) / 1000000 )) out=$out"
done
.venv-stt/Scripts/python.exe -I -c "
import hashlib,time,pathlib
p=pathlib.Path('external/models/tiny.en')
t=time.perf_counter()
for n in ['config.json','model.bin','README.md','tokenizer.json','vocabulary.txt']:
    hashlib.file_digest((p/n).open('rb'),'sha256').hexdigest()
print('sha256_all_model_files_s=%.3f' % (time.perf_counter()-t))"
