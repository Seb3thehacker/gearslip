-- One row per phone per day: the first note that day counts, later ones update it.
CREATE TABLE IF NOT EXISTS pings (
  day TEXT NOT NULL,          -- YYYY-MM-DD, UTC
  uid TEXT NOT NULL,          -- salted hash of the phone's random install ID
  version TEXT NOT NULL,
  code INTEGER,
  build TEXT,
  android TEXT,               -- these four are from older builds; details now live in phones
  os TEXT,
  phone TEXT,
  car_today INTEGER,
  PRIMARY KEY (day, uid)
);

-- Each car a phone saw that day, and how the session ended.
CREATE TABLE IF NOT EXISTS cars (
  day TEXT NOT NULL,
  uid TEXT NOT NULL,
  name TEXT, car TEXT, year TEXT, make TEXT, model TEXT,
  protocol TEXT,
  result TEXT,
  screen TEXT,                -- usable pixels, e.g. 1280x720
  dpi INTEGER,
  PRIMARY KEY (day, uid, name, car, year, protocol, result)
);

CREATE INDEX IF NOT EXISTS pings_uid ON pings (uid);

-- GitHub's running download count for each release's APK, saved once a day.
CREATE TABLE IF NOT EXISTS downloads (
  day TEXT NOT NULL,
  tag TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (day, tag)
);

-- Which releases are pre-releases, so the dashboard can pick out the latest full release.
CREATE TABLE IF NOT EXISTS releases (
  tag TEXT PRIMARY KEY,
  prerelease INTEGER NOT NULL,
  published TEXT
);

-- Each phone's latest details. Phones send them only when they change (and once a month), so
-- a field a note leaves out keeps its last value.
CREATE TABLE IF NOT EXISTS phones (
  uid TEXT PRIMARY KEY,
  android TEXT,
  os TEXT,
  phone TEXT,
  updated TEXT
);
