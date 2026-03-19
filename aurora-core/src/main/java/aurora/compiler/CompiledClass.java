package aurora.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intermediate class data structure used during compilation.
 * This class holds the metadata and bytecode for a class before it is
 * instantiated as an runtime object in the VM.
 */
public class CompiledClass {
    /** The name of the class. */
    public final String name;

    /** The name of the superclass, if any. */
    public String superClassName = null;

    /** The initializer (constructor) of the class, if defined. */
    public CompiledFunction initializer = null;

    /** A map of method names to their respective compiled function data. */
    public final Map<String, CompiledFunction> methods = new HashMap<>();

    public List<String> interfaceNames = new ArrayList<>();

    public boolean isTrait = false;

    public boolean isEnum = false;

    /**
     * Constructs a new CompiledClass with the specified name.
     *
     * @param name The name of the class.
     */
    public CompiledClass(String name) {
        this.name = name;
    }
}
