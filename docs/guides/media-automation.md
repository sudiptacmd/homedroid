# Media automation: Prowlarr, Radarr, Sonarr and Seerr

**Video:** [Watch the setup on the Homedroid website](https://homedroid.seltiv.com/guides/media-automation/)

Homedroid already runs Jellyfin and qBittorrent. Add these four apps and the phone looks after
your movie and TV library for you:

1. You ask for a movie or show in **Seerr** (formerly Jellyseerr).
2. **Radarr** (movies) or **Sonarr** (TV) searches the sites **Prowlarr** knows about and picks
   the best release that fits your quality settings.
3. **qBittorrent** downloads it.
4. Radarr or Sonarr renames it and files it in `Movies` or `Shows`, and **Jellyfin** shows it.

Sonarr keeps watching for new episodes of the shows you follow, and Radarr for movies that
weren't out yet when you asked.

## Install

In the phone app or the dashboard, open **Apps** and install, in any order:

| App | What it does | Port |
| --- | --- | --- |
| Jellyfin | Plays the library | 8096 |
| qBittorrent | Downloads | 8081 |
| Prowlarr | Your indexers (torrent sites), in one place | 9696 |
| Radarr | Movies | 7878 |
| Sonarr | TV shows | 8989 |
| Jellyseerr (Seerr) | Search and request page for you and your family | 5055 |

You don't need all of them. Radarr or Sonarr with qBittorrent is enough to automate downloads;
Prowlarr saves you adding each indexer twice; Seerr is the nice front end.

Sonarr and Seerr need a 64-bit phone (ARM64). Prowlarr and Radarr also run on 32-bit ARM.

Homedroid installs **Sonarr 5**, which is still Sonarr's test release. Sonarr 4 can't run on
Android: it is built on .NET 6, which doesn't start inside Android's app sandbox. Sonarr 5 uses a
newer .NET and works the same way for everything described here. Homedroid moves to the
stable Sonarr 5 when it comes out; your shows and settings carry over.
Each of Prowlarr, Radarr and Sonarr uses about 150 to 250 MB of memory while running, Seerr
about 200 MB; a phone with 4 GB or more is comfortable with the whole set.

## What Homedroid connects for you

When the apps start, Homedroid connects them, and it checks again every few minutes for apps
you install later. Each connection is made only once: anything you change in the apps
afterwards is kept, and a connection you delete stays deleted until you add it back
yourself.

- **Radarr** gets qBittorrent as its download client (category `radarr`) and `/media/Movies`
  as its root folder.
- **Sonarr** gets qBittorrent (category `tv-sonarr`) and `/media/Shows`.
- **Prowlarr** gets Radarr and Sonarr as apps (full sync, so every indexer you add to Prowlarr
  appears in both) and qBittorrent for grabs you make in Prowlarr itself.
- **aria2**, the lighter torrent client, is added the same way to Radarr, Sonarr and Prowlarr
  when it is installed, instead of or beside qBittorrent. It has no categories, so everything it
  downloads goes to `/media/Downloads`, where Radarr and Sonarr pick it up.
- **Jellyfin** gets a **Movies** and a **Shows** library on those folders. It picks them up the
  next time it starts, which happens when an app is installed.
- **Seerr** gets Radarr and Sonarr, with the `HD-1080p` profile, as soon as you have finished
  its setup wizard (see below).

Everything is in the shared **Media** folder, so if you moved the media library to a microSD
card or USB drive, the downloads and the library go there too.

## First steps

1. **Create your logins.** Prowlarr, Radarr and Sonarr ask for a username and password the
   first time you open them. Set them right away, before anyone else on your network does.
2. **Add indexers in Prowlarr.** Indexers → Add Indexer. Public trackers work without an
   account. After saving, they show up in Radarr and Sonarr within a minute
   (Settings → Indexers).
3. **Set up Seerr.** Open it and choose **Jellyfin**. Sign in with your Jellyfin account and
   enter `127.0.0.1` as the address and `8096` as the port. Pick the libraries to sync and
   finish the wizard; you can skip the Radarr and Sonarr step, Homedroid adds them for you a
   few seconds after the wizard ends.
4. **Ask for something.** Search in Seerr and press Request. Watch it appear in Radarr's or
   Sonarr's queue, in qBittorrent, and then in Jellyfin.

## Good to know

- **Disk space.** qBittorrent keeps seeding finished downloads. Radarr and Sonarr try to use a
  hard link when they file a download, which takes no extra space. On a microSD card or USB
  drive that can't hold links, they copy instead, so a movie takes space twice until you
  remove the torrent. To avoid that, set a seeding limit in qBittorrent (Options →
  BitTorrent → Seeding Limits) and turn on **Remove Completed** for the download client in
  Radarr and Sonarr.
- **API keys.** Each app's key is under Settings → General. In the terminal,
  `/usr/local/lib/homedroid/media-stack keys` prints all of them.
- **Updates.** These apps don't update themselves; their built-in updater is off. Use
  **Update** on the app in Homedroid when a new Homedroid release brings newer versions.
- **Reconnect.** To have Homedroid make a connection again, delete its marker in the terminal
  and wait a few minutes, for example
  `rm /var/lib/radarr/.homedroid-linked-qbittorrent` for Radarr's qBittorrent connection.
- **From outside your home.** Through Tailscale the apps work as they do at home. Don't publish
  Prowlarr, Radarr or Sonarr on the internet with a Cloudflare tunnel; Seerr is the one meant
  for other people.

Use these apps for content you have the right to download.
