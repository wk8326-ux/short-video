# TVBox configuration

Import URL: `https://short.deepfuck.you/tvbox.json`

In TVBox or a compatible client, open settings, select the configuration URL
field, and import this URL. The configuration contains five standard JSON CMS
sites (`type: 1`): 155api, Forest, Fanhao, Lajiao and Naixiang. Each site uses its
own upstream API for categories, pagination, search, details and playback.

The source file is `frontend/public/tvbox.json`; Vite includes it in the frontend
build. Production also serves a copy through an exact nginx `/tvbox.json`
location, backed by `/var/www/short-video-tvbox/tvbox.json`, so this configuration
can be updated independently of the application image. Keep both copies in sync.
The response uses `application/json`, allows cross-origin reads and requires
cache revalidation. No login credentials or expiring playback links are stored
in the configuration.

Verification on 2026-10-24:

- All five APIs returned categories, category listings and video details.
- Three videos per site returned HLS playlists and actual video segment bytes;
  referenced encryption keys were checked where present.
- One video per site was decoded for three seconds with FFmpeg. Each produced
  74 decoded video frames with exit status zero and no reported errors.
- Search returned results on all five sites. 155api returned no items for some
  exact titles while common keywords worked; that behavior originates upstream.
- Fanhao's HTTPS endpoint was verified and is used in the configuration.

These checks cover sampled videos. Upstream catalogues and individual links can
change independently of this project.
