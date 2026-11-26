# Wi-Fi repeater

**Video:** [Watch the setup on the Homedroid website](https://homedroid.seltiv.com/guides/wifi-repeater/)

The phone can share its internet connection with other devices through its own Wi-Fi
network, without root. It does this with a Wi-Fi Direct group and a built-in proxy, so it
is not a true repeater or hotspot: devices that join the network must be told to use the
phone as their proxy.

## Why a proxy

Android only lets apps with special privileges route or share a connection (the system
hotspot, or root). Homedroid is an ordinary app. What it can do is create a Wi-Fi network
(Wi-Fi Direct), listen on that network's address and relay traffic on the clients' behalf.
A device that points its proxy setting at the phone gets to the internet through it.

## What you need

- Android 10 or newer and a phone with Wi-Fi Direct (almost all have it).
- The phone should be connected to Wi-Fi (or have mobile data) for something to share.
  Most phones can host the group and stay connected to their own Wi-Fi at the same time.
  A few drop their Wi-Fi when the group starts; then the repeater only has mobile data to
  offer. The page tells you which it is a few seconds after it starts.
- Permission: **Nearby devices** on Android 13 and newer, **Location** on older versions.
  Android requires this to create a Wi-Fi network. Homedroid does not use your location.
- The Homedroid server running. The repeater starts and stops with it, and comes back
  after a restart.

## Turn it on

On the phone: **Settings → Services & connections → Wi-Fi repeater**. The first time,
Android asks for the permission. The **Repeater network** row below it shows the network
name, the passphrase and the proxy address.

Or in the dashboard: **Modules → Wi-Fi repeater**. The dashboard can't ask for the
permission itself; if it is missing, grant it in the phone's Settings first.

Settings (in the dashboard, or through the API below): the network name (it always begins with
`DIRECT-xy-`, which Android requires; the default is `DIRECT-hd-Homedroid`), the
passphrase (a random one is made for you), the band (automatic, 2.4 GHz or 5 GHz) and
whether clients may also reach other devices on your home network (off by default).

## Connect a device

1. Join the Wi-Fi network named on the page, with the passphrase, or scan its QR code (the
   code encodes `WIFI:T:WPA;S:<name>;P:<passphrase>;;`).
2. Set the device's proxy. The phone's address on its network is normally `192.168.49.1`;
   the page shows the real one.

| | |
|---|---|
| HTTP and HTTPS proxy | `192.168.49.1`, port **8282** |
| SOCKS5 proxy | `192.168.49.1`, port **8284** |
| Automatic configuration (PAC) | `http://192.168.49.1:8283/proxy.pac` |

**Android**: Wi-Fi settings → the network → Advanced → Proxy → Manual, or Proxy auto-config
with the PAC address. If Android warns that the network has no internet, choose to stay
connected.

**iPhone and iPad**: Settings → Wi-Fi → (i) next to the network → Configure Proxy → Manual
(server and port, authentication off) or Automatic (the PAC address).

**Windows 11**: Settings → Network & internet → Proxy → Manual proxy setup → Set up, or
use a setup script with the PAC address.

**macOS**: System Settings → Network → Wi-Fi → Details → Proxies. Turn on Web Proxy (HTTP)
and Secure Web Proxy (HTTPS) with the address and port, or Automatic Proxy Configuration.

**Linux (GNOME)**: Settings → Network → Network Proxy → Manual (HTTP, HTTPS and SOCKS), or
Automatic with the PAC address. For a command line: `export http_proxy=http://192.168.49.1:8282
https_proxy=http://192.168.49.1:8282`, or `curl --socks5-hostname 192.168.49.1:8284 <url>`.

**Firefox** has its own setting (Settings → Network Settings), and can use SOCKS5 with
"Proxy DNS when using SOCKS v5".

## What it shows

Connected devices, the number of connections and the bytes relayed in each direction.
It is in the dashboard (Modules → Wi-Fi repeater) and `GET /api/repeater`.

## Honest limits

- **Proxy only.** Apps that follow the system proxy work (browsers, most apps built on the
  system networking). Many do not: games, voice and video calls, some streaming apps,
  VPN clients and apps that ignore the proxy setting. They will show "no internet".
- **UDP.** The HTTP proxy carries TCP only. UDP (some games, calls, HTTP/3 and QUIC,
  DNS to your own servers) works only for apps that use the SOCKS5 proxy and its UDP
  support. Most browsers fall back from QUIC to normal HTTPS by themselves.
- **Speed.** The phone's one Wi-Fi radio serves its own connection and the group. Expect a
  fraction of your line speed, and less with several devices. The 5 GHz band is faster
  when the phone's own Wi-Fi is also 5 GHz; some phones can run the group only on the
  same channel as their own connection.
- **Phones differ.** Some phones can't do both at once, limit the group to 2.4 GHz, or
  need Location turned on to create the network. Android may also close the group by
  itself (another app using Wi-Fi Direct, Wi-Fi turned off); Homedroid then tries again
  every few seconds, up to once a minute.
- **Battery and heat.** Keep the phone charging.
- **Security.** The Wi-Fi passphrase is the only access control: anyone who has it can use
  the proxy. The proxies listen only on the group's address, not on your home Wi-Fi. By
  default they refuse destinations on your home network, this phone's own services and
  anything private, so a guest can reach the internet, not your NAS or the dashboard. The
  proxy does not log which sites are visited. Traffic between a client and the phone is
  encrypted by Wi-Fi; traffic to plain HTTP sites is not encrypted beyond that.
- **Names.** The proxy looks names up for the devices that use it. Anything that doesn't use
  the proxy may not be able to resolve names on this network.

## For scripts

`GET /api/repeater` returns the status. `POST /api/repeater` with `{"enabled": true}` turns it
on or off, and accepts `name`, `passphrase`, `band` (`auto`, `2.4`, `5`), `allowLan` and
`"regenerate": true` for a new passphrase.
