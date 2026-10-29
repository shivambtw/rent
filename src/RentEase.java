import java.io.*;
import java.math.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * RentEase core: equipment, customers, coupons, rentals, billing.
 * No database: data lives in memory and is saved to plain TSV files in the data folder.
 */
public class RentEase {
    static final int API_VERSION = 2; // keep in sync with REQUIRED_API in backend.py
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal LATE_FEE_RATE = new BigDecimal("0.50"); // extra 50% of the daily rate per late day
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{3,20}$");

    private final Scanner in;
    private final Map<Integer, Equipment> items = new LinkedHashMap<>();
    private final Map<Integer, Customer> customers = new LinkedHashMap<>();
    private final Map<String, Coupon> coupons = new LinkedHashMap<>();
    private final Map<Integer, Rental> rentals = new LinkedHashMap<>();
    private int nextItem = 1, nextCustomer = 1, nextRental = 1;
    private Path dataDir; // null = in-memory only (self-test)

    private RentEase(boolean utf8) {
        in = new Scanner(System.in, utf8 ? StandardCharsets.UTF_8 : Charset.defaultCharset());
    }

    public static void main(String[] args) {
        Path dir = Paths.get("data");
        String mode = "";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data") && i + 1 < args.length) dir = Paths.get(args[++i]);
            else mode = args[i];
        }

        if (mode.equals("--self-test")) {
            new RentEase(false).selfTest();
            return;
        }

        boolean api = mode.equals("--api");
        if (api) System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        RentEase app = new RentEase(api);
        app.dataDir = dir;
        app.load();
        if (api) app.serve();
        else app.run();
    }

    // ---------------- Seed data (only on first run) ----------------

    private void seed() {
        add("Cordless Drill", "Tools", new BigDecimal("12.50"));
        add("Pressure Washer", "Garden", new BigDecimal("30.00"));
        add("Projector", "Events", new BigDecimal("45.00"));
        add("Extension Ladder", "Tools", new BigDecimal("18.00"));
        add("Lawn Mower", "Garden", new BigDecimal("25.00"));
        add("PA Sound System", "Events", new BigDecimal("60.00"));
        add("DSLR Camera", "Photography", new BigDecimal("40.00"));
        addCustomer("Ada Lovelace", "5550100100", "ada@example.com", "12 Analytical Way");
        addCoupon("WELCOME10", "PERCENT", "10", "0", "", "0");
        addCoupon("FLAT20", "FLAT", "20", "100", "", "0");
        addCoupon("SUMMER25", "PERCENT", "25", "150", "", "50");
    }

    // ---------------- Persistence ----------------

    private void load() {
        if (!Files.exists(dataDir.resolve("items.tsv"))) {
            seed();
            return;
        }
        try {
            for (String[] c : readRows("items.tsv")) {
                Equipment e = new Equipment(Integer.parseInt(c[0]), c[1], c[2], new BigDecimal(c[3]));
                e.maintenance = Boolean.parseBoolean(c[4]);
                items.put(e.id, e);
                nextItem = Math.max(nextItem, e.id + 1);
            }
            for (String[] c : readRows("customers.tsv")) {
                Customer u = new Customer(Integer.parseInt(c[0]), c[1], c[2], c[3], c[4], LocalDate.parse(c[5]));
                customers.put(u.id, u);
                nextCustomer = Math.max(nextCustomer, u.id + 1);
            }
            for (String[] c : readRows("coupons.tsv")) {
                Coupon k = new Coupon(c[0], c[1], new BigDecimal(c[2]), new BigDecimal(c[3]),
                        c[4].isEmpty() ? null : LocalDate.parse(c[4]), Integer.parseInt(c[5]));
                k.used = Integer.parseInt(c[6]);
                k.active = Boolean.parseBoolean(c[7]);
                coupons.put(k.code, k);
            }
            for (String[] c : readRows("rentals.tsv")) {
                Rental r = new Rental(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]),
                        LocalDate.parse(c[3]), LocalDate.parse(c[4]), c[6]);
                if (!c[5].isEmpty()) r.returned = LocalDate.parse(c[5]);
                if (!c[7].isEmpty()) {
                    Bill b = new Bill();
                    b.days = Integer.parseInt(c[7]);
                    b.lateDays = Integer.parseInt(c[8]);
                    b.base = new BigDecimal(c[9]);
                    b.loyaltyPercent = new BigDecimal(c[10]);
                    b.loyalty = new BigDecimal(c[11]);
                    b.coupon = new BigDecimal(c[12]);
                    b.lateFee = new BigDecimal(c[13]);
                    b.total = new BigDecimal(c[14]);
                    r.bill = b;
                }
                rentals.put(r.id, r);
                nextRental = Math.max(nextRental, r.id + 1);
                if (r.returned == null) items.get(r.itemId).rented = true;
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Data files in '" + dataDir + "' look corrupted (" + e + "). "
                    + "Fix or delete that folder to start fresh.");
        }
    }

    private void save() {
        if (dataDir == null) return;
        try {
            Files.createDirectories(dataDir);
            List<String> lines = new ArrayList<>();
            for (Equipment e : items.values()) lines.add(row(e.id, e.name, e.category, e.rate.toPlainString(), e.maintenance));
            write("items.tsv", lines);

            lines = new ArrayList<>();
            for (Customer u : customers.values()) lines.add(row(u.id, u.name, u.phone, u.email, u.address, u.joined));
            write("customers.tsv", lines);

            lines = new ArrayList<>();
            for (Coupon k : coupons.values())
                lines.add(row(k.code, k.type, k.value.toPlainString(), k.minAmount.toPlainString(),
                        k.expires == null ? "" : k.expires, k.maxUses, k.used, k.active));
            write("coupons.tsv", lines);

            lines = new ArrayList<>();
            for (Rental r : rentals.values()) {
                Bill b = r.bill;
                lines.add(row(r.id, r.itemId, r.customerId, r.start, r.due, r.returned == null ? "" : r.returned, r.coupon,
                        b == null ? "" : b.days, b == null ? "" : b.lateDays, b == null ? "" : b.base.toPlainString(),
                        b == null ? "" : b.loyaltyPercent.toPlainString(), b == null ? "" : b.loyalty.toPlainString(),
                        b == null ? "" : b.coupon.toPlainString(), b == null ? "" : b.lateFee.toPlainString(),
                        b == null ? "" : b.total.toPlainString()));
            }
            write("rentals.tsv", lines);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save data to '" + dataDir + "': " + e.getMessage());
        }
    }

    private void write(String name, List<String> lines) throws IOException {
        Path tmp = dataDir.resolve(name + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, dataDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private List<String[]> readRows(String name) throws IOException {
        List<String[]> rows = new ArrayList<>();
        Path f = dataDir.resolve(name);
        if (!Files.exists(f)) return rows;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] c = line.split("\t", -1);
            for (int i = 0; i < c.length; i++) c[i] = unesc(c[i]);
            rows.add(c);
        }
        return rows;
    }

    private static String row(Object... fields) {
        StringJoiner j = new StringJoiner("\t");
        for (Object o : fields) j.add(esc(o == null ? "" : String.valueOf(o)));
        return j.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------- API mode (used by the Streamlit UI) ----------------
    // Protocol: one request per line, fields separated by TAB. One JSON line comes back:
    //   {"ok":true,"data":...}  or  {"ok":false,"error":"..."}
    //
    //   LIST_ITEMS | ADD name category rate | UPDATE_ITEM id name category rate
    //   SET_MAINTENANCE id 0|1 | DELETE_ITEM id
    //   LIST_CUSTOMERS | ADD_CUSTOMER name phone email address | UPDATE_CUSTOMER id name phone email address
    //   LIST_COUPONS | ADD_COUPON code PERCENT|FLAT value minAmount expires maxUses
    //   TOGGLE_COUPON code | DELETE_COUPON code
    //   QUOTE itemId customerId start due coupon | RENT itemId customerId start due coupon
    //   QUOTE_RETURN rentalId date | EXTEND rentalId newDue | RETURN rentalId date
    //   LIST_RENTALS | STATS | QUIT
    private void serve() {
        System.out.println("{\"ok\":true,\"data\":\"ready:" + API_VERSION + "\"}");
        System.out.flush();
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            String out;
            try {
                switch (p[0]) {
                    case "LIST_ITEMS" -> out = ok(itemsJson());
                    case "ADD" -> out = ok(itemJson(add(p[1], p[2], new BigDecimal(p[3]))));
                    case "UPDATE_ITEM" -> out = ok(itemJson(updateItem(Integer.parseInt(p[1]), p[2], p[3], new BigDecimal(p[4]))));
                    case "SET_MAINTENANCE" -> out = ok(itemJson(setMaintenance(Integer.parseInt(p[1]), p[2].equals("1"))));
                    case "DELETE_ITEM" -> {
                        deleteItem(Integer.parseInt(p[1]));
                        out = ok("\"deleted\"");
                    }
                    case "LIST_CUSTOMERS" -> out = ok(customersJson());
                    case "ADD_CUSTOMER" -> out = ok(customerJson(addCustomer(p[1], p[2], p[3], p[4])));
                    case "UPDATE_CUSTOMER" -> out = ok(customerJson(updateCustomer(Integer.parseInt(p[1]), p[2], p[3], p[4], p[5])));
                    case "LIST_COUPONS" -> out = ok(couponsJson());
                    case "ADD_COUPON" -> out = ok(couponJson(addCoupon(p[1], p[2], p[3], p[4], p[5], p[6])));
                    case "TOGGLE_COUPON" -> out = ok(couponJson(toggleCoupon(p[1])));
                    case "DELETE_COUPON" -> {
                        deleteCoupon(p[1]);
                        out = ok("\"deleted\"");
                    }
                    case "QUOTE" -> out = ok(quoteJson(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            LocalDate.parse(p[3]), LocalDate.parse(p[4]), p[5]));
                    case "RENT" -> out = ok(rentalJson(rent(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            LocalDate.parse(p[3]), LocalDate.parse(p[4]), p[5])));
                    case "QUOTE_RETURN" -> out = ok(billJson(quoteReturn(Integer.parseInt(p[1]), LocalDate.parse(p[2])), false));
                    case "EXTEND" -> out = ok(rentalJson(extend(Integer.parseInt(p[1]), LocalDate.parse(p[2]))));
                    case "RETURN" -> out = ok(rentalJson(returnRental(Integer.parseInt(p[1]), LocalDate.parse(p[2]))));
                    case "LIST_RENTALS" -> out = ok(rentalsJson());
                    case "STATS" -> out = ok(statsJson());
                    case "QUIT" -> {
                        System.out.println(ok("\"bye\""));
                        System.out.flush();
                        return;
                    }
                    default -> out = err("Unknown command: " + p[0]);
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                out = err("Missing arguments for " + p[0]);
            } catch (NumberFormatException | DateTimeParseException e) {
                out = err("Invalid number or date: " + e.getMessage());
            } catch (RuntimeException e) {
                out = err(e.getMessage());
            }
            System.out.println(out);
            System.out.flush();
        }
    }

    private static String ok(String data) { return "{\"ok\":true,\"data\":" + data + "}"; }

    private static String err(String msg) { return "{\"ok\":false,\"error\":" + q(msg) + "}"; }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Tiny JSON object builder. */
    private static final class Json {
        private final StringBuilder sb = new StringBuilder("{");
        private boolean first = true;

        Json raw(String key, String value) {
            if (!first) sb.append(',');
            first = false;
            sb.append(q(key)).append(':').append(value);
            return this;
        }

        Json s(String key, Object value) { return raw(key, value == null ? "null" : q(value.toString())); }

        Json n(String key, Object value) { return raw(key, String.valueOf(value)); }

        Json m(String key, BigDecimal value) { return raw(key, value == null ? "null" : value.toPlainString()); }

        @Override
        public String toString() { return sb + "}"; }
    }

    private String itemJson(Equipment e) {
        int times = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        for (Rental r : rentals.values()) {
            if (r.itemId != e.id) continue;
            times++;
            if (r.bill != null) revenue = revenue.add(r.bill.total);
        }
        return new Json().n("id", e.id).s("name", e.name).s("category", e.category).m("rate", e.rate)
                .n("available", e.isAvailable()).s("status", e.status())
                .n("timesRented", times).m("revenue", revenue).toString();
    }

    private String customerJson(Customer u) {
        int done = completed(u.id), active = 0, total = 0;
        BigDecimal spent = BigDecimal.ZERO;
        for (Rental r : rentals.values()) {
            if (r.customerId != u.id) continue;
            total++;
            if (r.returned == null) active++;
            else spent = spent.add(r.bill.total);
        }
        return new Json().n("id", u.id).s("name", u.name).s("phone", u.phone).s("email", u.email)
                .s("address", u.address).s("joined", u.joined).s("tier", tier(done))
                .m("loyaltyPercent", loyaltyPercent(u.id)).n("rentals", total).n("activeRentals", active)
                .m("spent", spent).toString();
    }

    private String couponJson(Coupon k) {
        return new Json().s("code", k.code).s("type", k.type).m("value", k.value).m("minAmount", k.minAmount)
                .s("expires", k.expires).n("maxUses", k.maxUses).n("used", k.used).n("active", k.active)
                .s("status", k.status(LocalDate.now())).s("label", k.label()).toString();
    }

    private String billJson(Bill b, boolean fin) {
        return new Json().n("days", b.days).n("lateDays", b.lateDays).m("base", b.base)
                .m("loyaltyPercent", b.loyaltyPercent).m("loyalty", b.loyalty).m("coupon", b.coupon)
                .m("lateFee", b.lateFee).m("total", b.total).n("final", fin).toString();
    }

    private String rentalJson(Rental r) {
        Equipment item = items.get(r.itemId);
        Customer cust = customers.get(r.customerId);
        LocalDate today = LocalDate.now();
        boolean overdue = r.returned == null && today.isAfter(r.due);
        Bill b = r.bill != null ? r.bill : bill(item, cust, r.start, r.due, overdue ? today : r.due, coupons.get(r.coupon));
        String status = r.returned != null ? "Returned" : overdue ? "Overdue" : "Active";
        return new Json().n("id", r.id).n("itemId", r.itemId).s("equipment", item.name)
                .n("customerId", r.customerId).s("customer", cust.name).s("phone", cust.phone)
                .s("start", r.start).s("due", r.due).s("returned", r.returned)
                .s("coupon", r.coupon.isEmpty() ? null : r.coupon).s("status", status)
                .raw("bill", billJson(b, r.bill != null)).toString();
    }

    private String itemsJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Equipment e : items.values()) j.add(itemJson(e));
        return j.toString();
    }

    private String customersJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Customer u : customers.values()) j.add(customerJson(u));
        return j.toString();
    }

    private String couponsJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Coupon k : coupons.values()) j.add(couponJson(k));
        return j.toString();
    }

    private String rentalsJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Rental r : rentals.values()) j.add(rentalJson(r));
        return j.toString();
    }

    private String quoteJson(int itemId, int customerId, LocalDate start, LocalDate due, String couponCode) {
        Equipment item = requireItem(itemId);
        Customer cust = requireCustomer(customerId);
        checkDates(start, due);
        Coupon k = findCoupon(couponCode, item, start, due);
        return billJson(bill(item, cust, start, due, due, k), false);
    }

    private String statsJson() {
        int available = 0, rented = 0, maintenance = 0, active = 0, overdue = 0, returned = 0;
        BigDecimal revenue = BigDecimal.ZERO, discounts = BigDecimal.ZERO, lateFees = BigDecimal.ZERO;
        LocalDate today = LocalDate.now();
        for (Equipment e : items.values()) {
            if (e.maintenance) maintenance++;
            else if (e.rented) rented++;
            else available++;
        }
        for (Rental r : rentals.values()) {
            if (r.returned == null) {
                active++;
                if (today.isAfter(r.due)) overdue++;
            } else {
                returned++;
                revenue = revenue.add(r.bill.total);
                discounts = discounts.add(r.bill.loyalty).add(r.bill.coupon);
                lateFees = lateFees.add(r.bill.lateFee);
            }
        }
        return new Json().n("items", items.size()).n("available", available).n("rented", rented)
                .n("maintenance", maintenance).n("customers", customers.size()).n("activeRentals", active)
                .n("overdue", overdue).n("returned", returned).m("revenue", revenue)
                .m("discounts", discounts).m("lateFees", lateFees).toString();
    }

    // ---------------- Domain operations ----------------

    private Equipment requireItem(int id) {
        Equipment e = items.get(id);
        if (e == null) throw new IllegalArgumentException("Equipment ID does not exist.");
        return e;
    }

    private Customer requireCustomer(int id) {
        Customer c = customers.get(id);
        if (c == null) throw new IllegalArgumentException("Customer ID does not exist.");
        return c;
    }

    private static void checkRate(String name, String category, BigDecimal rate) {
        if (name == null || name.isBlank() || category == null || category.isBlank())
            throw new IllegalArgumentException("Name and category are required.");
        if (rate == null || rate.signum() <= 0)
            throw new IllegalArgumentException("Daily rate must be positive.");
    }

    Equipment add(String name, String category, BigDecimal rate) {
        checkRate(name, category, rate);
        Equipment e = new Equipment(nextItem++, name.trim(), category.trim(), rate.setScale(2, RoundingMode.HALF_UP));
        items.put(e.id, e);
        save();
        return e;
    }

    Equipment updateItem(int id, String name, String category, BigDecimal rate) {
        Equipment old = requireItem(id);
        checkRate(name, category, rate);
        Equipment e = new Equipment(id, name.trim(), category.trim(), rate.setScale(2, RoundingMode.HALF_UP));
        e.maintenance = old.maintenance;
        e.rented = old.rented;
        items.put(id, e);
        save();
        return e;
    }

    Equipment setMaintenance(int id, boolean on) {
        Equipment e = requireItem(id);
        if (on && e.rented) throw new IllegalStateException("Equipment is rented out; return it before maintenance.");
        e.maintenance = on;
        save();
        return e;
    }

    void deleteItem(int id) {
        Equipment e = requireItem(id);
        if (e.rented) throw new IllegalStateException("Equipment is currently rented.");
        for (Rental r : rentals.values())
            if (r.itemId == id)
                throw new IllegalStateException("Equipment has rental history; put it under maintenance instead of deleting.");
        items.remove(id);
        save();
    }

    private static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private void checkCustomer(Integer selfId, String name, String phone, String email) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Customer name is required.");
        int n = digits(phone).length();
        if (n < 7 || n > 15) throw new IllegalArgumentException("Enter a valid phone number (7 to 15 digits).");
        if (email != null && !email.isBlank() && !EMAIL.matcher(email.trim()).matches())
            throw new IllegalArgumentException("Enter a valid email address.");
        for (Customer c : customers.values()) {
            if (selfId != null && c.id == selfId) continue;
            if (digits(c.phone).equals(digits(phone)))
                throw new IllegalArgumentException("A customer with this phone number already exists (ID " + c.id + ").");
        }
    }

    Customer addCustomer(String name, String phone, String email, String address) {
        checkCustomer(null, name, phone, email);
        Customer c = new Customer(nextCustomer++, name.trim(), phone.trim(), email.trim(), address.trim(), LocalDate.now());
        customers.put(c.id, c);
        save();
        return c;
    }

    Customer updateCustomer(int id, String name, String phone, String email, String address) {
        Customer old = requireCustomer(id);
        checkCustomer(id, name, phone, email);
        Customer c = new Customer(id, name.trim(), phone.trim(), email.trim(), address.trim(), old.joined);
        customers.put(id, c);
        save();
        return c;
    }

    Coupon addCoupon(String code, String type, String value, String min, String expires, String maxUses) {
        String key = code == null ? "" : code.trim().toUpperCase();
        if (!CODE.matcher(key).matches())
            throw new IllegalArgumentException("Coupon code must be 3-20 characters: letters, digits, - or _.");
        if (coupons.containsKey(key)) throw new IllegalArgumentException("Coupon " + key + " already exists.");
        if (!type.equals("PERCENT") && !type.equals("FLAT"))
            throw new IllegalArgumentException("Coupon type must be PERCENT or FLAT.");
        BigDecimal v = new BigDecimal(value), m = new BigDecimal(min.isBlank() ? "0" : min);
        if (v.signum() <= 0) throw new IllegalArgumentException("Coupon value must be greater than zero.");
        if (type.equals("PERCENT") && v.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException("A percentage coupon cannot exceed 100%.");
        if (m.signum() < 0) throw new IllegalArgumentException("Minimum amount cannot be negative.");
        int uses = maxUses.isBlank() ? 0 : Integer.parseInt(maxUses);
        if (uses < 0) throw new IllegalArgumentException("Max uses cannot be negative (0 = unlimited).");
        Coupon k = new Coupon(key, type, v.setScale(2, RoundingMode.HALF_UP), m.setScale(2, RoundingMode.HALF_UP),
                expires.isBlank() ? null : LocalDate.parse(expires), uses);
        coupons.put(key, k);
        save();
        return k;
    }

    Coupon toggleCoupon(String code) {
        Coupon k = coupons.get(code.trim().toUpperCase());
        if (k == null) throw new IllegalArgumentException("Coupon does not exist.");
        k.active = !k.active;
        save();
        return k;
    }

    void deleteCoupon(String code) {
        Coupon k = coupons.get(code.trim().toUpperCase());
        if (k == null) throw new IllegalArgumentException("Coupon does not exist.");
        if (k.used > 0) throw new IllegalStateException("Coupon has been used; disable it instead of deleting.");
        coupons.remove(k.code);
        save();
    }

    private static void checkDates(LocalDate start, LocalDate due) {
        if (start == null || due == null || !due.isAfter(start))
            throw new IllegalArgumentException("Due date must be after start date.");
    }

    /** Returns null for a blank code; throws with a friendly reason when the code can't be used. */
    private Coupon findCoupon(String code, Equipment item, LocalDate start, LocalDate due) {
        if (code == null || code.isBlank()) return null;
        String key = code.trim().toUpperCase();
        Coupon k = coupons.get(key);
        if (k == null) throw new IllegalArgumentException("Coupon " + key + " does not exist.");
        if (!k.active) throw new IllegalArgumentException("Coupon " + key + " is disabled.");
        if (k.expires != null && start.isAfter(k.expires))
            throw new IllegalArgumentException("Coupon " + key + " expired on " + k.expires + ".");
        if (k.maxUses > 0 && k.used >= k.maxUses)
            throw new IllegalArgumentException("Coupon " + key + " has been fully redeemed.");
        BigDecimal planned = item.rate.multiply(BigDecimal.valueOf(Math.max(1, ChronoUnit.DAYS.between(start, due))));
        if (planned.compareTo(k.minAmount) < 0)
            throw new IllegalArgumentException("Coupon " + key + " needs a rental of at least " + money(k.minAmount) + ".");
        return k;
    }

    private int completed(int customerId) {
        int n = 0;
        for (Rental r : rentals.values()) if (r.customerId == customerId && r.returned != null) n++;
        return n;
    }

    private static String tier(int completed) {
        return completed >= 6 ? "Gold" : completed >= 3 ? "Silver" : "Standard";
    }

    private BigDecimal loyaltyPercent(int customerId) {
        int n = completed(customerId);
        return BigDecimal.valueOf(n >= 6 ? 10 : n >= 3 ? 5 : 0);
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Billing rules: days x rate, minus loyalty % and coupon (both on the base), plus a late fee. */
    private Bill bill(Equipment item, Customer cust, LocalDate start, LocalDate due, LocalDate end, Coupon k) {
        Bill b = new Bill();
        b.days = (int) Math.max(1, ChronoUnit.DAYS.between(start, end));
        b.lateDays = (int) Math.max(0, ChronoUnit.DAYS.between(due, end));
        b.base = item.rate.multiply(BigDecimal.valueOf(b.days)).setScale(2, RoundingMode.HALF_UP);
        b.lateFee = item.rate.multiply(LATE_FEE_RATE).multiply(BigDecimal.valueOf(b.lateDays)).setScale(2, RoundingMode.HALF_UP);
        b.loyaltyPercent = loyaltyPercent(cust.id);
        b.loyalty = pct(b.base, b.loyaltyPercent);
        b.coupon = k == null ? BigDecimal.ZERO.setScale(2) : k.discountFor(b.base);
        if (b.loyalty.add(b.coupon).compareTo(b.base) > 0) b.coupon = b.base.subtract(b.loyalty);
        b.total = b.base.subtract(b.loyalty).subtract(b.coupon).add(b.lateFee).setScale(2, RoundingMode.HALF_UP);
        return b;
    }

    Rental rent(int itemId, int customerId, LocalDate start, LocalDate due, String couponCode) {
        Equipment item = requireItem(itemId);
        requireCustomer(customerId);
        if (item.maintenance) throw new IllegalStateException("Equipment is under maintenance.");
        if (item.rented) throw new IllegalStateException("Equipment is already rented.");
        checkDates(start, due);
        Coupon k = findCoupon(couponCode, item, start, due);

        item.rented = true;
        if (k != null) k.used++;
        Rental rental = new Rental(nextRental++, itemId, customerId, start, due, k == null ? "" : k.code);
        rentals.put(rental.id, rental);
        save();
        return rental;
    }

    Rental extend(int rentalId, LocalDate newDue) {
        Rental r = rentals.get(rentalId);
        if (r == null) throw new IllegalArgumentException("Rental ID does not exist.");
        if (r.returned != null) throw new IllegalStateException("Rental is already returned.");
        if (newDue == null || !newDue.isAfter(r.due))
            throw new IllegalArgumentException("New due date must be after the current due date (" + r.due + ").");
        r.due = newDue;
        save();
        return r;
    }

    /** What the bill would be if the rental were returned on the given date. */
    private Bill quoteReturn(int rentalId, LocalDate on) {
        Rental r = rentals.get(rentalId);
        if (r == null) throw new IllegalArgumentException("Rental ID does not exist.");
        if (r.returned != null) throw new IllegalStateException("Rental is already returned.");
        if (on == null || on.isBefore(r.start)) throw new IllegalArgumentException("Return date cannot be before the start date.");
        return bill(items.get(r.itemId), customers.get(r.customerId), r.start, r.due, on, coupons.get(r.coupon));
    }

    Rental returnRental(int rentalId, LocalDate returned) {
        Rental rental = rentals.get(rentalId);
        if (rental == null) throw new IllegalArgumentException("Rental ID does not exist.");
        if (rental.returned != null) throw new IllegalStateException("Rental is already returned.");
        if (returned == null || returned.isBefore(rental.start))
            throw new IllegalArgumentException("Return date is invalid.");

        Equipment item = items.get(rental.itemId);
        rental.bill = bill(item, customers.get(rental.customerId), rental.start, rental.due, returned,
                coupons.get(rental.coupon));
        rental.returned = returned;
        item.rented = false;
        save();
        return rental;
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------- Terminal menu ----------------

    private void run() {
        System.out.println("\n=== RentEase | Equipment Rentals ===");
        while (true) {
            System.out.println("\n1. Equipment    2. Add equipment   3. Rent        4. Return");
            System.out.println("5. Rentals      6. Customers       7. Add customer 8. Coupons   0. Exit");
            try {
                switch (ask("Choose an option: ")) {
                    case "1" -> showItems();
                    case "2" -> addFromPrompt();
                    case "3" -> rentFromPrompt();
                    case "4" -> returnFromPrompt();
                    case "5" -> showRentals();
                    case "6" -> showCustomers();
                    case "7" -> System.out.println("Added customer ID " + addCustomerFromPrompt().id + ".");
                    case "8" -> showCoupons();
                    case "0" -> {
                        System.out.println("Thanks for using RentEase.");
                        return;
                    }
                    default -> System.out.println("Please enter a number from 0 to 8.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private void showItems() {
        System.out.println("\nEQUIPMENT");
        if (items.isEmpty()) {
            System.out.println("No equipment available.");
            return;
        }
        System.out.printf("%-4s %-24s %-14s %-12s %s%n", "ID", "Name", "Category", "Daily rate", "Status");
        for (Equipment e : items.values()) {
            System.out.printf("%-4d %-24s %-14s %-12s %s%n",
                    e.id, cut(e.name, 24), cut(e.category, 14), money(e.rate), e.status());
        }
    }

    private void showCustomers() {
        System.out.println("\nCUSTOMERS");
        if (customers.isEmpty()) {
            System.out.println("No customers yet.");
            return;
        }
        System.out.printf("%-4s %-22s %-16s %-26s %s%n", "ID", "Name", "Phone", "Email", "Tier");
        for (Customer c : customers.values()) {
            System.out.printf("%-4d %-22s %-16s %-26s %s%n", c.id, cut(c.name, 22), c.phone,
                    cut(c.email, 26), tier(completed(c.id)));
        }
    }

    private void showCoupons() {
        System.out.println("\nCOUPONS");
        if (coupons.isEmpty()) {
            System.out.println("No coupons yet.");
            return;
        }
        System.out.printf("%-14s %-12s %-12s %-12s %s%n", "Code", "Discount", "Min rental", "Used", "Status");
        for (Coupon k : coupons.values()) {
            System.out.printf("%-14s %-12s %-12s %-12s %s%n", k.code, k.label(), money(k.minAmount),
                    k.used + (k.maxUses > 0 ? "/" + k.maxUses : ""), k.status(LocalDate.now()));
        }
    }

    private void showRentals() {
        System.out.println("\nRENTALS");
        if (rentals.isEmpty()) {
            System.out.println("No rentals created yet.");
            return;
        }
        System.out.printf("%-4s %-18s %-18s %-12s %-12s %s%n", "ID", "Customer", "Equipment", "Start", "Due", "Status");
        for (Rental r : rentals.values()) {
            String status = r.returned == null ? "Active" : "Returned " + r.returned + " (" + money(r.bill.total) + ")";
            System.out.printf("%-4d %-18s %-18s %-12s %-12s %s%n", r.id,
                    cut(customers.get(r.customerId).name, 18), cut(items.get(r.itemId).name, 18),
                    r.start, r.due, status);
        }
    }

    private void addFromPrompt() {
        Equipment e = add(required("Equipment name: "), required("Category: "), positiveMoney("Daily rate: "));
        System.out.println("Added " + e.name + " with ID " + e.id + ".");
    }

    private Customer addCustomerFromPrompt() {
        return addCustomer(required("Customer name: "), required("Phone: "), ask("Email (optional): "),
                ask("Address (optional): "));
    }

    private void rentFromPrompt() {
        if (items.values().stream().noneMatch(Equipment::isAvailable)) {
            System.out.println("Nothing is currently available.");
            return;
        }
        showItems();

        Equipment item;
        while (true) {
            item = items.get(positiveInt("Equipment ID: "));
            if (item == null) System.out.println("No equipment has that ID.");
            else if (!item.isAvailable()) System.out.println("That item is " + item.status().toLowerCase() + ".");
            else break;
        }

        showCustomers();
        Customer customer;
        while (true) {
            int id = nonNegativeInt("Customer ID (0 = new customer): ");
            if (id == 0) {
                try {
                    customer = addCustomerFromPrompt();
                    break;
                } catch (IllegalArgumentException e) {
                    System.out.println(e.getMessage());
                }
            } else if ((customer = customers.get(id)) == null) {
                System.out.println("No customer has that ID.");
            } else {
                break;
            }
        }

        LocalDate start = date("Start date (YYYY-MM-DD): ");
        LocalDate due;
        do {
            due = date("Due date (YYYY-MM-DD): ");
            if (!due.isAfter(start)) System.out.println("Due date must be after the start date.");
        } while (!due.isAfter(start));

        while (true) {
            String code = ask("Coupon code (blank for none): ");
            try {
                Rental rental = rent(item.id, customer.id, start, due, code);
                System.out.println("Rental #" + rental.id + " created; due " + due + ".");
                return;
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private void returnFromPrompt() {
        if (rentals.values().stream().noneMatch(r -> r.returned == null)) {
            System.out.println("There are no active rentals.");
            return;
        }
        showRentals();

        Rental rental;
        while (true) {
            rental = rentals.get(positiveInt("Rental ID to return: "));
            if (rental == null) System.out.println("No rental has that ID.");
            else if (rental.returned != null) System.out.println("That rental was already returned.");
            else break;
        }

        LocalDate returned;
        do {
            returned = date("Return date (YYYY-MM-DD): ");
            if (returned.isBefore(rental.start)) System.out.println("Return date cannot be before the start date.");
        } while (returned.isBefore(rental.start));

        Bill b = returnRental(rental.id, returned).bill;
        System.out.println("Returned successfully.");
        System.out.println("  Rental (" + b.days + " day(s)): " + money(b.base));
        if (b.loyalty.signum() > 0) System.out.println("  Loyalty " + b.loyaltyPercent + "%: -" + money(b.loyalty));
        if (b.coupon.signum() > 0) System.out.println("  Coupon " + rental.coupon + ": -" + money(b.coupon));
        if (b.lateFee.signum() > 0) System.out.println("  Late fee (" + b.lateDays + " day(s)): " + money(b.lateFee));
        System.out.println("  Total: " + money(b.total));
    }

    private String ask(String prompt) {
        System.out.print(prompt);
        return in.nextLine().trim();
    }

    private String required(String prompt) {
        String value;
        do {
            value = ask(prompt);
            if (value.isBlank()) System.out.println("This field cannot be blank.");
        } while (value.isBlank());
        return value;
    }

    private int nonNegativeInt(String prompt) {
        while (true) {
            try {
                int value = Integer.parseInt(ask(prompt));
                if (value >= 0) return value;
                System.out.println("Enter a whole number, 0 or more.");
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid whole number.");
            }
        }
    }

    private int positiveInt(String prompt) {
        while (true) {
            int value = nonNegativeInt(prompt);
            if (value > 0) return value;
            System.out.println("Enter a positive whole number.");
        }
    }

    private BigDecimal positiveMoney(String prompt) {
        while (true) {
            try {
                BigDecimal value = new BigDecimal(ask(prompt));
                if (value.signum() > 0) return value;
                System.out.println("Enter an amount greater than zero.");
            } catch (NumberFormatException e) {
                System.out.println("Enter an amount such as 12.50.");
            }
        }
    }

    private LocalDate date(String prompt) {
        while (true) {
            try {
                return LocalDate.parse(ask(prompt));
            } catch (DateTimeParseException e) {
                System.out.println("Use YYYY-MM-DD, for example 2026-10-22.");
            }
        }
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    // ---------------- Self test ----------------

    private void selfTest() {
        Equipment drill = add("Drill", "Tools", new BigDecimal("12.50"));
        Customer ada = addCustomer("Ada", "555-010-1234", "ada@example.com", "1 Main St");
        Customer ben = addCustomer("Ben", "555-010-9999", "", "");
        LocalDate jan10 = LocalDate.of(2026, 1, 10);

        // basic rent / return
        Rental first = rent(drill.id, ada.id, jan10, LocalDate.of(2026, 1, 12), "");
        expect(IllegalStateException.class, () -> rent(drill.id, ben.id, jan10, LocalDate.of(2026, 1, 11), ""));
        check(returnRental(first.id, LocalDate.of(2026, 1, 12)).bill.total, "25.00", "basic total");
        expect(IllegalStateException.class, () -> returnRental(first.id, LocalDate.of(2026, 1, 14)));

        // percent coupon
        addCoupon("ten", "PERCENT", "10", "0", "", "1");
        Rental second = rent(drill.id, ada.id, LocalDate.of(2026, 1, 13), LocalDate.of(2026, 1, 15), "TEN");
        check(returnRental(second.id, LocalDate.of(2026, 1, 15)).bill.total, "22.50", "percent coupon");
        expect(IllegalStateException.class, () -> deleteCoupon("TEN")); // used, must disable instead
        expect(IllegalArgumentException.class, () ->
                rent(drill.id, ben.id, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2), "TEN")); // max uses = 1

        // minimum, expiry
        addCoupon("FLAT20", "FLAT", "20", "100", "", "0");
        expect(IllegalArgumentException.class, () ->
                rent(drill.id, ben.id, jan10, LocalDate.of(2026, 1, 12), "FLAT20")); // 25 < 100 minimum
        addCoupon("OLD", "FLAT", "5", "0", "2026-01-01", "0");
        expect(IllegalArgumentException.class, () ->
                rent(drill.id, ben.id, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2), "OLD"));

        // late fee: 3 days x 12.50 = 37.50 + 2 late days x 6.25 = 12.50
        Rental third = rent(drill.id, ada.id, LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 21), "");
        check(returnRental(third.id, LocalDate.of(2026, 1, 23)).bill.total, "50.00", "late fee");

        // loyalty: Ada now has 3 returns -> Silver (5%): 12.50 - 0.63 = 11.87
        check(BigDecimal.valueOf(5), loyaltyPercent(ada.id).toPlainString(), "silver tier");
        Rental fourth = rent(drill.id, ada.id, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), "");
        expect(IllegalArgumentException.class, () -> extend(fourth.id, LocalDate.of(2026, 3, 2)));
        extend(fourth.id, LocalDate.of(2026, 3, 3));
        check(returnRental(fourth.id, LocalDate.of(2026, 3, 2)).bill.total, "11.87", "loyalty discount");

        // maintenance and validation
        setMaintenance(drill.id, true);
        expect(IllegalStateException.class, () -> rent(drill.id, ben.id, jan10, LocalDate.of(2026, 1, 11), ""));
        expect(IllegalArgumentException.class, () -> rent(drill.id, ben.id, jan10, jan10, ""));
        expect(IllegalArgumentException.class, () -> addCustomer("Eve", "555 010 1234", "", "")); // duplicate phone
        expect(IllegalArgumentException.class, () -> addCustomer("Eve", "12", "", ""));
        expect(IllegalArgumentException.class, () -> addCustomer("Eve", "5550109876", "not-an-email", ""));

        System.out.println("All checks passed.");
    }

    private static void check(Object actual, String expected, String what) {
        if (new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(expected)) != 0)
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            if (type.isInstance(e)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + e);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    // ---------------- Model ----------------

    static class Equipment {
        final int id;
        final String name, category;
        final BigDecimal rate;
        boolean maintenance, rented;

        Equipment(int id, String name, String category, BigDecimal rate) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.rate = rate;
        }

        boolean isAvailable() { return !maintenance && !rented; }

        String status() { return maintenance ? "Maintenance" : rented ? "Rented" : "Available"; }
    }

    static class Customer {
        final int id;
        final String name, phone, email, address;
        final LocalDate joined;

        Customer(int id, String name, String phone, String email, String address, LocalDate joined) {
            this.id = id;
            this.name = name;
            this.phone = phone;
            this.email = email;
            this.address = address;
            this.joined = joined;
        }
    }

    static class Coupon {
        final String code, type; // type: PERCENT or FLAT
        final BigDecimal value, minAmount;
        final LocalDate expires; // null = never
        final int maxUses; // 0 = unlimited
        int used;
        boolean active = true;

        Coupon(String code, String type, BigDecimal value, BigDecimal minAmount, LocalDate expires, int maxUses) {
            this.code = code;
            this.type = type;
            this.value = value;
            this.minAmount = minAmount;
            this.expires = expires;
            this.maxUses = maxUses;
        }

        BigDecimal discountFor(BigDecimal amount) {
            BigDecimal d = type.equals("PERCENT") ? pct(amount, value) : value;
            return d.min(amount).setScale(2, RoundingMode.HALF_UP);
        }

        String label() {
            return type.equals("PERCENT") ? value.stripTrailingZeros().toPlainString() + "% off" : "$" + value + " off";
        }

        String status(LocalDate today) {
            if (!active) return "Disabled";
            if (expires != null && today.isAfter(expires)) return "Expired";
            if (maxUses > 0 && used >= maxUses) return "Fully redeemed";
            return "Active";
        }
    }

    static class Bill {
        int days, lateDays;
        BigDecimal base, loyaltyPercent, loyalty, coupon, lateFee, total;
    }

    static class Rental {
        final int id, itemId, customerId;
        final LocalDate start;
        final String coupon; // "" = none
        LocalDate due, returned;
        Bill bill; // set when returned

        Rental(int id, int itemId, int customerId, LocalDate start, LocalDate due, String coupon) {
            this.id = id;
            this.itemId = itemId;
            this.customerId = customerId;
            this.start = start;
            this.due = due;
            this.coupon = coupon;
        }
    }
}
