# HIT-C UI Overhaul — Implementation Plan

## Summary

Full visual redesign from terminal/hacker aesthetic to e-ink reader aesthetic. New DrawerLayout with Projects (hero), Sessions, Shortcuts, Settings. Metropolis font family. Logo in header from transparent PNG asset. Every button functional.

---

## Phase 1: Foundation (fonts, colors, theme)

### 1a. Add Metropolis font family
- Download Metropolis Regular, Medium, SemiBold, Bold `.ttf` files
- Add to `app/src/main/res/font/`
- Create `font/metropolis.xml` font family XML
- Keep JetBrains Mono for code blocks only (Markwon syntax highlighting)

### 1b. Replace color palette
Update `colors.xml` to match brief:

| Role | Old | New |
|------|-----|-----|
| Primary Accent | `#E87B35` (orange) | `#FF6B5B` (coral) |
| Background main | `#1A1A1A` (pure dark) | `#454548` (e-ink gray) |
| Background surface/drawer | `#242424` | `#3A3A3C` (darker gray) |
| Background input | `#2E2E2E` | `#3A3A3C` |
| Text primary | `#F0E6D8` (warm) | `#F5F5F5` (off-white) |
| Text secondary | `#BFB09A` | `#9A9A9A` (muted) |
| Status bar / nav bar | Match `#454548` |

### 1c. Update theme
- `themes.xml`: Update colorPrimary to coral, backgrounds to e-ink gray
- Status bar + nav bar = `#454548`
- All `fontFamily="@font/jetbrains_mono"` → `@font/metropolis` (except code output)

---

## Phase 2: Layout Overhaul (DrawerLayout + new header)

### 2a. Convert `activity_main.xml` to DrawerLayout
**Current:** ConstraintLayout (single screen)
**New:** DrawerLayout wrapping main content + left drawer

```
DrawerLayout
├── Main Content (ConstraintLayout)
│   ├── Header Bar (hamburger | logo image | ... | relay badge | settings gear)
│   ├── Conversation ScrollView
│   ├── Shortcut Bar (horizontal scroll, above input)
│   └── Input Area (input field + coral send button)
└── Left Drawer (NavigationView / LinearLayout)
    ├── Drawer Header (logo + app name)
    ├── PROJECTS section (hero — RecyclerView of folder items)
    ├── SESSIONS section (conversation list — RecyclerView)
    ├── SHORTCUTS section (command list)
    └── SETTINGS section (model picker, relay URL, about)
```

### 2b. Header bar redesign
- **Left:** Hamburger menu icon (☰) — opens drawer
- **Center-left:** `hitc-logo-orange-trans.png` loaded as ImageView (not text)
- **Far right:** Relay/Direct badge + Settings gear icon, adjacent
- Remove: "New Session" button from header (moved to drawer Sessions section)
- Remove: "Connect" button (auto-connect on launch, mode shown in badge)

### 2c. Copy logo asset into drawable
- Copy `References and assets/hitc-logo-orange-trans.png` → `res/drawable/hitc_logo.png`
- Use in header as ImageView with fixed height (~28dp)
- Also reference for adaptive icon

---

## Phase 3: Drawer Implementation

### 3a. Projects Section (HERO FEATURE)
**How it works:**
1. When relay is connected, app calls new endpoint `GET /api/projects` on relay server
2. Relay server scans `WORKING_DIR` for subdirectories that look like dev projects (contain `.git/`, `package.json`, `build.gradle.kts`, `Cargo.toml`, etc.)
3. Returns list: `[{ name: "head-in-the-claude", path: "/Users/kylsolutions/Developer/kyl-solutions/head-in-the-claude" }, ...]`
4. App displays as clickable list in drawer

**On tap:**
- Inserts the project path into the chat input field: `Working in /Users/kylsolutions/Developer/kyl-solutions/head-in-the-claude — `
- User cursor placed after the dash for immediate follow-up
- Closes drawer
- Input field focused, ready to type

**When relay is not connected:**
- Projects section shows "Connect to relay to browse projects" in muted text
- Still visible but disabled

**Relay server change:**
- Add `GET /api/projects` endpoint to `server.js`
- Scans `WORKING_DIR` for directories containing project markers
- Returns JSON array of `{ name, path, type }` (type = git/node/gradle/etc)

### 3b. Sessions Section
- RecyclerView showing all conversations from Room DB
- Each item: title + timestamp + message count
- Tap → loads that conversation into main view
- Long-press → delete with confirmation
- "New Session" button at top of section
- Uses existing `ConversationDao.getAllConversations()` Flow

### 3c. Shortcuts Section
- Same shortcuts as current horizontal bar, but as vertical list in drawer
- Each item shows label + one-line description
- Tap → inserts into input (same as current `handleShortcut()` logic)
- Translation shortcuts (Zulu/Sesotho) included

### 3d. Settings Section
- Model picker (Sonnet / Opus / Haiku) — uses existing ApiKeyManager
- Relay URL configuration — uses existing HitcSessionManager
- API key (masked, with "Change" button → opens SetupActivity)
- "About hit(C)" — version, links

---

## Phase 4: Main Content Redesign

### 4a. Conversation area
- Background: `#454548` (e-ink gray)
- Text: Metropolis Regular, `#F5F5F5`, 15sp body
- Line height: 1.6 multiplier (generous, book-like)
- User messages: Slight left indent, coral accent bar on left
- Assistant messages: Full width, no bar
- Paragraph spacing: 12dp between messages
- Remove terminal welcome text, replace with clean empty state

### 4b. Input area
- Background: `#3A3A3C`
- Input field: Metropolis Regular, hint "Ask Claude...", rounded corners (12dp)
- Send button: Solid coral `#FF6B5B`, rounded, no text — just arrow icon
- Camera button: Subtle, left of input (keep existing)
- Remove monospace from input

### 4c. Shortcut bar (main screen)
- Keep horizontal scroll above input
- Restyle: Metropolis Medium, coral border on tap, pill shape
- Only visible when connected (same logic)

---

## Phase 5: Wiring & Polish

### 5a. Wire all drawer actions
- Hamburger → toggles DrawerLayout
- Project tap → drops path into input + closes drawer
- Session tap → loads conversation + closes drawer
- Session long-press → delete dialog
- New Session → creates conversation + closes drawer
- Shortcut tap → inserts command + closes drawer
- Settings items → functional (model picker, relay URL, API key change)

### 5b. Remove dead UI
- Remove `connectButton` (auto-connect handles this)
- Remove `newSessionButton` from header (in drawer now)
- Clean up any unused views/IDs

### 5c. App icon
- Generate adaptive icon from `hitc-logo-orange-trans.png`
- Background: `#1C1C1E` (dark charcoal per brief)
- Foreground: coral logo

---

## Files Modified

| File | Change |
|------|--------|
| `app/src/main/res/layout/activity_main.xml` | Full rewrite → DrawerLayout |
| `app/src/main/res/layout/item_project.xml` | **NEW** — Project list item |
| `app/src/main/res/layout/item_session.xml` | **NEW** — Session list item |
| `app/src/main/res/layout/item_shortcut.xml` | **NEW** — Shortcut list item |
| `app/src/main/res/layout/drawer_content.xml` | **NEW** — Drawer layout |
| `app/src/main/res/values/colors.xml` | Full palette swap |
| `app/src/main/res/values/themes.xml` | Theme updates |
| `app/src/main/res/values/strings.xml` | New strings for drawer sections |
| `app/src/main/res/font/metropolis*.ttf` | **NEW** — Font files |
| `app/src/main/res/font/metropolis.xml` | **NEW** — Font family |
| `app/src/main/res/drawable/hitc_logo.png` | **NEW** — Copied from assets |
| `app/src/main/res/drawable/bg_*.xml` | Update colors |
| `app/src/main/res/drawable/ic_menu.xml` | **NEW** — Hamburger icon |
| `app/src/main/res/drawable/ic_settings.xml` | **NEW** — Gear icon |
| `app/src/main/res/drawable/ic_send.xml` | **NEW** — Send arrow |
| `app/src/main/res/drawable/ic_folder.xml` | **NEW** — Project folder icon |
| `app/src/main/res/drawable/ic_new_session.xml` | **NEW** — New session icon |
| `app/src/main/java/.../MainActivity.kt` | Major rewrite — drawer, projects, sessions |
| `relay-server/server.js` | Add `GET /api/projects` endpoint |
| `app/src/main/java/.../RelayClient.kt` | Add `getProjects()` method |

## Files NOT Modified
- `AnthropicClient.kt` — no changes needed
- `database/` — existing schema is sufficient
- `repository/SessionRepository.kt` — already has all needed queries
- `SetupActivity.kt` — stays as-is (launched from settings)
- `TranslateActivity.kt` — stays as-is (launched from shortcuts)

---

## Estimated Scope
- ~15 new/modified layout XML files
- ~1 new font family (4 weights)
- ~400-500 lines of new Kotlin in MainActivity (drawer management, project loading, session list)
- ~30 lines added to relay server (projects endpoint)
- ~20 lines added to RelayClient (getProjects)
- Total: Medium-large — 2-3 focused sessions
