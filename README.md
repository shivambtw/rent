# RentEase: equipment rentals (Java core + Streamlit UI)

No database. The **Java** program holds all the business rules and saves data to plain text
files in a `data/` folder. The **Streamlit** UI (pure Python) just draws the screens.

## Features

| Area | What you can do |
|------|-----------------|
| **Dashboard** | Revenue, active and overdue rentals, availability, discounts given, charts by category and most-rented items |
| **Equipment** | Search and filter, add, edit rate, mark under maintenance, delete (only if never rented) |
| **Customers** | Register and edit customers (name, phone, email, address), search, see spend and tier |
| **New rental** | Pick or register a customer, pick equipment and dates, apply a coupon, see a live price |
| **Return & extend** | Price preview for the chosen return date, late fees, extend a due date, download an invoice |
| **All rentals** | Filter by status or search, export CSV, download invoices |
| **Coupons** | Percent or flat-amount codes with minimum rental, expiry date and usage limit; enable, disable, delete |

Pricing rules (all in `src/RentEase.java`):

- Rental charge = days x daily rate (minimum 1 day).
- **Loyalty tiers**: Silver after 3 completed rentals gets 5% off; Gold after 6 gets 10% off.
- **Coupons** apply to the rental charge. A coupon is rejected if it is disabled, expired (checked against the start date), fully redeemed, or the rental is below its minimum.
- **Late fee**: 50% of the daily rate for each day returned after the due date.
- Total = charge - loyalty - coupon + late fee.

Seed data on first run: 7 pieces of equipment, 1 customer and the coupons `WELCOME10` (10% off), `FLAT20` ($20 off a $100+ rental) and `SUMMER25` (25% off a $150+ rental, 50 uses).

## Run it locally (exact steps)

You need **Java 17+ (a JDK)** and **Python 3.9+**. Copy and run the commands in order.

### Step 1: get the code

```
git clone https://github.com/shivambtw/rent.git
cd rent
```

### Step 2: check Java and Python

```
java -version
javac -version
python --version
```

All three must print a version. If `java`/`javac` is missing, install JDK 17+ from https://adoptium.net and open a new terminal. (On macOS/Linux use `python3` if `python` is not found.)

### Step 3: create a virtual environment and install the UI dependencies

**Windows (PowerShell):**

```
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

**Windows (cmd):**

```
python -m venv .venv
.venv\Scripts\activate.bat
pip install -r requirements.txt
```

If PowerShell blocks the activate script, run `Set-ExecutionPolicy -Scope Process Bypass` first.

**macOS / Linux:**

```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### Step 4: build the Java jar

The jar committed in `dist/` is from the original basic version and is too old for this UI, so rebuild it:

**Windows:**

```
build.bat
```

**macOS / Linux / Git Bash:**

```
chmod +x build.sh
./build.sh
```

You should see `Built dist/rentease.jar`. (If you skip this and a JDK is installed, the app rebuilds the jar automatically on first launch.)

### Step 5 (optional): run the built-in Java checks

```
java -jar dist/rentease.jar --self-test
```

It should print `All checks passed.`

### Step 6: start the app

```
streamlit run app.py
```

The browser opens at http://localhost:8501. Press `Ctrl+C` in the terminal to stop it.

### Next time

Only these are needed:

```
cd rent
.venv\Scripts\activate        (macOS/Linux: source .venv/bin/activate)
streamlit run app.py
```

## Where is my data?

In the `data/` folder next to `app.py` (`items.tsv`, `customers.tsv`, `coupons.tsv`, `rentals.tsv`). They are plain tab-separated text files.
To reset everything to the demo data, stop the app and delete the `data/` folder.

## Other ways to run the Java program

```
java -jar dist/rentease.jar                 # terminal menu (same data files)
java -jar dist/rentease.jar --data mydata   # use a different data folder
java -jar dist/rentease.jar --self-test     # built-in checks for pricing, coupons, late fees, loyalty
```

## Troubleshooting

- **"`java` not found"**: install Java 17+ and reopen the terminal, or set `JAVA_HOME`.
- **"dist/rentease.jar is out of date"**: run `build.bat` / `./build.sh` (needs a JDK, not just a JRE).
- **Data files look corrupted**: delete the `data/` folder to start fresh.
- **Port already in use**: `streamlit run app.py --server.port 8502`.

## Project layout

```
app.py          Streamlit screens
backend.py      Talks to the Java process (and rebuilds the jar if needed)
src/RentEase.java  Data, rules, file storage, terminal menu
dist/rentease.jar  Compiled program
build.bat / build.sh
```
