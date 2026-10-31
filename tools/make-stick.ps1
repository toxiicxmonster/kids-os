# Builds the Kids OS USB installer on Windows. ERASES the target drive.
#
#   powershell -ExecutionPolicy Bypass -File tools\make-stick.ps1 -Drive F -Iso <ubuntu-24.04.x-live-server-amd64.iso> -Session <exported session folder>
#
# The stick is a FAT32 copy of the Ubuntu Server ISO (UEFI + Secure Boot boot it
# as-is) with a "Install Kids OS" boot entry that runs installer\user-data, plus
# kidsos\payload: the setup scripts from the given branch and the parent's
# session (tools/export-session.sh). Needs an elevated PowerShell (for formatting).
param(
    [Parameter(Mandatory)] [string] $Drive,
    [Parameter(Mandatory)] [string] $Iso,
    [Parameter(Mandatory)] [string] $Session,
    [string] $Branch = "stable",
    # Read-only deploy key that lets first boot clone the repo (revoke it after the install).
    [string] $InstallerKey = "",
    # Testing only: allow a mounted virtual disk (VHD) instead of a USB stick.
    [switch] $AllowVirtualDisk
)
$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$Drive = $Drive.TrimEnd(':', '\')

# Refuse anything that isn't a small USB drive.
$disk = Get-Disk -Number (Get-Partition -DriveLetter $Drive).DiskNumber
$allowed = $disk.BusType -eq "USB" -or ($AllowVirtualDisk -and $disk.BusType -eq "File Backed Virtual")
if (-not $allowed -or $disk.Size -gt 128GB) {
    throw "Drive ${Drive}: is not a USB stick of 128 GB or less ($($disk.BusType), $([math]::Round($disk.Size/1GB)) GB); refusing."
}
foreach ($f in "$Session\android-data.tar.zst", "$Session\tablet-movies.json", "$Session\tablet-home.keystore",
               "$Session\images\system.img", "$Session\images\vendor.img") {
    if (-not (Test-Path $f)) { throw "missing session file: $f (run tools/export-session.sh)" }
}

Write-Host "Formatting ${Drive}: as FAT32 'KIDSOS'..."
Format-Volume -DriveLetter $Drive -FileSystem FAT32 -NewFileSystemLabel KIDSOS -Force -Confirm:$false | Out-Null
$root = "${Drive}:\"

Write-Host "Copying the Ubuntu installer..."
$mount = Mount-DiskImage -ImagePath (Resolve-Path $Iso) -PassThru
try {
    $isoRoot = ($mount | Get-Volume).DriveLetter + ":\"
    robocopy $isoRoot $root /E /NFL /NDL /NJH /NJS /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copying the ISO failed (robocopy $LASTEXITCODE)" }
} finally {
    Dismount-DiskImage -ImagePath (Resolve-Path $Iso) | Out-Null
}
Get-ChildItem $root -Recurse -File | ForEach-Object { $_.IsReadOnly = $false }

Write-Host "Adding the 'Install Kids OS' boot entry..."
$grub = Join-Path $root "boot\grub\grub.cfg"
$entry = @'
menuentry "Install Kids OS (asks which disk to ERASE)" {
	set gfxpayload=keep
	linux	/casper/vmlinuz autoinstall ds=nocloud\;s=/cdrom/kidsos/nocloud/ ---
	initrd	/casper/initrd
}
'@
$cfg = Get-Content $grub -Raw
$timeout = "set default=0`nset timeout=10"
$cfg = $cfg -replace 'set timeout=\d+', $timeout
$first = $cfg.IndexOf("menuentry")
$cfg = $cfg.Substring(0, $first) + $entry + "`n" + $cfg.Substring($first)
[IO.File]::WriteAllText($grub, $cfg.Replace("`r`n", "`n"))

Write-Host "Adding the installer settings and payload..."
$nocloud = Join-Path $root "kidsos\nocloud"
$payload = Join-Path $root "kidsos\payload"
New-Item -ItemType Directory -Force $nocloud, $payload | Out-Null
Copy-Item "$repo\installer\user-data", "$repo\installer\meta-data" $nocloud
# The branch as it is on GitHub (stable usually exists only there, not locally).
git -C $repo fetch -q origin $Branch
if ($LASTEXITCODE -ne 0) { throw "couldn't fetch branch $Branch from GitHub" }
$ref = "origin/$Branch"
# Via a file: Windows PowerShell would mangle a binary pipe between native commands.
$archive = Join-Path $env:TEMP "kidsos-setup.tar"
git -C $repo archive --format=tar -o $archive $ref setup CLAUDE.md
if ($LASTEXITCODE -ne 0) { throw "couldn't export branch $Branch" }
tar -x -f $archive -C $payload
if ($LASTEXITCODE -ne 0) { throw "couldn't unpack the setup scripts" }
Remove-Item $archive
robocopy $Session (Join-Path $payload "session") /E /NFL /NDL /NJH /NJS /R:1 /W:1 | Out-Null
if ($LASTEXITCODE -ge 8) { throw "copying the session failed (robocopy $LASTEXITCODE)" }
if ($InstallerKey) {
    # First boot clones the latest setup from GitHub with this; without it, it uses the copy above.
    Copy-Item $InstallerKey (Join-Path $payload "session\installer_key")
}

$commit = git -C $repo rev-parse --short $ref
Set-Content (Join-Path $root "kidsos\README.txt") @"
Kids OS installer ($Branch @ $commit, built $(Get-Date -Format 'yyyy-MM-dd HH:mm')).
Boot the Dell from this stick (UEFI), choose "Install Kids OS", then:
  1. pick the network (Wi-Fi works), 2. pick the disk to ERASE, 3. create the parent login.
After the install it reboots and sets itself up (30-60 min, needs internet), then starts Kids OS.
This stick holds sign-ins (Google, Plex). Keep it safe, or wipe it after installing.
"@
Write-Host "Done: ${Drive}: is the Kids OS installer ($Branch @ $commit)."
