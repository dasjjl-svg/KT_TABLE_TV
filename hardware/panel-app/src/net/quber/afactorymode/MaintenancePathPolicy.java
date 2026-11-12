package net.quber.afactorymode;

final class MaintenancePathPolicy {
    private MaintenancePathPolicy() {}

    static String basename(String name) {
        if (name == null || name.length() > 64 || !name.matches("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*")) {
            throw new IllegalArgumentException("root_filename");
        }
        return name;
    }

    static String temporary(String name) { return basename(basename(name) + ".next"); }
}
