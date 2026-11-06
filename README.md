# Bill Manager

A Java 17 Swing desktop application with an embedded SQLite database. All screens, messages, comments and demo records are in English. Prices are displayed in USD and stored as integer cents.

## Start

Use JDK 17 or newer. The required SQLite JDBC driver is included in `lib`.

On a fresh checkout, create the synthetic database once, then start the application:

```bash
./reset-demo.sh
./run.sh
```

For later launches, use `./run.sh` alone to keep your data.

Sign in with **admin / 000000**. The generated database contains 20 menu items and 6 sample orders (4 settled and 2 open). `RMS.db` is local runtime data and is excluded from Git.

The scripts work from any current directory. The application locates the database next to the project; `-Drms.database=/absolute/path/to/RMS.db` overrides that location. A missing or obsolete database produces an actionable error rather than silently creating an empty database.

## Use IntelliJ IDEA

Open this project, select JDK 17, and run `MainWindow.main()`. Source files create the Swing components directly; no GUI Designer plugin or `.form` compiler is needed. The module uses the SQLite JDBC library already present in `lib`.

## Workflows

- Search the menu by a case-insensitive part of an item name, ID or category. Search treats quotes, percent signs and underscores as literal input. Filter by category or availability, and sort by settled sales.
- Add menu items or edit their prices, categories and availability.
- Add items to a draft, change quantities, enter a table number and guest count, and place an order.
- Open an existing order to add more items, inspect its details, or settle it.
- Review settled revenue and historical order details. Order lines retain their original item names and prices when the menu changes.
- Add additional administrators with salted password hashes.

Order tables are read-only. Failed saves keep the draft open. Checkout is atomic and cannot count the same order twice. Sales counts are derived from settled order lines rather than a separately updated counter.

If a menu price changes while a draft is open, saving rejects the stale quote. Use Refresh to review the updated draft total before saving again.

## Build and test

```bash
./build.sh
./test.sh
java -jar out/Bill_Manager.jar
```

The executable JAR bundles the current SQLite driver, including Apple Silicon support. Regression tests use temporary databases and do not change `RMS.db`.

Run `./ui-test.sh` from a desktop session to exercise the actual Swing controls, including failed login, input validation, menu editing, adding items, checkout, and sorted table selection. These tests also use temporary data.

## Reset synthetic data

Close any running copy of the application, then run:

```bash
./reset-demo.sh
```

This command replaces the project database with a newly generated synthetic database and restores the demo administrator account. Normal startup never resets data.
