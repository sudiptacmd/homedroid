#!/bin/sh
# Homedroid's helper for the media automation apps: Prowlarr, Radarr, Sonarr and Seerr.
# Written into Alpine by Homedroid on every start; edits are overwritten.
#
#   media-stack install <app>   download the pinned build and write its first settings
#   media-stack run <app>       start the app and connect it to the others
#   media-stack link            connect everything that is installed and running (safe to repeat)
#   media-stack seerr-link      wait for Seerr's first-run setup, then add Radarr and Sonarr to it
#   media-stack libraries       add the Movies and Shows libraries to Jellyfin
#   media-stack keys            show the API keys
#
# Connections made, each only once and only if missing, so changes made in the apps are kept:
#   Radarr, Sonarr  -> qBittorrent (download client), /media/Movies or /media/Shows (root folder)
#   Prowlarr        -> Radarr and Sonarr (indexer sync), qBittorrent (download client)
#   Seerr           -> Radarr and Sonarr (requests), after its setup wizard
#   Jellyfin        -> "Movies" and "Shows" libraries on the same folders
set -u

# MEDIA_STACK_ROOT puts the files somewhere else, for the tests (tests/media-stack.test.cjs).
R=${MEDIA_STACK_ROOT:-}
MEDIA=/media
QBT=http://127.0.0.1:8081

port() { case $1 in prowlarr) echo 9696 ;; radarr) echo 7878 ;; sonarr) echo 8989 ;; esac; }
api() { case $1 in prowlarr) echo v1 ;; *) echo v3 ;; esac; }
title() { case $1 in prowlarr) echo Prowlarr ;; radarr) echo Radarr ;; sonarr) echo Sonarr ;; esac; }
key() { sed -n 's:.*<ApiKey>\(.*\)</ApiKey>.*:\1:p' "$R/var/lib/$1/config.xml" 2>/dev/null; }
installed() { [ -f "$R/opt/$(title "$1")/$(title "$1").dll" ] && [ -s "$R/var/lib/$1/config.xml" ]; }
log() { echo "media-stack: $*"; }

# Pinned upstream builds for musl (Alpine), checked against their SHA-256. Sonarr is version 5,
# Sonarr's test release: version 4 is built on .NET 6, which can't start inside Android's app
# sandbox ("Failed to create CoreCLR"), and running it on a newer .NET breaks its queue.
build() { # app arch -> "url sha256", empty if there is no build for this CPU
  case $1/$2 in
  prowlarr/aarch64) v=3.1.0.5799; echo "https://github.com/Prowlarr/Prowlarr/releases/download/v$v/Prowlarr.master.$v.linux-musl-core-arm64.tar.gz 39b4c106ea0540204954f1f61570635b17c151009de679ef76a4ee1e718d3aec" ;;
  prowlarr/armv7)   v=3.1.0.5799; echo "https://github.com/Prowlarr/Prowlarr/releases/download/v$v/Prowlarr.master.$v.linux-musl-core-arm.tar.gz be4525546a968188319b89c841a0f2ca26b304905d603aa41d6553e761de925a" ;;
  prowlarr/x86_64)  v=3.1.0.5799; echo "https://github.com/Prowlarr/Prowlarr/releases/download/v$v/Prowlarr.master.$v.linux-musl-core-x64.tar.gz 7d00d1949deed6394d2aef6cb7f9e8f4a8fd4e78012b88a773e82917fd62387f" ;;
  radarr/aarch64)   v=7.0.3.10918; echo "https://github.com/Radarr/Radarr/releases/download/v$v/Radarr.master.$v.linux-musl-core-arm64.tar.gz 26eabc13de4b6179bbe0831992799e4d868ef3eb5f9503ea070fb5845054fdff" ;;
  radarr/armv7)     v=7.0.3.10918; echo "https://github.com/Radarr/Radarr/releases/download/v$v/Radarr.master.$v.linux-musl-core-arm.tar.gz ca236581cc39a8ce7da8342a57ac52ed7bd6982e845fbc3db4f35c0ca09fb14e" ;;
  radarr/x86_64)    v=7.0.3.10918; echo "https://github.com/Radarr/Radarr/releases/download/v$v/Radarr.master.$v.linux-musl-core-x64.tar.gz 14a1dc7737dd57955f1136a3fc90e0735f48c5eda7823ecb59eb00b5a9f8ecc7" ;;
  sonarr/aarch64)   v=5.0.0.3041; echo "https://github.com/Sonarr/Sonarr/releases/download/v$v/Sonarr.develop.$v.linux-musl-arm64.tar.gz 04ee911be5bcc6cc4538d8e063fbcaa8789509a41301e59365d5b36436ff1e73" ;;
  sonarr/x86_64)    v=5.0.0.3041; echo "https://github.com/Sonarr/Sonarr/releases/download/v$v/Sonarr.develop.$v.linux-musl-x64.tar.gz b408c83f3aa33c8616fe0ac9c479ac6036b6928d54d381c19cf4fd57158834d3" ;;
  esac
}

install_app() {
  app=$1 name=$(title "$1")
  set -- $(build "$app" "$(apk --print-arch)")
  [ $# -eq 2 ] || { echo "$name has no build for this phone's CPU"; exit 1; }
  url=$1 sum=$2
  apk add --no-cache icu-libs sqlite-libs ca-certificates curl jq >/dev/null || { echo "Installing $name's libraries failed"; exit 1; }
  if [ "$(cat "$R/opt/$name/.homedroid-build" 2>/dev/null)" != "$sum" ]; then
    log "downloading $name…"
    # Everything is prepared beside the installed copy, which is only replaced once the new one
    # is complete; the build marker is written last.
    tmp=$R/opt/.$app-new
    fail() { echo "$1"; rm -rf "$tmp"; exit 1; }
    rm -rf "$tmp"; mkdir -p "$tmp" || fail "Can't write to /opt"
    wget -q -O "$tmp/app.tgz" "$url" || fail "Download failed: $url"
    echo "$sum  $tmp/app.tgz" | sha256sum -c - >/dev/null || fail "Checksum mismatch for $url"
    tar xzf "$tmp/app.tgz" -C "$tmp" || fail "Unpacking $name failed (is the phone out of space?)"
    rm -f "$tmp/app.tgz"
    [ -f "$tmp/$name/$name.dll" ] || fail "$name's download is incomplete"
    rm -rf "$R/opt/$name.old"
    if [ -e "$R/opt/$name" ]; then mv "$R/opt/$name" "$R/opt/$name.old" || fail "Can't replace $name"; fi
    if ! mv "$tmp/$name" "$R/opt/$name"; then
      [ -e "$R/opt/$name.old" ] && mv "$R/opt/$name.old" "$R/opt/$name"
      fail "Can't replace $name"
    fi
    printf '%s' "$sum" > "$R/opt/$name/.homedroid-build"
    rm -rf "$tmp" "$R/opt/$name.old"
  fi
  mkdir -p "$R/var/lib/$app"
  # First settings only on first install, so the user's own are kept. Logins use a form; the
  # app asks for a username and password on the first visit. Updates come with Homedroid.
  if [ ! -s "$R/var/lib/$app/config.xml" ]; then
    # It holds the API key: readable by its owner only, and in place only once complete.
    ( umask 077; cat > "$R/var/lib/$app/config.xml.new" ) <<XML
<Config>
  <BindAddress>*</BindAddress>
  <Port>$(port "$app")</Port>
  <SslPort>$(($(port "$app") + 1000))</SslPort>
  <EnableSsl>False</EnableSsl>
  <UrlBase></UrlBase>
  <ApiKey>$(tr -dc a-f0-9 < /dev/urandom 2>/dev/null | head -c 32)</ApiKey>
  <AuthenticationMethod>Forms</AuthenticationMethod>
  <AuthenticationRequired>Enabled</AuthenticationRequired>
  <LaunchBrowser>False</LaunchBrowser>
  <UpdateMechanism>External</UpdateMechanism>
  <UpdateMechanismMessage>Updated with Homedroid: Apps, then Update.</UpdateMechanismMessage>
  <AnalyticsEnabled>False</AnalyticsEnabled>
  <LogLevel>info</LogLevel>
</Config>
XML
    mv "$R/var/lib/$app/config.xml.new" "$R/var/lib/$app/config.xml" || { echo "Can't write $name's settings"; exit 1; }
  fi
  case $app in
  radarr) mkdir -p "$R$MEDIA/Movies" "$R$MEDIA/Downloads" ;;
  sonarr) mkdir -p "$R$MEDIA/Shows" "$R$MEDIA/Downloads" ;;
  esac
  jellyfin_libraries
}

# Jellyfin libraries for the folders Radarr and Sonarr fill. Each is added once, so a user who
# removes one in Jellyfin keeps it removed. Jellyfin reads them on its next start.
jellyfin_libraries() {
  [ -x "$R/usr/bin/jellyfin" ] || return 0
  for lib in Movies:movies:radarr Shows:tvshows:sonarr; do
    lname=${lib%%:*} rest=${lib#*:}; ltype=${rest%%:*} lapp=${rest#*:}
    installed "$lapp" || continue
    mark=$R/var/lib/jellyfin/.library-$lname
    [ -e "$mark" ] && continue
    dir=$R/var/lib/jellyfin/root/default/$lname
    # A library the user already has under this name is left as it is.
    if [ ! -e "$dir" ]; then
      new=$R/var/lib/jellyfin/root/default/.$lname.new
      rm -rf "$new"
      mkdir -p "$new" "$R$MEDIA/$lname" &&
      printf '%s' "$MEDIA/$lname" > "$new/$lname.mblink" &&
      : > "$new/$ltype.collection" &&
      cat > "$new/options.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<LibraryOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <Enabled>true</Enabled>
  <EnableRealtimeMonitor>true</EnableRealtimeMonitor>
  <EnableChapterImageExtraction>false</EnableChapterImageExtraction>
  <ExtractChapterImagesDuringLibraryScan>false</ExtractChapterImagesDuringLibraryScan>
  <EnableTrickplayImageExtraction>false</EnableTrickplayImageExtraction>
  <ExtractTrickplayImagesDuringLibraryScan>false</ExtractTrickplayImagesDuringLibraryScan>
  <PathInfos>
    <MediaPathInfo>
      <Path>$MEDIA/$lname</Path>
    </MediaPathInfo>
  </PathInfos>
  <EnableAutomaticSeriesGrouping>true</EnableAutomaticSeriesGrouping>
  <SeasonZeroDisplayName>Specials</SeasonZeroDisplayName>
  <AutomaticRefreshIntervalDays>0</AutomaticRefreshIntervalDays>
</LibraryOptions>
XML
      if [ -s "$new/options.xml" ] && [ -s "$new/$lname.mblink" ] && mv "$new" "$dir"; then :; else
        rm -rf "$new"; continue
      fi
    fi
    touch "$mark"
  done
}

# --- HTTP helpers --------------------------------------------------------------------------
call() { # app method path [json]
  url="http://127.0.0.1:$(port "$1")/api/$(api "$1")$3"
  if [ $# -ge 4 ]; then
    curl -fsS -m 30 -X "$2" -H "X-Api-Key: $(key "$1")" -H 'Content-Type: application/json' --data-binary "$4" "$url"
  else
    curl -fsS -m 30 -X "$2" -H "X-Api-Key: $(key "$1")" "$url"
  fi
}
up() { installed "$1" && call "$1" GET /system/status >/dev/null 2>&1; }
wait_up() { i=0; until up "$1"; do i=$((i + 1)); [ $i -gt 120 ] && return 1; sleep 5; done; }
qbt_up() { [ -x "$R/usr/bin/qbittorrent-nox" ] && curl -fsS -m 5 -o /dev/null "$QBT/api/v2/app/version" 2>/dev/null; }

# A provider from the app's schema with the given fields set, as JSON. $4 is a jq object.
from_schema() { # app path implementation fields-json name
  call "$1" GET "$2/schema" | jq -c --arg impl "$3" --argjson set "$4" --arg name "$5" '
    [.[] | select(.implementation == $impl)][0]
    | .name = $name | .enable = true
    | .fields = [.fields[] | if $set[.name] != null then .value = $set[.name] else . end]'
}
has_named() { call "$1" GET "$2" | jq -e --arg n "$3" 'any(.[]; .name == $n)' >/dev/null; }

# Each connection is made once: a marker in the app's folder records it, so one the user removes
# in the app stays removed. Steps return 0 when connected (or already there), 2 when it can't be
# done yet (the other app isn't installed or running) and 1 on errors; only 0 is recorded.
once() { # app what step...
  m="$R/var/lib/$1/.homedroid-linked-$2"
  [ -e "$m" ] && return 0
  shift 2
  "$@" && touch "$m"
  return 0
}

add_qbittorrent() { # app category-field category
  qbt_up || return 2
  has_named "$1" /downloadclient qBittorrent && return 0
  body=$(from_schema "$1" /downloadclient QBittorrent \
    "{\"host\":\"127.0.0.1\",\"port\":8081,\"useSsl\":false,\"username\":\"\",\"password\":\"\",\"$2\":\"$3\"}" qBittorrent) || return 1
  call "$1" POST '/downloadclient?forceSave=true' "$body" >/dev/null || return 1
  log "$(title "$1") now sends downloads to qBittorrent"
}

add_root() { # app folder
  mkdir -p "$R$2"
  call "$1" GET /rootfolder | jq -e --arg p "$2" 'any(.[]; .path == $p or .path == ($p + "/"))' >/dev/null && return 0
  call "$1" POST /rootfolder "{\"path\":\"$2\"}" >/dev/null || return 1
  log "$(title "$1") keeps its library in $2"
}

add_to_prowlarr() { # radarr|sonarr
  up "$1" || return 2
  has_named prowlarr /applications "$(title "$1")" && return 0
  body=$(from_schema prowlarr /applications "$(title "$1")" \
    "{\"prowlarrUrl\":\"http://127.0.0.1:9696\",\"baseUrl\":\"http://127.0.0.1:$(port "$1")\",\"apiKey\":\"$(key "$1")\"}" "$(title "$1")") || return 1
  body=$(printf '%s' "$body" | jq -c '.syncLevel = "fullSync"')
  call prowlarr POST '/applications?forceSave=true' "$body" >/dev/null || return 1
  log "Prowlarr now gives its indexers to $(title "$1")"
}

# One pass at a time: every app starts its own, often at the same moment, and two passes could
# each add the same connection.
link() {
  mkdir -p "$R/var/lib"
  ( flock 9 && link_now ) 9>"$R/var/lib/.media-stack.lock"
  return 0
}

link_now() {
  if up radarr; then
    once radarr qbittorrent add_qbittorrent radarr movieCategory radarr
    once radarr root add_root radarr "$MEDIA/Movies"
  fi
  if up sonarr; then
    once sonarr qbittorrent add_qbittorrent sonarr tvCategory tv-sonarr
    once sonarr root add_root sonarr "$MEDIA/Shows"
  fi
  if up prowlarr; then
    once prowlarr qbittorrent add_qbittorrent prowlarr category prowlarr
    once prowlarr radarr add_to_prowlarr radarr
    once prowlarr sonarr add_to_prowlarr sonarr
  fi
  jellyfin_libraries
  return 0
}

run_app() {
  app=$1 name=$(title "$1")
  installed "$app" || { echo "$name is not installed"; exit 1; }
  echo "Homedroid media stack: set your username and password on the first visit. Homedroid connects $name to the other media apps for you."
  # Connect once the app answers, then look again every few minutes: for apps installed or
  # started later, and to retry anything that failed. Done connections cost one status check.
  ( wait_up "$app" && while :; do link; sleep 300; done ) &
  exec "$R/opt/$name/$name" -nobrowser -data="$R/var/lib/$app"
}

# --- Seerr ---------------------------------------------------------------------------------
# Seerr's settings live in its own folder, mounted here at /var/lib/seerr. Its API key only works
# after its setup wizard (sign in with Jellyfin), so this waits for that, then adds Radarr and
# Sonarr unless the wizard already did.
seerr() { # method path [json]
  k=$(jq -r '.main.apiKey // empty' "$R/var/lib/seerr/settings.json" 2>/dev/null)
  [ -n "$k" ] || return 1
  if [ $# -ge 3 ]; then
    curl -fsS -m 30 -X "$1" -H "X-Api-Key: $k" -H 'Content-Type: application/json' --data-binary "$3" "http://127.0.0.1:5055/api/v1$2"
  else
    curl -fsS -m 30 -X "$1" -H "X-Api-Key: $k" "http://127.0.0.1:5055/api/v1$2"
  fi
}
profile() { # app -> "id name": HD-1080p if it exists, else the first profile
  call "$1" GET /qualityprofile | jq -r '(map(select(.name == "HD-1080p")) + .)[0] | "\(.id) \(.name)"'
}
seerr_add() { # radarr|sonarr folder
  sa=$1 dir=$2
  [ -e "$R/var/lib/seerr/.homedroid-linked-$sa" ] && return 0
  up "$sa" || return 2
  out=$(seerr GET "/settings/$sa") || return 1
  n=$(printf '%s' "$out" | jq -e 'if type == "array" then length else error("not a list") end') || return 1
  if [ "$n" != 0 ]; then touch "$R/var/lib/seerr/.homedroid-linked-$sa"; return 0; fi
  prof=$(profile "$sa") || return 1
  pid=${prof%% *} pname=${prof#* }
  body=$(jq -nc --arg n "$(title "$sa")" --argjson port "$(port "$sa")" --arg key "$(key "$sa")" \
    --argjson pid "$pid" --arg pname "$pname" --arg dir "$dir" '{
      name: $n, hostname: "127.0.0.1", port: $port, apiKey: $key, useSsl: false, baseUrl: "",
      activeProfileId: $pid, activeProfileName: $pname, activeDirectory: $dir, is4k: false,
      isDefault: true, externalUrl: "", syncEnabled: true, preventSearch: false, tags: [],
      minimumAvailability: "released", enableSeasonFolders: true, animeTags: [],
      seriesType: "standard", animeSeriesType: "anime"}') || return 1
  seerr POST "/settings/$sa" "$body" >/dev/null || return 1
  touch "$R/var/lib/seerr/.homedroid-linked-$sa"
  log "Seerr now sends requests to $(title "$sa")"
}
seerr_link() {
  apk info -e curl jq >/dev/null 2>&1 || apk add --no-cache curl jq >/dev/null
  echo "Homedroid media stack: open Seerr, choose Jellyfin and sign in at 127.0.0.1, port 8096. Radarr and Sonarr are added after that."
  while :; do
    if [ "$(seerr GET /settings/public 2>/dev/null | jq -r .initialized)" = true ]; then
      ok=1
      if installed radarr; then seerr_add radarr "$MEDIA/Movies" || ok=0; fi
      if installed sonarr; then seerr_add sonarr "$MEDIA/Shows" || ok=0; fi
      [ $ok = 1 ] && break
    fi
    sleep 30
  done
  # Stay up so the app counts as running; check again now and then for newly installed apps.
  while sleep 3600; do
    if installed radarr; then seerr_add radarr "$MEDIA/Movies"; fi
    if installed sonarr; then seerr_add sonarr "$MEDIA/Shows"; fi
  done
}

keys() {
  for a in prowlarr radarr sonarr; do installed "$a" && echo "$(title "$a"): $(key "$a")"; done
  k=$(jq -r '.main.apiKey // empty' "$R/var/lib/seerr/settings.json" 2>/dev/null) && [ -n "$k" ] && echo "Seerr: $k"
  return 0
}

case ${1:-} in
install) install_app "$2" ;;
run) run_app "$2" ;;
link) link ;;
libraries) jellyfin_libraries ;;
seerr-link) seerr_link ;;
keys) keys ;;
*) sed -n '4,10s/^# //p' "$0"; exit 2 ;;
esac
