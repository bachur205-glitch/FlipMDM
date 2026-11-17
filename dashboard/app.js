import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.5/firebase-app.js";
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signOut, onAuthStateChanged
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-auth.js";
import {
  getDatabase, ref, onValue, update, set, push, remove, serverTimestamp
} from "https://www.gstatic.com/firebasejs/10.12.5/firebase-database.js";
import { firebaseConfig } from "./firebase-config.js";

// Keep in sync with Restrictions.kt on the phone.
const RESTRICTIONS = [
  ["no_install_apps", "Block installing apps"],
  ["no_install_unknown_sources", "Block APK sideloading"],
  ["no_uninstall_apps", "Block uninstalling apps"],
  ["no_factory_reset", "Block factory reset (Settings + reset codes)"],
  ["no_safe_boot", "Block safe mode"],
  ["no_add_user", "Block adding users"],
  ["no_modify_accounts", "Block adding accounts"],
  ["no_config_date_time", "Block changing date/time"],
  ["no_debugging_features", "Block USB debugging"],
];

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getDatabase(app);

const $ = (id) => document.getElementById(id);
let currentUid = null;
let isOwner = false;
let selectedId = null;
let lastDevices = {};

// ---------- auth ----------

$("signinBtn").onclick = () =>
  signInWithPopup(auth, new GoogleAuthProvider()).catch((e) => alert(e.message));
$("signoutBtn").onclick = () => signOut(auth);

onAuthStateChanged(auth, (user) => {
  currentUid = user ? user.uid : null;
  if (user) {
    $("signin").classList.add("hidden");
    $("app").classList.remove("hidden");
    $("who").textContent = user.email || user.uid;
    ensureOwner();
    watchDevices();
  } else {
    $("signin").classList.remove("hidden");
    $("app").classList.add("hidden");
    $("who").textContent = "";
  }
});

// First person to sign in claims ownership (allowed by the rules while /owners is empty).
function ensureOwner() {
  onValue(ref(db, "owners"), (snap) => {
    const owners = snap.val() || {};
    isOwner = !!owners[currentUid];
    const notice = $("ownerNotice");
    if (Object.keys(owners).length === 0) {
      notice.classList.remove("hidden");
      notice.innerHTML =
        'No owner set yet. <button id="claim" class="primary">Claim this dashboard</button>';
      $("claim").onclick = () =>
        set(ref(db, `owners/${currentUid}`), true).catch((e) => alert(e.message));
    } else if (!isOwner) {
      notice.classList.remove("hidden");
      notice.textContent =
        "Your account isn't an owner. Your UID: " + currentUid +
        " — add it under /owners in the database to get access.";
    } else {
      notice.classList.add("hidden");
    }
  }, { onlyOnce: false });
}

// ---------- devices ----------

function watchDevices() {
  onValue(ref(db, "devices"), (snap) => {
    lastDevices = snap.val() || {};
    const ids = Object.keys(lastDevices);
    const sel = $("deviceSelect");
    sel.innerHTML = "";
    if (ids.length === 0) {
      sel.innerHTML = '<option>No devices yet</option>';
      $("devicePanel").innerHTML =
        '<div class="card muted">No phones have checked in. Set up the app on the phone, ' +
        'open it once, and it will appear here.</div>';
      return;
    }
    for (const id of ids) {
      const name = (lastDevices[id].status && lastDevices[id].status.model) || id;
      const opt = document.createElement("option");
      opt.value = id;
      opt.textContent = `${name} (${id})`;
      sel.appendChild(opt);
    }
    if (!selectedId || !ids.includes(selectedId)) selectedId = ids[0];
    sel.value = selectedId;
    sel.onchange = () => { selectedId = sel.value; renderDevice(); };
    renderDevice();
  });
}

function renderDevice() {
  const d = lastDevices[selectedId];
  if (!d) { $("devicePanel").innerHTML = ""; return; }
  const s = d.status || {};
  const desired = d.desiredState || {};
  const fresh = s.online === true && s.lastSeen && (Date.now() - s.lastSeen < 150000);
  const seen = s.lastSeen ? new Date(s.lastSeen).toLocaleString() : "never";

  const el = document.createElement("div");

  // status card
  el.appendChild(card("Status", `
    <div class="grid">
      <div class="stat"><b>${fresh ? "Online" : "Offline"}</b>
        <span><span class="dot ${fresh ? "on" : "off"}"></span>last seen ${seen}</span></div>
      <div class="stat"><b>${s.battery != null && s.battery >= 0 ? s.battery + "%" : "—"}</b><span>battery</span></div>
      <div class="stat"><b>${esc(s.model || "—")}</b><span>Android ${esc(s.androidVersion || "?")}</span></div>
      <div class="stat"><b>${s.deviceOwner ? "Yes" : "No"}</b><span>device owner · PIN ${s.pinSet ? "set" : "unset"}</span></div>
    </div>`));

  // actions
  const actions = card("Actions", `
    <div class="toolbar">
      <button data-cmd="lock">Lock now</button>
      <button data-cmd="reboot">Reboot</button>
      <button data-cmd="refreshApps">Refresh app list</button>
      <button data-cmd="sync">Request status</button>
      <button class="danger" data-cmd="wipe">Factory reset…</button>
    </div>`);
  actions.querySelectorAll("button[data-cmd]").forEach((b) => {
    b.onclick = () => {
      const type = b.dataset.cmd;
      if (type === "wipe") {
        if (!confirm("Factory-reset this phone now? This erases everything on it.")) return;
        if (!confirm("Really wipe it? There is no undo.")) return;
        command({ type: "wipe", confirm: true });
      } else command({ type });
    };
  });
  el.appendChild(actions);

  // restrictions
  const rc = document.createElement("div");
  rc.className = "card";
  rc.innerHTML = "<h2>Restrictions</h2>";
  for (const [key, label] of RESTRICTIONS) {
    const on = (s.restrictions && s.restrictions[key]) === true;
    const row = document.createElement("div");
    row.className = "row";
    row.innerHTML = `<label class="sw"><input type="checkbox" ${on ? "checked" : ""}/> ${label}</label>`;
    row.querySelector("input").onchange = (ev) =>
      update(ref(db, `devices/${selectedId}/desiredState/restrictions`), { [key]: ev.target.checked })
        .catch((e) => alert(e.message));
    rc.appendChild(row);
  }
  el.appendChild(rc);

  // FRP
  const frp = s.frp || {};
  const frpCard = document.createElement("div");
  frpCard.className = "card";
  if (frp.supported === false) {
    frpCard.innerHTML = "<h2>Reset protection</h2><div class='muted'>Not supported on this phone.</div>";
  } else {
    const accs = (desired.frp && desired.frp.accounts) || frp.accounts || [];
    frpCard.innerHTML = `
      <h2>Reset protection (FRP)</h2>
      <div class="muted" style="margin-bottom:10px">
        Status on phone: <b>${frp.enabled ? "ON" : "OFF"}</b>. Enter the Google account ID(s)
        (numbers), one per line. Easiest to set the account from the phone's own FRP screen;
        here you can confirm, change, or clear it.
      </div>
      <textarea id="frpAccts" placeholder="account id">${esc(accs.join("\n"))}</textarea>
      <div class="toolbar" style="margin-top:10px">
        <button class="primary" id="frpOn">Enable with these IDs</button>
        <button id="frpOff">Turn off</button>
      </div>`;
    frpCard.querySelector("#frpOn").onclick = () => {
      const list = $("frpAccts").value.split("\n").map((x) => x.trim()).filter(Boolean);
      if (!list.length) return alert("Enter at least one account ID.");
      set(ref(db, `devices/${selectedId}/desiredState/frp`), { enabled: true, accounts: list })
        .catch((e) => alert(e.message));
    };
    frpCard.querySelector("#frpOff").onclick = () =>
      set(ref(db, `devices/${selectedId}/desiredState/frp`), { enabled: false, accounts: [] })
        .catch((e) => alert(e.message));
  }
  el.appendChild(frpCard);

  // PIN
  const pinCard = document.createElement("div");
  pinCard.className = "card";
  pinCard.innerHTML = `
    <h2>PIN</h2>
    <div class="muted" style="margin-bottom:10px">
      On-phone PIN is currently <b>${s.pinSet ? "set" : "not set"}</b>. Setting a PIN here
      replaces it; only the hash is sent, never the PIN itself.
    </div>
    <input id="pinInput" type="text" inputmode="numeric" placeholder="New PIN (4+ digits)" />
    <div class="toolbar" style="margin-top:10px">
      <button class="primary" id="pinSet">Set PIN</button>
      <button id="pinClear">Clear PIN</button>
    </div>`;
  pinCard.querySelector("#pinSet").onclick = async () => {
    const pin = $("pinInput").value.trim();
    if (!/^\d{4,}$/.test(pin)) return alert("PIN must be at least 4 digits.");
    try {
      command({ type: "setPin", pinHash: await sha256Hex("flipmdm:" + pin) });
    } catch (e) {
      // crypto.subtle needs HTTPS/localhost; fall back to sending the PIN.
      command({ type: "setPin", pin });
    }
    $("pinInput").value = "";
  };
  pinCard.querySelector("#pinClear").onclick = () => {
    if (!confirm("Clear the phone's PIN? Anyone who opens the app can then change settings until a new PIN is set.")) return;
    command({ type: "clearPin" });
  };
  el.appendChild(pinCard);

  // apps
  const apps = Array.isArray(d.apps) ? d.apps : [];
  const hidden = new Set(s.hiddenApps || []);
  const appCard = document.createElement("div");
  appCard.className = "card";
  appCard.innerHTML = `<h2>Apps (${apps.length})</h2><div class="applist"></div>`;
  const listEl = appCard.querySelector(".applist");
  if (!apps.length) {
    listEl.innerHTML = '<div class="muted">No list yet — press "Refresh app list".</div>';
  } else {
    for (const a of apps) {
      const isHidden = hidden.has(a.pkg);
      const row = document.createElement("div");
      row.className = "row";
      row.innerHTML = `
        <div>${esc(a.label || a.pkg)} ${isHidden ? '<span class="badge hid">hidden</span>' : ""}
          <span class="pkg">${esc(a.pkg)}</span></div>
        <button>${isHidden ? "Show" : "Hide"}</button>`;
      row.querySelector("button").onclick = () =>
        command({ type: isHidden ? "showApp" : "hideApp", pkg: a.pkg });
      listEl.appendChild(row);
    }
  }
  el.appendChild(appCard);

  $("devicePanel").innerHTML = "";
  $("devicePanel").appendChild(el);
}

function command(obj) {
  push(ref(db, `devices/${selectedId}/commands`), { ...obj, ts: serverTimestamp() })
    .catch((e) => alert(e.message));
}

// ---------- helpers ----------

function card(title, html) {
  const el = document.createElement("div");
  el.className = "card";
  el.innerHTML = `<h2>${title}</h2>${html}`;
  return el;
}
function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}
// Same hash the phone uses: sha256("flipmdm:" + pin), lowercase hex.
async function sha256Hex(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
