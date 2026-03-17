package aurora;

import aurora.compiler.Compiler;
import aurora.lsp.AuroraLanguageServer;
import aurora.parser.AuroraParser;
import aurora.parser.tree.Program;
import aurora.runtime.Chunk;
import aurora.runtime.VM;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * The main entry point for the Aurora CLI toolchain.
 * This class uses picocli to provide various commands for running, compiling,
 * and interacting with Aurora source code and object files.
 */
@CommandLine.Command(name = "aurora", mixinStandardHelpOptions = true, version = "0.1.0", description = "Aurora CLI toolchain", subcommands = {
        Main.RunCommand.class,
        Main.CompileCommand.class,
        aurora.tooling.AuroraBuild.class,
        aurora.tooling.AuroraDecompiler.class,
        aurora.tooling.AuroraBytecode.class,
        Main.LspCommand.class,
        CommandLine.HelpCommand.class
})
public final class Main implements Runnable {

    /**
     * Formats an {@link aurora.analyzer.AuroraDiagnostic} into a Rust-style error message
     * with ANSI colors, source location arrow, line snippet, and ^^^ pointer.
     */
    static String formatDiagnostic(aurora.analyzer.AuroraDiagnostic d, String sourceCode) {
        final String RESET = "\u001B[0m";
        final String RED   = "\u001B[31m";
        final String BOLD  = "\u001B[1m";
        final String CYAN  = "\u001B[36m";

        StringBuilder sb = new StringBuilder();

        // "error: <message>"
        sb.append(RED).append(BOLD).append("error").append(RESET)
                .append(BOLD).append("[type]: ").append(d.message()).append(RESET).append("\n");

        if (d.location() == null) return sb.toString();

        int line   = d.location().line();
        int col    = d.location().column();    // 1-indexed
        int endCol = d.location().endColumn(); // 1-indexed, inclusive

        // "  --> file:line:col"
        sb.append(CYAN).append("  --> ").append(RESET)
                .append(d.location().sourceName()).append(":").append(line).append(":").append(col).append("\n");

        if (sourceCode == null) return sb.toString();

        String[] lines = sourceCode.split("\r?\n", -1);
        if (line < 1 || line > lines.length) return sb.toString();

        String srcLine   = lines[line - 1];
        String lineNum   = String.valueOf(line);
        String padding   = " ".repeat(lineNum.length());

        sb.append(CYAN).append(padding).append(" |").append(RESET).append("\n");
        sb.append(CYAN).append(lineNum).append(" | ").append(RESET).append(srcLine).append("\n");
        sb.append(CYAN).append(padding).append(" | ").append(RESET);

        // スペースで col-1 文字分パディング（タブはそのまま保持）
        int startIdx = col - 1;
        for (int i = 0; i < startIdx && i < srcLine.length(); i++) {
            sb.append(srcLine.charAt(i) == '\t' ? '\t' : ' ');
        }

        // ^^^ の長さ: endCol - col + 1、最低1
        int caretLen = (endCol >= col) ? (endCol - col + 1) : 1;
        sb.append(RED).append(BOLD).append("^".repeat(caretLen)).append(RESET).append("\n");

        return sb.toString();
    }

    /**
     * The main entry point of the application.
     * Executes the command line interface with the provided arguments.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    /**
     * Default execution for the main command.
     * Displays the usage help message to the standard output.
     */
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * Serializes a {@link Chunk} of bytecode into a {@link DataOutputStream}.
     * This methods handles the conversion of constant pool entries (strings, numbers, functions, classes)
     * and the actual instruction stream into a binary format suitable for storage in .arobj files.
     *
     * @param out The stream to write the serialized chunk to.
     * @param chunk The bytecode chunk to be serialized.
     * @throws IOException If an I/O error occurs during serialization.
     */
    public static void writeChunk(DataOutputStream out, Chunk chunk) throws IOException {
        // 定数テーブル
        out.writeInt(chunk.constants.size());
        for (Object constant : chunk.constants) {
            if (constant == null) {
                out.writeByte(0); // None
            } else if (constant instanceof Boolean) {
                out.writeByte(1);
                out.writeBoolean((Boolean) constant);
            } else if (constant instanceof Integer) {
                out.writeByte(2);
                out.writeInt((Integer) constant);
            } else if (constant instanceof Long) {
                out.writeByte(3);
                out.writeLong((Long) constant);
            } else if (constant instanceof Float) {
                out.writeByte(4);
                out.writeFloat((Float) constant);
            } else if (constant instanceof Double) {
                out.writeByte(5);
                out.writeDouble((Double) constant);
            } else if (constant instanceof String) {
                out.writeByte(6);
                out.writeUTF((String) constant);
            } else if (constant instanceof aurora.compiler.CompiledFunction(String name, Chunk chunk1, int arity)) {
                out.writeByte(7);
                out.writeUTF(name);
                out.writeInt(arity);
                writeChunk(out, chunk1);
            } else if (constant instanceof aurora.compiler.CompiledClass cls) {
                out.writeByte(8);
                out.writeUTF(cls.name);
                out.writeBoolean(cls.initializer != null);
                if (cls.initializer != null) {
                    out.writeInt(cls.initializer.arity());
                    writeChunk(out, cls.initializer.chunk());
                }
                out.writeInt(cls.methods.size());
                for (var entry : cls.methods.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().arity());
                    writeChunk(out, entry.getValue().chunk());
                }
            } else {
                throw new IOException("Unknown constant type for serialization: " + constant.getClass());
            }
        }

        // コード
        out.writeInt(chunk.count);
        for (int i = 0; i < chunk.count; i++) {
            out.writeInt(chunk.code[i]);
            out.writeInt(chunk.lines[i]);
        }
    }

    /**
     * Command to run an Aurora source file or compiled object file.
     * It handles parsing, compilation (if necessary), and VM execution.
     */
    @CommandLine.Command(name = "run", description = "Run an Aurora source file")
    static class RunCommand implements Callable<Integer> {
        /** The path to the source file (.ar) or object file (.arobj, .arpkg). */
        @CommandLine.Parameters(index = "0", description = "The source file to run")
        private Path sourceFile;

        /** Whether to output the Abstract Syntax Tree (AST) instead of executing. */
        @CommandLine.Option(names = { "--outputAst" }, description = "Output the AST of the program")
        private boolean outputAst;

        /** Enables verbose output for error reporting and debugging. */
        @CommandLine.Option(names = { "-v", "--verbose" }, description = "Verbose output")
        private boolean verbose;

        /**
         * Executes the run command.
         *
         * @return Exit code (0 for success, 1 for failure).
         */
        @Override
        public Integer call() {
            try {
                if (sourceFile.getFileName().toString().endsWith(".arobj")) {
                    // バイナリを直接ロードして実行
                    try (java.io.DataInputStream dis = new java.io.DataInputStream(
                            new java.io.BufferedInputStream(new java.io.FileInputStream(sourceFile.toFile())))) {
                        loadAndRunChunk(dis);
                    }
                    return 0;
                } else if (sourceFile.getFileName().toString().endsWith(".arpkg")) {
                    // Extract main.arobj from zip and run
                    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            new java.io.FileInputStream(sourceFile.toFile()))) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.getName().equals("main.arobj")) {
                                java.io.DataInputStream dis = new java.io.DataInputStream(zis);
                                loadAndRunChunk(dis);
                                return 0;
                            }
                        }
                        System.err.println("[ERROR] main.arobj not found in " + sourceFile);
                        return 1;
                    }
                }

                String code = Files.readString(sourceFile, StandardCharsets.UTF_8);

                long parseStart = System.nanoTime();
                Program program = AuroraParser.parse(code, sourceFile.getFileName().toString());
                long parseEnd = System.nanoTime();

                if (outputAst) {
                    System.out.println(program);
                } else {
                    long compileStart = 0, compileEnd = 0, runStart = 0, runEnd = 0;
                    try {
                        compileStart = System.nanoTime();
                        Compiler compiler = new Compiler();
                        Chunk rawChunk = compiler.compile(program);
                        compileEnd = System.nanoTime();

                        VM vm = new VM();
                        Chunk loadedChunk = vm.inflateChunk(rawChunk);

                        runStart = System.nanoTime();
                        vm.run(loadedChunk);
                        runEnd = System.nanoTime();
                    } finally {
                        if (verbose && compileStart != 0) {
                            long parseNs   = parseEnd   - parseStart;
                            long compileNs = compileEnd - compileStart;
                            long runNs     = (runEnd > 0) ? runEnd - runStart : 0;
                            long totalNs   = parseNs + compileNs + runNs;
                            System.err.println("\n--- Timing ---");
                            System.err.printf("  parse:   %6.3f ms%n", parseNs   / 1_000_000.0);
                            System.err.printf("  compile: %6.3f ms%n", compileNs / 1_000_000.0);
                            System.err.printf("  run:     %6.3f ms%n", runNs     / 1_000_000.0);
                            System.err.printf("  total:   %6.3f ms%n", totalNs   / 1_000_000.0);
                        }
                    }
                }
                return 0;
            } catch (aurora.parser.SyntaxErrorException e) {
                System.err.println(e.getMessage());
                return 1;
            } catch (aurora.compiler.TypeErrorException e) {
                String src = null;
                try { src = Files.readString(sourceFile, StandardCharsets.UTF_8); } catch (IOException ignored) {}
                for (aurora.analyzer.AuroraDiagnostic d : e.getDiagnostics()) {
                    System.err.print(formatDiagnostic(d, src));
                }
                return 1;
            } catch (aurora.runtime.AuroraRuntimeException e) {
                System.err.println("Uncaught Aurora Exception: " + e.getMessage());
                if (verbose) {
                    System.err.println("\n--- Java Stack Trace ---");
                    e.printStackTrace(System.err);
                }
                return 1;
            } catch (IOException e) {
                System.err.println("[ERROR] Failed to read file: " + e.getMessage());
                if (verbose)
                    e.printStackTrace(System.err);
                return 1;
            } catch (Throwable e) {
                System.err.println("[ERROR] Unhandled error occurred: " + e.getMessage());
                if (verbose)
                    e.printStackTrace(System.err);
                return 1;
            }
        }

        /**
         * Loads a bytecode chunk from an input stream and executes it in the VM.
         *
         * @param dis The input stream containing the serialized chunk.
         * @throws IOException If an I/O error occurs or the file is invalid.
         */
        private void loadAndRunChunk(java.io.DataInputStream dis) throws IOException {
            int magic = dis.readInt();
            if (magic != 0x4155524F) {
                throw new IOException("Not a valid Aurora object file (Magic mismatch)");
            }
            int version = dis.readInt();
            // バージョンチェック (現在は固定)

            VM vm = new VM();
            Chunk loadedChunk = vm.loadChunk(dis);
            vm.run(loadedChunk);
        }
    }

    /**
     * Command to start the Aurora Language Server (LSP).
     * Provides IDE features like completion, diagnostics, and hovering.
     */
    @CommandLine.Command(name = "lsp", description = "Start the Aurora Language Server")
    static class LspCommand implements Callable<Integer> {
        /**
         * Executes the LSP command. Starts a language server listening on stdin/stdout.
         *
         * @return Exit code (0 for success, 1 for failure).
         */
        @Override
        public Integer call() {
            try {
                // Initialize server
                AuroraLanguageServer server = new aurora.lsp.AuroraLanguageServer();

                // Create LSP launcher
                // We use System.in and System.out for communication
                Launcher<LanguageClient> launcher = LSPLauncher
                        .createServerLauncher(server, System.in, System.out);

                // Connect the server to the client (proxy)
                server.connect(launcher.getRemoteProxy());

                // Start listening
                // This blocks until the client disconnects or the server is closed
                launcher.startListening().get();

                return 0;
            } catch (Throwable e) {
                // Log error to stderr so it doesn't interfere with LSP jsonrpc on stdout
                System.err.println("[ERROR] LSP Error: " + e.getMessage());
                e.printStackTrace(System.err);
                return 1;
            }
        }

    }

    /**
     * Command to compile an Aurora source file into a binary object file.
     */
    @CommandLine.Command(name = "compile", description = "Compile an Aurora source file")
    static class CompileCommand implements Callable<Integer> {
        /** The path to the source file (.ar) to be compiled. */
        @CommandLine.Parameters(index = "0", description = "The source file to compile")
        private Path sourceFile;

        /** The path where the compiled object file should be saved. */
        @CommandLine.Option(names = { "-o", "--output" }, description = "Output file")
        private Path outputFile;

        /** Enables verbose output for detailed error reporting. */
        @CommandLine.Option(names = { "-v", "--verbose" }, description = "Verbose output")
        private boolean verbose;

        /**
         * Executes the compilation command.
         *
         * @return Exit code (0 for success, 1 for failure).
         */
        @Override
        public Integer call() {
            try {
                String code = Files.readString(sourceFile, StandardCharsets.UTF_8);

                long parseStart = System.nanoTime();
                Program program = AuroraParser.parse(code, sourceFile.getFileName().toString());
                long parseEnd = System.nanoTime();

                long compileStart = System.nanoTime();
                Compiler compiler = new Compiler();
                Chunk chunk = compiler.compile(program);
                long compileEnd = System.nanoTime();

                Path outPath = outputFile != null ? outputFile
                        : sourceFile.resolveSibling(sourceFile.getFileName().toString().replace(".ar", ".arobj"));

                System.out.println("Compiling " + sourceFile + " to " + outPath + "...");

                try (DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(outPath.toFile())))) {
                    // マジックナンバー
                    out.writeInt(0x4155524F); // 'AURO'
                    // バージョン
                    out.writeInt(0x00000002);

                    Main.writeChunk(out, chunk);
                }

                if (verbose) {
                    long totalNs = (parseEnd - parseStart) + (compileEnd - compileStart);
                    System.err.println("\n--- Timing ---");
                    System.err.printf("  parse:   %6.3f ms%n", (parseEnd   - parseStart)   / 1_000_000.0);
                    System.err.printf("  compile: %6.3f ms%n", (compileEnd - compileStart) / 1_000_000.0);
                    System.err.printf("  total:   %6.3f ms%n", totalNs / 1_000_000.0);
                }

                return 0;
            } catch (aurora.parser.SyntaxErrorException e) {
                System.err.println(e.getMessage());
                return 1;
            } catch (aurora.compiler.TypeErrorException e) {
                String src = null;
                try { src = Files.readString(sourceFile, StandardCharsets.UTF_8); } catch (IOException ignored) {}
                for (aurora.analyzer.AuroraDiagnostic d : e.getDiagnostics()) {
                    System.err.print(formatDiagnostic(d, src));
                }
                return 1;
            } catch (IOException e) {
                System.err.println("[ERROR] Failed to read file: " + e.getMessage());
                if (verbose)
                    e.printStackTrace(System.err);
                return 1;
            } catch (Throwable e) {
                System.err.println("[ERROR] Compilation error: " + e.getMessage());
                if (verbose)
                    e.printStackTrace(System.err);
                return 1;
            }

        }
    }
}