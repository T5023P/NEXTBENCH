# NextBench Android — Design Spec

Date: 2026-07-27
Status: Approved design (full-phase spec)
Target directory: `nextbench/` (own fresh git repo)
Source of truth: the existing web app at `../nextbench-1` (React 19 + Firebase project `nextbench-a11ed`)

---

## 1. Product summary

NextBench is a verified student-to-student marketplace **and** campus social network. The Android app is a
ground-up native rewrite that reaches full feature parity with the website while feeling dramatically more
premium: WhatsApp/Telegram-smooth chat, Instagram-smooth scroll and stories, meaningful motion, custom
iconography, and native-only refinements (haptics, share targets, offline, push).

The app **reuses the live Firebase backend** (`nextbench-a11ed`): the same users, listings, chats, stories,
clubs, notifications, and the ~50 existing Cloud Functions. Nothing about the backend schema changes; the
Android client is a new consumer of the same data contract.

### Brand (from web `DESIGN.md` / `PRODUCT.md`)
- Audience: students 13–19. Emotional goal: **trust & safety first**, then quiet excitement.
- Tone: premium & refined but warm — Apple craft × Notion friendliness × Monzo confidence.
- Anti-references: not generic SaaS, not Facebook Marketplace/Craigslist, not a toy, not a bank.

---

## 2. Tech stack & architecture

| Concern | Choice |
|---|---|
| Language | Kotlin (100%) |
| UI | Jetpack Compose + Material 3 (custom theme, not stock M3 look) |
| Min / Target SDK | `minSdk 26`, `targetSdk 35` |
| Pattern | MVVM + Repository; unidirectional data flow |
| Async / realtime | Coroutines + Flow; Firestore listeners wrapped as `callbackFlow` |
| DI | Hilt |
| Navigation | Navigation-Compose, type-safe routes (sealed route objects) |
| Images | Coil (custom crossfade + shimmer placeholder) |
| Local prefs | DataStore (theme, onboarding, cached session flags) |
| Backend | Firebase Android SDK via BoM: Auth, Firestore, Storage, Functions, Messaging (FCM) |
| Image uploads | Cloudinary unsigned upload (parity with web); video/audio → Firebase Storage |
| Build | Gradle KTS, version catalog (`libs.versions.toml`) |

### Module layout (single Gradle project, multi-module)
```
nextbench/
  app/                     # Application, MainActivity, nav host, DI wiring, FCM service
  core/
    designsystem/          # tokens, theme, typography, custom icons, motion kit, base components
    common/                # Result types, dispatchers, extensions, formatters
  data/
    model/                 # Kotlin data classes for every Firestore collection
    firebase/              # Firebase providers, Cloudinary uploader, Functions callables
    repository/            # AuthRepository, FeedRepository, ChatRepository, ... (Flow-based)
  feature/
    auth/  feed/  marketplace/  chat/  stories/  clubs/
    profile/  notifications/  search/  admin/  settings/
  docs/specs/              # this spec + future phase specs
```
Each `feature/*` module owns its screens, ViewModels, and Compose UI, depending on `core/*` and `data/*`.

### Config placeholders the user provides
- `app/google-services.json` (from Firebase console, project `nextbench-a11ed`).
- Cloudinary cloud name + unsigned preset, and Giphy key → injected via `local.properties` →
  `BuildConfig` (never hardcoded, mirrors web `.env`).

---

## 3. Design system (port of web `DESIGN.md`)

### Color tokens (light / dark) — ported verbatim as `NbColors`
surface-base, surface-soft, surface-card, surface-elevated, ink, ink-muted, ink-faint, border,
border-strong, glass-bg, glass-border, nav-bg, brand-teal (`#0071E3`/`#0A84FF`), brand-pink (`#FF375F`),
brand-pink-soft (`#FF6482`), brand-mint (`#34C759`/`#30D158`), plus shadow/overlay/glow tokens.
Dark mode: system preference first, then DataStore override; global color-animate on toggle.

### Typography
- **Inter** (300–700) for body/UI/labels; **Playfair Display** (400/700/italic) for hero + section titles.
- Scale and label treatment (11–13px bold uppercase 0.2em tracking) match web.

### Motion kit (`core/designsystem/motion`)
- Shared easing `EaseOutQuart = CubicBezier(0.22, 1, 0.36, 1)`.
- Durations: entry 800ms, interaction 300ms. Stagger 150ms.
- Primitives: staggered list entrance, spring press-scale, skeleton shimmer, glass/blur surface,
  hero/shared-element transitions between list→detail, screen transitions (shared axis + fade).
- **No bounce/elastic**, no layout-property animation — matches web motion rules.

### Custom iconography & graphics
- Hand-authored `ImageVector` icon set (nav, actions, reactions) instead of stock Material glyphs.
- Custom brand vector assets: logo, animated splash mark, verified badge, empty-state
  illustrations, category glyphs. This is where "premium, meaningful UI" is expressed.

### Base components
Buttons (primary pink→teal press, secondary outline, round CTA), theme card, glass navbar,
label-caps, theme input, verification/friend badge, avatar (with story ring), skeletons, bottom sheet,
toast/snackbar, confirm dialog, lightbox.

---

## 4. Data model (Kotlin data classes ← Firestore)

Collections (from `firestore.rules`): `users`, `usernames`, `posts`, `post_replies`, `post_upvotes`,
`post_downvotes`, `post_reactions`, `saved_posts`, `products`, `wishlists`, `reviews`, `chatRooms`
(+ `messages` subcollection), `clubs` (+ `messages`), `stories`, `views`, `likes`, `follows`,
`follow_edges`, `notifications`, `reports`, `blocks`, `referrals`, `schools`, `school_requests`,
`linkPreviews`, `user_affinity`, `computed`, `private`.

Faithful shapes ported from web types:

- **UserData** (`AuthContext.tsx`): name, email, school, verified, verificationStatus
  (`pending|approved|rejected|flagged_manual`), verificationRejectionReason?, reputation, isAdmin,
  role?, profilePicture?, idCardUrl?, selfieUrl?, about?, username?, city, createdAt, updatedAt,
  firstName?, lastName?, anonymousPersonaName?, lastUsernameChange?, chatPrivacy?, accountType
  (`student|organization`), org* fields, fcmTokens?.
- **Post** (`Feed.tsx`): id, title, content, type, isAnonymous?, personaName?, personaEmoji?,
  reactionsCount?, city?, school, authorId, authorName, authorProfilePicture?, status, privacy,
  imageUrl?/imageUrls?, createdAt, upvotesCount, downvotesCount?, repliesCount, feedScore?, isHot?,
  poll? { choices, expiresAt, votes }.
- **Product** (`Marketplace`/`SellItem`): id, title, price (₹), category (Books/…), condition
  (Like New/…), description, images[] / imagesDetailed[], status (`available|reserved|sold`),
  sellerId, school, city, createdAt, wishlist/reservation fields.
- **Message** (`useChatEngine.ts`): id, senderId, senderName?, senderAvatar?, text?, image?,
  type (`text|voice|video|file`), audioUrl?, video?{url,poster,w,h,duration}, file?{url,name,size,mime,pages},
  duration?, createdAt, reply* metadata, deletedFor?, isDeletedForEveryone?, reactions?(Map<emoji,uid[]>),
  readBy?, clientMessageId?, status (`pending|failed|sent`), forwardedFrom?.
- **ChatRoom / Club**: participants/memberIds, lastMessage/lastSenderId, updatedAt, per-user state arrays
  `unreadBy/mutedBy/archivedBy/pinnedBy/deletedBy`; clubs add roles (lead/coLeadIds/memberIds),
  type (`public|private`), inviteCode, settings{hideMembersAbove50, onlyLeadsCanPost, slowMode, muteNotifications}.
- **Story** (`stories.ts`): id, authorId, authorUsername, authorPhotoURL, mediaType, mediaUrl, mediaPath,
  posterUrl?, posterPath?, width, height, durationMs?, layers[], privacy, status, createdAt(ms), expiresAt(ms);
  plus TrayEntry, StoryViewer.
- **Notification**: userId, type (`user_approved|listing_approved|listing_rejected|new_message|new_post|
  item_reserved|item_sold|new_review|admin_promoted|mention`), title, message, link?, postId?, read, createdAt.
- **Reactions** (`reactions.ts`): fixed set `dead 💀, too_real 😭, exposed 👀, crazy 🤯, wholesome ❤️,
  spill_more ☕, respect 🫡` with weights; 1 reaction per user per post (toggle/swap).

Firestore `Timestamp` ↔ Kotlin: repositories map to `Instant`/epoch-ms; server writes use
`FieldValue.serverTimestamp()`. All array-of-uid per-user toggles follow the web's "never bump updatedAt" rule.

### Cloud Functions reused (via `FirebaseFunctions.httpsCallable`)
createNotification, getDiscoveryFeed, getRecommendedProducts, getSuggestedUsers, searchDiscovery,
searchPublicUsers, getPublicProfile(+Content), getPostReplies, getProductReviews, createProductReview,
createInviteCode, submitInviteCode, lookupReferralCode, isReferralCodeAvailable, sendAuthOtpEmail,
verifyAuthOtpEmail, getLandingStats, deletePostCascade, and the passive triggers (moderation,
rate-limit, affinity learning, digests) which run server-side unchanged.

---

## 5. Navigation & app shell

Single `MainActivity` → `NavHost`. Top-level graph:

- **Unauthenticated:** Splash (animated brand mark) → Onboarding (2–3 premium slides, first launch only)
  → Auth (Login / Signup / Org Signup / Email-OTP) → Verification.
- **Authenticated shell:** custom glass **BottomBar** with animated indicator —
  **Feed · Marketplace · ＋(Sell/Post) · Messages · Profile** — plus a top app bar with logo, stories tray
  entry point, search, and notification bell (unread badge).
- **Route guards** mirror `App.tsx` exactly: auth-required routes, and **verification-required** routes
  (`/sell`, `/edit-item`, `/messages`, `/chat`) gated behind `verified == true`. Unverified users are routed
  to a "finish verification" state.

Deep links: notification `link` fields (`/chat/:id`, `/post/:id`, `/product/:id`, `/u/:username`) map to
Android deep links so taps on push open the right screen.

---

## 6. Feature scope by phase

Each phase below becomes its own detailed sub-spec → implementation plan → build with atomic commits.
This document is the umbrella; phase specs refine acceptance criteria before each phase is built.

### P0 — Foundation
Scaffold multi-module Gradle project; Hilt; Firebase wiring (Auth/Firestore/Storage/Functions/FCM);
version catalog; design-system module (tokens, fonts, custom icons, motion kit, base components);
data `model` + `firebase` providers + Cloudinary uploader; nav shell with 5 tabs + themed placeholders;
`google-services.json` slot; dark/light theming. **Exit:** app launches, themes, navigates, connects to
live Firebase (read of a public collection proves connectivity).

### P1 — Auth & Verification
Animated splash; onboarding; Login (email/password + Google + Email-OTP via `sendAuthOtpEmail`/
`verifyAuthOtpEmail`); Signup; Org Signup; student **Verification** (ID card + selfie capture/upload to
Cloudinary → status pending/approved/rejected screens); referral capture on signup (`pendingReferral`);
`AuthRepository` exposing auth state + `UserData` Flow; route guards. **Exit:** a real account can sign in
by all three methods, submit verification, and reach the gated shell.

### P2 — Feed / Community
Virtualized feed (LazyColumn + paging via `getDiscoveryFeed`); PostCard; create post (text/image/poll/
anonymous persona); upvote/downvote; the 7 special reactions; @mentions; replies thread; save/bookmark;
"hot"/trending; post detail with shared-element image hero; share sheet; report/block. Instagram-smooth
scroll (key stability, image prefetch, no jank).

### P3 — Marketplace
Grid/list of products with category + condition filters and search; ProductCard; ProductDetail (image
carousel + lightbox, seller card + reputation, reviews); Sell/Edit item (≤5 images, Cloudinary,
moderation); wishlist; reserve/sold lifecycle; recommendations (`getRecommendedProducts`); reviews
(`createProductReview`).

### P4 — Chat / DM (WhatsApp/Telegram-grade)
Conversation list (pin/mute/archive/unread/delete per-user arrays; presence; typing; last-message
preview); ChatRoom with: realtime messages (`callbackFlow`), optimistic send with `clientMessageId` +
pending/failed/sent, reply-swipe with structured preview, message reactions, forward, delete-for-me /
delete-for-everyone, read receipts, link previews, voice messages (record/waveform/play), image/video/file
attachments, context menu, multi-select toolbar, jump-to-latest, day separators, smooth keyboard-aware
insets and 60fps list. Parity with `useChatEngine.ts` semantics (typing 2s re-arm / 5s stale, read-receipt
batching).

### P5 — Stories (Instagram-grade)
Stories tray with seen/unseen rings; full-screen viewer with progress bars, tap/hold/swipe navigation,
layers renderer, owner bar, interaction bar, viewers sheet; story composer (media pick, layers, voice,
privacy); 24h expiry; view tracking (`views`); first-story notification.

### P6 — Clubs (group chat)
Public discovery + private invite-code join; club chat (reuses P4 chat engine with roles); roles
(lead/co-lead/member); club settings (slow-mode, only-leads-post, mute, hide-members-above-50); create
club; leave/transfer.

### P7 — Notifications / FCM
FCM service + token registration into `users.fcmTokens`; channels per notification type; in-app bell with
realtime unread; notifications list; foreground toast; deep-link routing on tap; digest/broadcast handled
server-side.

### P8 — Search / Discovery
Unified search across users/posts/products/clubs (`searchDiscovery`/`searchPublicUsers`); recent searches;
suggested users (`getSuggestedUsers`); trending.

### P9 — Profile / Social
Own + public profiles (`getPublicProfile`), username pages (`/u/:username`); follow/unfollow with
`follow_edges` mirroring; PFP crop/upload; edit profile & settings (about, username with change-cooldown,
chat privacy, org fields); reputation display; user's posts/listings/saved tabs.

### P10 — Admin / Moderation / Reports
Admin panel (approve/reject verifications, moderate listings & posts, view reports); report modal from any
entity; block/unblock; role-gated route.

### P11 — Native polish
Haptics, share-target intent (share into NextBench), app shortcuts/widget for messages, offline cache &
optimistic UX, pull-to-refresh, edge-to-edge + predictive back, accessibility pass, empty/error/skeleton
states everywhere, performance profiling (Baseline Profiles).

---

## 7. Cross-cutting concerns

- **Error handling:** a `Result`-style wrapper + a Firestore error mapper (parity with
  `firestore-errors.ts`) surfacing user-friendly toasts; every repository call is failable and typed.
- **Security:** all reads/writes obey the existing `firestore.rules` (client cannot bypass); no admin
  secrets in the app; Cloudinary uses unsigned preset only.
- **Moderation:** text/image moderation remains server-side (Cloud Functions triggers); client shows
  pending/blocked states.
- **Testing:** unit tests for repositories/mappers and ViewModels (fake Firebase), Compose UI tests for
  key flows (auth, send message, post), and manual device QA per phase.
- **Performance targets:** 60fps chat + feed scroll, cold start < 2s to shell on mid-range device,
  no dropped frames on story transitions.
- **Comments:** code avoids comments except where a non-obvious invariant must be recorded (e.g. the
  "never bump updatedAt on per-user toggles" rule).

## 8. Git & delivery

- `nextbench/` initialized as its own git repo. **Atomic commits** — one coherent change each.
- Build/run performed by the user (sandbox is network-restricted): the plan includes exact
  `google-services.json` placement, `local.properties` keys, and `./gradlew assembleDebug` steps.

## 9. Out of scope (initial)
iOS, web changes, backend/Cloud-Function changes, payment processing (not in web), and any feature not
present on the website except the P11 native-only enhancements listed above.
