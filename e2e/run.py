#!/usr/bin/env python3
"""
End-to-end verification of the URL Shortener, driven entirely through its public HTTP
surface. No test hooks, no database writes, no clock manipulation — if a state is
reachable here, a user can reach it too, which is the only reason this file is evidence
of anything.

Nine scenarios, run in an order that is itself a design decision (see ORDER below).
Each names the requirements it covers so a failure points at a contract, not at a line
number.

    python3 e2e/run.py            # against localhost
    python3 e2e/run.py --base https://app.example.com

ORDER
    The rate limits are real (FR-6) and they apply to this suite exactly as they apply
    to a user. Two consequences shape the running order:

      * Scenario 9 deliberately exhausts the login bucket, so it runs LAST. Anything
        after it would fail for reasons that have nothing to do with what it tests.
      * Link creation is capped at 10/minute/IP (FR-6.2), and register-or-login at
        5/minute/IP (FR-6.1) — those two share one bucket, which is the easy one to
        miss. Both ceilings are lower than this suite needs, so `Budget` waits rather
        than pretending, and a full run takes a few minutes of wall clock. A suite that
        quietly disabled the limiter would be testing a system nobody deploys.

RE-RUNNING
    Password-reset requests are capped at 3/hour/IP (FR-6.7) and this suite spends two
    of them, so a second run inside the same hour fails scenario 8 for an entirely
    correct reason. Either wait, or clear the counters in Redis before re-running.

    Everything else is namespaced by a per-run timestamp and re-runs cleanly.
"""

import argparse
import http.cookiejar
import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

# ── plumbing ─────────────────────────────────────────────────────────────────


class Http:
    """One HTTP client per Owner, because a cookie jar *is* a session."""

    def __init__(self, base, short_base):
        self.base = base
        self.short_base = short_base
        self.jar = http.cookiejar.CookieJar()
        context = ssl.create_default_context()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=context),
        )
        # A second opener that does NOT follow redirects. The redirect endpoint is the
        # system under test in scenario 4; following it would assert something about
        # example.com instead.
        self.no_follow = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), NoRedirect()
        )

    def call(self, method, path, body=None, bearer=None, absolute=None, follow=True, patient=True):
        """
        `patient` obeys a 429's `Retry-After` and tries once more.

        `Budget` keeps this suite's *own* spending under the ceilings, but it cannot know
        what the buckets already held when the run started — a previous run, or a browser
        left open, is enough. Rather than demand a clean Redis, the suite does what the
        contract tells every client to do with a 429, which is also a check on
        `Retry-After` being a usable number rather than decoration.

        Pass `patient=False` wherever the 429 *is* the assertion.
        """
        response = self._once(method, path, body, bearer, absolute, follow)
        if patient and response.status == 429:
            wait = response.headers.get("Retry-After", "")
            wait = min(int(wait), 65) if wait.isdigit() else 60
            print(f"      … {path or absolute} was rate limited; honouring Retry-After {wait}s")
            time.sleep(wait + 1)
            response = self._once(method, path, body, bearer, absolute, follow)
        return response

    def _once(self, method, path, body, bearer, absolute, follow):
        url = absolute or (self.base + path)
        request = urllib.request.Request(url, method=method)
        request.add_header("Content-Type", "application/json")
        # Identify the client, always — and against production this is load-bearing
        # rather than polite. Python's stdlib sends `Python-urllib/3.x`, which sits on
        # Cloudflare's known-bot list: the request is refused at the edge with a 403
        # and error 1010, never reaches the application, and appears in no log this
        # project controls. A named agent is both the honest thing to send and the
        # thing that makes a failure debuggable.
        request.add_header("User-Agent", "url-shortener-e2e/1.0")
        if bearer:
            request.add_header("Authorization", "Bearer " + bearer)
        data = json.dumps(body).encode() if body is not None else None
        opener = self.opener if follow else self.no_follow
        try:
            with opener.open(request, data, timeout=20) as response:
                return Response(response.status, dict(response.headers), response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, dict(error.headers), error.read())

    def cookie_names(self):
        return {cookie.name for cookie in self.jar}

    def forget_cookies(self):
        self.jar.clear()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args):
        return None


class Headers(dict):
    """
    Case-insensitive header lookup.

    HTTP header names are case-insensitive by specification, and HTTP/2 goes further and
    *requires* them lowercase on the wire. Locally this suite talks HTTP/1.1 to Tomcat,
    which sends `Location` and `Retry-After`; through Cloudflare the same headers arrive
    as `location` and `retry-after`. A plain dict finds one and not the other, and the
    failure looks exactly like a missing header — which is how six checks came to blame
    the application for something only the test had got wrong.
    """

    def __init__(self, headers):
        super().__init__({str(k).lower(): v for k, v in headers.items()})

    def get(self, key, default=None):
        return super().get(str(key).lower(), default)

    def __contains__(self, key):
        return super().__contains__(str(key).lower())


class Response:
    def __init__(self, status, headers, raw):
        self.status = status
        self.headers = Headers(headers)
        self.raw = raw

    @property
    def json(self):
        try:
            return json.loads(self.raw.decode() or "null")
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None

    @property
    def code(self):
        """The RFC 9457 `code`, which is the half of an error that is contractual."""
        body = self.json
        return body.get("code") if isinstance(body, dict) else None

    @property
    def detail(self):
        body = self.json
        return body.get("detail") if isinstance(body, dict) else None

    def field_error(self, field):
        body = self.json or {}
        for entry in body.get("errors", []):
            if entry.get("field") == field:
                return entry.get("message")
        return None


class Suite:
    def __init__(self):
        self.passed = 0
        self.failures = []
        self.notes = []
        self.skipped = []
        self.scenario = ""

    def begin(self, title, covers):
        self.scenario = title
        print(f"\n\033[1m{title}\033[0m")
        print(f"  covers: {covers}")

    def check(self, label, actual, expected):
        ok = actual == expected
        if ok:
            self.passed += 1
            print(f"  \033[32m✓\033[0m {label}")
        else:
            self.failures.append((self.scenario, label, actual, expected))
            print(f"  \033[31m✗\033[0m {label}\n      got:    {actual!r}\n      wanted: {expected!r}")
        return ok

    def skip(self, label, why):
        """
        Recorded separately from a pass, and printed at the end.

        A suite that silently drops a check when it cannot run reads exactly like a
        suite where the check passed, and the difference is the whole value of it.
        """
        self.skipped.append((self.scenario, label, why))
        print(f"  \033[90m–\033[0m {label}  \033[90m({why})\033[0m")

    def note(self, text):
        self.notes.append((self.scenario, text))
        print(f"  \033[33m•\033[0m {text}")

    def report(self):
        total = self.passed + len(self.failures)
        print("\n" + "─" * 74)
        if self.failures:
            print(f"\033[31mFAILED\033[0m  {self.passed}/{total} checks passed\n")
            for scenario, label, actual, expected in self.failures:
                print(f"  {scenario}\n    {label}\n      got {actual!r}, wanted {expected!r}")
        else:
            print(f"\033[32mALL PASS\033[0m  {self.passed}/{total} checks")
        if self.skipped:
            print(f"\n  \033[90m{len(self.skipped)} checks skipped — NOT verified:\033[0m")
            for scenario, label, why in self.skipped:
                print(f"    [{scenario}] {label} — {why}")
        if self.notes:
            print("\n  Notes:")
            for scenario, text in self.notes:
                print(f"    [{scenario}] {text}")
        return 1 if self.failures else 0


class Budget:
    """
    Keeps this suite under a per-minute rate limit by waiting for the window.

    Waiting rather than raising the ceiling is the point: the limiter is part of the
    system, and a suite that turned it off would prove the system works in a
    configuration nobody runs.

    Two of these exist, because two limits bind here and one of them is easy to miss:
    `POST /auth/register` and `POST /auth/login` share a single 5/minute bucket, so
    the registration edge cases and the sign-in edge cases spend the same budget.
    """

    def __init__(self, name, limit, window=60):
        self.name = name
        self.limit = limit
        self.window = window
        self.stamps = []

    def spend(self):
        now = time.monotonic()
        self.stamps = [s for s in self.stamps if now - s < self.window]
        if len(self.stamps) >= self.limit:
            wait = self.window - (now - self.stamps[0]) + 1
            print(f"      … waiting {wait:.0f}s for the {self.name} window")
            time.sleep(wait)
            self.stamps = []
        self.stamps.append(time.monotonic())


def mailpit_code(mailpit, address, subject_contains, tries=25):
    """The newest 6-digit code sent to one address. Codes only exist in email."""
    for _ in range(tries):
        try:
            with urllib.request.urlopen(mailpit + "/api/v1/messages?limit=200", timeout=10) as r:
                messages = json.loads(r.read().decode()).get("messages", [])
        except urllib.error.URLError:
            messages = []
        for message in messages:
            recipients = [to.get("Address", "").lower() for to in message.get("To", [])]
            if address.lower() in recipients and subject_contains in message.get("Subject", ""):
                with urllib.request.urlopen(
                    mailpit + "/api/v1/message/" + message["ID"], timeout=10
                ) as r:
                    text = json.loads(r.read().decode()).get("Text", "")
                found = re.search(r"\b(\d{6})\b", text)
                if found:
                    return found.group(1)
        time.sleep(0.4)
    raise AssertionError(f"no {subject_contains!r} code for {address} in Mailpit")


def iso(when):
    return when.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── scenarios ────────────────────────────────────────────────────────────────


def reset_and_revocation(suite, anonymous, owner, keyed, auth_budget, args, run,
                         owner_email, password, plaintext_write):
    """The half of scenario 8 that cannot run without an inbox."""
    reset_code = mailpit_code(args.mailpit, owner_email, "Reset your password")
    bad_reset = anonymous.call(
        "POST",
        "/auth/reset-password",
        {
            "email": owner_email,
            "code": "000000" if reset_code != "000000" else "111111",
            "password": "another-good-one",
        },
    )
    suite.check("a wrong reset code is refused", bad_reset.status, 400)

    new_password = "a-brand-new-passphrase"
    reset = anonymous.call(
        "POST",
        "/auth/reset-password",
        {"email": owner_email, "code": reset_code, "password": new_password},
    )
    # 204, not 200. The contract says so, and the reason is visible in the response: it
    # carries a Set-Cookie that clears the session rather than a body, because the Owner
    # who just reset their password is deliberately not signed in by doing so.
    suite.check("the password is reset", reset.status, 204)
    suite.check("and the session cookie is cleared", "Set-Cookie" in reset.headers, True)

    # The session that existed before the reset must be dead. This is the whole point of
    # token_version: a stolen cookie stops working the moment the password changes.
    suite.check(
        "every session issued before the reset is dead", owner.call("GET", "/auth/me").status, 401
    )
    suite.check(
        "an API Key issued before the reset still works",
        keyed.call("GET", "/links", bearer=plaintext_write).status in (200, 403),
        True,
    )
    suite.note("keys survive a password reset by design — they are not sessions (ADR-0020)")

    owner.forget_cookies()
    auth_budget.spend()
    signed_in = owner.call("POST", "/auth/login", {"email": owner_email, "password": new_password})
    suite.check("the new password works", signed_in.status, 200)

    auth_budget.spend()
    old_password_attempt = anonymous.call(
        "POST", "/auth/login", {"email": owner_email, "password": password}
    )
    suite.check("the old one does not", old_password_attempt.status, 401)

    logged_out = owner.call("POST", "/auth/logout")
    suite.check("logout answers 204", logged_out.status, 204)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--short-base", default=None, help="defaults to --base")
    parser.add_argument("--mailpit", default="http://localhost:8025")
    parser.add_argument(
        "--account",
        default=None,
        metavar="EMAIL:PASSWORD",
        help="Run against an existing, already-verified account instead of registering "
        "one. Use this against production, where there is no Mailpit to read a code "
        "from: the suite then skips only the checks that genuinely need an inbox and "
        "says so in the report, rather than failing them for the wrong reason.",
    )
    args = parser.parse_args()

    api = args.base.rstrip("/") + "/api/v1"
    short = (args.short_base or args.base).rstrip("/")

    suite = Suite()
    # FR-6.2: 10 Link creations / minute / IP.  FR-6.1: 5 register-or-login / minute / IP.
    budget = Budget("FR-6.2 creation", 9)
    auth_budget = Budget("FR-6.1 auth", 4)
    run = str(int(time.time()))
    password = "correct-horse-battery"

    owner = Http(api, short)          # the Owner everything happens to
    intruder = Http(api, short)       # a second Owner, for isolation checks
    anonymous = Http(api, short)      # no credential at all

    # With --account the suite adopts an existing verified Owner. Registration and
    # anything else that needs to read an inbox is then skipped rather than faked.
    has_mail = args.account is None
    if has_mail:
        owner_email = f"e2e-owner-{run}@example.test"
    else:
        owner_email, password = args.account.split(":", 1)
    intruder_email = f"e2e-intruder-{run}@example.test"

    print(f"\033[1mURL Shortener — end-to-end\033[0m\n  api:   {api}\n  short: {short}\n  run:   {run}")

    # ── 1 ────────────────────────────────────────────────────────────────────
    suite.begin("1 · Registration and sign-in", "FR-1.1 FR-1.2 FR-1.3 FR-1.4 FR-1.6")

    if has_mail:
        auth_budget.spend()
        created = owner.call("POST", "/auth/register", {"email": owner_email, "password": password})
        suite.check("register answers 201", created.status, 201)
        suite.check("a session cookie is set", "session" in owner.cookie_names(), True)
        suite.check("the new Owner is unverified", created.json.get("emailVerified"), False)
        suite.check("no password field comes back", "password" in (created.json or {}), False)

        me = owner.call("GET", "/auth/me")
        suite.check("/auth/me identifies the Owner", me.json.get("email"), owner_email)

        code = mailpit_code(args.mailpit, owner_email, "Confirm your email")
        suite.check("the verification code is 6 digits", bool(re.fullmatch(r"\d{6}", code)), True)
        verified = owner.call("POST", "/auth/verify-email", {"code": code})
        suite.check("verifying answers 200", verified.status, 200)
        suite.check("the Owner is now verified", verified.json.get("emailVerified"), True)
    else:
        suite.skip("registration and email verification", "needs an inbox; --account given")
        auth_budget.spend()
        signed = owner.call("POST", "/auth/login", {"email": owner_email, "password": password})
        suite.check("the given account signs in", signed.status, 200)
        suite.check("a session cookie is set", "session" in owner.cookie_names(), True)
        me = owner.call("GET", "/auth/me")
        suite.check("/auth/me identifies the Owner", me.json.get("email"), owner_email)
        # Everything downstream creates Links, which FR-1.7 forbids to an unverified
        # Owner. Failing here rather than in scenario 3 says what is actually wrong.
        suite.check("and is already verified", me.json.get("emailVerified"), True)

    # Edges: the ways registration and sign-in are supposed to refuse.
    auth_budget.spend()
    taken = anonymous.call("POST", "/auth/register", {"email": owner_email, "password": password})
    suite.check("a taken email is 409 EMAIL_TAKEN", (taken.status, taken.code), (409, "EMAIL_TAKEN"))

    auth_budget.spend()
    upper = anonymous.call(
        "POST", "/auth/register", {"email": owner_email.upper(), "password": password}
    )
    suite.check("email uniqueness ignores case", upper.code, "EMAIL_TAKEN")

    auth_budget.spend()
    short_password = anonymous.call(
        "POST", "/auth/register", {"email": f"e2e-weak-{run}@example.test", "password": "short"}
    )
    suite.check("a too-short password is 400", short_password.status, 400)

    # The two ways to fail a login must be indistinguishable, or the form becomes an
    # account-enumeration oracle. Asserting *both* halves is what makes that meaningful.
    auth_budget.spend()
    wrong = anonymous.call("POST", "/auth/login", {"email": owner_email, "password": "wrong-one"})
    auth_budget.spend()
    unknown = anonymous.call(
        "POST", "/auth/login", {"email": f"nobody-{run}@example.test", "password": password}
    )
    suite.check("a wrong password is 401", (wrong.status, wrong.code), (401, "UNAUTHENTICATED"))
    suite.check("an unknown email is 401", (unknown.status, unknown.code), (401, "UNAUTHENTICATED"))
    suite.check(
        "the two are byte-identical — no enumeration oracle", wrong.raw == unknown.raw, True
    )

    suite.check(
        "an anonymous request to /links is 401", anonymous.call("GET", "/links").status, 401
    )

    # ── 2 ────────────────────────────────────────────────────────────────────
    suite.begin(
        "2 · The verification gate and OTP rules",
        "FR-1.7 FR-1.8 FR-1.11 FR-6.8 ADR-0016 ADR-0017",
    )

    auth_budget.spend()
    intruder.call("POST", "/auth/register", {"email": intruder_email, "password": password})
    suite.check(
        "an unverified Owner may read their account",
        intruder.call("GET", "/auth/me").status,
        200,
    )
    budget.spend()
    gated = intruder.call("POST", "/links", {"destination": "https://example.com/gated"})
    suite.check(
        "but may not create a Link",
        (gated.status, gated.code),
        (403, "EMAIL_NOT_VERIFIED"),
    )
    suite.check("and is told what to do about it", bool(gated.detail), True)

    if has_mail:
        intruder_code = mailpit_code(args.mailpit, intruder_email, "Confirm your email")
        wrong_code = "000000" if intruder_code != "000000" else "111111"

        first = intruder.call("POST", "/auth/verify-email", {"code": wrong_code})
        suite.check(
            "a wrong code is 400 INVALID_CODE", (first.status, first.code), (400, "INVALID_CODE")
        )

        # Five wrong attempts must kill the code. This is the assertion that matters: the
        # proof is that the *correct* code stops working afterwards, because a sixth wrong
        # guess would answer INVALID_CODE whether the limit worked or not.
        for _ in range(4):
            intruder.call("POST", "/auth/verify-email", {"code": wrong_code})
        burnt = intruder.call("POST", "/auth/verify-email", {"code": intruder_code})
        suite.check("the correct code is dead after 5 wrong attempts", burnt.code, "INVALID_CODE")
    else:
        # A wrong code can still be submitted without knowing the right one — what
        # cannot be checked is that the right one is dead afterwards, which is the half
        # that matters.
        rejected = intruder.call("POST", "/auth/verify-email", {"code": "000000"})
        suite.check(
            "a wrong code is 400 INVALID_CODE",
            (rejected.status, rejected.code),
            (400, "INVALID_CODE"),
        )
        suite.skip("the 5-attempt limit kills a code", "needs the real code; --account given")

    resend = intruder.call("POST", "/auth/resend-verification")
    suite.check("a replacement code can be requested", resend.status, 202)
    again = intruder.call("POST", "/auth/resend-verification", patient=False)
    suite.check("but not twice in a minute (FR-6.8)", again.status, 429)
    suite.check("and it says when to retry", "Retry-After" in again.headers, True)

    # ── 3 ────────────────────────────────────────────────────────────────────
    suite.begin("3 · Creating Links", "FR-2.1 … FR-2.9")

    budget.spend()
    generated = owner.call("POST", "/links", {"destination": "https://example.com/generated"})
    suite.check("a Link is created", generated.status, 201)
    link = generated.json
    suite.check("the Short Code is 7 characters", len(link["code"]), 7)
    suite.check("the code is Base62-safe", bool(re.fullmatch(r"[A-Za-z0-9]{7}", link["code"])), True)
    suite.check("shortUrl arrives fully formed", link["shortUrl"].endswith("/" + link["code"]), True)
    suite.check("it starts Active", link["status"], "ACTIVE")

    alias = f"e2e{run}"
    budget.spend()
    aliased = owner.call(
        "POST", "/links", {"destination": "https://example.com/aliased", "alias": alias}
    )
    suite.check("a custom Alias is honoured", (aliased.status, aliased.json["code"]), (201, alias))

    budget.spend()
    clash = owner.call(
        "POST", "/links", {"destination": "https://example.com/other", "alias": alias}
    )
    suite.check("a taken Alias is 409 ALIAS_TAKEN", (clash.status, clash.code), (409, "ALIAS_TAKEN"))
    suite.note("a taken Alias never silently falls back to a generated code (FR-2.5)")

    budget.spend()
    reserved = owner.call(
        "POST", "/links", {"destination": "https://example.com/admin", "alias": "admin"}
    )
    suite.check(
        "a Reserved Word is 409 RESERVED_ALIAS", (reserved.status, reserved.code), (409, "RESERVED_ALIAS")
    )

    budget.spend()
    bad_alias = owner.call(
        "POST", "/links", {"destination": "https://example.com/x", "alias": "no spaces!"}
    )
    suite.check("an Alias outside [A-Za-z0-9_-] is 400", bad_alias.status, 400)

    budget.spend()
    relative = owner.call("POST", "/links", {"destination": "example.com/no-scheme"})
    suite.check(
        "a non-absolute Destination is 422", (relative.status, relative.code), (422, "INVALID_DESTINATION")
    )

    budget.spend()
    javascript = owner.call("POST", "/links", {"destination": "javascript:alert(1)"})
    suite.check(
        "a javascript: Destination is refused",
        (javascript.status, javascript.code),
        (422, "INVALID_DESTINATION"),
    )

    budget.spend()
    loopback = owner.call("POST", "/links", {"destination": "http://127.0.0.1:8080/admin"})
    suite.check(
        "a Private Destination is 422 DESTINATION_NOT_ALLOWED",
        (loopback.status, loopback.code),
        (422, "DESTINATION_NOT_ALLOWED"),
    )

    budget.spend()
    self_target = owner.call("POST", "/links", {"destination": short + "/" + link["code"]})
    suite.check(
        "a Destination pointing at this service is refused", self_target.code, "DESTINATION_NOT_ALLOWED"
    )

    budget.spend()
    future = iso(datetime.now(timezone.utc) + timedelta(days=30))
    with_expiry = owner.call(
        "POST", "/links", {"destination": "https://example.com/seasonal", "expiresAt": future}
    )
    suite.check("an expiry may be set at creation", with_expiry.status, 201)

    # ── 4 ────────────────────────────────────────────────────────────────────
    suite.begin("4 · Redirecting, and the uniform 404", "FR-3.1 FR-3.2 FR-3.3 ADR-0003 ADR-0008")

    hit = anonymous.call("GET", "", absolute=short + "/" + link["code"], follow=False)
    suite.check("an Active Link answers 302", hit.status, 302)
    suite.check("302, never 301 — a 301 is cached forever", hit.status == 301, False)
    suite.check("Location is the Destination", hit.headers.get("Location"), "https://example.com/generated")
    suite.check("the redirect is not cacheable", hit.headers.get("Cache-Control"), "private, no-cache")
    suite.check("and not indexable", hit.headers.get("X-Robots-Tag"), "noindex")

    # Four different reasons, one indistinguishable answer.
    unknown_hit = anonymous.call("GET", "", absolute=short + "/zzzNotReal", follow=False)

    budget.spend()
    disabled_link = owner.call("POST", "/links", {"destination": "https://example.com/disabled"}).json
    owner.call("PATCH", f"/links/{disabled_link['id']}", {"status": "DISABLED"})
    disabled_hit = anonymous.call("GET", "", absolute=short + "/" + disabled_link["code"], follow=False)

    budget.spend()
    expired_link = owner.call("POST", "/links", {"destination": "https://example.com/expired"}).json
    past = iso(datetime.now(timezone.utc) - timedelta(days=1))
    owner.call("PATCH", f"/links/{expired_link['id']}", {"expiresAt": past})
    expired_hit = anonymous.call("GET", "", absolute=short + "/" + expired_link["code"], follow=False)

    budget.spend()
    deleted_link = owner.call("POST", "/links", {"destination": "https://example.com/deleted"}).json
    owner.call("DELETE", f"/links/{deleted_link['id']}")
    deleted_hit = anonymous.call("GET", "", absolute=short + "/" + deleted_link["code"], follow=False)

    for label, response in [
        ("unknown", unknown_hit),
        ("disabled", disabled_hit),
        ("expired", expired_hit),
        ("deleted", deleted_hit),
    ]:
        suite.check(f"a {label} code answers 404", response.status, 404)

    bodies = {unknown_hit.raw, disabled_hit.raw, expired_hit.raw, deleted_hit.raw}
    suite.check("all four 404 bodies are byte-identical", len(bodies), 1)
    suite.note("a Visitor cannot tell 'never existed' from 'deleted' — ADR-0008")

    # ── 5 ────────────────────────────────────────────────────────────────────
    suite.begin("5 · Managing Links", "FR-4.1 … FR-4.9 ADR-0009")

    listing = owner.call("GET", "/links?page=0&size=5")
    suite.check("the list is paginated", listing.json.get("size"), 5)
    suite.check("it reports a total", isinstance(listing.json.get("totalElements"), int), True)
    codes = [item["code"] for item in listing.json["content"]]
    suite.check("newest first", codes[0], expired_link["code"])
    suite.check("a deleted Link is gone from the list", deleted_link["code"] in codes, False)

    found = owner.call("GET", f"/links?search={alias}")
    suite.check("search finds a Link by its code", any(i["code"] == alias for i in found.json["content"]), True)
    by_destination = owner.call("GET", "/links?search=seasonal")
    suite.check("search finds a Link by its Destination", by_destination.json["totalElements"] >= 1, True)
    disabled_only = owner.call("GET", "/links?status=DISABLED")
    suite.check(
        "filtering by status works",
        all(i["status"] == "DISABLED" for i in disabled_only.json["content"]),
        True,
    )

    moved = owner.call(
        "PATCH", f"/links/{link['id']}", {"destination": "https://example.com/moved-to"}
    )
    suite.check("the Destination can be changed", moved.json["destination"], "https://example.com/moved-to")
    suite.check("the Short Code did not change", moved.json["code"], link["code"])

    history = owner.call("GET", f"/links/{link['id']}/history")
    suite.check("the change is recorded", len(history.json) >= 1, True)
    suite.check(
        "the previous Destination is what was recorded",
        history.json[0]["oldDestination"],
        "https://example.com/generated",
    )
    suite.check(
        "alongside the new one", history.json[0]["newDestination"], "https://example.com/moved-to"
    )
    suite.check("with a moment", bool(history.json[0].get("changedAt")), True)
    suite.note("FR-4.9 — the audit trail ADR-0009 promised is readable, not just written")

    redirected_after = anonymous.call("GET", "", absolute=short + "/" + link["code"], follow=False)
    suite.check(
        "the redirect follows the new Destination",
        redirected_after.headers.get("Location"),
        "https://example.com/moved-to",
    )

    cleared = owner.call("PATCH", f"/links/{with_expiry.json['id']}", {"expiresAt": None})
    suite.check("an expiry can be cleared", cleared.json.get("expiresAt"), None)

    reenabled = owner.call("PATCH", f"/links/{disabled_link['id']}", {"status": "ACTIVE"})
    suite.check("a Link can be re-enabled", reenabled.json["status"], "ACTIVE")

    # A deleted Short Code is burned forever, for the same reason a code can never be
    # renamed: freeing the string lets someone else inherit its traffic.
    budget.spend()
    reclaim = owner.call(
        "POST", "/links", {"destination": "https://example.com/reclaim", "alias": deleted_link["code"]}
    )
    suite.check("a deleted Short Code can never be reclaimed", reclaim.code, "ALIAS_TAKEN")

    # Isolation. 404 and not 403, because 403 confirms the thing exists.
    theirs = intruder.call("GET", f"/links/{link['id']}")
    suite.check("another Owner's Link is 404", (theirs.status, theirs.code), (404, "NOT_FOUND"))
    suite.check(
        "not 403, which would confirm it exists", theirs.status == 403, False
    )
    suite.check(
        "another Owner cannot edit it",
        intruder.call("PATCH", f"/links/{link['id']}", {"destination": "https://evil.test"}).status,
        404,
    )
    suite.check(
        "another Owner cannot delete it",
        intruder.call("DELETE", f"/links/{link['id']}").status,
        404,
    )
    suite.check(
        "a Link id that is not a number is also 404",
        owner.call("GET", "/links/not-a-number").status,
        404,
    )

    # ── 6 ────────────────────────────────────────────────────────────────────
    suite.begin("6 · Statistics", "FR-5.1 FR-5.2 FR-5.4")

    for _ in range(3):
        anonymous.call("GET", "", absolute=short + "/" + aliased.json["code"], follow=False)
    time.sleep(0.5)

    stats = owner.call("GET", f"/links/{aliased.json['id']}/stats")
    suite.check("statistics answer 200", stats.status, 200)
    suite.check("the total Click count is recorded", stats.json.get("totalClicks"), 3)
    suite.check("a daily series comes back", isinstance(stats.json.get("daily"), list), True)
    suite.check(
        "another Owner cannot read them",
        intruder.call("GET", f"/links/{aliased.json['id']}/stats").status,
        404,
    )

    # ── 7 ────────────────────────────────────────────────────────────────────
    suite.begin("7 · API Keys, scopes and expiry", "FR-8.1 … FR-8.11 ADR-0019 ADR-0020")

    write_key = owner.call(
        "POST",
        "/api-keys",
        {"name": "e2e load generator", "scopes": ["links:write"], "expiresInDays": 30},
    )
    suite.check("a key is created", write_key.status, 201)
    suite.check("its scopes come back", write_key.json["scopes"], ["links:write"])
    suite.check("its expiry is set", bool(write_key.json["expiresAt"]), True)
    plaintext_write = write_key.json["key"]
    suite.check("the plaintext is returned once", plaintext_write.startswith("sk_live_"), True)

    read_key = owner.call(
        "POST", "/api-keys", {"name": "e2e reader", "scopes": ["links:read"], "expiresInDays": None}
    )
    plaintext_read = read_key.json["key"]
    suite.check("a key may have no expiry", read_key.json["expiresAt"], None)

    keys = owner.call("GET", "/api-keys")
    suite.check("keys are listed", keys.status, 200)
    suite.check(
        "and never carry key material", any("key" in entry for entry in keys.json), False
    )
    suite.check(
        "a prefix and last4 identify them",
        all(entry["keyPrefix"] and entry["last4"] for entry in keys.json),
        True,
    )

    # The scope split, in both directions.
    keyed = Http(api, short)
    budget.spend()
    by_key = keyed.call("POST", "/links", {"destination": "https://example.com/by-key"}, bearer=plaintext_write)
    suite.check("a links:write key creates a Link", by_key.status, 201)
    refused_read = keyed.call("GET", "/links", bearer=plaintext_write)
    suite.check(
        "and cannot list them", (refused_read.status, refused_read.code), (403, "INSUFFICIENT_SCOPE")
    )
    suite.check("the refusal names the missing scope", "links:read" in (refused_read.detail or ""), True)
    suite.check(
        "nor read statistics",
        keyed.call("GET", f"/links/{by_key.json['id']}/stats", bearer=plaintext_write).code,
        "INSUFFICIENT_SCOPE",
    )

    suite.check("a links:read key lists Links", keyed.call("GET", "/links", bearer=plaintext_read).status, 200)
    budget.spend()
    suite.check(
        "and cannot create one",
        keyed.call("POST", "/links", {"destination": "https://example.com/nope"}, bearer=plaintext_read).code,
        "INSUFFICIENT_SCOPE",
    )

    # FR-8.5 is not a scope, and no combination of scopes grants it.
    suite.check(
        "no key can list keys",
        keyed.call("GET", "/api-keys", bearer=plaintext_read).code,
        "FORBIDDEN",
    )
    suite.check(
        "no key can mint keys",
        keyed.call(
            "POST", "/api-keys", {"name": "escalation", "scopes": ["links:read"]}, bearer=plaintext_write
        ).code,
        "FORBIDDEN",
    )

    # FR-8.6 — the header wins, and the two credentials never mix. `owner` holds a
    # valid session cookie that *could* do this; the read-only key must still refuse.
    both = owner.call("POST", "/links", {"destination": "https://example.com/both"}, bearer=plaintext_read)
    suite.check(
        "with a cookie AND a key, the key decides", (both.status, both.code), (403, "INSUFFICIENT_SCOPE")
    )
    suite.note("FR-8.6 — a request that half-used each credential is how privilege bugs start")

    # Validation.
    suite.check(
        "a key needs at least one scope",
        owner.call("POST", "/api-keys", {"name": "none", "scopes": []}).status,
        400,
    )
    unknown_scope = owner.call("POST", "/api-keys", {"name": "bad", "scopes": ["links:destroy"]})
    suite.check("an unknown scope is refused", unknown_scope.status, 400)
    suite.check(
        "and the valid ones are named", "links:read" in (unknown_scope.detail or ""), True
    )
    suite.check(
        "a lifetime over a year is refused",
        owner.call("POST", "/api-keys", {"name": "long", "scopes": ["links:read"], "expiresInDays": 400}).status,
        400,
    )

    # Revocation, and the uniform 401 it shares with expiry.
    owner.call("DELETE", f"/api-keys/{read_key.json['id']}")
    revoked = keyed.call("GET", "/links", bearer=plaintext_read)
    nonexistent = keyed.call("GET", "/links", bearer="sk_live_" + "x" * 43)
    suite.check("a revoked key is refused", (revoked.status, revoked.code), (401, "UNAUTHENTICATED"))
    suite.check(
        "indistinguishable from a key that never existed", revoked.raw == nonexistent.raw, True
    )
    suite.check(
        "revoking it twice is 404, not a leak",
        owner.call("DELETE", f"/api-keys/{read_key.json['id']}").status,
        404,
    )
    suite.check(
        "a revoked key leaves the list",
        any(entry["id"] == read_key.json["id"] for entry in owner.call("GET", "/api-keys").json),
        False,
    )
    suite.note(
        "an EXPIRED key answers the same 401 as an unknown one; the key list is the only "
        "place the difference shows (FR-8.11), and that costs one DB write to reach here"
    )

    # ── 8 ────────────────────────────────────────────────────────────────────
    suite.begin("8 · Password reset and session revocation", "FR-1.9 FR-1.10 FR-1.12 ADR-0018")

    registered = anonymous.call("POST", "/auth/forgot-password", {"email": owner_email})
    unregistered = anonymous.call(
        "POST", "/auth/forgot-password", {"email": f"ghost-{run}@example.test"}
    )

    # FR-6.7 caps reset requests at 3/hour/IP and this pair spends two of them, so a
    # second run inside the hour meets its own earlier run. That is the limiter working,
    # not the property failing — and `patient` cannot wait it out, because it declines to
    # sleep for an hourly window. Skipped rather than failed: a red check here would send
    # the next reader looking for an enumeration oracle that is not there.
    if 429 in (registered.status, unregistered.status):
        suite.skip(
            "the identical-202 pair (FR-1.12)",
            "FR-6.7 spent for this hour, most likely by a previous run",
        )
    else:
        suite.check("a reset request answers 202", registered.status, 202)
        suite.check("so does one for an unknown address", unregistered.status, 202)
        suite.check(
            "identically — no enumeration oracle (FR-1.12)",
            registered.raw == unregistered.raw,
            True,
        )

    if not has_mail:
        # Deliberately not attempted. Completing a reset would change the password of an
        # account the operator handed us, and this suite does not get to do that.
        suite.skip("completing a password reset", "needs an inbox; --account given")
        suite.skip("sessions die when the password changes", "depends on the reset above")
        logged_out = owner.call("POST", "/auth/logout")
        suite.check("logout answers 204", logged_out.status, 204)
    else:
        reset_and_revocation(
            suite, anonymous, owner, keyed, auth_budget, args, run,
            owner_email, password, plaintext_write,
        )
    # ── 9 ────────────────────────────────────────────────────────────────────
    suite.begin("9 · Rate limiting — runs last, it spends the budget", "FR-6.1 FR-6.5")

    limited = None
    attempts = 0
    for attempts in range(1, 12):
        attempt = anonymous.call(
            "POST",
            "/auth/login",
            {"email": owner_email, "password": "definitely-wrong"},
            patient=False,
        )
        if attempt.status == 429:
            limited = attempt
            break

    suite.check("repeated logins are eventually refused", limited is not None, True)
    if limited:
        suite.check("with 429 RATE_LIMITED", (limited.status, limited.code), (429, "RATE_LIMITED"))
        suite.check("and a Retry-After header", "Retry-After" in limited.headers, True)
        suite.check(
            "whose value is a number of seconds",
            limited.headers.get("Retry-After", "").isdigit(),
            True,
        )
        suite.note(f"the login limit engaged on attempt {attempts} (FR-6.1 allows 5/minute)")

    return suite.report()


if __name__ == "__main__":
    sys.exit(main())
