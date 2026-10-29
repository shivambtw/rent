"""Streamlit UI for RentEase. All data and business rules live in the Java program
(dist/rentease.jar); this file only draws the screens. See backend.py for the bridge."""
from datetime import date, timedelta

import pandas as pd
import streamlit as st

from backend import JavaBackend

st.set_page_config(page_title="RentEase", page_icon="🛠️", layout="wide")

st.markdown(
    """
    <style>
      .block-container {padding-top: 2rem;}
      [data-testid="stMetric"] {background: rgba(128,128,128,.08); border-radius: 12px; padding: 12px 16px;}
    </style>
    """,
    unsafe_allow_html=True,
)


@st.cache_resource
def backend() -> JavaBackend:
    return JavaBackend()


try:
    api = backend()
except Exception as exc:  # noqa: BLE001
    st.title("🛠️ RentEase")
    st.error(str(exc))
    st.stop()


def money(x) -> str:
    return f"${x:,.2f}"


def safe(action):
    """Run a backend call; show errors in the UI instead of crashing."""
    try:
        return action()
    except ValueError as exc:
        st.error(str(exc))
    except Exception as exc:  # noqa: BLE001
        st.error(f"Unexpected error: {exc}")
    return None


def flash(message: str):
    """Show a message after the next rerun (st.success would vanish on st.rerun())."""
    st.session_state["flash"] = message


def show_flash():
    message = st.session_state.pop("flash", None)
    if message:
        st.success(message)


TIER_BADGE = {"Standard": "⚪ Standard", "Silver": "🥈 Silver", "Gold": "🥇 Gold"}
STATUS_BADGE = {"Available": "🟢 Available", "Rented": "🟠 Rented", "Maintenance": "🔧 Maintenance",
                "Active": "🟢 Active", "Overdue": "🔴 Overdue", "Returned": "✅ Returned"}


def invoice_text(r: dict) -> str:
    b = r["bill"]
    lines = [
        "RENTEASE - INVOICE",
        "=" * 40,
        f"Rental #{r['id']}",
        f"Customer : {r['customer']} ({r['phone']})",
        f"Equipment: {r['equipment']}",
        f"Period   : {r['start']} to {r['returned'] or r['due']}",
        "-" * 40,
        f"Rental charge ({b['days']} day(s))".ljust(30) + money(b["base"]).rjust(10),
    ]
    if b["loyalty"]:
        lines.append(f"Loyalty discount ({b['loyaltyPercent']}%)".ljust(30) + ("-" + money(b["loyalty"])).rjust(10))
    if b["coupon"]:
        lines.append(f"Coupon {r['coupon']}".ljust(30) + ("-" + money(b["coupon"])).rjust(10))
    if b["lateFee"]:
        lines.append(f"Late fee ({b['lateDays']} day(s))".ljust(30) + money(b["lateFee"]).rjust(10))
    lines += ["-" * 40, "TOTAL".ljust(30) + money(b["total"]).rjust(10), "=" * 40, "Thank you for renting with us!"]
    return "\n".join(lines)


def show_bill(b: dict):
    """Price breakdown as a small table of metrics."""
    cols = st.columns(5)
    cols[0].metric(f"Rental ({b['days']} day{'s' if b['days'] != 1 else ''})", money(b["base"]))
    cols[1].metric(f"Loyalty ({b['loyaltyPercent']:g}%)", "-" + money(b["loyalty"]))
    cols[2].metric("Coupon", "-" + money(b["coupon"]))
    cols[3].metric(f"Late fee ({b['lateDays']}d)", money(b["lateFee"]))
    cols[4].metric("Total", money(b["total"]))


# ---------------- Sidebar navigation ----------------
st.sidebar.title("🛠️ RentEase")
page = st.sidebar.radio(
    "Go to",
    ["📊 Dashboard", "📦 Equipment", "👤 Customers", "➕ New rental", "↩️ Return & extend",
     "📋 All rentals", "🎟️ Coupons"],
    label_visibility="collapsed",
)
st.sidebar.caption("Data is saved in the `data/` folder next to this app.")

items = api.call("LIST_ITEMS")
customers = api.call("LIST_CUSTOMERS")
coupons = api.call("LIST_COUPONS")
rentals = api.call("LIST_RENTALS")

show_flash()

# ================= Dashboard =================
if page == "📊 Dashboard":
    st.title("📊 Dashboard")
    s = api.call("STATS")
    c = st.columns(4)
    c[0].metric("Revenue", money(s["revenue"]))
    c[1].metric("Active rentals", s["activeRentals"])
    c[2].metric("Overdue", s["overdue"])
    c[3].metric("Customers", s["customers"])
    c = st.columns(4)
    c[0].metric("Available now", f"{s['available']} / {s['items']}")
    c[1].metric("Under maintenance", s["maintenance"])
    c[2].metric("Discounts given", money(s["discounts"]))
    c[3].metric("Late fees collected", money(s["lateFees"]))

    overdue = [r for r in rentals if r["status"] == "Overdue"]
    if overdue:
        st.error(f"⚠️ {len(overdue)} overdue rental(s)")
        st.dataframe(
            pd.DataFrame([{"ID": r["id"], "Customer": r["customer"], "Phone": r["phone"],
                           "Equipment": r["equipment"], "Was due": r["due"],
                           "Late fee so far": money(r["bill"]["lateFee"])} for r in overdue]),
            hide_index=True, use_container_width=True)

    left, right = st.columns(2)
    with left:
        st.subheader("Revenue by category")
        if items and s["revenue"]:
            df = pd.DataFrame(items).groupby("category")["revenue"].sum()
            st.bar_chart(df)
        else:
            st.info("Revenue shows up here after the first return.")
    with right:
        st.subheader("Most rented equipment")
        ranked = sorted((i for i in items if i["timesRented"]), key=lambda i: -i["timesRented"])[:5]
        if ranked:
            st.bar_chart(pd.DataFrame({"Rentals": [i["timesRented"] for i in ranked]},
                                      index=[i["name"] for i in ranked]))
        else:
            st.info("No rentals yet.")

# ================= Equipment =================
elif page == "📦 Equipment":
    st.title("📦 Equipment")
    f1, f2, f3 = st.columns([2, 1, 1])
    search = f1.text_input("Search", placeholder="Search by name…")
    categories = sorted({i["category"] for i in items})
    cat = f2.selectbox("Category", ["All"] + categories)
    status = f3.selectbox("Status", ["All", "Available", "Rented", "Maintenance"])

    shown = [i for i in items
             if search.lower() in i["name"].lower()
             and (cat == "All" or i["category"] == cat)
             and (status == "All" or i["status"] == status)]
    if shown:
        st.dataframe(
            pd.DataFrame([{"ID": i["id"], "Name": i["name"], "Category": i["category"],
                           "Daily rate": money(i["rate"]), "Status": STATUS_BADGE[i["status"]],
                           "Times rented": i["timesRented"], "Revenue": money(i["revenue"])} for i in shown]),
            hide_index=True, use_container_width=True)
    else:
        st.info("No equipment matches.")

    with st.expander("➕ Add equipment"):
        with st.form("add_item", clear_on_submit=True):
            c1, c2, c3 = st.columns(3)
            name = c1.text_input("Name")
            category = c2.text_input("Category", help="e.g. Tools, Garden, Events")
            rate = c3.number_input("Daily rate ($)", min_value=0.0, value=10.0, step=0.5, format="%.2f")
            if st.form_submit_button("Add"):
                res = safe(lambda: api.call("ADD", name, category, f"{rate:.2f}"))
                if res:
                    flash(f"Added {res['name']} with ID {res['id']}.")
                    st.rerun()

    if items:
        with st.expander("✏️ Edit, maintenance or delete"):
            pick = st.selectbox("Equipment", items, format_func=lambda i: f"#{i['id']}  {i['name']}",
                                key="edit_item_pick")
            with st.form("edit_item"):
                c1, c2, c3 = st.columns(3)
                name = c1.text_input("Name", value=pick["name"], key=f"en{pick['id']}")
                category = c2.text_input("Category", value=pick["category"], key=f"ec{pick['id']}")
                rate = c3.number_input("Daily rate ($)", min_value=0.0, value=float(pick["rate"]), step=0.5,
                                       format="%.2f", key=f"er{pick['id']}")
                if st.form_submit_button("Save changes"):
                    if safe(lambda: api.call("UPDATE_ITEM", pick["id"], name, category, f"{rate:.2f}")):
                        flash("Equipment updated.")
                        st.rerun()
            b1, b2 = st.columns(2)
            if pick["status"] == "Maintenance":
                if b1.button("✅ Back in service"):
                    if safe(lambda: api.call("SET_MAINTENANCE", pick["id"], 0)):
                        flash(f"{pick['name']} is available again.")
                        st.rerun()
            elif b1.button("🔧 Mark under maintenance"):
                if safe(lambda: api.call("SET_MAINTENANCE", pick["id"], 1)):
                    flash(f"{pick['name']} is now under maintenance.")
                    st.rerun()
            if b2.button("🗑️ Delete (only if never rented)"):
                if safe(lambda: api.call("DELETE_ITEM", pick["id"])):
                    flash(f"Deleted {pick['name']}.")
                    st.rerun()

# ================= Customers =================
elif page == "👤 Customers":
    st.title("👤 Customers")
    search = st.text_input("Search", placeholder="Name, phone or email…")
    shown = [u for u in customers if search.lower() in f"{u['name']} {u['phone']} {u['email']}".lower()]
    if shown:
        st.dataframe(
            pd.DataFrame([{"ID": u["id"], "Name": u["name"], "Phone": u["phone"], "Email": u["email"],
                           "Address": u["address"], "Member since": u["joined"],
                           "Tier": TIER_BADGE[u["tier"]], "Rentals": u["rentals"],
                           "Active": u["activeRentals"], "Total spent": money(u["spent"])} for u in shown]),
            hide_index=True, use_container_width=True)
    else:
        st.info("No customers found.")
    st.caption("Loyalty tiers: Silver after 3 completed rentals (5% off), Gold after 6 (10% off).")

    with st.expander("➕ Register a customer"):
        with st.form("add_customer", clear_on_submit=True):
            c1, c2 = st.columns(2)
            name = c1.text_input("Full name")
            phone = c2.text_input("Phone")
            email = c1.text_input("Email (optional)")
            address = c2.text_input("Address (optional)")
            if st.form_submit_button("Register"):
                res = safe(lambda: api.call("ADD_CUSTOMER", name, phone, email, address))
                if res:
                    flash(f"Registered {res['name']} with ID {res['id']}.")
                    st.rerun()

    if customers:
        with st.expander("✏️ Edit a customer"):
            pick = st.selectbox("Customer", customers, format_func=lambda u: f"#{u['id']}  {u['name']}",
                                key="edit_cust_pick")
            with st.form("edit_customer"):
                k = pick["id"]
                c1, c2 = st.columns(2)
                name = c1.text_input("Full name", value=pick["name"], key=f"cn{k}")
                phone = c2.text_input("Phone", value=pick["phone"], key=f"cp{k}")
                email = c1.text_input("Email", value=pick["email"], key=f"ce{k}")
                address = c2.text_input("Address", value=pick["address"], key=f"ca{k}")
                if st.form_submit_button("Save changes"):
                    if safe(lambda: api.call("UPDATE_CUSTOMER", k, name, phone, email, address)):
                        flash("Customer updated.")
                        st.rerun()

# ================= New rental =================
elif page == "➕ New rental":
    st.title("➕ New rental")
    free = [i for i in items if i["available"]]
    if not free:
        st.info("Nothing is currently available.")
        st.stop()

    nonce = st.session_state.setdefault("rent_nonce", 0)  # bump to reset the widgets after success
    NEW = {"id": 0, "name": "➕ New customer…"}
    st.subheader("1. Who is renting?")
    cust = st.selectbox("Customer", [NEW] + customers,
                        format_func=lambda u: u["name"] if u["id"] == 0
                        else f"#{u['id']}  {u['name']}  ·  {u['phone']}  ·  {TIER_BADGE[u['tier']]}",
                        key=f"r_cust{nonce}")
    if cust["id"] == 0:
        c1, c2 = st.columns(2)
        n_name = c1.text_input("Full name", key=f"r_nn{nonce}")
        n_phone = c2.text_input("Phone", key=f"r_np{nonce}")
        n_email = c1.text_input("Email (optional)", key=f"r_ne{nonce}")
        n_addr = c2.text_input("Address (optional)", key=f"r_na{nonce}")

    st.subheader("2. What and when?")
    item = st.selectbox("Equipment", free, key=f"r_item{nonce}",
                        format_func=lambda i: f"#{i['id']}  {i['name']}  ({money(i['rate'])}/day)")
    c1, c2 = st.columns(2)
    start = c1.date_input("Start date", value=date.today(), key=f"r_start{nonce}")
    due = c2.date_input("Due date", value=date.today() + timedelta(days=1), key=f"r_due{nonce}")

    st.subheader("3. Coupon (optional)")
    active_codes = [k["code"] for k in coupons if k["status"] == "Active"]
    if active_codes:
        st.caption("Active codes: " + ", ".join(f"`{k['code']}` ({k['label']})" for k in coupons
                                                if k["status"] == "Active"))
    code = st.text_input("Coupon code", key=f"r_code{nonce}").strip().upper()

    st.subheader("4. Price")
    quote = None
    if due <= start:
        st.warning("Due date must be after the start date.")
    elif cust["id"] != 0:
        quote = safe(lambda: api.call("QUOTE", item["id"], cust["id"], start, due, code))
    elif customers:
        # New customers have no loyalty discount; borrow an existing customer only to price + validate the coupon.
        quote = safe(lambda: api.call("QUOTE", item["id"], customers[0]["id"], start, due, code))
        if quote:
            quote.update(loyalty=0, loyaltyPercent=0, total=quote["base"] - quote["coupon"] + quote["lateFee"])
    if quote:
        show_bill(quote)
        st.caption("Estimate assumes the item comes back on the due date. Late returns add a late fee "
                   "(50% of the daily rate per extra day).")

    if st.button("Create rental", type="primary", disabled=due <= start):
        customer_id = cust["id"]
        if customer_id == 0:
            created = safe(lambda: api.call("ADD_CUSTOMER", n_name, n_phone, n_email, n_addr))
            customer_id = created["id"] if created else None
        if customer_id:
            res = safe(lambda: api.call("RENT", item["id"], customer_id, start, due, code))
            if res:
                st.session_state["rent_nonce"] = nonce + 1
                flash(f"Rental #{res['id']} created for {res['customer']}: {res['equipment']}, due {res['due']}."
                      + (f" Coupon {res['coupon']} applied." if res["coupon"] else ""))
                st.rerun()

# ================= Return & extend =================
elif page == "↩️ Return & extend":
    st.title("↩️ Return & extend")
    active = [r for r in rentals if r["returned"] is None]
    if not active:
        st.info("There are no active rentals.")
    else:
        rental = st.selectbox(
            "Active rental", active,
            format_func=lambda r: f"#{r['id']}  {r['equipment']} — {r['customer']}  "
                                  f"(due {r['due']}{'  ⚠️ OVERDUE' if r['status'] == 'Overdue' else ''})")
        tab_ret, tab_ext = st.tabs(["Return", "Extend due date"])
        with tab_ret:
            started = date.fromisoformat(rental["start"])
            ret = st.date_input("Return date", value=max(date.today(), started), min_value=started,
                                key=f"ret{rental['id']}")
            bill = safe(lambda: api.call("QUOTE_RETURN", rental["id"], ret))
            if bill:
                show_bill(bill)
                if bill["lateDays"]:
                    st.warning(f"Returned {bill['lateDays']} day(s) after the due date, so a late fee applies.")
            if st.button("Confirm return", type="primary"):
                res = safe(lambda: api.call("RETURN", rental["id"], ret))
                if res:
                    st.session_state["last_invoice"] = res
                    flash(f"Returned. Total: {money(res['bill']['total'])}")
                    st.rerun()
        with tab_ext:
            new_due = st.date_input("New due date", value=date.fromisoformat(rental["due"]) + timedelta(days=1),
                                    key=f"ext{rental['id']}")
            if st.button("Extend rental"):
                if safe(lambda: api.call("EXTEND", rental["id"], new_due)):
                    flash(f"Rental #{rental['id']} now due {new_due}.")
                    st.rerun()

    last = st.session_state.get("last_invoice")
    if last:
        st.divider()
        st.subheader(f"Invoice for rental #{last['id']}")
        text = invoice_text(last)
        st.code(text, language=None)
        st.download_button("⬇️ Download invoice", text, file_name=f"invoice-{last['id']}.txt")

# ================= All rentals =================
elif page == "📋 All rentals":
    st.title("📋 All rentals")
    f1, f2 = st.columns([1, 2])
    status = f1.selectbox("Status", ["All", "Active", "Overdue", "Returned"])
    search = f2.text_input("Search", placeholder="Customer or equipment…")
    shown = [r for r in rentals
             if (status == "All" or r["status"] == status)
             and search.lower() in f"{r['customer']} {r['equipment']}".lower()]
    if shown:
        df = pd.DataFrame([{
            "ID": r["id"], "Customer": r["customer"], "Phone": r["phone"], "Equipment": r["equipment"],
            "Start": r["start"], "Due": r["due"], "Returned": r["returned"] or "",
            "Coupon": r["coupon"] or "", "Status": STATUS_BADGE[r["status"]],
            "Total": money(r["bill"]["total"]),
        } for r in shown])
        st.dataframe(df, hide_index=True, use_container_width=True)
        st.caption("Totals for active and overdue rentals are projected up to today (or the due date).")
        st.download_button("⬇️ Export CSV", df.to_csv(index=False), file_name="rentals.csv", mime="text/csv")
        done = [r for r in shown if r["returned"]]
        if done:
            pick = st.selectbox("Invoice for a returned rental", done,
                                format_func=lambda r: f"#{r['id']}  {r['equipment']} — {r['customer']}")
            st.download_button("⬇️ Download invoice", invoice_text(pick), file_name=f"invoice-{pick['id']}.txt")
    else:
        st.info("No rentals match.")

# ================= Coupons =================
elif page == "🎟️ Coupons":
    st.title("🎟️ Coupons")
    if coupons:
        st.dataframe(
            pd.DataFrame([{"Code": k["code"], "Discount": k["label"],
                           "Min rental": money(k["minAmount"]), "Expires": k["expires"] or "Never",
                           "Used": f"{k['used']}/{k['maxUses']}" if k["maxUses"] else f"{k['used']}",
                           "Status": k["status"]} for k in coupons]),
            hide_index=True, use_container_width=True)
    else:
        st.info("No coupons yet.")

    with st.expander("➕ Create a coupon", expanded=not coupons):
        with st.form("add_coupon", clear_on_submit=True):
            c1, c2, c3 = st.columns(3)
            code = c1.text_input("Code", placeholder="SAVE15", help="3-20 letters, digits, - or _")
            kind = c2.selectbox("Type", ["Percent off", "Flat amount off"])
            value = c3.number_input("Value (% or $)", min_value=0.0, value=10.0, step=1.0)
            c4, c5, c6 = st.columns(3)
            min_amount = c4.number_input("Minimum rental ($)", min_value=0.0, value=0.0, step=10.0)
            has_expiry = c5.checkbox("Has expiry date")
            expiry = c5.date_input("Expires on", value=date.today() + timedelta(days=30))
            max_uses = c6.number_input("Max uses (0 = unlimited)", min_value=0, value=0, step=1)
            if st.form_submit_button("Create coupon"):
                res = safe(lambda: api.call(
                    "ADD_COUPON", code, "PERCENT" if kind == "Percent off" else "FLAT", f"{value:.2f}",
                    f"{min_amount:.2f}", expiry if has_expiry else "", int(max_uses)))
                if res:
                    flash(f"Coupon {res['code']} created ({res['label']}).")
                    st.rerun()

    if coupons:
        with st.expander("⚙️ Enable, disable or delete"):
            pick = st.selectbox("Coupon", coupons, format_func=lambda k: f"{k['code']} — {k['status']}")
            b1, b2 = st.columns(2)
            if b1.button("Disable" if pick["active"] else "Enable"):
                if safe(lambda: api.call("TOGGLE_COUPON", pick["code"])):
                    flash(f"{pick['code']} {'disabled' if pick['active'] else 'enabled'}.")
                    st.rerun()
            if b2.button("🗑️ Delete (only if never used)"):
                if safe(lambda: api.call("DELETE_COUPON", pick["code"])):
                    flash(f"Deleted {pick['code']}.")
                    st.rerun()
