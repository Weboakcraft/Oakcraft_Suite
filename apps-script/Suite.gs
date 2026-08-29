/**
 * OakCraft Suite — login + access-list backend  (Google Apps Script bound to a Google Sheet)
 *
 * SETUP (one time, ~5 minutes)
 *   1. Create a new Google Sheet (e.g. "OakCraft Suite Users") → Extensions → Apps Script.
 *   2. Delete the sample code, paste this whole file, save (Ctrl+S).
 *   3. Pick the function  setup  in the toolbar and press Run → allow the permissions.
 *      → creates the "Suite_Users" tab and adds YOUR e-mail as admin (all apps).
 *   4. Deploy → New deployment → type "Web app" → Execute as: Me, Who has access: Anyone → Deploy.
 *      Copy the Web app URL (ends with /exec).
 *   5. In the Oakcraft_Suite repo put that URL in  www/apps.json → "backend"  and commit.
 *      Every phone picks it up automatically (the app re-reads apps.json from the repo).
 *   6. Open the Suite app → sign in with your e-mail → it asks you to create your password.
 *      Then use menu → "Users & access" (or edit the sheet) to add your team.
 *
 * SHEET "Suite_Users"   (you can also edit it by hand)
 *   Email | Name | Role | Apps | Status | PassHash | Created | LastLogin
 *   Role   : admin (sees every app + the Users screen) or user
 *   Apps   : *  (all)  or comma-separated ids from apps.json, e.g.  crm,attendance
 *   Status : active / disabled
 *   PassHash: filled automatically at the user's first sign-in (leave blank to reset a password)
 *
 * OPTIONAL: Project Settings → Script properties → GITHUB_TOKEN = a GitHub token with public_repo read
 *   access → raises the GitHub API limit for version checks (only needed for a very large team).
 *
 * After editing this file: Deploy → Manage deployments → ✎ → Version: New → Deploy (same URL stays).
 */

var SHEET = 'Suite_Users';
var HEAD = ['Email', 'Name', 'Role', 'Apps', 'Status', 'PassHash', 'Created', 'LastLogin'];
var TOKEN_DAYS = 30;          // how long a sign-in stays valid on a phone
var ADMIN_EMAIL = '';         // optional: put your e-mail here before running setup()
var GH_CACHE_SEC = 180;       // GitHub answers are cached this long (server side)

/* ------------------------------------------------------------------ setup ------ */
function setup() {
  var sh = sheet_();
  var me = norm_(ADMIN_EMAIL) || norm_(Session.getEffectiveUser().getEmail());
  if (me && !findUser_(me)) sh.appendRow([me, 'Admin', 'admin', '*', 'active', '', new Date(), '']);
  secret_();
  Logger.log('Suite backend ready. Admin: ' + me + '. Now deploy as Web app (Execute as Me, access: Anyone).');
}

/* ------------------------------------------------------------------ http ------- */
function doGet(e) { return handle_(e); }
function doPost(e) { return handle_(e); }

function handle_(e) {
  var req = {};
  try { req = e && e.postData && e.postData.contents ? JSON.parse(e.postData.contents) : ((e && e.parameter) || {}); } catch (err) { req = {}; }
  var out;
  try { out = route_(req); }
  catch (err) { var m = String(err && err.message || err); out = { ok: false, error: m === 'auth' ? 'auth' : m }; }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}

function route_(q) {
  var a = String(q.action || '');
  if (a === 'ping') return { ok: true, service: 'oakcraft-suite', time: new Date().toISOString() };
  if (a === 'login') return login_(q);
  var me = auth_(q.token);                                   // throws 'auth' when missing / expired / disabled
  if (a === 'me') return { ok: true, user: pub_(me) };
  if (a === 'setPassword') return setPassword_(me, q);
  if (a === 'gh') return gh_(q);
  if (!isAdmin_(me)) return { ok: false, error: 'Admin only' };
  if (a === 'users.list') return { ok: true, users: allUsers_().map(pub_) };
  if (a === 'users.save') return saveUser_(q.user || {}, me);
  if (a === 'users.delete') return deleteUser_(q.email, me);
  if (a === 'users.resetPassword') return resetPassword_(q.email);
  return { ok: false, error: 'Unknown action: ' + a };
}

/* ------------------------------------------------------------------ auth ------- */
function login_(q) {
  var email = norm_(q.email), pass = String(q.password || ''), np = String(q.newPassword || '');
  if (!email) return { ok: false, error: 'E-mail required' };
  var cache = CacheService.getScriptCache(), key = 'fail:' + email;
  var fails = +(cache.get(key) || 0);
  if (fails >= 8) return { ok: false, error: 'Too many attempts — try again in 15 minutes' };
  var u = findUser_(email);
  if (!u) return { ok: false, error: 'This e-mail is not on the Suite access list. Ask your admin to add you.' };
  if (!isActive_(u)) return { ok: false, error: 'Your access has been disabled. Contact your admin.' };
  if (!u.passHash) {
    if (!np) return { ok: false, setup: true };
    if (np.length < 6) return { ok: false, error: 'Password must be at least 6 characters' };
    withLock_(function () { sheet_().getRange(u.row, col_('PassHash')).setValue(hash_(email, np)); });
  } else if (!pass || hash_(email, pass) !== u.passHash) {
    cache.put(key, String(fails + 1), 900);
    return { ok: false, error: 'Wrong password' };
  }
  cache.remove(key);
  try { sheet_().getRange(u.row, col_('LastLogin')).setValue(new Date()); } catch (e) {}
  return { ok: true, token: token_(email), user: pub_(u) };
}

function setPassword_(me, q) {
  var o = String(q.oldPassword || ''), n = String(q.newPassword || '');
  if (n.length < 6) return { ok: false, error: 'New password must be at least 6 characters' };
  if (me.passHash && hash_(me.email, o) !== me.passHash) return { ok: false, error: 'Current password is wrong' };
  withLock_(function () { sheet_().getRange(me.row, col_('PassHash')).setValue(hash_(me.email, n)); });
  return { ok: true };
}

function auth_(token) {
  token = String(token || '');
  var i = token.indexOf('.');
  if (i < 0) throw new Error('auth');
  var payload = token.slice(0, i), sig = token.slice(i + 1);
  if (sign_(payload) !== sig) throw new Error('auth');
  var parts;
  try { parts = Utilities.newBlob(Utilities.base64DecodeWebSafe(payload)).getDataAsString().split('|'); } catch (e) { throw new Error('auth'); }
  if (parts.length < 2 || +parts[1] < Date.now()) throw new Error('auth');
  var u = findUser_(parts[0]);
  if (!u || !isActive_(u)) throw new Error('auth');
  return u;
}
function token_(email) {
  var payload = Utilities.base64EncodeWebSafe(email + '|' + (Date.now() + TOKEN_DAYS * 86400000));
  return payload + '.' + sign_(payload);
}
function sign_(s) { return hex_(Utilities.computeHmacSha256Signature(s, secret_())); }
function hash_(email, pass) { return hex_(Utilities.computeHmacSha256Signature('pw:' + norm_(email) + ':' + pass, secret_())); }
function hex_(bytes) { return bytes.map(function (b) { var h = (b & 255).toString(16); return h.length < 2 ? '0' + h : h; }).join(''); }
function secret_() {
  var p = PropertiesService.getScriptProperties(), s = p.getProperty('SUITE_SECRET');
  if (!s) { s = Utilities.getUuid() + Utilities.getUuid(); p.setProperty('SUITE_SECRET', s); }
  return s;
}

/* ------------------------------------------------------------------ users ------ */
function sheet_() {
  var ss = SpreadsheetApp.getActive();
  var sh = ss.getSheetByName(SHEET);
  if (!sh) { sh = ss.insertSheet(SHEET); }
  if (sh.getLastRow() === 0) { sh.appendRow(HEAD); sh.getRange(1, 1, 1, HEAD.length).setFontWeight('bold'); sh.setFrozenRows(1); sh.setColumnWidth(1, 220); sh.setColumnWidth(6, 120); }
  return sh;
}
var colCache_ = null;
function col_(name) {
  if (!colCache_) {
    colCache_ = {};
    var head = sheet_().getRange(1, 1, 1, Math.max(sheet_().getLastColumn(), HEAD.length)).getValues()[0];
    head.forEach(function (h, i) { if (h) colCache_[String(h).trim().toLowerCase()] = i + 1; });
    HEAD.forEach(function (h) { if (!colCache_[h.toLowerCase()]) { var sh = sheet_(); var c = sh.getLastColumn() + 1; sh.getRange(1, c).setValue(h).setFontWeight('bold'); colCache_[h.toLowerCase()] = c; } });
  }
  return colCache_[name.toLowerCase()];
}
function allUsers_() {
  var sh = sheet_(), n = sh.getLastRow();
  if (n < 2) return [];
  var vals = sh.getRange(2, 1, n - 1, Math.max(sh.getLastColumn(), HEAD.length)).getValues();
  var out = [];
  vals.forEach(function (r, i) {
    var email = norm_(r[col_('Email') - 1]);
    if (!email) return;
    out.push({ row: i + 2, email: email, name: String(r[col_('Name') - 1] || ''), role: String(r[col_('Role') - 1] || 'user').trim().toLowerCase() || 'user',
      apps: String(r[col_('Apps') - 1] || '').trim(), status: String(r[col_('Status') - 1] || 'active').trim().toLowerCase() || 'active',
      passHash: String(r[col_('PassHash') - 1] || '').trim() });
  });
  return out;
}
function findUser_(email) {
  email = norm_(email);
  var all = allUsers_();
  for (var i = 0; i < all.length; i++) if (all[i].email === email) return all[i];
  return null;
}
function isActive_(u) { return u.status !== 'disabled' && u.status !== 'inactive' && u.status !== 'blocked'; }
function isAdmin_(u) { return /^(admin|administrator|owner)$/.test(u.role); }
function pub_(u) {
  var apps = u.apps === '*' || u.apps.toLowerCase() === 'all' ? '*' : u.apps.split(/[,\s]+/).filter(function (x) { return x; }).map(function (x) { return x.toLowerCase(); });
  return { email: u.email, name: u.name, role: u.role, apps: apps, status: isActive_(u) ? 'active' : 'disabled', hasPassword: !!u.passHash };
}
function saveUser_(rec, me) {
  var email = norm_(rec.email);
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return { ok: false, error: 'Valid e-mail required' };
  var role = /admin/i.test(String(rec.role || '')) ? 'admin' : 'user';
  var apps = rec.apps === '*' || rec.apps === 'all' ? '*' : (Array.isArray(rec.apps) ? rec.apps : String(rec.apps || '').split(/[,\s]+/)).filter(function (x) { return x; }).map(function (x) { return String(x).toLowerCase(); }).join(',');
  var status = /disabled|inactive|blocked/i.test(String(rec.status || '')) ? 'disabled' : 'active';
  if (email === me.email && (role !== 'admin' || status !== 'active')) return { ok: false, error: 'You cannot remove your own admin access' };
  withLock_(function () {
    var sh = sheet_(), u = findUser_(email);
    if (u) {
      sh.getRange(u.row, col_('Name')).setValue(String(rec.name || u.name));
      sh.getRange(u.row, col_('Role')).setValue(role);
      sh.getRange(u.row, col_('Apps')).setValue(apps);
      sh.getRange(u.row, col_('Status')).setValue(status);
    } else {
      var row = []; row[col_('Email') - 1] = email; row[col_('Name') - 1] = String(rec.name || ''); row[col_('Role') - 1] = role;
      row[col_('Apps') - 1] = apps; row[col_('Status') - 1] = status; row[col_('PassHash') - 1] = ''; row[col_('Created') - 1] = new Date(); row[col_('LastLogin') - 1] = '';
      for (var i = 0; i < row.length; i++) if (row[i] === undefined) row[i] = '';
      sh.appendRow(row);
    }
  });
  return { ok: true, user: pub_(findUser_(email)) };
}
function deleteUser_(email, me) {
  email = norm_(email);
  if (!email) return { ok: false, error: 'E-mail required' };
  if (email === me.email) return { ok: false, error: 'You cannot remove yourself' };
  var done = false;
  withLock_(function () { var u = findUser_(email); if (u) { sheet_().deleteRow(u.row); done = true; } });
  return done ? { ok: true } : { ok: false, error: 'User not found' };
}
function resetPassword_(email) {
  var u = findUser_(email);
  if (!u) return { ok: false, error: 'User not found' };
  withLock_(function () { sheet_().getRange(u.row, col_('PassHash')).setValue(''); });
  return { ok: true };
}

/* ------------------------------------------------------------------ github ----- */
/** Proxies a few GitHub API reads for the app (cached; uses GITHUB_TOKEN if set so a big team never hits the 60/h limit). */
function gh_(q) {
  var paths = Array.isArray(q.paths) ? q.paths.slice(0, 12) : [];
  var cache = CacheService.getScriptCache(), results = {};
  var token = PropertiesService.getScriptProperties().getProperty('GITHUB_TOKEN');
  paths.forEach(function (p) {
    p = String(p || '');
    if (!/^\/repos\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.\/%?=&-]*$/.test(p)) { results[p] = null; return; }
    var key = 'gh:' + p, hit = cache.get(key);
    if (hit) { try { results[p] = JSON.parse(hit); return; } catch (e) {} }
    try {
      var headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'OakCraftSuite' };
      if (token) headers.Authorization = 'Bearer ' + token;
      var r = UrlFetchApp.fetch('https://api.github.com' + p, { muteHttpExceptions: true, headers: headers, followRedirects: true });
      if (r.getResponseCode() !== 200) { results[p] = null; return; }
      var j = trimGh_(JSON.parse(r.getContentText()));
      results[p] = j;
      try { cache.put(key, JSON.stringify(j), GH_CACHE_SEC); } catch (e) {}
    } catch (e) { results[p] = null; }
  });
  return { ok: true, results: results };
}
function trimGh_(j) {
  var rel = function (x) { return { tag_name: x.tag_name, name: x.name, body: x.body, published_at: x.published_at, html_url: x.html_url, draft: !!x.draft, prerelease: !!x.prerelease,
    assets: (x.assets || []).map(function (a) { return { name: a.name, browser_download_url: a.browser_download_url, size: a.size, updated_at: a.updated_at }; }) }; };
  if (Array.isArray(j)) return j.map(rel);
  if (j && j.workflow_runs) return { total_count: j.total_count, workflow_runs: j.workflow_runs.map(function (x) { return { run_number: x.run_number, created_at: x.created_at, updated_at: x.updated_at, conclusion: x.conclusion, head_branch: x.head_branch }; }) };
  if (j && j.tag_name !== undefined) return rel(j);
  return j;
}

/* ------------------------------------------------------------------ util ------- */
function norm_(s) { return String(s || '').trim().toLowerCase(); }
function withLock_(fn) {
  var lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try { colCache_ = null; return fn(); } finally { lock.releaseLock(); }
}
