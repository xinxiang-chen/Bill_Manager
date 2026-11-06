/** Immutable values returned by the application service. Monetary values use cents. */
public final class Models {
    private Models() { }

    public record Product(String id, String name, String category, long priceCents,
                          boolean available, long sales) { }
    public record StaffMember(String id, String name) { }
    public record Order(String id, String time, String tableNumber, int guests,
                        String staffId, boolean settled, long totalCents) { }
    public record OrderLine(String productId, String name, long priceCents, int quantity) {
        public long totalCents() { return Math.multiplyExact(priceCents, quantity); }
    }
}
