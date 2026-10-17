"""Run the pinned Fiji launcher headless, capturing stdout+stderr to a file.
Usage: python -I runfiji.py <logfile> <launcher args...>"""
import subprocess, sys, time
FIJI = r"C:\Users\arnav\Documents\dev\Fiji"
JH = FIJI + r"\java\win64\zulu21.42.19-ca-jdk21.0.7-win_x64"
log = sys.argv[1]
args = [FIJI + r"\fiji-windows-x64.exe", "--java-home=" + JH, "--allow-multiple", "--headless"] + sys.argv[2:]
t0 = time.time()
with open(log, "wb") as f:
    try:
        rc = subprocess.run(args, cwd=FIJI, stdout=f, stderr=subprocess.STDOUT, timeout=900).returncode
    except subprocess.TimeoutExpired:
        rc = "TIMEOUT"
print("rc=%s elapsed=%.1fs" % (rc, time.time() - t0))
