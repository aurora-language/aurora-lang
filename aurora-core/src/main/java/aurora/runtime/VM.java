package aurora.runtime;

import aurora.analyzer.ModuleResolver;
import aurora.compiler.CompiledClass;
import aurora.compiler.CompiledFunction;
import aurora.compiler.Compiler;
import aurora.compiler.TypeErrorException;
import aurora.parser.AuroraParser;
import aurora.parser.tree.Program;
import aurora.runtime.modules.ConcurrentModule;
import aurora.runtime.modules.IoModule;
import aurora.runtime.modules.NativeModule;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The core Virtual Machine for the Aurora language.
 * This class implements a stack-based interpreter that executes Aurora
 * bytecode.
 * It manages the operand stack, the call stack (frames), and provides methods
 * for loading and inflating code chunks, registering native modules, and
 * executing the main run loop.
 */
public class VM {
    /**
     * The operand stack used for intermediate expression results and function
     * arguments.
     */
    public final Stack<ArObject> stack = new Stack<>();

    /**
     * The call stack containing information about the current function execution
     * chain.
     */
    public final Stack<Frame> frames = new Stack<>();

    /**
     * Represents state that is shared across multiple threads in the VM.
     * This includes global variables, the executor service for threading,
     * and information about loaded modules.
     */
    public static class SharedState {
        /** A thread-safe map of global variable names to their values. */
        public final Map<String, ArObject> globals = new ConcurrentHashMap<>();

        /** The thread pool used for executing concurrent Aurora threads. */
        public final ExecutorService threadPool = Executors.newCachedThreadPool();

        /** A list of filesystem paths where the VM searches for modules. */
        public final List<Path> libraryPaths = new ArrayList<>();

        /** A set of identifiers for modules that have already been loaded. */
        public final Set<String> loadedModules = ConcurrentHashMap.newKeySet();

        public final ModuleResolver moduleResolver = new ModuleResolver();

        /**
         * Initializes a new shared state with default library paths.
         */
        public SharedState() {
            // Default library paths
            libraryPaths.add(Paths.get("aurora/lib"));
            libraryPaths.add(Paths.get("."));
            moduleResolver.setProjectRoot(Paths.get("."));
        }

        /**
         * Adds a new search path for libraries.
         * 
         * @param path The path to add.
         */
        public void addLibraryPath(Path path) {
            if (!libraryPaths.contains(path))
                libraryPaths.add(0, path); // Prepend so user paths take priority
        }
    }

    /** The shared state associated with this VM instance. */
    public final SharedState shared;

    /**
     * Initializes a new VM with default shared state and standard libraries.
     */
    public VM() {
        this(new SharedState());
        setupStandardLibrary();
    }

    /**
     * Initializes a new VM with an existing shared state.
     * 
     * @param shared The shared state to use.
     */
    public VM(SharedState shared) {
        this.shared = shared;
    }

    public Chunk loadChunk(DataInputStream in) throws IOException {
        int constantCount = in.readInt();
        Chunk chunk = new Chunk("<loaded>");
        for (int i = 0; i < constantCount; i++) {
            byte tag = in.readByte();
            switch (tag) {
                case 0:
                    chunk.addConstant(ArNone.INSTANCE);
                    break;
                case 1:
                    chunk.addConstant(in.readBoolean() ? ArBool.TRUE : ArBool.FALSE);
                    break;
                case 2:
                    chunk.addConstant(new ArInt(in.readInt()));
                    break;
                case 3:
                    chunk.addConstant(new ArLong(in.readLong()));
                    break;
                case 4:
                    chunk.addConstant(new ArFloat(in.readFloat()));
                    break;
                case 5:
                    chunk.addConstant(new ArDouble(in.readDouble()));
                    break;
                case 6:
                    chunk.addConstant(new ArString(in.readUTF()));
                    break;
                case 7: {
                    String name = in.readUTF();
                    int arity = in.readInt();
                    Chunk fnChunk = loadChunk(in); // Recursive load
                    chunk.addConstant(new ArFunction(name, fnChunk, arity));
                    break;
                }
                case 8: {
                    String name = in.readUTF();
                    ArClass klass = new ArClass(name);
                    if (in.readBoolean()) {
                        klass.initializer = new ArFunction("<init>", loadChunk(in), 0);
                    }
                    int methodCount = in.readInt();
                    for (int m = 0; m < methodCount; m++) {
                        String methodName = in.readUTF();
                        klass.methods.put(methodName, new ArFunction(methodName, loadChunk(in), 0));
                    }
                    chunk.addConstant(klass);
                    break;
                }
                default:
                    throw new IOException("Unknown constant tag: " + tag);
            }
        }

        int instructionCount = in.readInt();
        for (int i = 0; i < instructionCount; i++) {
            chunk.write(in.readInt(), in.readInt(), 0);
        }

        return chunk;
    }

    /**
     * Converts a raw Chunk (containing Java primitives and Compiled* objects)
     * into an inflated Chunk (containing ArObject instances).
     */
    public Chunk inflateChunk(Chunk rawChunk) {
        Chunk inflated = new Chunk(rawChunk.sourceFile != null ? rawChunk.sourceFile : "<inflated>");
        inflated.code = rawChunk.code;
        inflated.count = rawChunk.count;
        inflated.lines = rawChunk.lines;
        inflated.columns = rawChunk.columns;
        for (Object rawConst : rawChunk.constants) {
            inflated.addConstant(inflate(rawConst));
        }
        return inflated;
    }

    private ArObject inflate(Object raw) {
        switch (raw) {
            case null -> {
                return ArNone.INSTANCE;
            }
            case ArObject arObject -> {
                return arObject;
            }
            case Boolean b -> {
                return b ? ArBool.TRUE : ArBool.FALSE;
            }
            case Integer i -> {
                return new ArInt(i);
            }
            case Long l -> {
                return new ArLong(l);
            }
            case Float v -> {
                return new ArFloat(v);
            }
            case Double v -> {
                return new ArDouble(v);
            }
            case String s -> {
                return new ArString(s);
            }
            case CompiledFunction cf -> {
                return new ArFunction(cf.name(), inflateChunk(cf.chunk()), cf.arity());
            }
            case CompiledClass cc -> {
                ArClass klass = new ArClass(cc.name);
                if (cc.superClassName != null) {
                    ArObject superObj = shared.globals.get(cc.superClassName);
                    if (superObj == null) {
                        // Try simple name search
                        for (var entry : shared.globals.entrySet()) {
                            if (entry.getValue() instanceof ArClass && (entry.getKey().endsWith("." + cc.superClassName)
                                    || entry.getKey().equals(cc.superClassName))) {
                                superObj = entry.getValue();
                                break;
                            }
                        }
                    }

                    for (String ifaceName : cc.interfaceNames) {
                        ArObject ifaceObj = shared.globals.get(ifaceName);
                        if (ifaceObj instanceof ArClass ifaceClass) {
                            klass.interfaces.add(ifaceClass);
                            ifaceClass.methods.forEach(klass.methods::putIfAbsent);
                        }
                    }

                    if (superObj instanceof ArClass) {
                        klass.superClass = (ArClass) superObj;
                    } else {
                        klass.superClass = OBJECT_CLASS;
                    }
                } else if (!cc.name.equals("object")) {
                    klass.superClass = OBJECT_CLASS;
                }

                // Register class BEFORE inflating members to support recursive types or method
                // owner linkage
                shared.globals.put(cc.name, klass);

                if (cc.initializer != null) {
                    klass.initializer = (ArFunction) inflate(cc.initializer);
                    klass.initializer.ownerClass = klass;
                }
                for (var entry : cc.methods.entrySet()) {
                    ArFunction method = (ArFunction) inflate(entry.getValue());
                    method.ownerClass = klass;
                    klass.methods.put(entry.getKey(), method);
                }
                // System.out.println("[DEBUG] Inflated class " + klass.name + " with " +
                // klass.methods.size() + " methods");
                return klass;
                // System.out.println("[DEBUG] Inflated class " + klass.name + " with " +
                // klass.methods.size() + " methods");
            }
            default -> {
            }
        }
        throw new AuroraRuntimeException("Cannot inflate raw constant: " + raw.getClass());
    }

    private static final ArClass INT_CLASS = new ArClass("int");
    private static final ArClass LONG_CLASS = new ArClass("long");
    private static final ArClass FLOAT_CLASS = new ArClass("float");
    private static final ArClass DOUBLE_CLASS = new ArClass("double");
    private static final ArClass STRING_CLASS = new ArClass("string");
    private static final ArClass BOOL_CLASS = new ArClass("bool");
    private static final ArClass OBJECT_CLASS = new ArClass("object");
    private static final ArClass NONE_CLASS = new ArClass("none");
    private static final ArClass ARRAY_CLASS = new ArClass("array");
    private static final ArClass FUTURE_CLASS = new ArClass("future");
    private static final ArClass ITERABLE_TRAIT = new ArClass("Iterable");
    private static final ArClass THROWABLE_TRAIT = new ArClass("Throwable");
    private static final ArClass COMPARABLE_TRAIT = new ArClass("Comparable");

    private boolean isInstanceOf(ArObject value, ArObject type) {
        if (type == OBJECT_CLASS)
            return true;
        if (value == null || value == ArNone.INSTANCE)
            return type == NONE_CLASS;

        if (type instanceof ArClass targetKlass) {
            if (targetKlass == INT_CLASS)
                return value instanceof ArInt;
            if (targetKlass == LONG_CLASS)
                return value instanceof ArLong;
            if (targetKlass == FLOAT_CLASS)
                return value instanceof ArFloat;
            if (targetKlass == DOUBLE_CLASS)
                return value instanceof ArDouble;
            if (targetKlass == STRING_CLASS)
                return value instanceof ArString;
            if (targetKlass == BOOL_CLASS)
                return value instanceof ArBool;
            if (targetKlass == ARRAY_CLASS)
                return value instanceof ArArray;
            if (targetKlass == FUTURE_CLASS)
                return value instanceof ArFuture;

            if (value instanceof ArInstance inst) {
                ArClass currentKlass = inst.klass;
                while (currentKlass != null) {
                    if (currentKlass == targetKlass) return true;
                    if (currentKlass.implementsTrait(targetKlass)) return true;
                    currentKlass = currentKlass.superClass;
                }
            }
        }
        return false;
    }

    public void registerModule(NativeModule module) {
        module.register(this);
    }

    public void bind(Object module) {
        NativeBinder.bind(this, module);
    }

    private void setupStandardLibrary() {
        shared.globals.put("int", INT_CLASS);
        shared.globals.put("long", LONG_CLASS);
        shared.globals.put("float", FLOAT_CLASS);
        shared.globals.put("double", DOUBLE_CLASS);
        shared.globals.put("string", STRING_CLASS);
        shared.globals.put("bool", BOOL_CLASS);
        shared.globals.put("object", OBJECT_CLASS);
        shared.globals.put("none", NONE_CLASS);
        shared.globals.put("array", ARRAY_CLASS);
        shared.globals.put("future", FUTURE_CLASS);
        shared.globals.put("Iterable", ITERABLE_TRAIT);
        shared.globals.put("Throwable", THROWABLE_TRAIT);
        shared.globals.put("Comparable", COMPARABLE_TRAIT);

        // All classes inherit from object
        INT_CLASS.superClass = OBJECT_CLASS;
        LONG_CLASS.superClass = OBJECT_CLASS;
        FLOAT_CLASS.superClass = OBJECT_CLASS;
        DOUBLE_CLASS.superClass = OBJECT_CLASS;
        STRING_CLASS.superClass = OBJECT_CLASS;
        BOOL_CLASS.superClass = OBJECT_CLASS;
        ARRAY_CLASS.superClass = OBJECT_CLASS;
        FUTURE_CLASS.superClass = OBJECT_CLASS;

        ARRAY_CLASS.interfaces.add(ITERABLE_TRAIT);

        // Add built-in methods to string
        ArNativeFunction stringLength = new ArNativeFunction("length", 1) {
            @Override
            public ArObject call(List<ArObject> args) {
                return new ArInt(((ArString) args.get(0)).value.length());
            }
        };
        STRING_CLASS.methods.put("length", stringLength);
        shared.globals.put("string.length", stringLength);

        ArNativeFunction stringIsEmpty = new ArNativeFunction("isEmpty", 1) {
            @Override
            public ArObject call(List<ArObject> args) {
                return ((ArString) args.get(0)).value.isEmpty() ? ArBool.TRUE : ArBool.FALSE;
            }
        };
        STRING_CLASS.methods.put("isEmpty", stringIsEmpty);
        shared.globals.put("string.isEmpty", stringIsEmpty);

        // Universal methods
        OBJECT_CLASS.methods.put("toString", new ArNativeFunction("toString", 1) {
            @Override
            public ArObject call(List<ArObject> args) {
                return new ArString(args.getFirst().toString());
            }
        });
        OBJECT_CLASS.methods.put("<init>", new ArNativeFunction("<init>", -1) { // -1 for variadic/any
            @Override
            public ArObject call(List<ArObject> args) {
                return ArNone.INSTANCE;
            }
        });

        registerModule(new IoModule());
        registerModule(new ConcurrentModule());

        loadRuntimeModules();
    }

    private void loadRuntimeModules() {
        // 1. Scan Aurora/Runtime
        for (Path root : shared.libraryPaths) {
            Path runtimeRoot = root.resolve("Aurora/Runtime");
            if (!Files.exists(runtimeRoot)) continue;
            try (Stream<Path> fs = Files.walk(runtimeRoot)) {
                fs.filter(p -> p.toString().endsWith(".ar") || p.toString().endsWith(".arobj"))
                        .forEach(file -> {
                            Path rel = root.relativize(file);
                            String fqn = rel.toString()
                                    .replace(File.separatorChar, '.')
                                    .replaceAll("\\.(ar|arobj)$", "");
                            try {
                                loadModule(fqn);
                            } catch (AuroraRuntimeException e) {
                                System.err.println(e.getAuroraStackTrace());
                            }
                        });
            } catch (IOException ignored) {}
            break;
        }

        // 2. Load explicit implicit imports from Lib
        for (String path : aurora.Lib.implicitImports) {
            try {
                loadModule(path);
            } catch (AuroraRuntimeException e) {
                System.err.println(e.getAuroraStackTrace());
            } catch (TypeErrorException e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
    }

    public String arToString(ArObject obj) {
        if (obj instanceof ArInstance inst) {
            ArObject strMethod = inst.klass.findMethod("str");
            if (strMethod != null) {
                callValue(strMethod, 0, inst);
                runLoop();
                ArObject result = stack.pop();
                return result instanceof ArString s ? s.value : result.toString();
            }
            return "[" + inst.klass.name + "]";
        }
        if (obj instanceof ArClass klass) return "[class " + klass.name + "]";
        if (obj instanceof ArNone)   return "none";
        return obj.toString();
    }

    public void run(Chunk chunk) {
        frames.push(new Frame(chunk, new ArFunction("script", chunk, 0), null));
        runLoop();
    }

    public void runLoop() {
        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            if (frame.pc >= frame.chunk.count) {
                frames.pop();
                continue;
            }

            int instruction = frame.chunk.code[frame.pc];
            OpCode op = OpCode.values()[instruction];

            // Debug trace
            //System.out.println("PC: " + frame.pc + " OP: " + op + " STACK: " + stack);

            frame.pc++;

            try {
                switch (op) {
                    case LOAD_CONST: {
                        int constantIndex = frame.chunk.code[frame.pc];
                        frame.pc++;
                        ArObject constant = (ArObject) frame.chunk.constants.get(constantIndex);
                        stack.push(constant);
                        break;
                    }
                    case PRINT: {
                        throw new RuntimeException("Debug PRINT Opcode disabled");
                    }
                    case TRUE:
                        stack.push(ArBool.TRUE);
                        break;
                    case FALSE:
                        stack.push(ArBool.FALSE);
                        break;
                    case ADD: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(add(a, b));
                        break;
                    }
                    case SUB: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(subtract(a, b));
                        break;
                    }
                    case MUL: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(multiply(a, b));
                        break;
                    }
                    case DIV: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(divide(a, b));
                        break;
                    }
                    case MOD: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(modulo(a, b));
                        break;
                    }
                    case EQUAL: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(areEqual(a, b) ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case NOT_EQUAL: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(!areEqual(a, b) ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case GREATER: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(compare(a, b) > 0 ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case GREATER_EQUAL: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(compare(a, b) >= 0 ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case LESS: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(compare(a, b) < 0 ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case LESS_EQUAL: {
                        ArObject b = stack.pop();
                        ArObject a = stack.pop();
                        stack.push(compare(a, b) <= 0 ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case NOT: {
                        ArObject val = stack.pop();
                        if (val instanceof ArBool) {
                            stack.push(!((ArBool) val).value ? ArBool.TRUE : ArBool.FALSE);
                        } else {
                            throw new AuroraRuntimeException("Operand must be a boolean. Got: " + val);
                        }
                        break;
                    }
                    case NEG: {
                        ArObject val = stack.pop();
                        stack.push(negate(val));
                        break;
                    }
                    case GET_LOCAL: {
                        int index = frame.chunk.code[frame.pc];
                        frame.pc++;
                        ArObject val = frame.get(index);
                        //System.out.println("[DEBUG] GET_LOCAL index=" + index + " val=" + val);
                        stack.push(val);
                        break;
                    }
                    case SET_LOCAL: {
                        int index = frame.chunk.code[frame.pc];
                        frame.pc++;
                        //System.out.println("[DEBUG] SET_LOCAL index=" + index + " val=" +
                        stack.peek();
                        frame.set(index, stack.peek());
                        break;
                    }
                    case GET_GLOBAL: {
                        int nameIndex = frame.chunk.code[frame.pc];
                        frame.pc++;
                        String name = ((ArString) frame.chunk.constants.get(nameIndex)).value;
                        ArObject value = shared.globals.get(name);
                        if (value == null) {
                            throw new AuroraRuntimeException("Undefined global variable: " + name);
                        }
                        stack.push(value);
                        break;
                    }
                    case SET_GLOBAL: {
                        int nameIndex = frame.chunk.code[frame.pc];
                        frame.pc++;
                        String name = ((ArString) frame.chunk.constants.get(nameIndex)).value;
                        shared.globals.put(name, stack.peek());
                        break;
                    }
                    case JUMP: {
                        frame.pc = frame.chunk.code[frame.pc];
                        break;
                    }
                    case JUMP_IF_FALSE: {
                        int offset = frame.chunk.code[frame.pc];
                        frame.pc++;
                        ArObject condition = stack.pop();
                        if (condition instanceof ArBool && !((ArBool) condition).value) {
                            frame.pc = offset;
                        }
                        break;
                    }
                    case CALL: {
                        int argCount = frame.chunk.code[frame.pc];
                        frame.pc++;
                        List<ArObject> args = new ArrayList<>();
                        for (int i = 0; i < argCount; i++) {
                            args.addFirst(stack.pop());
                        }
                        ArObject callee = stack.pop();
                        for (ArObject arg : args) {
                            stack.push(arg);
                        }
                        callValue(callee, argCount);
                        break;
                    }
                    case RETURN: {
                        ArObject result = stack.pop();
                        boolean wasInitializer = frames.peek().isInitializer;
                        frames.pop();
                        if (frames.isEmpty())
                            return;
                        if (!wasInitializer) {
                            stack.push(result);
                        }
                        break;
                    }
                    case NEW: {
                        int argCount = frame.chunk.code[frame.pc];
                        frame.pc++;

                        List<ArObject> args = new ArrayList<>();
                        for (int i = 0; i < argCount; i++) {
                            args.addFirst(stack.pop());
                        }
                        ArObject maybeKlass = stack.pop();
                        if (!(maybeKlass instanceof ArClass klass)) {
                            throw new AuroraRuntimeException("Can only instantiate classes, got "
                                    + maybeKlass.getClass().getSimpleName() + ": " + maybeKlass);
                        }

                        ArInstance instance = new ArInstance(klass);
                        stack.push(instance);
                        ArFunction initializer = klass.findInitializer();
                        if (initializer != null) {
                            // Put args back for callValue
                            for (ArObject arg : args)
                                stack.push(arg);

                            // Call initializer (constructor)
                            // We need to pass the instance as 'self' (slot 0)
                            callValue(initializer, argCount, instance);
                            frames.peek().isInitializer = true;
                        } else if (argCount != 0) {
                            throw new AuroraRuntimeException("Expected 0 arguments but got " + argCount);
                        }
                        break;
                    }
                    case GET_PROPERTY: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        ArObject instance = stack.pop();
                        if (instance instanceof ArInstance inst) {
                            if (inst.fields.containsKey(name)) {
                                stack.push(inst.fields.get(name));
                                break;
                            }
                            ArObject method = inst.klass.findMethod(name);
                            if (method != null) {
                                stack.push(new ArBoundMethod(inst, method));
                                break;
                            }
                            throw new AuroraRuntimeException("Undefined property: " + name);
                        } else if (instance instanceof ArClass klass) {
                            ArObject method = klass.findMethod(name);
                            if (method != null) {
                                stack.push(method);
                                break;
                            }
                            throw new AuroraRuntimeException(
                                    "Undefined static member: " + name + " in class " + klass.name);
                        } else {
                            // Support methods on built-in types
                            ArClass klass = getClassFor(instance);
                            if (klass != null) {
                                ArObject method = klass.findMethod(name);
                                if (method != null) {
                                    stack.push(new ArBoundMethod(instance, method));
                                    break;
                                }
                            }
                        }
                        throw new AuroraRuntimeException("Undefined property or method: " + name + " for " + instance.getClass().getSimpleName());
                    }
                    case SET_PROPERTY: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        ArObject value = stack.pop();
                        ArObject instance = stack.pop();
                        if (instance instanceof ArInstance) {
                            ((ArInstance) instance).fields.put(name, value);
                            stack.push(value);
                        } else {
                            throw new AuroraRuntimeException("Only instances have properties.");
                        }
                        break;
                    }
                    case INVOKE: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        int argCount = frame.chunk.code[frame.pc++];

                        List<ArObject> args = new ArrayList<>();
                        for (int i = 0; i < argCount; i++) {
                            args.addFirst(stack.pop());
                        }
                        ArObject instance = stack.pop();

                        if (instance instanceof ArInstance inst) {
                            if (inst.fields.containsKey(name)) {
                                // Put args back for callValue
                                for (ArObject arg : args)
                                    stack.push(arg);
                                callValue(inst.fields.get(name), argCount);
                            } else {
                                ArObject method = inst.klass.findMethod(name);
                                if (method != null) {
                                    // Put args back
                                    for (ArObject arg : args)
                                        stack.push(arg);
                                    callValue(method, argCount, inst);
                                } else {
                                    throw new AuroraRuntimeException("Undefined method: " + name);
                                }
                            }
                            // ...
                        } else if (instance instanceof ArClass klass) {
                            ArObject method = klass.findMethod(name);
                            if (method != null) {
                                for (ArObject arg : args)
                                    stack.push(arg);
                                callValue(method, argCount);
                                break;
                            }
                            throw new AuroraRuntimeException("Undefined static method: " + name + " in " + klass.name);
                        } else {
                            ArClass klass = getClassFor(instance);
                            if (klass != null) {
                                ArObject method = klass.findMethod(name);
                                if (method != null) {
                                    for (ArObject arg : args)
                                        stack.push(arg);
                                    callValue(method, argCount, instance);
                                    break;
                                }
                                // Try finding in globals as fallback for built-in methods (e.g. string.length)
                                String typeName = klass.name;
                                ArObject globalMethod = shared.globals.get(typeName + "." + name);
                                if (globalMethod != null) {
                                    for (ArObject arg : args)
                                        stack.push(arg);
                                    callValue(globalMethod, argCount, instance);
                                    break;
                                }
                            }
                            throw new AuroraRuntimeException("Only instances have methods or method not found on "
                                    + instance.getClass().getSimpleName() + " (method: " + name + ")");
                        }
                        break;
                    }
                    case SUPER_INVOKE: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        int argCount = frame.chunk.code[frame.pc++];

                        List<ArObject> args = new ArrayList<>();
                        for (int i = 0; i < argCount; i++) {
                            args.addFirst(stack.pop());
                        }
                        ArObject instance = stack.pop();

                        if (!(instance instanceof ArInstance inst)) {
                            throw new AuroraRuntimeException("Only instances have super methods.");
                        }
                        if (frame.function.ownerClass == null || frame.function.ownerClass.superClass == null) {
                            throw new AuroraRuntimeException("No superclass for method: " + name);
                        }
                        ArObject method = frame.function.ownerClass.superClass.findMethod(name);
                        if (method != null) {
                            for (ArObject arg : args)
                                stack.push(arg);
                            callValue(method, argCount, inst);
                        } else {
                            throw new AuroraRuntimeException("Undefined super method: " + name);
                        }
                        break;
                    }
                    case SUPER_GET_PROPERTY: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        ArObject instance = stack.pop();
                        if (!(instance instanceof ArInstance inst)) {
                            throw new AuroraRuntimeException("Only instances have super properties.");
                        }
                        if (frame.function.ownerClass == null || frame.function.ownerClass.superClass == null) {
                            throw new AuroraRuntimeException("No superclass for property: " + name);
                        }

                        if (inst.fields.containsKey(name)) {
                            stack.push(inst.fields.get(name));
                            break;
                        }

                        ArObject method = frame.function.ownerClass.superClass.findMethod(name);
                        if (method != null) {
                            stack.push(new ArBoundMethod(inst, method));
                            break;
                        }
                        throw new AuroraRuntimeException("Undefined super property: " + name);
                    }
                    case SUPER_SET_PROPERTY: {
                        String name = ((ArString) frame.chunk.constants.get(frame.chunk.code[frame.pc++])).value;
                        ArObject value = stack.pop();
                        ArObject instance = stack.pop();
                        if (instance instanceof ArInstance) {
                            // Current implementation has only one field map per instance,
                            // so super.prop = val is the same as this.prop = val.
                            ((ArInstance) instance).fields.put(name, value);
                            stack.push(value);
                        } else {
                            throw new AuroraRuntimeException("Only instances have properties.");
                        }
                        break;
                    }
                    case NEW_ARRAY: {
                        int count = frame.chunk.code[frame.pc++];
                        List<ArObject> elements = new ArrayList<>(count);
                        // Elements are on stack in reverse order?
                        // Compiler visits elements 0..N.
                        // So stack has elem0, elem1... elemN (top).
                        // We need to pop N times.
                        // Popping gives elemN first.
                        for (int i = 0; i < count; i++) {
                            elements.add(null);
                        }
                        for (int i = count - 1; i >= 0; i--) {
                            elements.set(i, stack.pop());
                        }
                        stack.push(new ArArray(elements));
                        break;
                    }
                    case GET_INDEX: {
                        ArObject index = stack.pop();
                        ArObject object = stack.pop();
                        if (object instanceof ArArray array && index instanceof ArInt) {
                            int idx = ((ArInt) index).value;
                            if (idx < 0 || idx >= array.elements.size()) {
                                throw new AuroraRuntimeException("Index out of bounds: " + idx);
                            }
                            stack.push(array.elements.get(idx));
                        } else {
                            throw new AuroraRuntimeException("Invalid index operation.");
                        }
                        break;
                    }
                    case SET_INDEX: {
                        ArObject value = stack.pop();
                        ArObject index = stack.pop();
                        ArObject object = stack.pop();
                        if (object instanceof ArArray array && index instanceof ArInt) {
                            int idx = ((ArInt) index).value;
                            if (idx < 0 || idx >= array.elements.size()) {
                                throw new AuroraRuntimeException("Index out of bounds: " + idx);
                            }
                            array.elements.set(idx, value);
                            stack.push(value);
                        } else {
                            throw new AuroraRuntimeException("Invalid index assignment.");
                        }
                        break;
                    }
                    case IS: {
                        ArObject type = stack.pop();
                        ArObject value = stack.pop();
                        stack.push(isInstanceOf(value, type) ? ArBool.TRUE : ArBool.FALSE);
                        break;
                    }
                    case AS: {
                        ArObject type = stack.pop();
                        ArObject value = stack.pop();
                        if (isInstanceOf(value, type)) {
                            stack.push(value);
                        } else {
                            throw new AuroraRuntimeException(
                                    "ClassCastException: " + value + " cannot be cast to " + type);
                        }
                        break;
                    }
                    case TRY: {
                        int catchOffset = frame.chunk.code[frame.pc++];
                        frame.handlers.push(new ExceptionHandler(catchOffset, stack.size()));
                        break;
                    }
                    case END_TRY: {
                        frame.handlers.pop();
                        break;
                    }
                    case THROW: {
                        ArObject exception = stack.pop();
                        throw new AuroraRuntimeException(exception);
                    }
                    case SPAWN_THREAD: {
                        int argCount = frame.chunk.code[frame.pc++];
                        ArObject callee = stack.pop();
                        List<ArObject> args = new ArrayList<>();
                        for (int i = 0; i < argCount; i++) {
                            args.addFirst(stack.pop());
                        }

                        CompletableFuture<ArObject> cf = CompletableFuture.supplyAsync(() -> {
                            VM newVm = new VM(shared);
                            if (callee instanceof ArFunction fn) {
                                Chunk newChunk = new Chunk("<thread>");
                                // Copy constants or use them
                                // We need a way to call the function.
                                // Simplest is to push args and CALL fn.
                                for (ArObject arg : args) {
                                    newVm.stack.push(arg);
                                }
                                newVm.callValue(fn, args.size());
                                newVm.runLoop(); // Run until frames empty
                                return newVm.getStackTop() != null ? newVm.getStackTop() : ArNone.INSTANCE;
                            } else {
                                throw new AuroraRuntimeException("Can only spawn threads for functions.");
                            }
                        }, shared.threadPool);

                        stack.push(new ArFuture(cf));
                        break;
                    }
                    case ITER_HAS_NEXT: {
                        ArObject indexObj = stack.pop();
                        ArObject iterableObj = stack.pop();
                        if (iterableObj instanceof ArArray && indexObj instanceof ArInt) {
                            int idx = ((ArInt) indexObj).value;
                            int size = ((ArArray) iterableObj).elements.size();
                            stack.push(idx < size ? ArBool.TRUE : ArBool.FALSE);
                        } else {
                            throw new AuroraRuntimeException("ITER_HAS_NEXT requires an array and an int index.");
                        }
                        break;
                    }
                    case ITER_NEXT: {
                        ArObject indexObj = stack.pop();
                        ArObject iterableObj = stack.pop();
                        if (iterableObj instanceof ArArray array && indexObj instanceof ArInt) {
                            int idx = ((ArInt) indexObj).value;
                            if (idx < 0 || idx >= array.elements.size()) {
                                throw new AuroraRuntimeException("Iterator index out of bounds: " + idx);
                            }
                            stack.push(array.elements.get(idx));
                        } else {
                            throw new AuroraRuntimeException("ITER_NEXT requires an array and an int index.");
                        }
                        break;
                    }
                    case POP: {
                        if (!stack.isEmpty()) {
                            stack.pop();
                        }
                        break;
                    }
                    case IMPORT: {
                        int nameIndex = frame.chunk.code[frame.pc++];
                        Object nameObj = frame.chunk.constants.get(nameIndex);
                        String moduleName;
                        if (nameObj instanceof ArString) {
                            moduleName = ((ArString) nameObj).value;
                        } else if (nameObj instanceof String) {
                            moduleName = (String) nameObj;
                        } else {
                            throw new AuroraRuntimeException("IMPORT operand must be a string, got: " + nameObj);
                        }
                        loadModule(moduleName);
                        break;
                    }
                    case HALT: {
                        frames.pop();
                        if (frames.isEmpty())
                            return;
                        // Module finished, continue with the caller frame
                        break;
                    }
                    default:
                        throw new AuroraRuntimeException("Unknown opcode: " + op);
                }
            } catch (AuroraRuntimeException e) {
                if (!handleException(e)) {
                    //e.printStackTrace();
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new AuroraRuntimeException("Internal VM Error: " + e.getMessage());
            }
        }
    }

    private void throwException(ArObject exception) {
        throw new AuroraRuntimeException(exception);
    }

    private void executeModuleChunk(Chunk moduleChunk) {
        Stack<Frame> savedFrames = new Stack<>();
        savedFrames.addAll(frames);
        frames.clear();

        frames.push(new Frame(moduleChunk, new ArFunction("<module>", moduleChunk, 0), null));
        runLoop();

        frames.addAll(savedFrames);
    }

    /**
     * Dynamically loads a module by name.
     * Search order:
     * 1. Pre-compiled .arobj file in library paths
     * 2. Source .ar file in library paths (compiled on the fly)
     * Module names use dot notation (e.g., "Aurora.Io") which maps to
     * directory structure (Aurora/Io.arobj or Aurora/Io.ar).
     */
    private void loadModule(String moduleName) {
        if (shared.loadedModules.contains(moduleName))
            return;
        shared.loadedModules.add(moduleName);

        String pathPart = moduleName.replace('.', '/');

        // 1. Try pre-compiled .arobj
        for (Path root : shared.libraryPaths) {
            Path arobjPath = root.resolve(pathPart + ".arobj");
            if (Files.exists(arobjPath)) {
                loadArobj(arobjPath);
                return;
            }
        }

        // 2. Try source .ar (compile on the fly)
        for (Path root : shared.libraryPaths) {
            Path arPath = root.resolve(pathPart + ".ar");
            if (Files.exists(arPath)) {
                compileAndLoad(arPath);
                return;
            }
        }

        // 3. Try mod.ar fallback (directory module)
        for (Path root : shared.libraryPaths) {
            Path modPath = root.resolve(pathPart).resolve("mod.ar");
            if (Files.exists(modPath)) {
                compileAndLoad(modPath);
                return;
            }
        }

        throw new AuroraRuntimeException("Module not found: " + moduleName + " (searched: " + shared.libraryPaths + ")");
    }

    private void loadArobj(Path path) {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path.toFile())))) {
            int magic = dis.readInt();
            if (magic != 0x4155524F) {
                throw new AuroraRuntimeException("Invalid module file (bad magic): " + path);
            }
            dis.readInt(); // version

            Chunk moduleChunk = loadChunk(dis);
            executeModuleChunk(moduleChunk);
        } catch (IOException e) {
            System.err.println("Failed to load module: " + path + " - " + e.getMessage());
        }
    }

    private void compileAndLoad(Path path) {
        try {
            String code = Files.readString(path, StandardCharsets.UTF_8);
            Program program = AuroraParser.parse(code, path.getFileName().toString(), shared.moduleResolver);

            Compiler compiler = new Compiler(shared.moduleResolver);
            Chunk rawChunk = compiler.compile(program);
            Chunk moduleChunk = inflateChunk(rawChunk);

            executeModuleChunk(moduleChunk);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load module: " + path + " - " + e.getMessage());
        }
    }

    private boolean handleException(AuroraRuntimeException e) {
        // Collect stack trace elements
        List<String> traceLines = new ArrayList<>();
        Stack<Frame> tempFrames = new Stack<>();
        tempFrames.addAll(frames);

        while (!frames.isEmpty()) {
            Frame frame = frames.peek();

            // Check for handlers
            if (!frame.handlers.isEmpty()) {
                ExceptionHandler handler = frame.handlers.pop();
                frame.pc = handler.catchPc;
                // Restore stack depth
                while (stack.size() > handler.stackDepth) {
                    stack.pop();
                }
                // Push exception object
                stack.push(e.auroraObject);
                return true;
            }
            frames.pop();
        }

        // No handler found, print the stack trace
        System.err.println("Uncaught Aurora Exception: " + e.getMessage());

        int count = 0;
        int maxCountWidth = String.valueOf(tempFrames.size()).length();
        if (maxCountWidth < 3)
            maxCountWidth = 3;

        // Print frames in reverse (from top of stack to bottom)
        for (int i = tempFrames.size() - 1; i >= 0; i--) {
            Frame f = tempFrames.get(i);
            int pc = f.pc > 0 ? f.pc - 1 : 0;
            int line = f.chunk.lines[pc];
            int col = f.chunk.columns[pc];

            String fileName = f.chunk.sourceFile != null ? f.chunk.sourceFile : "<unknown>";
            String funcName = f.function != null ? f.function.name : "<unknown>";
            if (f.function != null && f.function.ownerClass != null) {
                funcName = f.function.ownerClass.name + "." + funcName;
            }

            // Native functions are not pushed onto the frames stack,
            // so we don't need a special case for <native> here.

            // Align by count
            String countStr = String.format("%-" + maxCountWidth + "d", count);
            System.err.println(countStr + " at " + fileName + ":" + funcName + " (" + line + ":" + col + ")");
            count++;
        }

        return false;
    }

    ArObject getStackTop() {
        return stack.isEmpty() ? null : stack.peek();
    }

    public void callValue(ArObject callee, int argCount) {
        callValue(callee, argCount, null);
    }

    public void callValue(ArObject callee, int argCount, ArObject receiver) {
        switch (callee) {
            case ArFunction function -> {
                int totalArgs = argCount + (receiver != null ? 1 : 0);
                if (totalArgs != function.arity) {
                    throw new AuroraRuntimeException("Expected " + function.arity + " arguments but got " + totalArgs);
                }
                Frame frame = new Frame(function.chunk, function, frames.peek());
                // Arguments are on the stack. We need to put them into the frame's registers.
                // If there's a receiver (self), it goes to slot 0.
                int offset = (receiver != null) ? 1 : 0;
                if (receiver != null) {
                    frame.set(0, receiver);
                }
                // Arguments are pushed onto the stack in order, so the last argument is at the
                // top.
                // We should pop them and put them in registers in order.
                for (int i = argCount - 1; i >= 0; i--) {
                    frame.set(i + offset, stack.pop());
                }
                frames.push(frame);
            }
            case ArNativeFunction function -> {
                int totalArgs = argCount + (receiver != null ? 1 : 0);
                if (function.arity != -1 && totalArgs != function.arity) {
                    throw new AuroraRuntimeException(
                            "Expected " + function.arity + " arguments but got " + totalArgs + " (incl. receiver)");
                }
                List<ArObject> args = new ArrayList<>();
                for (int i = 0; i < argCount; i++) {
                    args.addFirst(stack.pop());
                }
                if (receiver != null) {
                    args.addFirst(receiver);
                }
                stack.push(function.call(args));
            }
            case ArBoundMethod method -> callValue(method.method, argCount, method.receiver);
            case ArClass arClass ->
                // Instantiation via class call? (Python style)
                // The spec says 'new ClassName()', so maybe we don't support this.
                    throw new AuroraRuntimeException("Use 'new' to instantiate classes.");
            case null, default -> throw new AuroraRuntimeException("Can only call functions and classes.");
        }
    }

    private ArObject add(ArObject a, ArObject b) {
        if (a == null || b == null) {
            throw new AuroraRuntimeException("Cannot add null operands: a=" + a + ", b=" + b);
        }
        if (a instanceof ArInt intA && b instanceof ArInt intB)
            return new ArInt(intA.value + intB.value);
        if (a instanceof ArLong longA && b instanceof ArLong longB)
            return new ArLong(longA.value + longB.value);
        if (a instanceof ArFloat floatA && b instanceof ArFloat floatB)
            return new ArFloat(floatA.value + floatB.value);
        if (a instanceof ArDouble doubleA && b instanceof ArDouble doubleB)
            return new ArDouble(doubleA.value + doubleB.value);
        if (a instanceof ArString || b instanceof ArString)
            return new ArString(a.toString() + b);
        throw new AuroraRuntimeException("Invalid operands for +");
    }

    private ArObject subtract(ArObject a, ArObject b) {
        if (a instanceof ArInt intA && b instanceof ArInt intB)
            return new ArInt(intA.value - intB.value);
        if (a instanceof ArLong longA && b instanceof ArLong longB)
            return new ArLong(longA.value - longB.value);
        if (a instanceof ArFloat floatA && b instanceof ArFloat floatB)
            return new ArFloat(floatA.value - floatB.value);
        if (a instanceof ArDouble doubleA && b instanceof ArDouble doubleB)
            return new ArDouble(doubleA.value - doubleB.value);
        throw new AuroraRuntimeException("Invalid operands for -");
    }

    private ArObject multiply(ArObject a, ArObject b) {
        if (a instanceof ArInt intA && b instanceof ArInt intB)
            return new ArInt(intA.value * intB.value);
        if (a instanceof ArLong longA && b instanceof ArLong longB)
            return new ArLong(longA.value * longB.value);
        if (a instanceof ArFloat floatA && b instanceof ArFloat floatB)
            return new ArFloat(floatA.value * floatB.value);
        if (a instanceof ArDouble doubleA && b instanceof ArDouble doubleB)
            return new ArDouble(doubleA.value * doubleB.value);
        throw new AuroraRuntimeException("Invalid operands for *");
    }

    private ArObject divide(ArObject a, ArObject b) {
        if (a instanceof ArInt intA && b instanceof ArInt intB) {
            if (intB.value == 0)
                throw new AuroraRuntimeException("Division by zero");
            return new ArInt(intA.value / intB.value);
        }
        if (a instanceof ArLong longA && b instanceof ArLong longB) {
            if (longB.value == 0)
                throw new AuroraRuntimeException("Division by zero");
            return new ArLong(longA.value / longB.value);
        }
        if (a instanceof ArFloat floatA && b instanceof ArFloat floatB) {
            if (floatB.value == 0.0f)
                throw new AuroraRuntimeException("Division by zero");
            return new ArFloat(floatA.value / floatB.value);
        }
        if (a instanceof ArDouble doubleA && b instanceof ArDouble doubleB) {
            if (doubleB.value == 0.0)
                throw new AuroraRuntimeException("Division by zero");
            return new ArDouble(doubleA.value / doubleB.value);
        }
        throw new AuroraRuntimeException("Invalid operands for /");
    }

    private ArObject modulo(ArObject a, ArObject b) {
        if (a instanceof ArInt intA && b instanceof ArInt intB) {
            if (intB.value == 0)
                throw new AuroraRuntimeException("Modulo by zero");
            return new ArInt(intA.value % intB.value);
        }
        if (a instanceof ArLong longA && b instanceof ArLong longB) {
            if (longB.value == 0)
                throw new AuroraRuntimeException("Modulo by zero");
            return new ArLong(longA.value % longB.value);
        }
        throw new AuroraRuntimeException("Invalid operands for %");
    }

    private ArObject negate(ArObject a) {
        if (a instanceof ArInt intA)
            return new ArInt(-intA.value);
        if (a instanceof ArLong longA)
            return new ArLong(-longA.value);
        if (a instanceof ArFloat floatA)
            return new ArFloat(-floatA.value);
        if (a instanceof ArDouble doubleA)
            return new ArDouble(-doubleA.value);
        throw new AuroraRuntimeException("Operand must be a number.");
    }

    private boolean areEqual(ArObject a, ArObject b) {
        if (a == b)
            return true;
        if (a == null || b == null)
            return false;
        if (a.getClass() == b.getClass()) {
            if (a instanceof ArInt intA && b instanceof ArInt intB)
                return intA.value == intB.value;
            if (a instanceof ArLong longA && b instanceof ArLong longB)
                return longA.value == longB.value;
            if (a instanceof ArFloat floatA && b instanceof ArFloat floatB)
                return floatA.value == floatB.value;
            if (a instanceof ArDouble doubleA && b instanceof ArDouble doubleB)
                return doubleA.value == doubleB.value;
            if (a instanceof ArBool boolA && b instanceof ArBool boolB)
                return boolA.value == boolB.value;
            if (a instanceof ArString strA && b instanceof ArString strB)
                return strA.value.equals(strB.value);
            if (a instanceof ArNone)
                return true;
        }
        if (isNumber(a) && isNumber(b)) {
            return compareNumbers(a, b) == 0;
        }
        return false;
    }

    private int compare(ArObject a, ArObject b) {
        if (a instanceof ArString && b instanceof ArString) {
            return ((ArString) a).value.compareTo(((ArString) b).value);
        }
        if (isNumber(a) && isNumber(b)) {
            return compareNumbers(a, b);
        }
        throw new AuroraRuntimeException("Invalid operands for comparison");
    }

    private boolean isNumber(ArObject o) {
        return o instanceof ArInt || o instanceof ArLong || o instanceof ArFloat || o instanceof ArDouble;
    }

    private int compareNumbers(ArObject a, ArObject b) {
        if (a instanceof ArDouble || b instanceof ArDouble || a instanceof ArFloat || b instanceof ArFloat) {
            double valA = getDoubleValue(a);
            double valB = getDoubleValue(b);
            return Double.compare(valA, valB);
        } else {
            long valA = getLongValue(a);
            long valB = getLongValue(b);
            return Long.compare(valA, valB);
        }
    }

    private double getDoubleValue(ArObject o) {
        if (o instanceof ArInt intO)
            return intO.value;
        if (o instanceof ArLong longO)
            return longO.value;
        if (o instanceof ArFloat floatO)
            return floatO.value;
        if (o instanceof ArDouble doubleO)
            return doubleO.value;
        throw new AuroraRuntimeException("Not a number");
    }

    private long getLongValue(ArObject o) {
        if (o instanceof ArInt intO)
            return intO.value;
        if (o instanceof ArLong longO)
            return longO.value;
        if (o instanceof ArFloat floatO)
            return (long) floatO.value;
        if (o instanceof ArDouble doubleO)
            return (long) doubleO.value;
        throw new AuroraRuntimeException("Not a number");
    }

    private ArClass getClassFor(ArObject value) {
        if (value instanceof ArInt)
            return INT_CLASS;
        if (value instanceof ArLong)
            return LONG_CLASS;
        if (value instanceof ArFloat)
            return FLOAT_CLASS;
        if (value instanceof ArDouble)
            return DOUBLE_CLASS;
        if (value instanceof ArString)
            return STRING_CLASS;
        if (value instanceof ArBool)
            return BOOL_CLASS;
        if (value == ArNone.INSTANCE)
            return NONE_CLASS;
        if (value instanceof ArArray)
            return ARRAY_CLASS;
        if (value instanceof ArFuture)
            return FUTURE_CLASS;
        if (value instanceof ArInstance)
            return ((ArInstance) value).klass;
        return OBJECT_CLASS;
    }
}
