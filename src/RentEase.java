import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class RentEase {
    private final Scanner in = new Scanner(System.in);
    private final Map<Integer, Equipment> items = new LinkedHashMap<>();
    private final Map<Integer, Rental> rentals = new LinkedHashMap<>();
    private int nextItem = 1, nextRental = 1;

    public static void main(String[] args) {
        RentEase app = new RentEase();
        if (args.length > 0 && args[0].equals("--self-test")) {
            app.selfTest();
        } else if (args.length > 0 && args[0].equals("--api")) {
            app.seed();
            app.serve();
        } else {
            app.seed();
            app.run();
        }
    }

    private void seed() {
        add("Cordless Drill", "Tools", new BigDecimal("12.50"));
        add("Pressure Washer", "Garden", new BigDecimal("30.00"));
        add("Projector", "Events", new BigDecimal("45.00"));
    }

    private void run() {
        System.out.println("\n=== RentEase | Equipment Rentals ===");
        while (true) {
            System.out.println("\n1. View equipment  2. Add equipment  3. Rent");
            System.out.println("4. Return          5. View rentals  0. Exit");

            switch (ask("Choose an option: ")) {
                case "1" -> showItems();
                case "2" -> addFromPrompt();
                case "3" -> rentFromPrompt();
                case "4" -> returnFromPrompt();
                case "5" -> showRentals();
                case "0" -> {
                    System.out.println("Thanks for using RentEase.");
                    return;
                }
                default -> System.out.println("Please enter a number from 0 to 5.");
            }
        }
    }

    // ---------- API mode (used by the Streamlit UI) ----------
    // Protocol: one request per line, fields separated by TAB.
    //   LIST_ITEMS | LIST_RENTALS | ADD name category rate
    //   RENT itemId customer start due | RETURN rentalId returnDate
    // Each request gets exactly one JSON line back: {"ok":true,"data":...} or {"ok":false,"error":"..."}
    private void serve() {
        System.out.println("{\"ok\":true,\"data\":\"ready\"}");
        System.out.flush();
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            String out;
            try {
                switch (p[0]) {
                    case "LIST_ITEMS" -> out = ok(itemsJson());
                    case "LIST_RENTALS" -> out = ok(rentalsJson());
                    case "ADD" -> {
                        Equipment e = add(p[1], p[2], new BigDecimal(p[3]));
                        out = ok(itemJson(e));
                    }
                    case "RENT" -> {
                        Rental r = rent(Integer.parseInt(p[1]), p[2],
                                LocalDate.parse(p[3]), LocalDate.parse(p[4]));
                        out = ok(rentalJson(r));
                    }
                    case "RETURN" -> {
                        BigDecimal total = returnRental(Integer.parseInt(p[1]), LocalDate.parse(p[2]));
                        out = ok("{\"total\":" + total.toPlainString() + "}");
                    }
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

    private String itemJson(Equipment e) {
        return "{\"id\":" + e.id + ",\"name\":" + q(e.name) + ",\"category\":" + q(e.category)
                + ",\"rate\":" + e.rate.toPlainString() + ",\"available\":" + e.available + "}";
    }

    private String rentalJson(Rental r) {
        return "{\"id\":" + r.id + ",\"itemId\":" + r.itemId
                + ",\"equipment\":" + q(items.get(r.itemId).name)
                + ",\"customer\":" + q(r.customer) + ",\"start\":" + q(r.start.toString())
                + ",\"due\":" + q(r.due.toString())
                + ",\"returned\":" + (r.returned == null ? "null" : q(r.returned.toString())) + "}";
    }

    private String itemsJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Equipment e : items.values()) j.add(itemJson(e));
        return j.toString();
    }

    private String rentalsJson() {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Rental r : rentals.values()) j.add(rentalJson(r));
        return j.toString();
    }

    private void showItems() {
        System.out.println("\nEQUIPMENT");
        if (items.isEmpty()) {
            System.out.println("No equipment available.");
            return;
        }
        System.out.printf("%-4s %-24s %-14s %-12s %s%n",
                "ID", "Name", "Category", "Daily rate", "Status");
        for (Equipment e : items.values()) {
            System.out.printf("%-4d %-24s %-14s %-12s %s%n",
                    e.id, cut(e.name, 24), cut(e.category, 14), money(e.rate),
                    e.available ? "Available" : "Rented");
        }
    }

    private void addFromPrompt() {
        Equipment e = add(required("Equipment name: "), required("Category: "),
                positiveMoney("Daily rate: "));
        System.out.println("Added " + e.name + " with ID " + e.id + ".");
    }

    private void rentFromPrompt() {
        if (items.values().stream().noneMatch(e -> e.available)) {
            System.out.println("Nothing is currently available.");
            return;
        }
        showItems();

        Equipment item;
        while (true) {
            item = items.get(positiveInt("Equipment ID: "));
            if (item == null) System.out.println("No equipment has that ID.");
            else if (!item.available) System.out.println("That item is already rented.");
            else break;
        }

        String customer = required("Customer name: ");
        LocalDate start = date("Start date (YYYY-MM-DD): ");
        LocalDate due;
        do {
            due = date("Due date (YYYY-MM-DD): ");
            if (!due.isAfter(start)) System.out.println("Due date must be after the start date.");
        } while (!due.isAfter(start));

        Rental rental = rent(item.id, customer, start, due);
        System.out.println("Rental #" + rental.id + " created; due " + due + ".");
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
            if (returned.isBefore(rental.start)) {
                System.out.println("Return date cannot be before the start date.");
            }
        } while (returned.isBefore(rental.start));

        System.out.println("Returned successfully. Total: " + money(returnRental(rental.id, returned)));
    }

    private void showRentals() {
        System.out.println("\nRENTALS");
        if (rentals.isEmpty()) {
            System.out.println("No rentals created yet.");
            return;
        }
        System.out.printf("%-4s %-18s %-18s %-12s %-12s %s%n",
                "ID", "Customer", "Equipment", "Start", "Due", "Status");

        for (Rental r : rentals.values()) {
            String status = r.returned == null ? "Active" : "Returned " + r.returned;
            System.out.printf("%-4d %-18s %-18s %-12s %-12s %s%n",
                    r.id, cut(r.customer, 18), cut(items.get(r.itemId).name, 18),
                    r.start, r.due, status);
        }
    }

    Equipment add(String name, String category, BigDecimal rate) {
        if (name == null || name.isBlank() || category == null || category.isBlank())
            throw new IllegalArgumentException("Name and category are required.");
        if (rate == null || rate.signum() <= 0)
            throw new IllegalArgumentException("Daily rate must be positive.");

        Equipment e = new Equipment(nextItem++, name.trim(), category.trim(),
                rate.setScale(2, RoundingMode.HALF_UP));
        items.put(e.id, e);
        return e;
    }

    Rental rent(int itemId, String customer, LocalDate start, LocalDate due) {
        Equipment item = items.get(itemId);
        if (item == null) throw new IllegalArgumentException("Equipment ID does not exist.");
        if (!item.available) throw new IllegalStateException("Equipment is already rented.");
        if (customer == null || customer.isBlank()) throw new IllegalArgumentException("Customer is required.");
        if (start == null || due == null || !due.isAfter(start))
            throw new IllegalArgumentException("Due date must be after start date.");

        item.available = false;
        Rental rental = new Rental(nextRental++, itemId, customer.trim(), start, due);
        rentals.put(rental.id, rental);
        return rental;
    }

    BigDecimal returnRental(int rentalId, LocalDate returned) {
        Rental rental = rentals.get(rentalId);
        if (rental == null) throw new IllegalArgumentException("Rental ID does not exist.");
        if (rental.returned != null) throw new IllegalStateException("Rental is already returned.");
        if (returned == null || returned.isBefore(rental.start))
            throw new IllegalArgumentException("Return date is invalid.");

        rental.returned = returned;
        Equipment item = items.get(rental.itemId);
        item.available = true;

        long days = Math.max(1, ChronoUnit.DAYS.between(rental.start, returned));
        return item.rate.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
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

    private int positiveInt(String prompt) {
        while (true) {
            try {
                int value = Integer.parseInt(ask(prompt));
                if (value > 0) return value;
                System.out.println("Enter a positive whole number.");
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid whole number.");
            }
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

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private void selfTest() {
        Equipment drill = add("Drill", "Tools", new BigDecimal("12.50"));
        Rental first = rent(drill.id, "Ada",
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 12));

        expect(IllegalStateException.class, () ->
                rent(drill.id, "Ben", LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 11)));

        assert returnRental(first.id, LocalDate.of(2026, 1, 12))
                .compareTo(new BigDecimal("25.00")) == 0;

        Rental second = rent(drill.id, "Ben",
                LocalDate.of(2026, 1, 13), LocalDate.of(2026, 1, 14));

        assert returnRental(second.id, LocalDate.of(2026, 1, 13))
                .compareTo(new BigDecimal("12.50")) == 0;

        expect(IllegalArgumentException.class, () ->
                rent(drill.id, "Ada", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2)));

        expect(IllegalStateException.class, () ->
                returnRental(second.id, LocalDate.of(2026, 1, 14)));

        System.out.println("All edge-case checks passed.");
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (RuntimeException e) {
            assert type.isInstance(e);
        }
    }

    static class Equipment {
        final int id;
        final String name, category;
        final BigDecimal rate;
        boolean available = true;

        Equipment(int id, String name, String category, BigDecimal rate) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.rate = rate;
        }
    }

    static class Rental {
        final int id, itemId;
        final String customer;
        final LocalDate start, due;
        LocalDate returned;

        Rental(int id, int itemId, String customer, LocalDate start, LocalDate due) {
            this.id = id;
            this.itemId = itemId;
            this.customer = customer;
            this.start = start;
            this.due = due;
        }
    }
}