# Your own mail server

Homedroid can run [Stalwart](https://stalw.art), a mail server with SMTP (sending and
receiving between servers), IMAP and JMAP (for your mail apps) and a web admin. Install it
from **Modules → Mail server (Stalwart)**, then open **Mail** in the dashboard: a setup
wizard asks a few questions, shows the DNS records to add, checks that everything is in
place and lists the settings for your phone and computer mail apps.

Read the next section before you start. Running mail at home often does not work, for
reasons the phone cannot change.

## What can go wrong first

- **Port 25.** Other mail servers deliver to your server on port 25. Most home internet
  providers block it, in one or both directions. If yours does, mail cannot reach the
  phone directly, and sending directly will fail too.
- **No public address.** Phones on mobile data, and some home connections (carrier-grade
  NAT, "CGNAT"), have no address the internet can reach. Port forwarding cannot fix this.
- **Reverse DNS.** Big providers (Gmail, Outlook) check that your IP address's reverse DNS
  (PTR) names your mail server. Only your internet provider can set that, and home
  providers rarely do. Without it, your mail often lands in spam.
- **Reputation.** Home IP addresses are often on spam lists, whatever you do.
- **Uptime.** Mail is received only while the phone is on, charged and online. Other
  servers retry for a few days, then bounce the message.

So pick the route that fits your situation:

| You have | Use |
|---|---|
| A real public IP, port 25 open in both directions, and a provider willing to set the PTR | **Directly from my home internet** |
| A real public IP, but port 25 blocked or Gmail rejects you | **Send through a relay**. Incoming mail still arrives through your router. |
| No public IP, or port 25 blocked | **Receive only, through a small server (VPS)**, plus a relay if you also want to send |

Sending through a relay is the most reliable choice for most people. Transactional email
services (Amazon SES, Mailgun, Brevo, Postmark and others) give you an SMTP server, a user
name and a password, usually with a free tier that is plenty for personal mail.

You also need a **domain name** you can edit DNS for. A mail address is `you@your-domain`.

## 1. Install

Modules → **Mail server (Stalwart)** → Install. It downloads about 70 MB and needs about
230 MB. The program is Stalwart's official build for your phone's CPU, pinned to one version
and checked against its SHA-256 before it is installed. Nothing runs until you finish the
setup.

## 2. Answer the setup questions

Dashboard → **Mail**. You are asked for:

- **Domain**: `example.com`.
- **Hostname**: the name of the mail server, normally `mail.example.com`. It is what other
  servers connect to, and it appears in the MX record.
- **How mail reaches the phone**: the three routes above.
- **Address** (direct and relay routes): your public IP address, or a dynamic DNS name
  (like `myhome.duckdns.org`) if your IP changes. Homedroid's dynamic DNS feature can keep a
  name up to date. For the VPS route, this is the VPS's address.
- **Relay** (relay route): the provider's server name, port (587 is usual), user name and
  password, and the **SPF include** value the provider tells you to publish (for example
  `spf.mailgun.org`).
- **DMARC policy** and **report address** (optional). Start with `none`.

Save. The first start creates the database, which takes a minute or two. Then the page
shows the web admin login: user `admin@your-domain` and a password the server generated.
Keep it; it is also on the Mail module's card and in `/var/lib/stalwart/admin-password`
inside Alpine.

The web admin is at `http://<phone-ip>:8825/admin`. It is plain HTTP: use it from your
home network or over Tailscale, and **do not forward port 8825 to the internet**.

## 3. Forward ports on your router (direct and relay routes)

Android apps cannot open ports below 1024, so Stalwart listens on high ports. On the
router, forward the standard port to the phone's address (give the phone a fixed address in
the router first):

| Router (outside) | Phone | What it is |
|---|---|---|
| 25 | 2525 | Mail from other servers (SMTP). Required to receive. |
| 465 | 4650 | Sending from mail apps, TLS |
| 587 | 2587 | Sending from mail apps, STARTTLS |
| 993 | 9930 | Reading mail in apps (IMAP over TLS) |

Port forwarding for 465, 587 and 993 is only needed to use your mail from outside your home.
On your own Wi-Fi, mail apps can connect to the phone's address and the phone ports
directly. For the VPS route, nothing is opened at home (see below).

## 4. Add the DNS records

The Mail page lists every record with the exact name and value, and a copy button next to
each. Add them where your domain's DNS is managed (your registrar or a DNS host). In
dashboard terms, "Host" is the name relative to your domain (`@` for the domain itself).

For `example.com` with the mail server `mail.example.com` at `203.0.113.7`:

| Type | Host | Value | What it does |
|---|---|---|---|
| A | `mail` | `203.0.113.7` | Where the mail server is. With a dynamic DNS name this is a CNAME to it instead. |
| MX | `@` | `10 mail.example.com.` | Tells other servers where to deliver mail for your domain. |
| TXT | `@` | `v=spf1 mx ip4:203.0.113.7 ~all` | SPF: which servers may send as you. |
| TXT | `v1-rsa-…._domainkey` | `v=DKIM1; k=rsa; h=sha256; p=…` | DKIM: the signature key. Generated by the server. |
| TXT | `_dmarc` | `v=DMARC1; p=none` | DMARC: what receivers do with mail that fails. |

Notes:

- **DKIM** keys are created when the server first starts. If the Mail page says "pending",
  start the server and reload it. Homedroid uses one 2048-bit RSA key and does not rotate it,
  because a rotation would need new DNS records.
- A 2048-bit DKIM value is longer than the 255 characters one DNS string can hold. Most DNS
  panels split it for you when you paste the whole value. If yours does not, the page
  shows the value split into pieces, and the zone-file line uses the quoted form.
- **SPF**: with a relay, the value is `v=spf1 include:<provider> ~all`. Without the
  provider's include, mail from the relay fails SPF. `~all` means "probably not me". Once
  mail flows and you have read a few DMARC reports, you can change it to `-all`.
- **DMARC**: start with `p=none`. Once your mail passes, move to `quarantine`, then
  `reject`. A report address (`rua`) needs a mailbox that exists.
- Optional records: `autoconfig` and `autodiscover` CNAMEs help Thunderbird and Outlook
  find the settings, but they only work if port 80 (and for Outlook, 443 with a trusted
  certificate) reaches the phone, which this setup does not do. SRV records tell apps the
  ports. MTA-STS needs an HTTPS website with a trusted certificate. Skip these unless you
  know you need them.
- Record changes can take minutes to hours to be seen by other servers. A low TTL (300)
  makes retries quicker while you are setting up.

## 5. Check

Press **Check** on the Mail page. It looks at:

- whether the server is running and each port answers on the phone,
- the TLS certificate (self-signed, expired, wrong name),
- whether this connection can reach other mail servers on port 25 (the direct route),
  or the relay,
- your public IP address, whether the mail hostname leads to it, and whether its reverse
  DNS names the server,
- each DNS record, looked up through public DNS (Cloudflare, with Google as a fallback),
  against what Homedroid expects.

The checks run **from the phone**, so they cannot prove the internet can reach it. The real
test is to send a message to `you@your-domain` from another provider (Gmail, for example), and
to send one from your account to that provider. Services like mail-tester.com give a
deliverability score.

## 6. Make mailboxes and connect mail apps

On the Mail page, add an account (name and password; a generated password is shown once).
The page then shows what to enter in a mail app:

| | At home (on your Wi-Fi) | From anywhere (router forwarding set up) |
|---|---|---|
| Incoming (IMAP) | the phone's address, port 9930, SSL/TLS | `mail.example.com`, port 993, SSL/TLS |
| Outgoing (SMTP) | the phone's address, port 4650, SSL/TLS (or 2587, STARTTLS) | `mail.example.com`, port 465, SSL/TLS (or 587, STARTTLS) |
| User name | the full address, `alice@example.com` | the same |
| Password | the account's password | the same |

Use an "IMAP" or "Other" account in the app, not Gmail or Exchange. Android: Gmail app →
Add another account → Other. iPhone: Settings → Mail → Accounts → Add Account → Other.
Thunderbird, K-9 Mail / Thunderbird for Android, Apple Mail and Outlook all work.

Stalwart also speaks JMAP at `http://<phone-ip>:8825/jmap/session`, for clients that use it.

## 7. Certificates

Stalwart starts with a self-signed certificate, so mail apps warn once and you accept.
Other mail servers generally accept an untrusted certificate when delivering to you, but
a trusted one is better.

Homedroid cannot obtain a Let's Encrypt certificate by itself: that needs port 80 or 443
(blocked for apps), or a DNS API. To use a trusted certificate, get one for
`mail.example.com` elsewhere (a DNS challenge with `certbot` or `lego` on a computer, for
example) and paste the certificate and private key into the Mail page's certificate form.
Stalwart restarts to use it. Let's Encrypt certificates last 90 days, so repeat this
before they expire; the check warns when one has expired.

## Sending through a relay

Choose "Send through a relay" and enter the provider's SMTP details. All mail for other
domains is handed to the relay; mail to your own domain stays local. Add the provider's SPF
include, and the provider's own DKIM and domain verification records as it tells you (the
provider needs to know you own the domain). If the relay's port 587 is blocked on your
network, many providers also offer 2525 or 465.

## Receiving through a VPS (no home port forwarding)

A VPS with a public IP accepts mail on port 25 and passes it to the phone over a private
link. Tailscale (built into Homedroid) is the easiest. On the VPS, on Tailscale too:

```sh
socat TCP-LISTEN:25,fork,reuseaddr TCP:<phone-tailscale-ip>:2525
```

Set the VPS's address in the wizard (so the A record and SPF match it) and make the mail
hostname lead to the VPS. Notes:

- The VPS needs port 25 open in its provider's firewall. Some VPS providers block it until
  you ask.
- Plain forwarding hides the sender's address: Stalwart sees the VPS as the sender when it
  checks SPF and the reverse DNS. HAProxy with the PROXY protocol keeps the real address;
  then add the VPS's address to Stalwart's trusted proxy networks in the web admin
  (its network settings; look for trusted proxy networks). Without that, expect more of your incoming mail to look
  suspicious to the spam filter.
- Sending needs a relay or a VPS that also sends on the phone's behalf. The wizard accepts
  a relay in this mode.

## Battery, uptime and backups

- The server runs as long as Homedroid's server runs. Keep the phone charging, with
  battery optimization off for Homedroid, and give it a fixed address on your network.
  Stalwart needs noticeably more memory than most apps here (its database, spam filter and
  web interface); phones with 2 GB of RAM or less may struggle.
- On the first start, Stalwart downloads its web interface and some spam and location data
  from the internet, so the web admin appears a minute after the ports open.
- Everything lives in `/var/lib/stalwart` inside Alpine (`files/alpine/var/lib/stalwart` in
  the app's data): the database (`data/stalwart.db`, with your mail), `etc/config.json`,
  your setup in `setup/` and the admin password. Nothing copies it elsewhere for you. To
  back up, turn the module off in Modules (so the database is quiet), copy that folder
  off the phone (SFTP works), and turn it back on. Do this regularly; a phone can break.
  The mail's location can be moved to an SD card or USB drive with the module's storage
  setting.

## Ports

| Use | Phone port | Public port | Notes |
|---|---|---|---|
| SMTP, mail from other servers | 2525 | 25 | Plain, upgrades with STARTTLS |
| Submission, STARTTLS | 2587 | 587 | Needs login |
| Submission, TLS | 4650 | 465 | Needs login |
| IMAP, TLS | 9930 | 993 | Needs login |
| Web admin, JMAP, autoconfig | 8825 | 80 (optional) | Plain HTTP; keep it on your own network |

These avoid the ports Homedroid and its other apps use (8080, 8800, 8801, 8443, 8022,
3000, 8096, 8081, 8123, 2283, 8282 to 8284).

## Troubleshooting

- **The Mail page says the server isn't ready.** Look at Logs → stalwart. The first start
  can take several minutes on slow phones.
- **"Applying the mail settings failed"** in the log: the setup could not be applied.
  Send the message after it to a bug report.
- **Mail from Gmail never arrives.** Check that MX and the A record resolve to your
  address, that port 25 is forwarded to 2525, and that your provider does not block 25.
- **Your mail lands in spam or is rejected.** Look at the DNS checks (SPF, DKIM, DMARC) and
  the reverse DNS (PTR) check. A relay is the dependable fix.
- **Lost the admin password.** It is in `/var/lib/stalwart/admin-password` inside Alpine, and
  on the Mail page.
- **Start over.** Modules → Mail server → Clear data removes the mail and settings.

## For scripts

The dashboard's API (same login as the rest, `Authorization: Bearer <dashboard password>`):

- `GET /api/mail` status, ports, admin login, saved answers, modes and limitations
- `POST /api/mail/setup` the wizard's answers
- `GET /api/mail/dns` the DNS records
- `POST /api/mail/check` the health checks
- `GET /api/mail/clients?user=alice` mail app settings
- `GET`, `POST /api/mail/accounts`, `POST /api/mail/accounts/<id>/password`,
  `DELETE /api/mail/accounts/<id>`
- `POST /api/mail/certificate` a PEM certificate and private key
