package aurora.lsp;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple file logger for the Aurora LSP server.
 * Since the LSP communication happens over standard input/output, writing to {@code System.out}
 * would corrupt the JSON-RPC stream. This logger writes to a temporary file instead.
 * All debug output goes to: {@code java.io.tmpdir/aurora-lsp.log}
 */
public class LspLogger {
    /** The path to the log file. */
    private static final String LOG_FILE = System.getProperty("java.io.tmpdir") + "/aurora-lsp.log";

    /** Formatter for log timestamps. */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** The writer instance used for logging. */
    private static PrintWriter writer;

    static {
        try {
            writer = new PrintWriter(new FileWriter(LOG_FILE, false)); // overwrite on each server start
            log("=== Aurora LSP Server started ===");
            log("Log file: " + LOG_FILE);
        } catch (IOException e) {
            // Last resort — stderr won't corrupt stdio transport
            System.err.println("[aurora-lsp] Failed to open log file: " + e.getMessage());
        }
    }

    public static void log(String msg) {
        if (writer == null)
            return;
        writer.println("[" + LocalTime.now().format(FMT) + "] " + msg);
        writer.flush();
    }

    public static void log(String fmt, Object... args) {
        log(String.format(fmt, args));
    }

    public static void error(String msg, Throwable t) {
        log("[ERROR] " + msg);
        if (t != null && writer != null) {
            t.printStackTrace(writer);
            writer.flush();
        }
    }
}