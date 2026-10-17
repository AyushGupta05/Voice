# P51 -- capture ONLY windows owned by a given Fiji process tree (agent G4, 2026-10-17).
# Privacy rule: never capture the desktop or windows of other processes.
#  * mode "list"    : print visible top-level windows owned by RootPid and its descendants.
#  * mode "capture" : for each matching window: move fully on-screen if needed, bring to front, verify with
#                     WindowFromPoint on a grid that every sampled point belongs to the Fiji process tree,
#                     then CopyFromScreen over the DWM extended frame bounds (excludes the invisible
#                     resize border and drop shadow). A window failing the check is REFUSED (not saved).
#  * mode "menu"    : bring the Fiji main window to the front, open top-level menu number MenuIndex
#                     with the keyboard (F10, Right x n, Down), then CopyFromScreen over the union of the
#                     main window and the popup-menu window(s) -- ONLY after verifying with
#                     WindowFromPoint on a grid of points that every sampled pixel belongs to the Fiji
#                     process tree; otherwise nothing is saved.
param(
  [Parameter(Mandatory=$true)][int]$RootPid,
  [ValidateSet("list","capture","printwindow","menu")][string]$Mode = "list",
  [string]$OutDir = ".",
  [string]$Prefix = "",
  [string]$TitleRegex = ".*",
  [int]$MenuIndex = 0,
  [string]$MenuName = "menu",
  [int]$TrimW = 0,
  [int]$TrimH = 0,
  [int]$TrimX = 0
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class W {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT p);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT r, int size);
  public static List<IntPtr> All() {
    var list = new List<IntPtr>();
    EnumWindows((h, l) => { list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }
  public static string Text(IntPtr h) { var s = new StringBuilder(512); GetWindowText(h, s, 512); return s.ToString(); }
  public static string Cls(IntPtr h) { var s = new StringBuilder(256); GetClassName(h, s, 256); return s.ToString(); }
  public static uint Pid(IntPtr h) { uint p; GetWindowThreadProcessId(h, out p); return p; }
  public static RECT Frame(IntPtr h) {
    RECT r; if (DwmGetWindowAttribute(h, 9, out r, Marshal.SizeOf(typeof(RECT))) != 0) GetWindowRect(h, out r); return r;
  }
  public static RECT Raw(IntPtr h) { RECT r; GetWindowRect(h, out r); return r; }
}
"@
[void][W]::SetProcessDPIAware()

# Fiji process tree (launcher + descendants).
$all = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name
$pids = New-Object System.Collections.Generic.HashSet[uint32]
[void]$pids.Add([uint32]$RootPid)
do {
  $added = $false
  foreach ($p in $all) { if ($pids.Contains([uint32]$p.ParentProcessId) -and -not $pids.Contains([uint32]$p.ProcessId)) { [void]$pids.Add([uint32]$p.ProcessId); $added = $true } }
} while ($added)

function Get-FijiWindows {
  $res = @()
  foreach ($h in [W]::All()) {
    if (-not [W]::IsWindowVisible($h)) { continue }
    $pid_ = [W]::Pid($h)
    if (-not $pids.Contains($pid_)) { continue }
    $f = [W]::Frame($h)
    if (($f.R - $f.L) -lt 20 -or ($f.B - $f.T) -lt 20) { continue }
    $res += [pscustomobject]@{ Handle=$h; Pid=$pid_; Title=[W]::Text($h); Class=[W]::Cls($h); L=$f.L; T=$f.T; R=$f.R; B=$f.B }
  }
  return $res
}

function Test-RegionOwned($L, $T, $R, $B) {
  # Sample a grid; every sampled point must resolve to a window of the Fiji process tree.
  $bad = 0
  for ($x = $L + 2; $x -lt $R - 1; $x += [Math]::Max(4, [int](($R - $L) / 24))) {
    for ($y = $T + 2; $y -lt $B - 1; $y += [Math]::Max(4, [int](($B - $T) / 24))) {
      $pt = New-Object W+POINT; $pt.X = $x; $pt.Y = $y
      $h = [W]::WindowFromPoint($pt)
      if ($h -eq [IntPtr]::Zero -or -not $pids.Contains([W]::Pid($h))) { $bad++ }
    }
  }
  return $bad
}

function Safe-Name($s) { ($s -replace '[^A-Za-z0-9]+', '-').Trim('-').ToLower() }

New-Item -ItemType Directory -Force $OutDir | Out-Null
$wins = Get-FijiWindows
"pids: " + (($pids | ForEach-Object { $_ }) -join ",")

if ($Mode -eq "list") {
  $wins | Format-Table Handle, Pid, Class, L, T, R, B, Title -AutoSize | Out-String -Width 300
  exit 0
}

if ($Mode -eq "capture") {
  # Foreground each Fiji window, move it fully on-screen if needed, verify that every sampled point of its
  # frame rectangle belongs to the Fiji process tree, then copy exactly that rectangle from the screen.
  # (PrintWindow(PW_RENDERFULLCONTENT) was tried first and rendered AWT/Java2D content incompletely and
  #  unscaled at 150 % display scaling, so it is not used.)
  $scr = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  foreach ($w in $wins) {
    if ($w.Title -notmatch $TitleRegex) { continue }
    $raw = [W]::Raw($w.Handle)
    $dx = 0; $dy = 0
    if ($w.R -gt $scr.Right) { $dx = $scr.Right - $w.R }
    if ($w.L + $dx -lt $scr.Left) { $dx = $scr.Left - $w.L }
    if ($w.B -gt $scr.Bottom) { $dy = $scr.Bottom - $w.B }
    if ($w.T + $dy -lt $scr.Top) { $dy = $scr.Top - $w.T }
    if ($dx -ne 0 -or $dy -ne 0) {
      [void][W]::SetWindowPos($w.Handle, [IntPtr]::Zero, $raw.L + $dx, $raw.T + $dy, 0, 0, 0x0015)  # NOSIZE|NOZORDER|NOACTIVATE
      Start-Sleep -Milliseconds 400
    }
    [W]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [W]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)  # Alt tap so SetForegroundWindow is honoured
    [void][W]::SetForegroundWindow($w.Handle)
    Start-Sleep -Milliseconds 900
    $f = [W]::Frame($w.Handle)
    $bad = Test-RegionOwned $f.L $f.T $f.R $f.B
    if ($bad -gt 0) { "REFUSED '$($w.Title)': $bad sampled points not owned by Fiji"; continue }
    $cw = $f.R - $f.L; $ch = $f.B - $f.T
    $bmp = New-Object System.Drawing.Bitmap $cw, $ch
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($f.L, $f.T, 0, 0, $bmp.Size)
    $g.Dispose()
    $name = if ($w.Title) { Safe-Name $w.Title } else { "untitled-" + $w.Class.ToLower() }
    $file = Join-Path $OutDir ($Prefix + $name + ".png")
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    "captured ${cw}x${ch} '$($w.Title)' -> $file"
  }
  exit 0
}

if ($Mode -eq "printwindow") {
  # Method A used for the committed screenshots: Win32 PrintWindow(PW_RENDERFULLCONTENT) renders ONLY the given
  # window's own surface into a memory bitmap -- no screen pixels are read, so no other window or the desktop can
  # appear. Works while the interactive desktop is behind the screen saver. Cropped to DWM extended frame bounds.
  foreach ($w in $wins) {
    if ($w.Title -notmatch $TitleRegex) { continue }
    $raw = [W]::Raw($w.Handle)
    $rw = $raw.R - $raw.L; $rh = $raw.B - $raw.T
    $bmp = New-Object System.Drawing.Bitmap $rw, $rh
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $g.GetHdc()
    $ok = [W]::PrintWindow($w.Handle, $hdc, 2)
    $g.ReleaseHdc($hdc); $g.Dispose()
    if ($TrimW -gt 0) {
      # Java's AWT answers WM_PRINT at 100 % scale while the window is 150 % on screen, so the rendered
      # window occupies only the top-left TrimW x TrimH pixels (Java logical window size) of the bitmap.
      $cx = $TrimX; $cy = 0; $cw = [Math]::Min($rw, $TrimW) - $TrimX; $ch = [Math]::Min($rh, $TrimH)
    } else { $cx = $w.L - $raw.L; $cy = $w.T - $raw.T; $cw = $w.R - $w.L; $ch = $w.B - $w.T }
    $crop = $bmp.Clone((New-Object System.Drawing.Rectangle $cx, $cy, $cw, $ch), $bmp.PixelFormat)
    $name = if ($w.Title) { Safe-Name $w.Title } else { "untitled-" + $w.Class.ToLower() }
    $file = Join-Path $OutDir ($Prefix + $name + ".png")
    $crop.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $crop.Dispose(); $bmp.Dispose()
    "printwindow ok=$ok raw=${rw}x${rh} saved=${cw}x${ch} '$($w.Title)' -> $file"
  }
  exit 0
}

if ($Mode -eq "menu") {
  $main = $wins | Where-Object { $_.Class -eq "SunAwtFrame" -and ($_.Title -match "ImageJ|Fiji") } | Select-Object -First 1
  if (-not $main) { "main window not found"; exit 2 }
  [W]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [W]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)  # Alt tap: lets SetForegroundWindow succeed
  [void][W]::SetForegroundWindow($main.Handle)
  Start-Sleep -Milliseconds 600
  if ([W]::GetForegroundWindow() -ne $main.Handle) { "could not bring main window to front"; exit 3 }
  [System.Windows.Forms.SendKeys]::SendWait("{F10}")
  Start-Sleep -Milliseconds 300
  for ($i = 0; $i -lt $MenuIndex; $i++) { [System.Windows.Forms.SendKeys]::SendWait("{RIGHT}"); Start-Sleep -Milliseconds 120 }
  [System.Windows.Forms.SendKeys]::SendWait("{DOWN}")
  Start-Sleep -Milliseconds 900
  $wins = Get-FijiWindows
  $popups = @($wins | Where-Object { $_.Class -eq "#32768" })
  "popups: $($popups.Count)"
  $L = $main.L; $T = $main.T; $R = $main.R; $B = $main.B
  foreach ($p in $popups) { $L = [Math]::Min($L, $p.L); $T = [Math]::Min($T, $p.T); $R = [Math]::Max($R, $p.R); $B = [Math]::Max($B, $p.B) }
  # Only the parts that are covered by Fiji windows may be kept: capture main window and each popup separately
  # (union rectangle may include desktop), then compose them on a neutral background.
  $canvas = New-Object System.Drawing.Bitmap ($R - $L), ($B - $T)
  $g = [System.Drawing.Graphics]::FromImage($canvas)
  $g.Clear([System.Drawing.Color]::FromArgb(255, 128, 128, 128))
  $regions = @($main) + $popups
  $refused = 0
  foreach ($r in $regions) {
    $bad = Test-RegionOwned $r.L $r.T $r.R $r.B
    if ($bad -gt 0) { "refusing region '$($r.Title)' class=$($r.Class): $bad sampled points not owned by Fiji"; $refused++; continue }
    $part = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
    $pg = [System.Drawing.Graphics]::FromImage($part)
    $pg.CopyFromScreen($r.L, $r.T, 0, 0, $part.Size)
    $pg.Dispose()
    $g.DrawImage($part, $r.L - $L, $r.T - $T)
    $part.Dispose()
  }
  $g.Dispose()
  [System.Windows.Forms.SendKeys]::SendWait("{ESC}"); Start-Sleep -Milliseconds 150
  [System.Windows.Forms.SendKeys]::SendWait("{ESC}"); Start-Sleep -Milliseconds 150
  [System.Windows.Forms.SendKeys]::SendWait("{ESC}")
  if ($refused -eq 0 -and $popups.Count -gt 0) {
    $file = Join-Path $OutDir ($Prefix + $MenuName + ".png")
    $canvas.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    "captured menu -> $file"
  } else { "menu capture not saved (refused=$refused popups=$($popups.Count))" }
  $canvas.Dispose()
  exit 0
}
