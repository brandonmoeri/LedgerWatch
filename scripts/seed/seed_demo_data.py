#!/usr/bin/env python3
"""Seed LedgerWatch with several months of realistic demo data, plus ground-truth fraud labels.

Writes SQL to stdout (pipe it into psql) and a labels JSON file for evals. Standard library only.

    python scripts/seed/seed_demo_data.py | docker compose exec -T postgres \
        psql -v ON_ERROR_STOP=1 -q -U ledger -d ledgerwatch

Why SQL rather than the API: POST /transactions stamps created_at with now(), and the fraud rules
judge each transaction as of its created_at, so backdated history can only be written directly.
To keep that history consistent with what the services would have produced, this script:

  * runs the three fraud rules (VELOCITY, AMOUNT_SPIKE, NEW_ACCOUNT_LARGE_DEBIT) in Python with
    the defaults from transaction-service's application.yml, producing the same detail strings;
  * settles each transaction the way OutboxPublisher + account-service would: fraud verdict first,
    then ACCOUNT_NOT_ACTIVE, then INSUFFICIENT_FUNDS (a balance may not go below zero);
  * writes fraud_screening (every settled transaction), fraud_flag (one per signal) and
    balance_adjustment (every POSTED one), and sets each account's balance to the sum of its
    POSTED transactions, which is what the dashboard's balance-over-time assumes.

Re-runnable: every seeded row has a deterministic id, and the SQL deletes the previous seed's
rows (only those) before inserting. --purge emits just the delete.

Determinism: the same --seed, --days, --end and --fraud-mode give identical data and labels.
Without --end, the data ends yesterday (UTC), so timestamps move with the calendar.
"""

import argparse
import bisect
import datetime as dt
import json
import math
import os
import random
import sys
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Callable, Dict, List, Optional, Tuple

UTC = dt.timezone.utc
NS = uuid.UUID("6f1c2a4e-9d0b-4b7e-8a51-3c2f0e7d9a10")
CENT = Decimal("0.01")

# Mirrors transaction-service application.yml (fraud.*). Keep in sync if the defaults change.
VELOCITY_MAX = 10
VELOCITY_WINDOW = dt.timedelta(minutes=10)
VELOCITY_WINDOW_ISO = "PT10M"
SPIKE_MULTIPLIER = Decimal(3)
SPIKE_LOOKBACK = dt.timedelta(days=30)
SPIKE_LOOKBACK_ISO = "PT720H"  # Duration.ofDays(30).toString()
SPIKE_MIN_HISTORY = 3
NEW_ACCOUNT_MAX_AGE = dt.timedelta(hours=24)
NEW_ACCOUNT_MAX_AGE_ISO = "PT24H"
NEW_ACCOUNT_THRESHOLD = Decimal(1000)


def uid(*parts: str) -> uuid.UUID:
    return uuid.uuid5(NS, ":".join(parts))


def money(x) -> Decimal:
    return Decimal(str(x)).quantize(CENT, ROUND_HALF_EVEN)


def java_duration(delta: dt.timedelta) -> str:
    """java.time.Duration#toString for a non-negative whole-second duration."""
    seconds = int(delta.total_seconds())
    if seconds == 0:
        return "PT0S"
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return "PT" + (f"{h}H" if h else "") + (f"{m}M" if m else "") + (f"{s}S" if s else "")


def poisson(rng: random.Random, lam: float) -> int:
    limit, k, p = math.exp(-lam), 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def at(day: dt.date, rng: random.Random, lo_hour: float, hi_hour: float) -> dt.datetime:
    start = dt.datetime.combine(day, dt.time(), UTC)
    return start + dt.timedelta(seconds=rng.randint(int(lo_hour * 3600), int(hi_hour * 3600) - 1))


def days_between(first: dt.date, last: dt.date):
    d = first
    while d <= last:
        yield d
        d += dt.timedelta(days=1)


def last_day_of_month(d: dt.date) -> bool:
    return (d + dt.timedelta(days=1)).month != d.month


# --------------------------------------------------------------------------------------------
# Model
# --------------------------------------------------------------------------------------------


@dataclass
class Txn:
    id: uuid.UUID
    type: str  # CREDIT | DEBIT
    amount: Optional[Decimal]  # None: resolved to the full balance at settlement (closing sweep)
    description: str
    at: dt.datetime
    tag: str  # what this is, e.g. groceries, rent, payroll; used to explain benign flags
    scenario: Optional[str] = None
    fraud: bool = False
    # Filled in by settle()
    status: str = ""
    rejection: Optional[str] = None
    signals: List[Tuple[str, str]] = field(default_factory=list)
    blocked: bool = False
    screened_at: Optional[dt.datetime] = None
    updated_at: Optional[dt.datetime] = None


@dataclass
class Account:
    key: str
    owner: str
    persona: str
    opened_at: dt.datetime
    rng: random.Random
    status_changes: List[Tuple[dt.datetime, str]] = field(default_factory=list)
    txns: List[Txn] = field(default_factory=list)
    seq: int = 0
    balance: Decimal = Decimal("0.00")

    @property
    def id(self) -> uuid.UUID:
        return uid("account", self.key)

    def add(self, type_, amount, description, when, tag, scenario=None, fraud=False) -> Txn:
        self.seq += 1
        txn = Txn(
            id=uid("txn", self.key, str(self.seq)),
            type=type_,
            amount=None if amount is None else money(amount),
            description=description,
            at=when,
            tag=tag,
            scenario=scenario,
            fraud=fraud,
        )
        self.txns.append(txn)
        return txn

    def credit(self, amount, description, when, tag, **kw) -> Txn:
        return self.add("CREDIT", amount, description, when, tag, **kw)

    def debit(self, amount, description, when, tag, **kw) -> Txn:
        return self.add("DEBIT", amount, description, when, tag, **kw)

    def status_at(self, when: dt.datetime) -> str:
        status = "ACTIVE"
        for change_at, new_status in sorted(self.status_changes):
            if change_at <= when:
                status = new_status
        return status

    @property
    def final_status(self) -> str:
        return sorted(self.status_changes)[-1][1] if self.status_changes else "ACTIVE"


# --------------------------------------------------------------------------------------------
# Everyday activity
# --------------------------------------------------------------------------------------------

# category -> (merchants, (earliest hour, latest hour), median amount, lognormal sigma)
CATEGORIES: Dict[str, Tuple[List[str], Tuple[float, float], float, float]] = {
    "groceries": (
        ["Whole Foods Market", "Trader Joe's", "Safeway", "Kroger", "Aldi", "Sprouts Farmers Mkt"],
        (8, 21), 64, 0.45),
    "dining": (
        ["Chipotle", "Thai Basil Kitchen", "Shake Shack", "Panera Bread", "Sushi Zen",
         "Luigi's Trattoria", "Taqueria El Sol", "DoorDash"],
        (11, 22.5), 27, 0.5),
    "coffee": (["Starbucks", "Blue Bottle Coffee", "Dunkin'", "Corner Bean Cafe"], (6, 11), 6.4, 0.3),
    "fuel": (["Shell", "Chevron", "Exxon", "Arco"], (6, 21), 46, 0.25),
    "shopping": (
        ["Amazon.com", "Target", "Walmart", "Best Buy", "Home Depot", "IKEA", "TJ Maxx", "REI"],
        (9, 22), 48, 0.75),
    "rideshare": (["Uber Trip", "Lyft Ride"], (7, 24), 18, 0.45),
    "pharmacy": (["CVS Pharmacy", "Walgreens"], (9, 20), 22, 0.55),
    "entertainment": (["AMC Theatres", "Ticketmaster", "Steam Games", "Bowlero"], (12, 23), 34, 0.5),
}
ATM_AMOUNTS = [40, 60, 80, 100, 120, 200]
FRIENDS = ["A. Chen", "B. Morales", "C. Park", "D. Fischer", "E. Rossi", "F. Haddad", "G. Silva"]

DEFAULT_SUBS = [("Netflix", 9, 15.49), ("Spotify", 14, 11.99), ("Apple iCloud", 21, 2.99)]
DEFAULT_BILLS = [
    ("Pacific Gas & Electric", 18, 92.0, 24.0),
    ("Comcast Xfinity Internet", 6, 79.99, 0.0),
    ("Verizon Wireless", 24, 86.0, 6.0),
]


@dataclass
class Income:
    kind: str  # biweekly | semimonthly | monthly | irregular
    amount: float
    payer: str
    day: int = 1  # monthly: day of month
    jitter: float = 0.01  # relative variation of each payment
    per_month: float = 0.0  # irregular: expected payments per month
    tag: str = "payroll"

    def pays_on(self, d: dt.date, anchor: dt.date, rng: random.Random) -> bool:
        if self.kind == "biweekly":
            return d >= anchor and (d - anchor).days % 14 == 0
        if self.kind == "semimonthly":
            return d.day == 15 or last_day_of_month(d)
        if self.kind == "monthly":
            return d.day == self.day
        if self.kind == "irregular":
            return rng.random() < self.per_month / 30.4
        raise ValueError(self.kind)


@dataclass
class Lifestyle:
    incomes: List[Income]
    spend: Dict[str, float]  # category -> purchases per week
    spend_scale: float = 1.0
    rent: Optional[Tuple[float, str, int]] = None  # (amount, payee, day of month)
    bills: List[Tuple[str, int, float, float]] = field(default_factory=lambda: list(DEFAULT_BILLS))
    subs: List[Tuple[str, int, float]] = field(default_factory=lambda: list(DEFAULT_SUBS))
    atm_per_month: float = 1.0
    p2p_per_month: float = 1.5
    savings_sweep: Optional[Tuple[float, int]] = None  # (amount, day): transfer to savings
    payroll: float = 0.0  # business only: biweekly payroll it pays out
    suppliers: List[Tuple[str, float]] = field(default_factory=list)  # business: (name, mean invoice)


def live(acct: Account, life: Lifestyle, first: dt.date, last: dt.date) -> None:
    """Everyday transactions on [first, last]."""
    rng = acct.rng
    anchor = first + dt.timedelta(days=(4 - first.weekday()) % 7)  # first Friday: biweekly pay
    for d in days_between(first, last):
        weekend = d.weekday() >= 5
        for inc in life.incomes:
            if inc.pays_on(d, anchor, rng):
                amount = inc.amount * (1 + rng.uniform(-inc.jitter, inc.jitter))
                acct.credit(amount, inc.payer, at(d, rng, 3, 7) if inc.kind != "irregular"
                            else at(d, rng, 9, 18), inc.tag)
        if life.rent and d.day == life.rent[2]:
            acct.debit(life.rent[0], f"ACH debit - {life.rent[1]} rent", at(d, rng, 6, 9), "rent")
        for name, day, mean, sd in life.bills:
            if d.day == day:
                amount = max(mean + rng.gauss(0, sd), mean * 0.4) if sd else mean
                acct.debit(amount, f"Autopay - {name}", at(d, rng, 5, 10), "utilities")
        for name, day, amount in life.subs:
            if d.day == day:
                acct.debit(amount, f"Recurring payment - {name}", at(d, rng, 0, 6), "subscription")
        if life.savings_sweep and d.day == life.savings_sweep[1]:
            acct.debit(life.savings_sweep[0], "Online transfer to savings ****2290",
                       at(d, rng, 8, 12), "savings_transfer")
        for category, per_week in life.spend.items():
            merchants, (lo, hi), median, sigma = CATEGORIES[category]
            factor = 1.35 if weekend and category in ("dining", "entertainment", "shopping") else 1.0
            for _ in range(poisson(rng, per_week / 7 * factor)):
                amount = max(rng.lognormvariate(math.log(median * life.spend_scale), sigma), 1.5)
                merchant = rng.choice(merchants)
                acct.debit(amount, f"Card purchase - {merchant}", at(d, rng, lo, hi), category)
        if rng.random() < life.atm_per_month / 30.4:
            acct.debit(rng.choice(ATM_AMOUNTS), "ATM withdrawal", at(d, rng, 8, 22), "atm")
        if rng.random() < life.p2p_per_month / 30.4:
            friend = rng.choice(FRIENDS)
            amount = rng.choice([15, 20, 25, 30, 40, 45, 60, 80])
            if rng.random() < 0.5:
                acct.debit(amount, f"Zelle payment to {friend}", at(d, rng, 9, 23), "p2p")
            else:
                acct.credit(amount, f"Zelle from {friend}", at(d, rng, 9, 23), "p2p")


def salaried(pay, cadence, employer, rent=None, scale=1.0, **kw) -> Lifestyle:
    return Lifestyle(
        incomes=[Income(cadence, pay, f"Direct deposit - {employer} PAYROLL")],
        spend={"groceries": 1.6, "dining": 2.5, "coffee": 3, "fuel": 0.9, "shopping": 1.4,
               "rideshare": 0.6, "pharmacy": 0.3, "entertainment": 0.4},
        spend_scale=scale, rent=rent, **kw)


def student(pay, employer, allowance) -> Lifestyle:
    return Lifestyle(
        incomes=[Income("biweekly", pay, f"Direct deposit - {employer} PAYROLL", jitter=0.12),
                 Income("monthly", allowance, "Zelle from Family", day=1, jitter=0.0, tag="allowance")],
        spend={"groceries": 1.0, "dining": 3.0, "coffee": 3.5, "shopping": 0.8, "rideshare": 1.0,
               "entertainment": 0.6},
        spend_scale=0.7,
        bills=[("Verizon Wireless", 24, 45.0, 3.0)],
        subs=[("Spotify Student", 14, 5.99), ("Netflix", 9, 15.49)],
        atm_per_month=1.5, p2p_per_month=3)


def retiree(pension, social_security) -> Lifestyle:
    return Lifestyle(
        incomes=[Income("monthly", pension, "ACH credit - CalPERS pension", day=1, jitter=0.0,
                        tag="pension"),
                 Income("monthly", social_security, "Direct deposit - SSA Social Security", day=3,
                        jitter=0.0, tag="pension")],
        spend={"groceries": 2.0, "dining": 1.0, "coffee": 1.0, "fuel": 0.6, "shopping": 0.7,
               "pharmacy": 0.8},
        spend_scale=0.9,
        bills=DEFAULT_BILLS + [("Medicare Part B premium", 3, 174.70, 0.0)],
        subs=[("Netflix", 9, 15.49)],
        atm_per_month=2.0, p2p_per_month=0.5)


def freelancer(rate_per_month, scale=1.0, rent=None) -> Lifestyle:
    return Lifestyle(
        incomes=[Income("irregular", rate_per_month / 4, "ACH credit - Stripe payout",
                        jitter=0.45, per_month=4, tag="client_payment")],
        spend={"groceries": 1.5, "dining": 2.0, "coffee": 5, "shopping": 1.2, "rideshare": 0.8,
               "entertainment": 0.4},
        spend_scale=scale, rent=rent,
        subs=DEFAULT_SUBS + [("Adobe Creative Cloud", 2, 59.99), ("Figma", 2, 15.00)])


def business(settlement, settle_per_week, payroll, suppliers, rent, processor="Square") -> Lifestyle:
    """A small business: card-processor settlements in, payroll/suppliers/rent out."""
    return Lifestyle(
        incomes=[Income("irregular", settlement, f"ACH credit - {processor} settlement",
                        jitter=0.35, per_month=settle_per_week * 4.33, tag="card_settlement")],
        spend={}, rent=rent,
        bills=[("Pacific Gas & Electric - commercial", 18, 640.0, 120.0),
               ("Comcast Business", 6, 189.99, 0.0),
               ("Hartford business insurance", 12, 412.0, 0.0)],
        subs=[("QuickBooks Online", 4, 90.00), ("Gusto payroll fees", 2, 82.00)],
        atm_per_month=0, p2p_per_month=0, payroll=payroll, suppliers=suppliers)


def run_business(acct: Account, life: Lifestyle, first: dt.date, last: dt.date) -> None:
    live(acct, life, first, last)
    rng = acct.rng
    anchor = first + dt.timedelta(days=(4 - first.weekday()) % 7)
    for d in days_between(first, last):
        if d >= anchor and (d - anchor).days % 14 == 0:
            acct.debit(life.payroll * rng.uniform(0.95, 1.06), "ACH debit - Gusto payroll",
                       at(d, rng, 4, 7), "business_payroll")
        if d.weekday() == 1:  # weekly supplier invoices on Tuesdays
            name, mean = rng.choice(life.suppliers)
            acct.debit(mean * rng.uniform(0.7, 1.3), f"ACH debit - {name} invoice",
                       at(d, rng, 9, 16), "supplier")


# --------------------------------------------------------------------------------------------
# Accounts and scenarios
# --------------------------------------------------------------------------------------------


@dataclass
class Scenario:
    name: str
    kind: str  # fraud | benign
    typology: str
    account: Account
    summary: str
    signal: str  # what a good reviewer (or the AI) should notice
    txns: List[Txn] = field(default_factory=list)


class World:
    def __init__(self, seed: int, days: int, end: dt.date):
        self.seed = seed
        self.end = end
        self.first_day = end - dt.timedelta(days=days - 1)
        self.E = dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), UTC)  # exclusive
        self.accounts: List[Account] = []
        self.scenarios: List[Scenario] = []

    def rel(self, days_before: int, hh: int, mm: int, ss: int = 0) -> dt.datetime:
        """A time of day on the date `days_before` days before the day after --end."""
        d = (self.E - dt.timedelta(days=days_before)).date()
        return dt.datetime.combine(d, dt.time(hh, mm, ss), UTC)

    def open(self, key: str, owner: str, persona: str, opened_at: Optional[dt.datetime] = None,
             deposit: Optional[float] = None, deposit_desc="Opening deposit - transfer from external bank"
             ) -> Account:
        rng = random.Random(f"{self.seed}:{key}")
        if opened_at is None:
            day = self.first_day + dt.timedelta(days=rng.randint(0, 20))
            opened_at = at(day, rng, 9, 17)
        acct = Account(key, owner, persona, opened_at, rng)
        if deposit:
            acct.credit(deposit, deposit_desc, opened_at + dt.timedelta(minutes=rng.randint(3, 25)),
                        "opening_deposit")
        self.accounts.append(acct)
        return acct

    def life_window(self, acct: Account) -> Tuple[dt.date, dt.date]:
        return acct.opened_at.date() + dt.timedelta(days=2), self.end

    def scenario(self, *args, **kw) -> Scenario:
        s = Scenario(*args, **kw)
        self.scenarios.append(s)
        return s


def build(world: World) -> None:
    w = world

    # ---- Ordinary customers -----------------------------------------------------------------
    plain = [
        ("maya", "Maya Thompson", "salaried renter", 6500,
         salaried(2350, "biweekly", "BRIGHTPATH HEALTH", rent=(1650, "Greenway Property Mgmt", 1))),
        ("marcus", "Marcus Bell", "salaried homeowner", 9000,
         salaried(3900, "semimonthly", "CASCADE ENGINEERING", scale=1.2, savings_sweep=(500, 16))),
        ("henry", "Henry Walsh", "retiree", 15000, retiree(2100, 1850)),
        ("aisha", "Aisha Rahman", "freelance designer", 7000,
         freelancer(5200, rent=(1400, "Elm Street Lofts", 1))),
        ("chloe", "Chloe Dubois", "student", 900, student(560, "CAMPUS BOOKSTORE", 400)),
        ("benjamin", "Benjamin Carter", "salaried renter", 5000,
         salaried(2100, "biweekly", "METRO TRANSIT AUTH", rent=(1500, "Riverside Apartments", 1))),
        ("samuel", "Samuel Osei", "salaried", 8000,
         salaried(3300, "semimonthly", "OAKRIDGE SCHOOL DIST", savings_sweep=(400, 2))),
        ("hannah", "Hannah Kim", "freelance developer", 12000, freelancer(7800, scale=1.15)),
    ]
    for key, owner, persona, deposit, life in plain:
        acct = w.open(key, owner, persona, deposit=deposit)
        live(acct, life, *w.life_window(acct))

    cafe = w.open("cafe", "Harbor Street Cafe LLC", "small business (cafe)", deposit=25000,
                  deposit_desc="Opening deposit - wire from First Coast Bank")
    cafe_life = business(1450, 5, 5200, [("Sysco Foods", 1600), ("Pacific Coffee Roasters", 1100),
                                         ("Restaurant Depot", 1300)],
                         rent=(3400, "Harbor Street Holdings", 1))
    run_business(cafe, cafe_life, *w.life_window(cafe))

    # ---- A: card testing burst (caught by VELOCITY) -----------------------------------------
    noah = w.open("noah", "Noah Patel", "salaried", deposit=4000)
    live(noah, salaried(2600, "biweekly", "NORTHSTAR LOGISTICS"), *w.life_window(noah))
    s = w.scenario(
        "card_testing", "fraud", "card testing", noah,
        "Stolen card details tested with 14 tiny online charges in about 7 minutes at 2am, then "
        "two larger electronics purchases.",
        "Burst of sub-$5 charges at unfamiliar online merchants in the middle of the night, "
        "followed by larger purchases: classic card testing. VELOCITY fires from the 11th charge.")
    t = w.rel(12, 2, 13, 7)
    testers = ["DIGITALGOODS*7731", "WEBSTORE SVC", "GIVEHOPE DONATIONS", "GAMECREDITS.IO",
               "PAYSVC*VERIFY", "APPCOINS MARKET"]
    for _ in range(14):
        s.txns.append(noah.debit(noah.rng.choice([0.99, 1.00, 1.49, 1.99, 2.50, 3.00, 4.99]),
                                 f"Card purchase - {noah.rng.choice(testers)}", t, "card_test",
                                 scenario=s.name, fraud=True))
        t += dt.timedelta(seconds=noah.rng.randint(18, 38))
    for amount in (389.99, 412.50):
        t += dt.timedelta(seconds=noah.rng.randint(50, 80))
        s.txns.append(noah.debit(amount, "Card purchase - ELECTRONIX-DIRECT.COM", t, "card_test",
                                 scenario=s.name, fraud=True))

    # ---- B: account takeover of a retiree (caught by AMOUNT_SPIKE), then frozen -------------
    grace = w.open("grace", "Grace Liu", "retiree", deposit=38000,
                   deposit_desc="Opening deposit - transfer from Fidelity brokerage")
    live(grace, retiree(1900, 1650), *w.life_window(grace))
    s = w.scenario(
        "account_takeover", "fraud", "account takeover", grace,
        "A retiree's online banking is taken over late at night: four large transfers to a new "
        "external account, a new Zelle payee and a crypto exchange within about an hour. The bank "
        "freezes the account the next morning and two further attempts are declined; it is "
        "unfrozen three days later.",
        "Late-night, first-time payees, amounts far above a low and steady spending pattern, money "
        "leaving to external accounts and crypto. AMOUNT_SPIKE fires on the four that went "
        "through; the two attempts after the freeze are declined as ACCOUNT_NOT_ACTIVE.")
    for when, amount, desc in [
        (w.rel(20, 23, 41, 12), 2950.00, "Online transfer to ext acct ****4471"),
        (w.rel(20, 23, 58, 40), 1980.00, "Zelle payment to D. Marsh"),
        (w.rel(19, 0, 22, 5), 1450.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
        (w.rel(19, 0, 47, 31), 2200.00, "Online transfer to ext acct ****4471"),
        (w.rel(19, 10, 5, 2), 900.00, "Online transfer to ext acct ****4471"),
        (w.rel(19, 10, 19, 44), 650.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
    ]:
        s.txns.append(grace.debit(amount, desc, when, "takeover", scenario=s.name, fraud=True))
    grace.status_changes += [(w.rel(19, 9, 32), "FROZEN"), (w.rel(16, 14, 10), "ACTIVE")]

    # ---- C: new-account bust-out with a bad check (partly caught) ---------------------------
    jordan = w.open("jordan", "Jordan Hayes", "new account", opened_at=w.rel(9, 14, 2, 19))
    s = w.scenario(
        "new_account_bust_out", "fraud", "first-party / check fraud", jordan,
        "A brand-new account deposits two mobile checks and moves the money out immediately: one "
        "large transfer within hours of opening, then $950 withdrawals kept under the $1,000 "
        "threshold. The second check is returned unpaid and the account is frozen.",
        "Funds out within hours of opening, then repeated amounts just under the large-debit "
        "threshold, and a returned deposit. Only the first transfer trips "
        "NEW_ACCOUNT_LARGE_DEBIT; the rest are designed to evade it.")
    for when, kind, amount, desc in [
        (w.rel(9, 14, 20, 3), "CREDIT", 4800.00, "Mobile check deposit #1001"),
        (w.rel(9, 17, 45, 50), "DEBIT", 4500.00, "Online transfer to ext acct ****9020"),
        (w.rel(8, 15, 10, 11), "CREDIT", 3000.00, "Mobile check deposit #1002"),
        (w.rel(7, 16, 5, 37), "DEBIT", 950.00, "ATM withdrawal - 7th & Main"),
        (w.rel(7, 16, 41, 2), "DEBIT", 950.00, "Zelle payment to K. Ruiz"),
        (w.rel(6, 11, 2, 26), "DEBIT", 950.00, "Card purchase - GIFTCARD MALL"),
        (w.rel(5, 9, 0, 0), "DEBIT", 3000.00, "Returned item - mobile check deposit #1002 (NSF)"),
    ]:
        s.txns.append(jordan.add(kind, amount, desc, when, "bust_out", scenario=s.name, fraud=True))
    jordan.status_changes.append((w.rel(5, 9, 31), "FROZEN"))

    # ---- D: structuring cash deposits at an auto shop (missed by the rules) -----------------
    shop = w.open("shop", "Kowalski Auto Repair LLC", "small business (auto repair)",
                  deposit=30000, deposit_desc="Opening deposit - wire from Lakeshore Credit Union")
    run_business(shop, business(4300, 3, 6800, [("NAPA Auto Parts", 2600), ("AutoZone Pro", 1900),
                                                ("LKQ Corporation", 2400)],
                                rent=(4200, "Industrial Park Partners", 1), processor="Clover"),
                 *w.life_window(shop))
    s = w.scenario(
        "structuring", "fraud", "structuring (AML)", shop,
        "Eleven cash deposits of $8,700-$9,900 over five weeks, spread across branches, each kept "
        "under the $10,000 currency-transaction-report threshold.",
        "Repeated cash deposits just under $10,000 at several branches. No rule fires: the "
        "business's normal settlements are large enough that 3x their average sits above $10,000.")
    day = 45
    for _ in range(11):
        when = w.rel(day, shop.rng.randint(10, 15), shop.rng.randint(0, 59), shop.rng.randint(0, 59))
        branch = shop.rng.choice(["0142", "0187", "0203", "0311"])
        amount = shop.rng.randrange(8700, 9901, 50)
        s.txns.append(shop.credit(amount, f"Cash deposit - branch {branch}", when, "cash_deposit",
                                  scenario=s.name, fraud=True))
        day -= shop.rng.randint(2, 4)

    # ---- E: money mule pass-through (partly caught) -----------------------------------------
    tyler = w.open("tyler", "Tyler Brooks", "student", opened_at=w.rel(150, 13, 22, 9), deposit=300)
    live(tyler, student(480, "QUICKMART", 250), *w.life_window(tyler))
    s = w.scenario(
        "money_mule", "fraud", "money mule", tyler,
        "A low-income student account starts receiving $1,200-$2,400 Zelle payments from strangers "
        "and forwards about 90% of each within hours to an external account or a bitcoin ATM.",
        "Inbound payments from many unrelated senders, each followed within hours by an outbound "
        "transfer of almost the same amount. AMOUNT_SPIKE flags each outbound transfer and the "
        "first few inbound payments, but once the credit average rises the later inbound ones "
        "pass, and no rule links an inbound payment to the transfer that follows it.")
    senders = ["A. Lindqvist", "R. Moreau", "P. Castellanos", "S. Whitfield", "M. Adeyemi",
               "L. Novak", "T. Haraldsen", "C. Bianchi"]
    day = 40
    for i, sender in enumerate(senders):
        rng = tyler.rng
        when = w.rel(day, rng.randint(9, 19), rng.randint(0, 59), rng.randint(0, 59))
        amount = rng.randrange(1200, 2401, 25)
        s.txns.append(tyler.credit(amount, f"Zelle from {sender}", when, "mule_in",
                                   scenario=s.name, fraud=True))
        out = when + dt.timedelta(minutes=rng.randint(55, 330))
        desc = ("Online transfer to ext acct ****8812" if i % 2 == 0
                else "Card purchase - BITCOIN DEPOT ATM #2231")
        s.txns.append(tyler.debit(round(amount * rng.uniform(0.88, 0.93)), desc, out, "mule_out",
                                  scenario=s.name, fraud=True))
        day -= rng.randint(3, 6)

    # ---- F: dormant account reactivated (missed by the rules) -------------------------------
    ruth = w.open("ruth", "Ruth Abernathy", "retiree (secondary account)",
                  opened_at=dt.datetime.combine(w.first_day + dt.timedelta(days=1),
                                                dt.time(10, 4, 51), UTC),
                  deposit=8500)
    first, _ = w.life_window(ruth)
    live(ruth, Lifestyle(incomes=[], spend={"groceries": 1.5, "pharmacy": 0.7}, bills=[], subs=[],
                         atm_per_month=1, p2p_per_month=0), first, first + dt.timedelta(days=20))
    s = w.scenario(
        "dormant_reactivation", "fraud", "account takeover (dormant)", ruth,
        "A secondary account untouched for about five months suddenly sends six payments to a "
        "crypto exchange and an external account over two days, mostly at night.",
        "Activity after months of silence, all outbound, to crypto/external payees never seen "
        "before. No rule fires: AMOUNT_SPIKE needs 3 transactions in the last 30 days and there "
        "are none.")
    for when, amount, desc in [
        (w.rel(6, 1, 12, 40), 850.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
        (w.rel(6, 1, 31, 8), 920.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
        (w.rel(6, 3, 2, 55), 1800.00, "Online transfer to ext acct ****3306"),
        (w.rel(5, 0, 48, 17), 780.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
        (w.rel(5, 2, 14, 3), 950.00, "Card purchase - COINVAULT CRYPTO EXCHANGE"),
        (w.rel(5, 23, 39, 30), 1200.00, "Online transfer to ext acct ****3306"),
    ]:
        s.txns.append(ruth.debit(amount, desc, when, "dormant_takeover", scenario=s.name,
                                 fraud=True))

    # ---- G: annual bonus (benign; AMOUNT_SPIKE false positive) ------------------------------
    lucas = w.open("lucas", "Lucas Becker", "salaried renter", deposit=5500)
    live(lucas, salaried(2750, "biweekly", "NORTHWIND LOGISTICS",
                         rent=(1750, "Parkview Residences", 1)), *w.life_window(lucas))
    s = w.scenario(
        "annual_bonus", "benign", "income spike", lucas,
        "The employer that pays the customer's salary pays an annual bonus of $14,500.",
        "Same payer and payment channel as every payroll deposit; a large credit from a known "
        "employer is not suspicious. No rule fires, though only by luck: biweekly pay leaves "
        "two credits in 30 days, under AMOUNT_SPIKE's min-history of 3.")
    s.txns.append(lucas.credit(14500.00, "Direct deposit - NORTHWIND LOGISTICS BONUS",
                               w.rel(30, 5, 12, 44), "bonus", scenario=s.name))

    # ---- H: car down payment (benign; AMOUNT_SPIKE false positive) --------------------------
    sofia = w.open("sofia", "Sofia Martinez", "salaried, family", deposit=11000)
    live(sofia, salaried(2900, "biweekly", "VALLEY MEDICAL GROUP", scale=1.3,
                         rent=(2100, "Sunset Ridge Homes", 1)), *w.life_window(sofia))
    s = w.scenario(
        "car_down_payment", "benign", "large one-off purchase", sofia,
        "A family pays a $6,500 down payment on a car by check at a local dealership, followed by "
        "monthly auto-loan payments.",
        "Daytime, local, a named dealership, and a matching monthly loan payment afterwards. "
        "AMOUNT_SPIKE fires on the down payment (false positive).")
    s.txns.append(sofia.debit(6500.00, "Check #1047 - Lakeside Auto Sales (down payment)",
                              w.rel(25, 15, 31, 9), "car_purchase", scenario=s.name))
    for back in (18, 18 - 30, 18 - 60):
        if back > 0:
            sofia.debit(489.00, "Autopay - Lakeside Auto Finance loan", w.rel(back, 7, 2),
                        "auto_loan")

    # ---- I: legitimate new account with home-sale proceeds (benign; NEW_ACCOUNT FP) ---------
    elena = w.open("elena", "Elena Petrova", "salaried, relocating",
                   opened_at=w.rel(60, 10, 15, 33))
    s = w.scenario(
        "home_sale_new_account", "benign", "new account, large movement", elena,
        "A customer who just moved opens an account with $22,000 of home-sale proceeds wired from a "
        "title company and the same afternoon writes a $1,850 security deposit check to her new "
        "apartment complex, which then collects rent monthly.",
        "Funds come from a title company and go to a landlord who becomes a recurring payee, and "
        "a salary starts soon after. NEW_ACCOUNT_LARGE_DEBIT fires (false positive).")
    s.txns.append(elena.credit(22000.00, "Wire from Coastal Title Co - home sale proceeds",
                               w.rel(60, 10, 41, 2), "home_sale", scenario=s.name))
    s.txns.append(elena.debit(1850.00, "Check #101 - Maple Court Apartments (security deposit)",
                              w.rel(60, 15, 20, 47), "security_deposit", scenario=s.name))
    live(elena, salaried(3100, "semimonthly", "SUMMIT ANALYTICS",
                         rent=(1850, "Maple Court Apartments", 1)), *w.life_window(elena))

    # ---- J: POS batch catch-up at the cafe (benign; VELOCITY false positive) ----------------
    s = w.scenario(
        "pos_batch_catch_up", "benign", "burst of credits", cafe,
        "After a card-terminal outage, the cafe's processor settles twelve held batches within "
        "about eight minutes.",
        "All credits, all from the business's own card processor, amounts in its normal range. "
        "VELOCITY fires (false positive).")
    t = w.rel(3, 23, 49, 5)
    for n in range(12):
        s.txns.append(cafe.credit(cafe.rng.uniform(380, 1250),
                                  f"ACH credit - Square settlement (batch {n + 1} of 12)", t,
                                  "card_settlement", scenario=s.name))
        t += dt.timedelta(seconds=cafe.rng.randint(25, 50))

    # ---- K: holiday abroad (benign, unusual; mostly unflagged) ------------------------------
    olivia = w.open("olivia", "Olivia Grant", "salaried renter", deposit=7500)
    live(olivia, salaried(3000, "semimonthly", "HARBORVIEW BANK CORP",
                          rent=(1700, "Bayside Commons", 1)), *w.life_window(olivia))
    s = w.scenario(
        "travel_abroad", "benign", "unusual but legitimate", olivia,
        "A week's holiday in Portugal booked six weeks ahead: flights and hotel, then card "
        "purchases in Lisbon and Porto with foreign-transaction fees.",
        "Foreign merchants and FX fees, but booked well in advance, consistent travel pattern, "
        "normal amounts. A reviewer should not treat this as fraud.")
    s.txns.append(olivia.debit(1140.00, "Card purchase - TAP AIR PORTUGAL", w.rel(52, 20, 14, 3),
                               "travel", scenario=s.name))
    s.txns.append(olivia.debit(960.00, "Card purchase - BOOKING.COM Hotel Avenida Lisboa",
                               w.rel(51, 21, 2, 48), "travel", scenario=s.name))
    trip = ["Pastelaria Alfama, Lisboa", "Time Out Market, Lisboa", "Uber Lisboa",
            "Pingo Doce, Lisboa", "Livraria Lello, Porto", "CP Comboios de Portugal",
            "Restaurante O Gaveto, Porto", "Museu Calouste Gulbenkian"]
    for back in range(36, 29, -1):
        for _ in range(olivia.rng.randint(2, 4)):
            amount = olivia.rng.uniform(6, 85)
            when = w.rel(back, olivia.rng.randint(9, 22), olivia.rng.randint(0, 59))
            s.txns.append(olivia.debit(amount, f"Card purchase - {olivia.rng.choice(trip)} (EUR)",
                                       when, "travel", scenario=s.name))
            s.txns.append(olivia.debit(round(amount * 0.03, 2), "Foreign transaction fee",
                                       when + dt.timedelta(seconds=1), "fx_fee", scenario=s.name))

    # ---- L: customer closes the account (benign) --------------------------------------------
    ethan = w.open("ethan", "Ethan Nguyen", "student", deposit=1200)
    first, _ = w.life_window(ethan)
    close_at = w.rel(50, 14, 5, 0)
    live(ethan, student(610, "UNIVERSITY DINING SVCS", 350), first,
         close_at.date() - dt.timedelta(days=1))
    s = w.scenario(
        "account_closure", "benign", "account closed", ethan,
        "A student moves banks: the full balance is transferred out and the account closed; a "
        "streaming subscription he forgot to move is declined afterwards.",
        "A one-off transfer of the whole balance followed by closure, then declined recurring "
        "charges. Routine.")
    s.txns.append(ethan.debit(None, "Online transfer to ext acct ****5512 - closing balance",
                              w.rel(50, 13, 58, 21), "account_closure", scenario=s.name))
    ethan.status_changes.append((close_at, "CLOSED"))
    for back in (50 - 9, 50 - 9 - 30):  # roughly the next Netflix dates; declined
        if back > 0:
            s.txns.append(ethan.debit(15.49, "Recurring payment - Netflix", w.rel(back, 3, 12),
                                      "subscription", scenario=s.name))


# --------------------------------------------------------------------------------------------
# Settlement: fraud rules + account-service, as the running system would have done it
# --------------------------------------------------------------------------------------------


def settle(acct: Account, mode: str) -> None:
    acct.txns.sort(key=lambda t: (t.at, str(t.id)))
    times = [t.at for t in acct.txns]
    posted: Dict[str, List[Tuple[dt.datetime, Decimal]]] = {"CREDIT": [], "DEBIT": []}
    rng = random.Random(f"settle:{acct.key}")
    balance = Decimal("0.00")
    for txn in acct.txns:
        if txn.amount is None:  # closing sweep: the whole balance
            txn.amount = balance
        signals: List[Tuple[str, str]] = []

        # AMOUNT_SPIKE: POSTED, same type, created in [t - 30d, t)
        window = [a for when, a in posted[txn.type] if txn.at - SPIKE_LOOKBACK <= when < txn.at]
        if len(window) >= SPIKE_MIN_HISTORY:
            total = sum(window, Decimal(0))
            if txn.amount * len(window) > total * SPIKE_MULTIPLIER:
                avg = (total / len(window)).quantize(CENT, ROUND_HALF_EVEN)
                signals.append(("AMOUNT_SPIKE",
                                f"amount {txn.amount} exceeds {SPIKE_MULTIPLIER}x the "
                                f"{SPIKE_LOOKBACK_ISO} {txn.type} average of {avg} over "
                                f"{len(window)} transactions"))

        # NEW_ACCOUNT_LARGE_DEBIT
        if txn.type == "DEBIT" and txn.amount >= NEW_ACCOUNT_THRESHOLD:
            age = txn.at - acct.opened_at
            if age < NEW_ACCOUNT_MAX_AGE:
                signals.append(("NEW_ACCOUNT_LARGE_DEBIT",
                                f"DEBIT of {txn.amount} (threshold {NEW_ACCOUNT_THRESHOLD}) on an "
                                f"account {java_duration(age)} old (minimum "
                                f"{NEW_ACCOUNT_MAX_AGE_ISO})"))

        # VELOCITY: every transaction, any status, created in [t - 10m, t], counting this one
        count = bisect.bisect_right(times, txn.at) - bisect.bisect_left(times, txn.at - VELOCITY_WINDOW)
        if count > VELOCITY_MAX:
            signals.append(("VELOCITY",
                            f"{count} transactions in {VELOCITY_WINDOW_ISO} (max {VELOCITY_MAX})"))

        txn.signals = signals
        txn.blocked = bool(signals) and mode == "enforce"
        txn.screened_at = txn.at + dt.timedelta(milliseconds=rng.randint(400, 3500))
        txn.updated_at = txn.screened_at + dt.timedelta(milliseconds=rng.randint(40, 400))
        if txn.blocked:
            txn.status, txn.rejection = "REJECTED", "SUSPECTED_FRAUD"
        elif acct.status_at(txn.at) != "ACTIVE":
            txn.status, txn.rejection = "REJECTED", "ACCOUNT_NOT_ACTIVE"
        elif txn.type == "DEBIT" and balance - txn.amount < 0:
            txn.status, txn.rejection = "REJECTED", "INSUFFICIENT_FUNDS"
        else:
            txn.status = "POSTED"
            balance += txn.amount if txn.type == "CREDIT" else -txn.amount
            posted[txn.type].append((txn.at, txn.amount))
    acct.balance = balance
    # A closing sweep of a zero balance is not a transaction anyone would make.
    acct.txns = [t for t in acct.txns if t.amount and t.amount > 0]


# --------------------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------------------


def sql_str(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (Decimal, int)):
        return str(v)
    if isinstance(v, dt.datetime):
        return "'" + v.isoformat() + "'"
    return "'" + str(v).replace("'", "''") + "'"


def insert(out, table: str, columns: List[str], rows: List[tuple], batch: int = 500) -> None:
    for i in range(0, len(rows), batch):
        out.write(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n")
        out.write(",\n".join("(" + ", ".join(sql_str(v) for v in row) + ")"
                             for row in rows[i:i + batch]))
        out.write(";\n")


def write_sql(out, accounts: List[Account], purge_only: bool) -> None:
    ids = ", ".join(sql_str(str(a.id)) for a in accounts)
    seeded_txns = f"SELECT id FROM transactions.transaction WHERE account_id IN ({ids})"
    out.write("-- Generated by scripts/seed/seed_demo_data.py. Do not edit.\n")
    out.write("BEGIN;\n")
    out.write(f"DELETE FROM transactions.fraud_flag WHERE account_id IN ({ids});\n")
    out.write(f"DELETE FROM transactions.fraud_screening WHERE transaction_id IN ({seeded_txns});\n")
    out.write(f"DELETE FROM transactions.outbox_event WHERE aggregate_id IN ({seeded_txns});\n")
    out.write(f"DELETE FROM transactions.transaction WHERE account_id IN ({ids});\n")
    out.write(f"DELETE FROM accounts.account WHERE id IN ({ids});  -- cascades balance_adjustment\n")
    if not purge_only:
        acct_rows, adj_rows, txn_rows, screen_rows, flag_rows = [], [], [], [], []
        for a in accounts:
            changes = [when for when, _ in a.status_changes]
            last_posted = [t.updated_at for t in a.txns if t.status == "POSTED"]
            updated = max(changes + last_posted + [a.opened_at])
            acct_rows.append((str(a.id), a.owner, a.final_status, a.balance, a.opened_at, updated, 0))
            for t in a.txns:
                txn_rows.append((str(t.id), str(a.id), t.type, t.status, t.amount, t.description,
                                 t.at, t.updated_at, t.rejection))
                signals = json.dumps([{"rule": r, "detail": d} for r, d in t.signals])
                screen_rows.append((str(t.id), t.blocked, signals, t.screened_at))
                for rule, detail in t.signals:
                    flag_rows.append((str(uid("flag", str(t.id), rule)), str(t.id), str(a.id), rule,
                                      detail, t.blocked, t.screened_at))
                if t.status == "POSTED":
                    delta = t.amount if t.type == "CREDIT" else -t.amount
                    adj_rows.append((str(t.id), str(a.id), delta, t.updated_at))
        insert(out, "accounts.account",
               ["id", "owner_name", "status", "balance", "created_at", "updated_at", "version"],
               acct_rows)
        insert(out, "accounts.balance_adjustment", ["id", "account_id", "delta", "applied_at"],
               adj_rows)
        insert(out, "transactions.transaction",
               ["id", "account_id", "type", "status", "amount", "description", "created_at",
                "updated_at", "rejection_reason"], txn_rows)
        insert(out, "transactions.fraud_screening",
               ["transaction_id", "blocked", "signals", "screened_at"], screen_rows)
        insert(out, "transactions.fraud_flag",
               ["id", "transaction_id", "account_id", "rule", "detail", "blocked", "flagged_at"],
               flag_rows)
    out.write("COMMIT;\n")


def labels(world: World, args) -> dict:
    by_id = {t.id: (a, t) for a in world.accounts for t in a.txns}
    scenario_of = {t.id: s for s in world.scenarios for t in s.txns if t.id in by_id}
    roles = {s.account.key: ("subject" if s.name in ("structuring", "money_mule",
                                                     "new_account_bust_out") else
                             "victim" if s.kind == "fraud" else "customer")
             for s in world.scenarios}

    scenarios = []
    for s in world.scenarios:
        txns = [t for t in s.txns if t.id in by_id]
        fraud_txns = [t for t in txns if t.fraud]
        flagged = [t for t in fraud_txns if t.signals]
        scenarios.append({
            "name": s.name,
            "kind": s.kind,
            "typology": s.typology,
            "account_id": str(s.account.id),
            "owner": s.account.owner,
            "summary": s.summary,
            "what_to_notice": s.signal,
            "transaction_ids": [str(t.id) for t in txns],
            "rules_fired": {str(t.id): [r for r, _ in t.signals] for t in txns if t.signals},
            "caught_by_rules": None if s.kind == "benign" else
            ("all" if len(flagged) == len(fraud_txns) else "none" if not flagged else "partial"),
        })

    flags = []
    for a in world.accounts:
        for t in a.txns:
            if t.signals:
                s = scenario_of.get(t.id)
                flags.append({
                    "transaction_id": str(t.id),
                    "account_id": str(a.id),
                    "rules": [r for r, _ in t.signals],
                    "label": "fraud" if t.fraud else "benign",
                    "reason": s.name if s else t.tag,
                })

    missed = [str(t.id) for a in world.accounts for t in a.txns if t.fraud and not t.signals]
    all_txns = [t for a in world.accounts for t in a.txns]
    return {
        "generated_with": {"seed": args.seed, "days": args.days, "end": world.end.isoformat(),
                           "fraud_mode": args.fraud_mode},
        "notes": "Ground truth for the seeded demo data. 'fraud' transactions are the ones a "
                 "reviewer should escalate; every other transaction is legitimate. 'flags' lists "
                 "every transaction a fraud rule fired on, with whether the rule was right. "
                 "'missed_by_rules' are fraud transactions no rule flagged.",
        "accounts": [{"id": str(a.id), "owner": a.owner, "persona": a.persona,
                      "role": roles.get(a.key, "customer"), "status": a.final_status,
                      "balance": str(a.balance)} for a in world.accounts],
        "scenarios": scenarios,
        "flags": flags,
        "fraud_transaction_ids": [str(t.id) for t in all_txns if t.fraud],
        "missed_by_rules": missed,
        "totals": {
            "accounts": len(world.accounts),
            "transactions": len(all_txns),
            "fraud_transactions": sum(t.fraud for t in all_txns),
            "flagged_transactions": len(flags),
            "flagged_fraud": sum(f["label"] == "fraud" for f in flags),
            "flagged_benign": sum(f["label"] == "benign" for f in flags),
            "missed_fraud": len(missed),
        },
    }


def report(world: World, lab: dict) -> str:
    txns = [t for a in world.accounts for t in a.txns]
    statuses = Counter(t.rejection or t.status for t in txns)
    rules = Counter(r for t in txns for r, _ in t.signals)
    benign_reasons = Counter(f["reason"] for f in lab["flags"] if f["label"] == "benign")
    lines = [
        f"Seeded {len(world.accounts)} accounts, {len(txns)} transactions "
        f"({world.first_day} to {world.end}).",
        "  outcomes: " + ", ".join(f"{k} {v}" for k, v in statuses.most_common()),
        "  rule hits: " + ", ".join(f"{k} {v}" for k, v in sorted(rules.items())),
        "  benign flags by reason: " + ", ".join(f"{k} {v}" for k, v in benign_reasons.most_common()),
        "  scenarios:",
    ]
    for s in lab["scenarios"]:
        caught = f" caught={s['caught_by_rules']}" if s["kind"] == "fraud" else ""
        lines.append(f"    {s['kind']:6} {s['name']:24} {len(s['transaction_ids']):3} txns, "
                     f"{len(s['rules_fired']):2} flagged{caught}")
    t = lab["totals"]
    lines.append(f"  fraud txns {t['fraud_transactions']}: {t['flagged_fraud']} flagged, "
                 f"{t['missed_fraud']} missed; benign flags {t['flagged_benign']}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--days", type=int, default=183, help="history length (min 160)")
    parser.add_argument("--end", type=dt.date.fromisoformat,
                        default=dt.datetime.now(UTC).date() - dt.timedelta(days=1),
                        help="last day with data, YYYY-MM-DD (default: yesterday, UTC)")
    parser.add_argument("--fraud-mode", choices=["monitor", "enforce"], default="monitor",
                        help="monitor (default): flagged transactions still post, as when "
                             "fraud.mode=monitor; enforce: they are rejected as SUSPECTED_FRAUD")
    parser.add_argument("--labels", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                         "out", "labels.json"),
                        help="where to write the ground-truth labels JSON")
    parser.add_argument("--out", help="write SQL here instead of stdout")
    parser.add_argument("--purge", action="store_true",
                        help="only delete previously seeded rows")
    args = parser.parse_args()
    if args.days < 160:
        parser.error("--days must be at least 160 (the money-mule account opens 150 days back)")

    world = World(args.seed, args.days, args.end)
    build(world)
    for acct in world.accounts:
        settle(acct, args.fraud_mode)

    out = open(args.out, "w", encoding="utf-8", newline="\n") if args.out else sys.stdout
    try:
        write_sql(out, world.accounts, args.purge)
    finally:
        if args.out:
            out.close()
    if args.purge:
        print(f"Purge SQL for {len(world.accounts)} seeded accounts.", file=sys.stderr)
        return

    lab = labels(world, args)
    os.makedirs(os.path.dirname(os.path.abspath(args.labels)), exist_ok=True)
    with open(args.labels, "w", encoding="utf-8", newline="\n") as f:
        json.dump(lab, f, indent=2)
        f.write("\n")
    print(report(world, lab), file=sys.stderr)
    print(f"Labels: {args.labels}", file=sys.stderr)


if __name__ == "__main__":
    main()
