import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Shared validation keeps UI input and direct service calls consistent. */
public final class Input {
    public static final long MAX_PRICE_CENTS = 1_000_000;
    public static final int MAX_QUANTITY = 999;
    public static final List<String> CATEGORIES = List.of(
            "Meat", "Vegetarian", "Soup", "Main course", "Dessert", "Beverage", "Other");

    private Input() { }

    public static String text(String value, String label, int maximum) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty()) throw new AppException(label + " is required.");
        if (result.length() > maximum || result.chars().anyMatch(Character::isISOControl)) {
            throw new AppException(label + " must contain at most " + maximum + " printable characters.");
        }
        return result;
    }

    public static String identifier(String value, String label) {
        String result = text(value, label, 32);
        if (!result.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) {
            throw new AppException(label + " may contain letters, numbers, hyphens and underscores.");
        }
        return result;
    }

    public static void price(long cents) {
        if (cents <= 0 || cents > MAX_PRICE_CENTS) {
            throw new AppException("Price must be between $0.01 and $10,000.00.");
        }
    }

    public static long parsePrice(String value) {
        try {
            long cents = new BigDecimal(value.strip()).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
            price(cents);
            return cents;
        } catch (ArithmeticException | NumberFormatException | NullPointerException e) {
            throw new AppException("Enter a valid price with at most two decimal places.");
        }
    }

    public static int quantity(int value) {
        if (value < 1 || value > MAX_QUANTITY) {
            throw new AppException("Item quantity must be between 1 and " + MAX_QUANTITY + ".");
        }
        return value;
    }

    public static int guests(String value) {
        try {
            int count = Integer.parseInt(value.strip());
            if (count < 1 || count > 1000) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException | NullPointerException e) {
            throw new AppException("Guest count must be a whole number between 1 and 1,000.");
        }
    }

    public static String money(long cents) {
        return String.format(Locale.US, "$%,.2f", BigDecimal.valueOf(cents, 2));
    }
}
