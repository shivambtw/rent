"""Streamlit UI for RentEase. Talks to the compiled Java program (dist/rentease.jar)
over stdin/stdout: one tab-separated request line in, one JSON line out."""
import json
import shutil
import subprocess
import threading
from datetime import date, timedelta
from pathlib import Path

import pandas as pd
import streamlit as st

JAR = Path(__file__).parent / "dist" / "rentease.jar"


class JavaBackend:
    """Keeps one long-running Java process alive so its in-memory data persists."""

    def __init__(self, jar: Path):
        java = shutil.which("java")
        if java is None:
            raise RuntimeError("Java not found. Install JDK/JRE 17+ and make sure `java` is on PATH.")
        if not jar.exists():
            raise RuntimeError(f"{jar} not found. Build it first (see README).")
        self.proc = subprocess.Popen(
            [java, "-jar", str(jar), "--api"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", bufsize=1,
        )
        self.lock = threading.Lock()
        ready = json.loads(self.proc.stdout.readline())
        assert ready.get("ok"), "Java backend failed to start"

    def call(self, *parts):
        clean = [str(p).replace("\t", " ").replace("\n", " ").replace("\r", " ") for p in parts]
        with self.lock:
            if self.proc.poll() is not None:
                raise RuntimeError("Java process has stopped. Reload the page.")
            self.proc.stdin.write("\t".join(clean) + "\n")
            self.proc.stdin.flush()
            line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError("Java process closed unexpectedly.")
        reply = json.loads(line)
        if not reply["ok"]:
            raise ValueError(reply["error"])
        return reply["data"]


@st.cache_resource
def backend() -> JavaBackend:
    return JavaBackend(JAR)


st.set_page_config(page_title="RentEase", page_icon="🛠️", layout="wide")
st.title("🛠️ RentEase | Equipment Rentals")

try:
    api = backend()
except Exception as exc:  # noqa: BLE001
    st.error(str(exc))
    st.stop()


def safe(action):
    """Run a backend call; show errors in the UI instead of crashing."""
    try:
        return action()
    except ValueError as exc:
        st.error(str(exc))
    except Exception as exc:  # noqa: BLE001
        st.error(f"Unexpected error: {exc}")
    return None


items = api.call("LIST_ITEMS")
rentals = api.call("LIST_RENTALS")

tab_eq, tab_rent, tab_return, tab_rentals = st.tabs(
    ["Equipment", "Rent", "Return", "All rentals"]
)

# ---------------- Equipment ----------------
with tab_eq:
    st.subheader("Equipment")
    if items:
        df = pd.DataFrame(items)
        df["status"] = df["available"].map({True: "Available", False: "Rented"})
        df["rate"] = df["rate"].map(lambda r: f"${r:,.2f}")
        st.dataframe(
            df[["id", "name", "category", "rate", "status"]].rename(
                columns={"id": "ID", "name": "Name", "category": "Category",
                         "rate": "Daily rate", "status": "Status"}),
            hide_index=True, use_container_width=True)
    else:
        st.info("No equipment yet.")

    with st.form("add_item", clear_on_submit=True):
        st.markdown("**Add equipment**")
        c1, c2, c3 = st.columns(3)
        name = c1.text_input("Name")
        category = c2.text_input("Category")
        rate = c3.number_input("Daily rate ($)", min_value=0.0, value=10.0, step=0.5, format="%.2f")
        if st.form_submit_button("Add"):
            res = safe(lambda: api.call("ADD", name, category, f"{rate:.2f}"))
            if res:
                st.success(f"Added {res['name']} with ID {res['id']}.")
                st.rerun()

# ---------------- Rent ----------------
with tab_rent:
    st.subheader("Rent equipment")
    free = [i for i in items if i["available"]]
    if not free:
        st.info("Nothing is currently available.")
    else:
        with st.form("rent_form", clear_on_submit=True):
            choice = st.selectbox(
                "Equipment", free,
                format_func=lambda i: f"#{i['id']}  {i['name']}  (${i['rate']:.2f}/day)")
            customer = st.text_input("Customer name")
            c1, c2 = st.columns(2)
            start = c1.date_input("Start date", value=date.today())
            due = c2.date_input("Due date", value=date.today() + timedelta(days=1))
            if st.form_submit_button("Create rental"):
                res = safe(lambda: api.call("RENT", choice["id"], customer, start, due))
                if res:
                    st.success(f"Rental #{res['id']} created; due {res['due']}.")
                    st.rerun()

# ---------------- Return ----------------
with tab_return:
    st.subheader("Return equipment")
    active = [r for r in rentals if r["returned"] is None]
    if not active:
        st.info("There are no active rentals.")
    else:
        with st.form("return_form", clear_on_submit=True):
            choice = st.selectbox(
                "Active rental", active,
                format_func=lambda r: f"#{r['id']}  {r['equipment']} — {r['customer']} (from {r['start']})")
            ret = st.date_input("Return date", value=date.today())
            if st.form_submit_button("Return"):
                res = safe(lambda: api.call("RETURN", choice["id"], ret))
                if res:
                    st.success(f"Returned successfully. Total: ${res['total']:,.2f}")
                    st.rerun()

# ---------------- Rentals ----------------
with tab_rentals:
    st.subheader("All rentals")
    if rentals:
        df = pd.DataFrame(rentals)
        df["status"] = df["returned"].map(lambda r: "Active" if r is None else f"Returned {r}")
        st.dataframe(
            df[["id", "customer", "equipment", "start", "due", "status"]].rename(
                columns={"id": "ID", "customer": "Customer", "equipment": "Equipment",
                         "start": "Start", "due": "Due", "status": "Status"}),
            hide_index=True, use_container_width=True)
    else:
        st.info("No rentals created yet.")
