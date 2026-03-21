package aurora.tooling;

import aurora.runtime.*;
import picocli.CommandLine;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CLI command for decompiling Aurora bytecode files ({@code .arobj} or {@code .arpkg}).
 * It attempts to reconstruct a readable representation of the original source
 * code from the compiled chunk.
 */
@CommandLine.Command(name = "decompile", description = "Decompile an .arobj or .arpkg file")
public class AuroraDecompiler implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The file to decompile")
    private Path inputFile;

    @CommandLine.Option(names = { "-o", "--output" }, description = "Output file (optional)")
    private Path outputFile;

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(inputFile)) {
            System.err.println("Input file not found: " + inputFile);
            return 1;
        }

        Chunk chunk = null;
        if (inputFile.toString().endsWith(".arpkg")) {
            // Extract from zip
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputFile.toFile()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().equals("main.arobj")) {
                        DataInputStream dis = new DataInputStream(zis);
                        // Skip magic
                        dis.readInt();
                        // Skip version
                        dis.readInt();
                        // Load Chunk
                        chunk = new VM().loadChunk(dis);
                        break;
                    }
                }
            }
        } else {
            try (DataInputStream dis = new DataInputStream(new FileInputStream(inputFile.toFile()))) {
                // Skip magic
                int magic = dis.readInt();
                if (magic != 0x4155524F) {
                    // Try without magic? No, assume valid arobj
                    System.err.println("Invalid magic number");
                    return 1;
                }
                // Skip version
                dis.readInt();

                chunk = new VM().loadChunk(dis);
            }
        }

        if (chunk == null) {
            System.err.println("Failed to load chunk from " + inputFile);
            return 1;
        }

        String decompiled = decompileChunk(chunk);

        if (outputFile != null) {
            Files.writeString(outputFile, decompiled);
            System.out.println("Decompiled to " + outputFile);
        } else {
            System.out.println(decompiled);
        }

        return 0;
    }

    private String decompileChunk(Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        // Decompile constants to find functions/classes
        for (Object constant : chunk.constants) {
            if (constant instanceof ArFunction) {
                ArFunction fn = (ArFunction) constant;
                sb.append("function ").append(fn.name).append("(");
                // Arity argument names? We don't save them.
                for (int i = 0; i < fn.arity; i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append("arg").append(i);
                }
                sb.append(") {\n");
                sb.append(decompileBody(fn.chunk, 1));
                sb.append("}\n\n");
            } else if (constant instanceof ArClass) {
                ArClass cls = (ArClass) constant;
                sb.append("class ").append(cls.name);
                if (cls.superClass != null && !cls.superClass.name.equals("object")) {
                    sb.append(" : ").append(cls.superClass.name);
                }
                sb.append(" {\n");
                // Methods
                for (var entry : cls.methods.entrySet()) {
                    ArFunction method = (ArFunction) entry.getValue();
                    sb.append("    function ").append(method.name).append("(");
                    for (int i = 0; i < method.arity; i++) {
                        if (i > 0)
                            sb.append(", ");
                        sb.append("arg").append(i);
                    }
                    sb.append(") {\n");
                    sb.append(decompileBody(method.chunk, 2));
                    sb.append("    }\n");
                }
                sb.append("}\n\n");
            }
        }

        // Main body
        sb.append("// Main Body\n");
        sb.append(decompileBody(chunk, 0));

        return sb.toString();
    }

    private String decompileBody(Chunk chunk, int indentLevel) {
        StringBuilder sb = new StringBuilder();
        String indent = "    ".repeat(indentLevel);
        // This is a naive decompiler that just lists opcodes for now,
        // as full control flow reconstruction is complex.
        // Or we can try to be a bit smarter.

        for (int i = 0; i < chunk.count; i++) {
            int code = chunk.code[i];
            OpCode op = OpCode.values()[code];
            sb.append(indent).append(op);

            switch (op) {
                case LOAD_CONST: {
                    int idx = chunk.code[++i];
                    Object k = chunk.constants.get(idx);
                    sb.append(" ").append(k);
                    break;
                }
                case GET_LOCAL:
                case SET_LOCAL: {
                    int idx = chunk.code[++i];
                    sb.append(" local_").append(idx);
                    break;
                }
                case GET_GLOBAL:
                case SET_GLOBAL:
                case GET_PROPERTY:
                case SET_PROPERTY:
                case INVOKE:
                case SUPER_INVOKE:
                case SUPER_GET_PROPERTY:
                case SUPER_SET_PROPERTY: {
                    int idx = chunk.code[++i];
                    Object k = chunk.constants.get(idx);
                    sb.append(" ").append(k);
                    if (op == OpCode.INVOKE || op == OpCode.SUPER_INVOKE) {
                        int args = chunk.code[++i];
                        sb.append(" argc=").append(args);
                    }
                    break;
                }
                case JUMP:
                case JUMP_IF_FALSE:
                case TRY: {
                    int offset = chunk.code[++i];
                    sb.append(" -> ").append(offset);
                    break;
                }
                case CALL:
                case NEW:
                case NEW_ARRAY: {
                    int val = chunk.code[++i];
                    sb.append(" count=").append(val);
                    break;
                }
                case IMPORT: {
                    int idx = chunk.code[++i];
                    Object k = chunk.constants.get(idx);
                    sb.append(" ").append(k);
                    break;
                }
                default:
                    break;
            }
            sb.append("\n");
        }
        return sb.toString();
    }

}
