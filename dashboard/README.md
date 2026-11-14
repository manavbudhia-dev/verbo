# Verbo dashboard

A web app for managing a Verbo keyboard: skill keys, connected apps and APIs, the
assistant's API key and model, and which phones are paired.

One Node process, no npm dependencies, data in a single SQLite file. Saved tokens and
API keys are encrypted at rest with `SECRET_KEY`.

## Deploy on an Oracle Cloud server

You need a domain (or subdomain) with an A record pointing at the server's public IP.
The keyboard only talks to the dashboard over HTTPS, and Caddy needs the domain to get
a certificate.

1. **Open ports 80 and 443.** In the Oracle console: your instance → subnet → security
   list → add ingress rules for TCP 80 and 443 from `0.0.0.0/0`. Oracle's images also
   block them on the host:

       # Ubuntu image
       sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
       sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
       sudo netfilter-persistent save

       # Oracle Linux image
       sudo firewall-cmd --permanent --add-service=http --add-service=https
       sudo firewall-cmd --reload

2. **Install Docker** (skip if present): `curl -fsSL https://get.docker.com | sudo sh`

3. **Copy this `dashboard` folder to the server**, then inside it:

       cp .env.example .env
       # edit .env: set DOMAIN, and SECRET_KEY to the output of `openssl rand -hex 32`
       sudo docker compose up -d --build

4. Open `https://your-domain`, create your account, then set `ALLOW_SIGNUP=false` in
   `.env` and run `sudo docker compose up -d` again so nobody else can sign up.

## Pair a keyboard

Dashboard → **Keyboards** → **Pair a keyboard** shows a one-time code. In the Verbo app,
under **Dashboard**, enter the site address and the code. The keyboard then pulls its
settings from the dashboard (on pairing, on **Sync now**, and every 10 minutes while in use).

## Google: Meet, Calendar and Gmail (optional)

With a Google account connected, the keyboard works in it by itself: the **Meeting link** shortcut drops a
real Google Meet link into the message, and skills can save Calendar events (with invites and a Meet link)
and search, read, draft and send Gmail. Users only press **Connect Google** once. Google requires each
server to bring its own client, so whoever runs the server does this one-time setup:

1. In [Google Cloud Console](https://console.cloud.google.com) create a project and enable the
   **Google Meet REST API**, **Google Calendar API** and **Gmail API**.
2. **OAuth consent screen**: External, add your own Google account as a test user, and add the scopes
   `https://www.googleapis.com/auth/meetings.space.created`, `https://www.googleapis.com/auth/calendar.events`,
   `https://www.googleapis.com/auth/gmail.compose` and `https://www.googleapis.com/auth/gmail.readonly`.
3. **Credentials → Create credentials → OAuth client ID → Web application**, with the redirect URI
   `https://your-domain/api/google/callback`.
4. Set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `PUBLIC_URL` (`https://your-domain`) on the server
   (in `.env` with Docker), then restart it.
5. In the dashboard open **Connections → Google → Connect Google**, sign in and tick every box. On the phone,
   tap **Sync now** under Web dashboard.

The Google sign-in is stored encrypted on the server and never sent to the phone; the phone asks the server
to do each action. If someone unticks Gmail or Calendar on Google's page, only what they allowed is turned on
and the card offers **Reconnect**. While the consent screen is in "Testing", Google expires the sign-in after
seven days and allows up to 100 test users; publishing an app that uses the Gmail scopes needs Google's
verification. Without this setup the Meeting link shortcut still offers an instant no-account (Jitsi) link.

## Run without Docker

Needs Node 22.13 or newer.

    SECRET_KEY=$(openssl rand -hex 32) PORT=8080 node server.js

Put it behind a reverse proxy that terminates HTTPS and set `TRUST_PROXY=1`. For local
testing over plain http, add `INSECURE_COOKIES=1`. For Google Meet also set `PUBLIC_URL` to the
site's public address, plus the two `GOOGLE_` variables.

## Back up

Everything is in the `verbo-data` volume (`/data/verbo.db`). Back up that file together
with your `SECRET_KEY`.
