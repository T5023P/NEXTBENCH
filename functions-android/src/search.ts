import { getFirestore } from "firebase-admin/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { stringValue } from "./message";

// ─── Token generation ────────────────────────────────────────────────────────

const MIN_TOKEN_LEN = 2;
const MAX_TOKEN_LEN = 15;
const MAX_TOKENS_PER_DOC = 80;

/**
 * Generate n-gram prefix tokens from one or more text strings.
 * "Akshat Singh" → ["ak","aks","aksh","akshat","si","sin","sing","singh"]
 * Used for prefix-match search via array-contains queries.
 */
function generateSearchTokens(...fields: string[]): string[] {
  const tokens = new Set<string>();
  for (const field of fields) {
    const words = field
      .toLowerCase()
      .trim()
      .split(/[\s\-_,./()[\]]+/)
      .filter((w) => w.length >= MIN_TOKEN_LEN);
    for (const word of words) {
      for (let len = MIN_TOKEN_LEN; len <= Math.min(word.length, MAX_TOKEN_LEN); len++) {
        tokens.add(word.slice(0, len));
      }
    }
  }
  return [...tokens].slice(0, MAX_TOKENS_PER_DOC);
}

// ─── Write triggers — keep searchTokens in sync ───────────────────────────────

/** Rebuilds searchTokens on user documents when name or username changes. */
export const indexUserOnWrite = onDocumentWritten("users/{userId}", async (event) => {
  const after = event.data?.after;
  if (!after?.exists) return;
  const prev = event.data?.before?.data() ?? {};
  const data = after.data() ?? {};
  if (data.name === prev.name && data.username === prev.username) return;
  await after.ref.update({
    searchTokens: generateSearchTokens(stringValue(data.name), stringValue(data.username)),
  });
});

/** Rebuilds searchTokens on product documents when title or category changes. */
export const indexProductOnWrite = onDocumentWritten("products/{productId}", async (event) => {
  const after = event.data?.after;
  if (!after?.exists) return;
  const prev = event.data?.before?.data() ?? {};
  const data = after.data() ?? {};
  if (data.title === prev.title && data.category === prev.category) return;
  await after.ref.update({
    searchTokens: generateSearchTokens(stringValue(data.title), stringValue(data.category)),
  });
});

/** Rebuilds searchTokens on post documents when title or content changes. */
export const indexPostOnWrite = onDocumentWritten("posts/{postId}", async (event) => {
  const after = event.data?.after;
  if (!after?.exists) return;
  const prev = event.data?.before?.data() ?? {};
  const data = after.data() ?? {};
  if (data.title === prev.title && data.content === prev.content) return;
  const content = stringValue(data.content).slice(0, 200);
  await after.ref.update({
    searchTokens: generateSearchTokens(stringValue(data.title), content),
  });
});

/** Rebuilds searchTokens on club documents when name or description changes. */
export const indexClubOnWrite = onDocumentWritten("clubs/{clubId}", async (event) => {
  const after = event.data?.after;
  if (!after?.exists) return;
  const prev = event.data?.before?.data() ?? {};
  const data = after.data() ?? {};
  if (data.name === prev.name && data.description === prev.description) return;
  await after.ref.update({
    searchTokens: generateSearchTokens(stringValue(data.name), stringValue(data.description)),
  });
});

// ─── searchDiscovery callable ─────────────────────────────────────────────────

/**
 * Unified search + discovery endpoint consumed by the Android app.
 *
 * Blank query (suggestions: true) → curated discovery content.
 * Non-blank query → prefix n-gram search across users, posts, products, clubs.
 *
 * Search relies on the `searchTokens` array field maintained by the write
 * triggers above.  Documents written before those triggers existed won't appear
 * in results until they are next updated (or a one-time backfill is run).
 */
export const searchDiscovery = onCall(async (request) => {
  if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Authentication required.");

  const rawQuery = stringValue(request.data?.query ?? "");
  const isSuggestions = request.data?.suggestions === true || rawQuery.trim().length === 0;
  const school = stringValue(request.data?.school ?? "");
  const city = stringValue(request.data?.city ?? "");

  if (isSuggestions) {
    return getDiscoveryContent(school, city);
  }

  // Use the longest meaningful prefix (up to MAX_TOKEN_LEN chars, lowercase).
  const token = rawQuery.trim().toLowerCase().slice(0, MAX_TOKEN_LEN);
  if (token.length < MIN_TOKEN_LEN) {
    return { users: [], posts: [], products: [], clubs: [] };
  }

  const [users, posts, products, clubs] = await Promise.all([
    queryUsers(token, school, city),
    queryPosts(token, school, city),
    queryProducts(token, school, city),
    queryClubs(token, school),
  ]);

  return { users, posts, products, clubs };
});

// ─── Per-collection query helpers ─────────────────────────────────────────────

async function queryUsers(
  token: string,
  school: string,
  city: string,
): Promise<Record<string, unknown>[]> {
  const db = getFirestore();
  const snap = await db.collection("users")
    .where("searchTokens", "array-contains", token)
    .limit(30)
    .get();
  return snap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .slice(0, 15)
    .map(docToUser);
}

async function queryPosts(
  token: string,
  school: string,
  city: string,
): Promise<Record<string, unknown>[]> {
  const db = getFirestore();
  const snap = await db.collection("posts")
    .where("searchTokens", "array-contains", token)
    .limit(40)
    .get();
  return snap.docs
    .filter((doc) => {
      if (stringValue(doc.get("status")) !== "approved") return false;
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .slice(0, 15)
    .map((doc) => ({ id: doc.id, ...doc.data() }));
}

async function queryProducts(
  token: string,
  school: string,
  city: string,
): Promise<Record<string, unknown>[]> {
  const db = getFirestore();
  const snap = await db.collection("products")
    .where("searchTokens", "array-contains", token)
    .limit(40)
    .get();
  return snap.docs
    .filter((doc) => {
      if (stringValue(doc.get("status")) !== "available") return false;
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .slice(0, 15)
    .map((doc) => ({ id: doc.id, ...doc.data() }));
}

async function queryClubs(token: string, school: string): Promise<Record<string, unknown>[]> {
  const db = getFirestore();
  const snap = await db.collection("clubs")
    .where("searchTokens", "array-contains", token)
    .limit(20)
    .get();
  return snap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      return true;
    })
    .slice(0, 8)
    .map(docToClub);
}

// ─── Discovery / suggestions mode ────────────────────────────────────────────

async function getDiscoveryContent(
  school: string,
  city: string,
): Promise<Record<string, unknown>> {
  const db = getFirestore();

  // Fetch each bucket in parallel; fall back gracefully on missing indexes.
  const [hotPostsSnap, productsSnap, usersSnap, clubsSnap] = await Promise.all([
    db.collection("posts")
      .where("status", "==", "approved")
      .where("isHot", "==", true)
      .limit(20)
      .get()
      .catch(() =>
        db.collection("posts").where("status", "==", "approved").limit(20).get(),
      ),
    db.collection("products")
      .where("status", "==", "available")
      .limit(30)
      .get(),
    db.collection("users")
      .where("verified", "==", true)
      .limit(30)
      .get(),
    db.collection("clubs")
      .limit(20)
      .get(),
  ]);

  const posts = hotPostsSnap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .sort((a, b) => {
      const scoreA = (a.get("feedScore") as number | undefined) ?? 0;
      const scoreB = (b.get("feedScore") as number | undefined) ?? 0;
      return scoreB - scoreA;
    })
    .slice(0, 6)
    .map((doc) => ({ id: doc.id, ...doc.data() }));

  const products = productsSnap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .sort((a, b) => {
      const wA = (a.get("wishlistCount") as number | undefined) ?? 0;
      const wB = (b.get("wishlistCount") as number | undefined) ?? 0;
      return wB - wA;
    })
    .slice(0, 10)
    .map((doc) => ({ id: doc.id, ...doc.data() }));

  const users = usersSnap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      if (city && stringValue(doc.get("city")) !== city) return false;
      return true;
    })
    .sort((a, b) => {
      const rA = (a.get("reputation") as number | undefined) ?? 0;
      const rB = (b.get("reputation") as number | undefined) ?? 0;
      return rB - rA;
    })
    .slice(0, 12)
    .map(docToUser);

  const clubs = clubsSnap.docs
    .filter((doc) => {
      if (school && stringValue(doc.get("school")) !== school) return false;
      return true;
    })
    .sort((a, b) => {
      const mA = (a.get("memberCount") as number | undefined) ?? 0;
      const mB = (b.get("memberCount") as number | undefined) ?? 0;
      return mB - mA;
    })
    .slice(0, 8)
    .map(docToClub);

  return { users, posts, products, clubs };
}

// ─── Document shape helpers ───────────────────────────────────────────────────

function docToUser(doc: FirebaseFirestore.DocumentSnapshot): Record<string, unknown> {
  const d = doc.data() ?? {};
  return {
    id: doc.id,
    name: stringValue(d.name) || "Student",
    username: stringValue(d.username) || null,
    school: stringValue(d.school),
    city: stringValue(d.city),
    about: stringValue(d.about) || null,
    profilePicture: stringValue(d.profilePicture) || null,
    verified: d.verified === true,
    reputation: typeof d.reputation === "number" ? d.reputation : 0,
  };
}

function docToClub(doc: FirebaseFirestore.DocumentSnapshot): Record<string, unknown> {
  const d = doc.data() ?? {};
  return {
    id: doc.id,
    name: stringValue(d.name),
    school: stringValue(d.school),
    city: stringValue(d.city),
    description: stringValue(d.description),
    memberCount: typeof d.memberCount === "number" ? d.memberCount : 0,
    profilePicture: stringValue(d.profilePicture) || null,
    isPublic: d.isPublic !== false,
  };
}
