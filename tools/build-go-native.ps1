# Windows cross-build for the Go daemons. proot/FFmpeg still use native/build-all.sh on Linux.
param([string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/dev.ps1"
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:GOPATH = "$projectRoot/.tools/go-cache"
$env:GOCACHE = "$projectRoot/.tools/go-build-cache"
$work = "$projectRoot/native/.work"
# Applies a patch unless it already is. The reverse check reports on stderr when it isn't,
# which Windows PowerShell would otherwise turn into a terminating error.
function Apply-Patch([string]$file, [string]$what) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        git apply --reverse --check $file 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            git apply $file
            if ($LASTEXITCODE -ne 0) { throw "$what patch failed" }
        }
    } finally { $ErrorActionPreference = $old }
}
New-Item -ItemType Directory -Force -Path $work | Out-Null
$sources = @(
    @{ Name='sshd'; Dir="$projectRoot/native/sshd"; Package='.'; Tags=''; Flags='' },
    @{ Name='caddy'; Repo='https://github.com/caddyserver/caddy.git'; Tag='v2.11.4'; Package='./cmd/caddy'; Tags=''; Flags='' },
    @{ Name='cloudflared'; Repo='https://github.com/cloudflare/cloudflared.git'; Tag='2026.9.3'; Package='./cmd/cloudflared'; Tags=''; Flags='-X main.Version=2026.9.3' },
    @{ Name='tailscaled'; Repo='https://github.com/tailscale/tailscale.git'; Tag='v1.102.5'; Package='./cmd/tailscaled';
       Tags='ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,ts_omit_ssh,ts_omit_wakeonlan,ts_omit_capture,ts_omit_relayserver,ts_omit_systray,ts_omit_taildrop,ts_omit_tpm,ts_omit_desktop_sessions';
       Flags='-X tailscale.com/version.longStamp=1.102.5 -X tailscale.com/version.shortStamp=1.102.5' }
)
foreach ($src in $sources) {
    if ($src.Repo) {
        $src.Dir = "$work/$($src.Name)-$($src.Tag)"
        if (!(Test-Path -LiteralPath "$($src.Dir)/go.mod")) {
            git clone --quiet --depth 1 --branch $src.Tag $src.Repo $src.Dir
            if ($LASTEXITCODE -ne 0) { throw "Could not fetch $($src.Name)" }
        }
    }
    Push-Location $src.Dir
    try {
        if ($src.Name -eq 'caddy') { Apply-Patch "$projectRoot/native/patches/caddy-cel-v2.patch" 'Caddy CEL compatibility' }
        if ($src.Name -eq 'tailscaled') { Apply-Patch "$projectRoot/native/patches/tailscale-local-port-map.patch" 'Tailscale local port map' }
        $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'; $env:GOARM = ''
        $pins = @(Get-Content "$projectRoot/native/go-security-deps.txt" | Where-Object { $_ -match "^$($src.Name) " } | ForEach-Object { ($_ -split ' ')[1] })
        if ($pins.Count) {
            go get @pins
            if ($LASTEXITCODE -ne 0) { throw "Security dependency update failed: $($src.Name)" }
        }
        foreach ($abi in $Abis) {
            $triple = switch ($abi) {
                'arm64-v8a' { $env:GOARCH='arm64'; 'aarch64-linux-android29' }
                'armeabi-v7a' { $env:GOARCH='arm'; $env:GOARM='7'; 'armv7a-linux-androideabi29' }
                'x86_64' { $env:GOARCH='amd64'; 'x86_64-linux-android29' }
                default { throw "Unsupported ABI: $abi" }
            }
            $env:GOOS='android'; $env:CGO_ENABLED='1'
            $env:CC = "$env:ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe --target=$triple"
            Write-Host "Building $($src.Name) ($abi)"
            go build -mod=mod -trimpath -buildvcs=false "-tags=$($src.Tags)" "-ldflags=-s -w $($src.Flags)" -o "$projectRoot/app/src/main/jniLibs/$abi/lib$($src.Name).so" $src.Package
            if ($LASTEXITCODE -ne 0) { throw "Native build failed: $($src.Name) ($abi)" }
        }
    } finally { Pop-Location }
}
