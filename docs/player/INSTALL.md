# Installing the co-op mod

Two people, two copies of Starsector, one shared campaign. Both of you do everything on this page.
There is no "client-only" install; the two machines run the same mod and check each other at
connect time.

## Quick start

1. Unzip so `mod_info.json` sits in `<Starsector>\mods\coop`.
2. Double-click `<Starsector>\mods\coop\Coop Launcher.cmd`.
3. Press **Fix** on every red row in the Install card. That is the classpath entry in `vmparams` and
   the mod tick in `enabled_mods.json`, the two things the mod cannot do for itself once the game is
   running.
4. Host: press **Host** at the top right, leave **Campaign** on `New campaign`, press **Copy** next
   to the invite line, send that line to your partner.
5. Guest: press **Guest**, press **Paste** next to the invite field. Address, port, password, seed,
   sector size and star age all come out of the line.
6. Both press **LAUNCH**, then **Play** in the vanilla launcher window that follows.
7. Both start a New Game, or load the save named on the line under the card, whichever that line
   says.

Everything below is the same thing at length, plus what to do when a step does not go through.

---

## 1. What has to match on both PCs

| Thing | Requirement |
|---|---|
| Starsector version | `0.98a-RC8` on both. A different RC is refused at connect. |
| The co-op mod | The same download, unzipped on both. Two different builds are refused even when the version number matches. |
| Mod folder name | Exactly `coop`, so the path reads `<Starsector>\mods\coop` on both. |
| Every other mod | Same list, same versions, on both. |
| Ironman | Off on both. A session where either side is in Ironman mode is refused. |

Section 8 explains what the refusal message looks like and how the check works.

## 2. Unpack the mod

Unzip so that `mod_info.json` sits directly in `<Starsector>\mods\coop`:

```text
K:\Starsector\mods\coop\mod_info.json
K:\Starsector\mods\coop\jars\coop.jar
K:\Starsector\mods\coop\jars\coop-forks.jar
K:\Starsector\mods\coop\data\
```

If your unzip produced `mods\coop\coop\mod_info.json`, move the inner folder up one level. If it
produced `mods\starsector-coop-1.0\`, rename it to `coop`. The folder name travels over the wire as
part of the install comparison, so `coop-v1` on one PC and `coop` on the other will not connect.

## 3. Put `coop-forks.jar` on the classpath

**Press Fix in the launcher and it is done.** Start `Coop Launcher.cmd` (section 5). The Install card
shows a red row reading `coop-forks.jar first on the JVM classpath`, with a **Fix** button on it. The
button copies `<Starsector>\vmparams` to `vmparams.backup`, then puts one 33-character entry at the
front of the classpath. Nothing else on the line changes, the file stays one line, and no newline is
added to the end of it. If a `vmparams.backup` is already sitting there it is left alone, so the copy
you took before your first edit is the one that survives.

A Starsector update overwrites `vmparams` and the row goes red again. Press **Fix** again; there is
nothing else to redo.

Why the file has to be touched at all: `jars/coop.jar` loads the way every mod's jar loads, from
`mod_info.json`. `jars/coop-forks.jar` cannot. It holds ten engine classes copied from Starsector and
modified (`Misc`, `RouteManager`, `SourceBasedFleetManager` and seven more), and the JVM only prefers
a copy over the original if the copy comes earlier on the system classpath. Starsector's mod loader is
a child classloader, which is too late. So the jar has to be named in `vmparams`, the file holding
the JVM command line the game starts with.

**If you skip it, the game still starts.** The forked classes fall back to stock behaviour and the
log carries one warning:

```text
Coop guest presence unavailable: coop-forks.jar is not on the JVM classpath, so the spawner forks
cannot be reached. NPC fleets will only spawn near the host.
```

That means patrols, traders and pirates cluster around the host and largely ignore the guest. The
session works; the world is lopsided.

### When Fix cannot do it

Two cases send you to the manual edit. On an install under `Program Files`, Windows refuses the write
to an ordinary process: the launcher asks whether to restart itself as administrator, and if you
approve the Windows prompt a second launcher window opens and makes the edit. Refuse the prompt and
nothing is written. On a modded-JRE install the game starts from a `.bat` file carrying its own
`-classpath` and `vmparams` is never read at all, so the launcher refuses to touch it and prints these
instructions instead.

**Back up the file first.** Copy `<Starsector>\vmparams` to `vmparams.backup`.

Open `<Starsector>\vmparams` in a text editor. It has no extension and holds one very long line.
Find the text ` -classpath ` and insert this immediately after it, semicolon included:

```text
..\mods\coop\jars\coop-forks.jar;
```

The classpath on a stock 0.98a-RC8 Windows install starts like this:

```text
 -classpath janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;...
```

After the edit:

```text
 -classpath ..\mods\coop\jars\coop-forks.jar;janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;...
```

Rules for the edit:

- The relative path is correct as written. The launcher runs the JVM from `starsector-core`, so `..`
  is the install root.
- It must be first in the list. Behind `starfarer.api.jar` it does nothing.
- Save as plain text. Do not let the editor turn the single line into several, and do not change
  anything else on it. A `vmparams` that arrives split over several lines is one the launcher's Fix
  button will refuse, because it cannot tell which line the JVM reads.
- Redo the edit after any Starsector update; the installer overwrites `vmparams`.
- On a modded JRE setup where the launcher is a `.bat` file instead, put the same entry at the front
  of that file's `-classpath`.

## 4. Enable the mod

The co-op launcher's `co-op enabled in mods\enabled_mods.json` row carries the same **Fix** button,
which adds `"coop"` to `<Starsector>\mods\enabled_mods.json` and leaves every other mod in the list
where it was. It writes the file the vanilla launcher would have written, so ticking and unticking
mods afterwards works as normal.

By hand instead: start Starsector's own launcher (`starsector.exe`), click MODS, tick **Starsector
Coop V1**, click OK. Both players. If either of you has other mods ticked, the other must tick the
identical set.

## 5. The launcher

Double-click `Coop Launcher.cmd` in `<Starsector>\mods\coop`. It runs on the JRE that ships with the
game, in `<Starsector>\jre`, so there is nothing to install and no Java to set up. Windows only in
this release.

The first time you run it after a download, Windows may put up an "Open File - Security Warning" box
for the `.cmd`. Press Run. That prompt comes from the download flag Windows puts on the file, and it
appears once.

The launcher works out where the game is from its own location, so leave `Coop Launcher.cmd` in
`<Starsector>\mods\coop`; a shortcut to it is fine, a copy on the desktop is not. If it guesses
wrong anyway, **Folder** in the Install card sets it straight.

What it does: it writes your settings into `saves\common\coop_options.json.data`, tells you about
anything wrong with the install, and starts `starsector.exe`. It does not replace the vanilla
launcher; that window still comes up and you still press Play in it. The only files it edits outside
`saves\common` are `vmparams` and `mods\enabled_mods.json`, and only when you press **Fix** on the row
that names one of them. Every other red row is reported with the fix and left to you.

### The host's fields

1. Press **Host**, the button at the top right of the window. That is what makes this install the
   host.
2. **Campaign** picks what this session is. It opens on `New campaign (seed ...)`, which is the right
   answer the first time. Once you have saved a co-op campaign, that campaign appears in the
   drop-down as its newest save, labelled the way the game's own load screen labels it: character,
   level, in-game date, and when you saved. Pick one to carry on with it; the folder name shows up
   under the drop-down, and **Seed**, **Sector size** and **Star age** go grey, because a save
   already has its sector and those three only do anything for a new game.
3. Leave **Port** at 7777 unless something else on your PC wants it. It is the TCP and UDP port your
   partner connects to.
4. **Password** fills itself in with a generated one when you leave it empty. The eye button next to
   it reveals what it holds. The invite carries the password, so your partner never types it.
   Clearing the field is allowed, leaves the port open to anyone who finds it while a session is
   waiting, and stops the launcher refilling it.
5. **Seed** is filled in for you when the launcher opens. Press **Generate** for a different one, or
   type your own. Both games generate the sector locally from this string, and the check at connect
   compares what came out. It only matters for a new campaign.
6. **Your address** is looked up for you when the launcher opens with the field empty. That is one
   HTTPS request to a service that replies with the address your packets came from; **Look up**
   repeats it. If the two of you connect over a LAN or a VPN, type that address over the answer.
7. **Sector size** and **Star age** are two drop-downs, defaulting to `normal` and `mixed`. Change
   them only if you want a different world; the invite carries whatever you pick, so your partner
   does not have to match them by hand.
8. Press **Copy** next to **Invite for your partner**, which updates on its own as you fill in the
   fields above. Send your partner the one line it puts on your clipboard.

The invite looks like this, and it carries the password in clear text, so send it the way you would
send a password:

```text
coop://203.0.113.9:7777/?seed=MN-1234567890123456789&pw=hullmod&size=normal&age=mixed
```

Picking an existing campaign adds one more part on the end, `cid`, which is that campaign's id. It is
what lets your partner's launcher name the save they should load. An invite for a new campaign does
not carry it.

### The line that says which save to load

The launcher cannot load a save for you, and it never touches your saves folder. What it does is
name the right one, under the card, in a single line:

```text
Load the save "Kaz Alba", level 12, saved 2026-05-28 18:16 (folder save_Kaz_1402953574686505955).
```

Start the game, press **Load Game**, and pick the slot that matches. The folder name in brackets is
there for the case the load screen shows you two saves that look alike; it is the folder under
`<install>\saves`.

The other two things it can say are just as literal. `Start a New Game with the seed above` means
this session is a fresh campaign, so neither of you loads anything. `No co-op save for this campaign
on this machine: start a New Game with the seed above` means the campaign your partner picked has
never been saved on your PC, which is what a first session looks like from the guest's side.

If the launcher cannot read `saves\common\coop_saves.json.data`, or that file was written by a newer
version of the mod than the launcher, it says so on that same line and stops naming saves. Nothing
else changes and **LAUNCH** still works.

The mod checks the same thing from inside the game. Load a save belonging to a different campaign
than the one the launcher was pointed at and a message names the save you meant to load instead. It
is a warning, not a refusal: it appears, you close it, and the game carries on. Loading an unrelated
save on purpose is allowed.

### The guest's fields

Press **Guest** at the top right, put the host's line on your clipboard, and press **Paste** next to
**Invite from your host**. Typing or pasting the line into that field fills in the rest by itself:
address, port, password, seed, sector size and star age. An invite that will not parse says which
part of it failed rather than clearing the fields. **Seed**, **Sector size** and **Star age** are
read-only here on purpose: they come from the invite, and they are used only when you start a new
campaign. Rejoining by loading a co-op save ignores all three. **Sector size** and **Star age** read
`normal` and `mixed` when the invite does not carry them, the same defaults the host's drop-downs
open on.

An accepted invite also puts the save line described above under the guest card, so you know before
you press **LAUNCH** whether this session starts with **Load Game** or with **New Game**. There is no
campaign drop-down on the guest side: the host decides which campaign the session is, and the invite
carries it.

You can also type **Host address** and **Port** in by hand and have the host tell you the password.
The seed still has to match, which is what the invite is for, and so do sector size and star age. The
guest's fields for both are read-only, so if the host changed either away from `normal` and `mixed`
and you are not pasting the invite, the only way to set them is by hand in the settings file, covered
in section 9.

### Check my connection, and Test connection

Do this once, before the first session, in this order. The host presses **Check my connection**,
reads the chips it puts up, and leaves the launcher open; the guest then presses **Test connection**
with the invite pasted in and reads four chips of its own: TCP, `launcher <version>`, UDP and a round
trip in milliseconds. Green on all four and there is nothing left to check.

`CONNECT.md` reads both sets of chips line by line and says what to do about a red one. If TCP
connects and no launcher answers, the usual cause is that the host's game is already running instead
of the host's launcher, and then there is nothing to test: press LAUNCH.

### The install check

The Install card shows a summary chip (`all N checks passed`, `1 warning`, `2 problems`) and lists
only the rows that are not `OK`, badged `OK`, `INFO`, `WARN` or `FAIL`; **Show all checks (N
passed)** reveals the rest. **Refresh** re-runs them after you fix something, **Guide** opens this
file, and **Folder** points the launcher at your Starsector folder when it could not work out where
the game is. Whatever a **Fix** button did, or refused to do, is written into the log drawer at the
bottom of the window.

| Row | Fails when |
|---|---|
| `Starsector install` | The folder the launcher is looking at has no `starsector.exe`, `vmparams` and `starsector-core` in it. |
| `starsector.exe` | The executable is not next to the `mods` folder. |
| `jre\bin\javaw.exe` | The install has no bundled JRE. A modded-JRE install starts the game from a `.bat` instead; launch it that way and use this window for settings only. |
| `starsector-core` | The folder is missing. |
| `mods\coop\jars\coop.jar` | The mod is unpacked wrong or incompletely. |
| `mods\coop\jars\coop-forks.jar` | Same. |
| `coop-forks.jar first on the JVM classpath` | Section 3 was skipped, or the entry is on the line but behind another jar, where it does nothing. Any way of writing the path counts (relative or absolute, either slash, any capitalisation); only its position on the line matters. **Fix** makes the edit, moving an entry that is in the wrong place rather than adding a second copy. |
| `no leftover -Dcoop.* in vmparams` | See below. No **Fix** button: these are flags somebody put there deliberately, and the launcher does not delete them. |
| `co-op enabled in mods\enabled_mods.json` | The mod is not ticked. **Fix** ticks it. |
| `mod_info.json version matches coop.jar` | Two builds got mixed in one folder. Delete `mods\coop` and unzip once. |
| `Game version` | Your Starsector is not the one the mod was built for. Part of the mod is compiled against the game's own classes, so the mod refuses to start a session on any other version and says `COOP-GAME`. The version is read out of the `Starting Starsector <version> launcher` line the game writes at the top of `starsector-core\starsector.log`, so before the game has run once here the row reads `unknown until the game has run once`. Ticking **Allow game version mismatch** under Advanced drops it to a `WARN` and lets LAUNCH work. |
| `settings file saves\common\coop_options.json.data` | The file exists and is not readable as plain JSON. The launcher refuses to overwrite it, because that would throw away every setting in it. |
| `Update available: <version>` | Not a failure. One request to GitHub's releases API at start, compared against the version baked into your jar, with an **Open release page** button on the row. It reads `Up to date: <version>` when you have the newest release, and `Update check: unavailable` with the reason when the request did not go through. |

**The `-Dcoop.*` warning is the one that will bite you.** A `-D` property on the `vmparams` line
outranks the settings file, so a leftover entry silently wins over everything you typed in the
launcher: an old port, an old address, a seed from a campaign you finished. The row names the exact
entries to delete. They get left behind by the developer launch scripts, so a normal install will not
have any.

Every row that says `FAIL` is a reason the session will not work, and LAUNCH refuses while one is
outstanding, naming each one. A `WARN` row is a reason it will work differently than you meant.

### Advanced, and LAUNCH

The **Advanced** button in the footer folds open a card holding the settings a normal session never
needs: **Port mapping** (`auto`/`off`), **Link HUD corner** (`TR`/`TL`/`BR`/`BL`), **Reconnect grace
(seconds)**, **Agent bridge port (0 = off)**, **Wiretap sample (every Nth)**, **Interaction delay
(ms)**, and a **Developer flags** group of checkboxes: **Diagnostics**, **Datagram wiretap**, **Frame
profiler**, **Full-fidelity guest system** (on by default), **Disable shared fast-forward**, **Disable
clock reconciler**, **Allow game version mismatch**, and **Start over inside the host's campaign
(guest)**. **Allow game version mismatch** runs the mod on a Starsector it was not built for, which
is there so a tester can try a new release candidate before the forks are rebuilt for it; nothing on
that version has been tested and nothing about it is supported. Every field already shows its real
default rather than a placeholder, which is what the card's own hint says: `Defaults shown. Change
only with a reason.` Leave the card closed unless you have one.

**LAUNCH** writes the settings file, closes the launcher's listener so the game can bind the port,
and starts `starsector.exe`; the button then reads `RUNNING` for as long as the game is up. The
window stays open afterwards, the log drawer opens on its own, and it tails `starsector.log`: the
connection doctor block, the `[COOP-DOCTOR]` lines, and every co-op warning. Closing the window does
not close the game.

The **Log** button in the footer opens and closes that same drawer at any time. Inside it,
**Save a bug report** packs a zip on your Desktop with the logs, your settings, your save and a
summary, and the **Include my newest save** checkbox next to it leaves the save out when unticked;
`REPORTING.md` covers what goes in the zip and what to do with it. **Open log folder** opens
`starsector-core`, and **Clear** empties the pane.

## 6. First session

1. Both of you: `vmparams` edited, mod ticked, and every install-check row reading `OK`.
2. Host: open `Coop Launcher.cmd`, press Host, check the port, seed and address it filled in, press
   Check my connection and read the chips, press Copy next to Invite for your partner, send the line.
3. Guest: open the launcher, press Guest, press Paste next to Invite from your host, press Test
   connection. Four green chips and you are done checking. LAUNCH stays grey until the host address
   is filled in, and the footer says what is missing.
4. Both press LAUNCH, then Play in the vanilla launcher window when it appears.
5. Both start New Game. The guest's Continue option names the host it will connect to, and the seed
   is already filled in from the invite.
6. Both games load paused into the lobby, with the guest's join steps listed as they pass. The host
   presses Start; a three second countdown runs; the clock starts.
7. The status line in the corner reads `HOST · waiting for guest`, then `session active` on both.

If it does not get that far, `CONNECT.md` is the next stop, and `REPORTING.md` says what to send.

## 7. Updating and uninstalling

**Both of you install the same download.** The handshake compares the git commit baked into the jar,
not just the version string, so two copies of "the same" version obtained separately are refused with
`COOP-MODS`. A new release means both of you update before the next session.

The launcher checks at start and puts the answer in the install-check panel: `Update available:
<version>`, `Up to date: <version>`, or `Update check: unavailable` with the reason. It is one request
to GitHub's releases API and it never blocks the window. If you run LazyWizard's Version Checker, the
mod ships a `coop.version` file, so it reports there too.

**A mod update can invalidate a co-op campaign in progress.** Save compatibility across releases is
not promised. Unless a release note says a save carries over, finish a campaign on the build you
started it on, or update together and start a new one.

A Starsector update overwrites `vmparams`, which drops the classpath entry from section 3. The
install check goes red again at the next launcher start and the **Fix** button on that row puts the
entry back.

To uninstall, delete `<Starsector>\mods\coop`. The classpath entry in `vmparams` can stay where it
is: the JVM ignores an entry pointing at a jar that is not there, so it costs nothing. Remove it if
you prefer a clean file. Two things do not go with the folder.
`saves\common\coop_options.json.data` lives in a Starsector folder rather than a mod folder, so
delete it by hand if you want your settings gone. And a campaign that was played in co-op needs the
mod to load at all, on both sides, from then on.

---

## 8. Why identical installs are required

Before a session starts, both games send each other a manifest and compare it field by field. One
difference in any field ends the connection with the difference printed as the reason. What gets
compared, from `coop.handshake.CoopHandshakeManifest` and `CoopHandshakeDiff`:

- The Starsector version string.
- The co-op mod's own build version and git commit, so two separately built copies of "the same"
  version are still caught. A build made from a modified checkout is stamped `<hash>-dirty` and is
  caught the same way.
- For every enabled mod: id, display name, version, declared game version, folder path as
  `mods/<name>`, and the list of jars it declares.
- For every enabled mod: a SHA-256 of its `mod_info.json` text, with line endings normalised so a
  CRLF/LF difference does not read as a mismatch.

Jar contents are not hashed. Starsector's script sandbox hands the mod no way to read jar bytes, so
two people who edited a mod's jar without touching its `mod_info.json` would pass this check. Install
mods from the same downloads and it does not come up.

Ironman is checked separately and rejects the session on its own, on either side.

## 9. Changing settings without the launcher

The launcher writes `saves\common\coop_options.json.data`, and that file is not private to it. The
in-game options page writes the same file, the mod reads it at every launch, and you can edit it in a
text editor. Everything below is that same set of values reached another way; a normal session needs
none of it.

### The "Coop Options" page

Open the intel screen, pick the **Coop** tag in the filter list, and open **Coop Options**. It sits
under the same tag as the session and stats pages and it is there from the first frame of a campaign
running under the mod, session or no session.

Three groups on it:

- **Session rules (host).** The rules this campaign is played under. They live in the campaign's own
  save and the host sends them to the guest, so both of you read the same value. The host presses the
  buttons; the guest reads each value with `(host setting)` after it and no button next to it.
- **Your preferences.** Local to your install and never sent to your partner: whether the link HUD is
  drawn, which corner it sits in.
- **Connection (read at launch).** Host port, join address and port, router port mapping, password,
  display name. Nothing reads these once a session has started, so the buttons work only while no
  session is running; during a session each row says `takes effect at next launch` instead.

Changes in the last two groups are written to `saves\common\coop_options.json.data` for you, and
the page creates that file if you do not have one. A session rule goes into the campaign's save
instead, and your partner is told in their event feed: `Co-op: the host set <name> to <value>.`

The intel screen draws buttons, not text fields, so the vocabulary is short: `Turn on` / `Turn off`
for a yes-or-no setting, `Change to <value>` to walk an enum, `Less` / `More` in 15 second steps for
the reconnect window, and `Clear` for the password. An address, a name or a new password still has to
be typed into the settings file, and those rows print
`text setting - edit saves/common/coop_options.json.data` with no button. `Reset to defaults` at the
bottom puts everything back to the shipped values; pressed by a guest it resets only that guest's own
preferences.

Three changes stop and ask first, with a dialog that names what you lose rather than asking whether
you are sure: turning off the pause while a guest reads a screen, clearing the password, and moving
the reconnect grace.

### Reading a row

Every value carries a tag saying where it came from:

| Tag | Means |
|---|---|
| `(host setting)` | You are the guest, and this is one of the host's session rules. The missing button is the design, not a fault. |
| `(this campaign)` | A session rule this campaign has stored in its save. |
| `(command line)` | Set as `-Dcoop.<key>=` in `vmparams`, and that layer is the one supplying the value on screen, so the row is read-only. A campaign that has already stored its own value for a session rule reads `(this campaign)` instead, and the host can still change it. |
| `(your settings)` | It comes from your `saves\common\coop_options.json.data`. |
| `(default)` | Nothing has set it and you are reading the value the mod ships with. |

A session rule that cannot take effect the instant it changes shows `pending - applies <when>` under
it until the moment arrives: `next screen open/close`, `next connection attempt`, `next drop`, `next
battle result`, `next month tick`, `next colonization`. Nothing here applies backwards. The one that
comes up in play is the guest-screen pause: flip it while your partner has the map open and it waits
for them to close it, rather than pulling the pause out from under them.

### Rows that do nothing yet

Seven rows are on the page with the setting stored and the behaviour not built. They carry the note
`no effect in this build - <phase> wires it`, and pressing the button changes the stored value and
nothing else. Five of them are session rules:

| Row | Note it prints |
|---|---|
| Guest may pause the world | `no effect in this build - Phase 25 wires it` |
| Allow joining a session in progress | `no effect in this build - Phase 27 wires it` |
| Battle loot split | `no effect in this build - Phase 22 wires it` |
| Colony income split | `no effect in this build - Phase 24 wires it` |
| Guest asks before colonizing | `no effect in this build - Phase 24 wires it` |

The other two are preferences: **Event feed detail** (`Phase 20.6 wires it`) and **Partner marker
colour** (`Phase 8 wires it`).

Two further session rules work, but not the way the rest of the group does. **Maximum guests** and
**Reconnect grace** both print
`each install reads its own value at launch; shown here so both players can see it`, because both are
read before any campaign exists. They are on the page so the two of you can compare them. Maximum
guests has no buttons at all: 1 is both its bounds in this build.

### The settings file

The same `coop.*` names live in a JSON file. Yours is here. The launcher rewrites it every time you
press LAUNCH, the options page creates it the first time you change a setting there, and you can
write it yourself:

```text
<Starsector>\saves\common\coop_options.json.data
```

The `.data` on the end comes from Starsector, not from the mod: the engine appends it to every file
a mod writes into `saves\common`, so that is the name to look for and to type into your editor. What
is inside is plain JSON whatever the extension says.

Flat JSON, only the keys you want to change:

```json
{
	"coop.hostPort": 7777,
	"coop.password": "hullmod",
	"coop.playerName": "Ayo",
	"coop.hudCorner": "BL"
}
```

Numbers, booleans and strings are all accepted for any key; the value is validated after it is read,
not before. `saves\common` is a Starsector folder rather than a mod folder, which is the entire
reason the file lives there: it survives updating or reinstalling the mod.

The mod also ships a reference copy at `<Starsector>\mods\coop\data\config\coop_options.json`,
listing every file-backed key with its type, its range, its default, when a change takes effect and
which phase owns the behaviour. It is overwritten on every mod update, so your values do not go in
it.

Precedence, highest first:

1. `-Dcoop.<key>=<value>` on the launch command line
2. `saves\common\coop_options.json.data`
3. `mods\coop\data\config\coop_options.json`
4. the default compiled into the mod

Both files are read once at launch, so anything you type into them by hand needs a relaunch. A change
made on the options page skips that: the page rewrites your file and the mod re-reads the key on the
spot, though a connection setting is still only picked up when a session starts. A bad value never
stops the game: an integer outside its range is clamped to the nearest bound, anything else unusable
falls back to the default, and either way one warning goes to the log. An unrecognised key is also
logged and skipped.

**The role keys carry one extra rule.** If any of `coop.hostPort`, `coop.connectHost` or
`coop.connectPort` is given as `-D`, the role is decided by the `-D` layer alone and the file's role
keys are ignored. Without that, a `coop.hostPort` sitting in your settings file would combine with a
`-Dcoop.connectHost` on the command line into "Configure either host or guest coop startup
properties, not both", and a machine set up to host could never be launched as a guest. Every other
key keeps plain per-key precedence.

### Every setting

The same names work as JSON keys in the settings file and as `-D` properties on the `vmparams` line,
anywhere before ` -classpath `:

```text
... -Xss4m -Dcoop.hostPort=7777 -Dcoop.newGameSeed=MN-1234567890123456789 -classpath ..\mods\coop\jars\coop-forks.jar;janino.jar;...
```

That layer outranks the launcher, the options page and both files, which is the whole reason the
install check warns about a leftover `-Dcoop.*`. It is a developer path; a player has no reason to
use it.

**Connection, read at launch.** Per install, never synced.

| Key | Default | Meaning |
|---|---|---|
| `coop.hostPort` | unset | The TCP and UDP port to listen on, 1 to 65535. Setting it is what makes this install the host. Empty means "not hosting". |
| `coop.connectHost` | unset | The host's address to join as guest. Hostname, IPv4, or IPv6 (bare `2001:db8::1` or bracketed `[2001:db8::1]`, never with the port glued on). Empty means "not joining". |
| `coop.connectPort` | unset | The host's port. Required whenever `coop.connectHost` is set. |
| `coop.portMapping` | `auto` | `auto` asks the router to open the host port (UPnP first, NAT-PMP second). `off` skips it. Any other value as a `-D` stops the game at startup. Guests never map anything. |
| `coop.password` | none | Lobby password. Same string on both, empty for none. The guest proves it knows the password without sending it, but the rest of the traffic is plaintext: this stops strangers from joining an open port, it does not encrypt the session. |
| `coop.playerName` | your character's name | The name your partner sees in the HUD and on the session page. |

**Session rules.** The host owns these. A new campaign takes them from the host's install at its
first frame and stores them in its own save from then on, so editing them here does nothing to a
campaign already in progress; use the options page for that.

| Key | Default | Meaning |
|---|---|---|
| `coop.maxGuests` | `1` | Peer capacity. Anything other than 1 is clamped back to 1 with a warning in the log. |
| `coop.reconnectGraceSeconds` | `60` | How long a dropped link is held open before the session ends, 0 to 3600. `0` ends it on the first drop. Each side sets its own. |
| `coop.pauseOnGuestScreens` | `true` | Whether the world stops while a guest browses the vanilla auto-pause screens. `false` lets the world keep running. Interaction dialogs and combat pause either way. |
| `coop.allowGuestPause` | `true` | Whether the guest may pause the shared world. No effect in this build. |
| `coop.allowMidSessionJoin` | `true` | Whether a guest may join a campaign already in progress. No effect in this build. |
| `coop.lootSplit` | `equal` | How salvage from a jointly fought battle is divided. No effect in this build. |
| `coop.incomeSplit` | `equal` | `equal` splits colony income 50/50; `host-banks` pays it all to the host. No effect in this build. |
| `coop.guestColonizationConsent` | `false` | `true` makes a guest founding a colony ask the host first. No effect in this build. |

**Your preferences.** Local, never sent to your partner.

| Key | Default | Meaning |
|---|---|---|
| `coop.hud.disable` | `false` | `true` removes the co-op status line. Re-read while the game runs. |
| `coop.hudCorner` | `TR` | Which corner the status line sits in: `TR`, `TL`, `BR`, `BL`. Re-read while the game runs. |
| `coop.feedVerbosity` | `all` | How much of the co-op event feed is shown: `all`, `important`, `minimal`. No effect in this build. |
| `coop.partnerColor` | unset | Colour of your partner's presence marker. No effect in this build. |

**One-shot and diagnostic keys.** These never appear in the shipped defaults file, and a value for
one of them in `data\config\coop_options.json` is skipped with a warning. Your own settings file is
not off limits to them: the launcher's Session and Advanced cards write them there, and at the next
launch the mod republishes each one it finds as the matching `-D` system property, unless a real `-D`
for that key is already on the `vmparams` line, which still wins. Editing one in by hand works the
same way.

| Key | Default | Meaning |
|---|---|---|
| `coop.newGameSeed` | unset | The sector seed, e.g. `MN-1234567890123456789`. Same value on both PCs; both New Game dialogs are pinned to it. |
| `coop.sectorSize` | unset | `small` or `normal`. Omit for the panel default (`normal`). Must match. |
| `coop.sectorAge` | unset | `young`, `average`, `old` or `mixed`. Omit for the panel default (`mixed`). Must match. |
| `coop.adoptCampaignId` | `false` | Guest only. Overrides the seed lock and adopts the host's in-flight campaign id, at the cost of this save's progress. Struck from your settings file as soon as it has been published, so it applies to one launch and no other. |
| `coop.expectedCampaignId` | unset | The campaign id the invite is for. The mod says so when the save you load belongs to a different campaign, and names the right one; it warns, never blocks. Struck from the file the same way. |
| `coop.allowGameVersionMismatch` | `false` | Lets the mod run on a Starsector other than the one it was built for. For testing a release candidate; nothing on that version is supported. |
| `coop.fullFidelityGuestSystem` | `true` | Kill switch for the full-fidelity guest-system driver. |
| `coop.ff.disable` | `false` | Forces the shared fast-forward lock unavailable. |
| `coop.clock.disable` | `false` | Turns off the clock reconciler and leaves calendar drift uncorrected. |
| `coop.debug.diagnostics` | `false` | Master switch for the dormant diagnostics: orbit dumps, dialog state, probes. |
| `coop.debug.bridge` | `0` | Port for the 127.0.0.1 agent bridge. `0` means no socket. |
| `coop.debug.wiretap` | `false` | Sampled message payloads plus per-type size histograms. |
| `coop.debug.wiretapSample` | `10` | With the wiretap on, log every Nth datagram per direction and type. |
| `coop.debug.frameProfile` | `false` | Per-frame pump profiler in the log. |
| `coop.debug.interactionDelayMs` | `0` | Makes the host hold every inbound interaction claim this many ms. A test instrument. |

Seed, size and age all feed the sector fingerprint the two games compare, so a difference in any of
them is caught at connect rather than discovered hours later. Setting host and guest properties on
the same install stops the game at startup with "Configure either host or guest coop startup
properties, not both".

The launcher's Advanced card sets `coop.portMapping`, `coop.hudCorner`, `coop.reconnectGraceSeconds`,
`coop.debug.bridge`, `coop.debug.wiretapSample`, `coop.debug.interactionDelayMs` and every Developer
flag checkbox, writing the key into your settings file only when you move it away from its default.
`coop.expectedCampaignId` is set by the host's **Campaign** drop-down and by the `cid` part of a
pasted invite, not by a field you can type into. `coop.password` and `coop.playerName` are Session
card fields. `coop.hud.disable`, `coop.maxGuests` and the session rules have no launcher field at all.

**Start over inside the host's campaign** is the Advanced card's form of `coop.adoptCampaignId`, and
it is never remembered between launches: ticking it is one-shot consent for that single launch, and
it discards the guest's co-op progress. That is the same escape the dev scripts call `-AdoptCampaign`.
The rejoin path it exists for is the ordinary one: load the co-op autosave the mod wrote, not New
Game, because a fresh campaign on the same seed is refused while the host's campaign is in flight.

Cadences, gate semantics, the seed and campaign checks, combat auto-pause, host authority itself: not
options, and not going to become options. The shipped file names that list too, so you can tell a
missing setting from a deliberate one.
