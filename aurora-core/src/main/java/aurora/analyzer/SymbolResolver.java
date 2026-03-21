package aurora.analyzer;

import aurora.analyzer.ModuleResolver;
import aurora.parser.tree.*;
import aurora.parser.tree.decls.*;
import aurora.parser.tree.expr.AccessExpr;
import aurora.parser.tree.expr.CallExpr;
import aurora.parser.tree.expr.SelfExpr;
import aurora.parser.tree.stmt.BlockStmt;

import java.util.List;

/**
 * A utility class for resolving symbols (variables, types, functions) within the Aurora AST.
 * It provides static methods to search through nested scopes and external modules to find
 * the declaration associated with a specific usage.
 */
public class SymbolResolver {

    // Convenience overload without external modules (e.g. for GoToDefinition
    // fallback)
    public static Node resolve(Node node, List<Node> path) {
        return resolve(node, path, null, null);
    }

    /**
     * Given the deepest node under the cursor and the full path from root → cursor,
     * find the declaration that node refers to, optionally looking in imported
     * modules.
     */
    public static Node resolve(Node node, List<Node> path, Program currentProgram, ModuleResolver modules) {
        if (node == null || path == null || path.isEmpty())
            return null;

        // Case A: cursor is directly ON a declaration node → return it immediately
        switch (node) {
            case Declaration decl -> {
                return decl;
            }

            // Case B: cursor is on a TypeNode (type annotation) → look up type name
            case TypeNode typeNode when typeNode.name != null && !typeNode.name.isEmpty() && !typeNode.name.equals("none") -> {
                Program prog = currentProgram != null ? currentProgram : findProgram(path);
                return resolveTypeName(prog, typeNode.name, modules);
            }

            // Case C: cursor is on `self` → no meaningful resolution without type inference
            case SelfExpr selfExpr -> {
                return null;
            }

            // Case D: cursor is on `self.member` AccessExpr → resolve member in enclosing
            // class
            case AccessExpr access when access.object instanceof SelfExpr -> {
                ClassDecl cls = findEnclosingClass(path);
                if (cls != null)
                    return resolveInClass(cls, access.member);
                RecordDecl rec = findEnclosingRecord(path);
                if (rec != null)
                    return resolveInRecord(rec, access.member);
            }
            default -> {
            }
        }

        // Case D.1: cursor is on `object.member` where object is an instance or a
        // module
        if (node instanceof AccessExpr access && access.object != null && !(access.object instanceof SelfExpr)) {
            Node objDecl = resolve(access.object, path, currentProgram, modules);
            Program prog = currentProgram != null ? currentProgram : findProgram(path);
            if (objDecl != null) {
                switch (objDecl) {
                    case FieldDecl fieldDecl when fieldDecl.init instanceof CallExpr callExpr && callExpr.callee instanceof AccessExpr callee && callee.object == null -> {
                        Node typeDecl = resolveTypeName(prog, callee.member, modules);
                        if (typeDecl instanceof ClassDecl cls)
                            return resolveInClass(cls, access.member);
                    }
                    case FieldDecl fieldDecl when fieldDecl.type != null -> {
                        Node typeDecl = resolveTypeName(prog, fieldDecl.type.name, modules);
                        if (typeDecl instanceof ClassDecl cls)
                            return resolveInClass(cls, access.member);
                    }
                    case ParamDecl paramDecl when paramDecl.type != null -> {
                        Node typeDecl = resolveTypeName(prog, paramDecl.type.name, modules);
                        if (typeDecl instanceof ClassDecl cls)
                            return resolveInClass(cls, access.member);
                    }
                    case Program.Import ignored -> {
                        if (modules != null && access.object instanceof AccessExpr ae && ae.object == null) {
                            Declaration ext = modules.resolveImportedModule(prog, ae.member);
                            if (ext instanceof ClassDecl cls) {
                                return resolveInClass(cls, access.member);
                            }
                        }
                    }
                    case ClassDecl cls -> {
                        return resolveInClass(cls, access.member);
                    }
                    default -> {
                    }
                }
            }
        }

        // Case E: cursor is on a bare identifier → look up by name in enclosing scopes
        String name = getName(node);
        if (name == null)
            return null;

        // Traverse path from deepest scope (size-2, skip the leaf identifier itself) up
        // to root
        for (int i = path.size() - 2; i >= 0; i--) {
            Node current = path.get(i);
            // child = the next deeper node in the path (needed to limit block search before
            // cursor)
            Node child = (i + 1 < path.size()) ? path.get(i + 1) : null;

            Node output = null;
            if (current instanceof BlockStmt block) {
                output = resolveInBlock(block, child, name);
            } else if (current instanceof FunctionDecl func) {
                output = resolveInFunction(func, name);
            } else if (current instanceof ClassDecl cls) {
                output = resolveInClass(cls, name);
            } else if (current instanceof RecordDecl rec) {
                output = resolveInRecord(rec, name);
            } else if (current instanceof InterfaceDecl iface) {
                output = resolveInInterface(iface, name);
            } else if (current instanceof Program prog) {
                output = resolveInProgram(prog, name, modules);
            }

            if (output != null)
                return output;
        }

        return null;
    }

    /**
     * Extract the identifier name from an expression node.
     * Returns null if the node is not an identifier reference.
     */
    private static String getName(Node node) {
        if (node instanceof AccessExpr access) {
            // bare identifier: foo (object == null)
            if (access.object == null)
                return access.member;
            // self.field or this.field
            if (access.object instanceof SelfExpr)
                return access.member;
        }
        return null;
    }

    // ---- Scope resolvers ----

    private static Node resolveInBlock(BlockStmt block, Node child, String name) {
        // Only search declarations that come BEFORE the child statement (to respect
        // scope order)
        int limit = block.statements.size();
        if (child != null) {
            int idx = block.statements.indexOf(child);
            if (idx != -1)
                limit = idx;
        }

        for (int j = limit - 1; j >= 0; j--) {
            Statement stmt = block.statements.get(j);
            // Unwrap ExprStmt wrapping a Declaration (local val/var declarations)
            if (stmt instanceof Declaration decl && name.equals(decl.name))
                return decl;
            // Also handle ExprStmt that wraps nothing — skip
        }
        return null;
    }

    private static Node resolveInFunction(FunctionDecl func, String name) {
        if (func.params != null) {
            for (ParamDecl param : func.params) {
                if (name.equals(param.name))
                    return param;
            }
        }
        return null;
    }

    private static Node resolveInClass(ClassDecl cls, String name) {
        if (cls.members != null) {
            for (Declaration decl : cls.members) {
                if (name.equals(decl.name))
                    return decl;
            }
        }
        return null;
    }

    private static Node resolveInRecord(RecordDecl rec, String name) {
        if (rec.members != null) {
            for (Declaration decl : rec.members) {
                if (name.equals(decl.name))
                    return decl;
            }
        }
        return null;
    }

    private static Node resolveInInterface(InterfaceDecl iface, String name) {
        if (iface.members != null) {
            for (Declaration decl : iface.members) {
                if (name.equals(decl.name))
                    return decl;
            }
        }
        return null;
    }

    // Old overload without module resolver
    private static Node resolveInProgram(Program program, String name) {
        return resolveInProgram(program, name, null);
    }

    private static Node resolveInProgram(Program program, String name, ModuleResolver modules) {
        if (program.statements != null) {
            for (Statement stmt : program.statements) {
                if (stmt instanceof Declaration decl && name.equals(decl.name))
                    return decl;
            }
        }

        if (program.imports != null) {
            for (Program.Import imp : program.imports) {
                if (imp instanceof Program.ImportAlias alias) {
                    if (name.equals(alias.alias))
                        return alias;
                } else if (imp instanceof Program.ImportMulti multi) {
                    for (Program.ImportMulti.Member m : multi.imports) {
                        if (name.equals(m.alias() != null ? m.alias() : m.id()))
                            return imp;
                    }
                } else {
                    // Simple import: `use Aurora.Io` → last segment is `Io`
                    String[] parts = imp.path.split("\\.");
                    if (parts.length > 0 && parts[parts.length - 1].equals(name)) {
                        // Try to resolve to the actual class in the imported module
                        if (modules != null) {
                            Declaration ext = modules.resolveImportedModule(program, name);
                            if (ext != null)
                                return ext;
                        }
                        return imp; // fall back to returning the import node itself
                    }
                }
            }
        }

        // Final fallback: search across ALL imported modules for a declaration with
        // this name
        if (modules != null) {
            Declaration ext = modules.resolveFromImports(program, name);
            if (ext != null)
                return ext;
        }

        return null;
    }

    // ---- Path helpers ----

    private static Program findProgram(List<Node> path) {
        for (Node n : path) {
            if (n instanceof Program p)
                return p;
        }
        return null;
    }

    private static ClassDecl findEnclosingClass(List<Node> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof ClassDecl cls)
                return cls;
        }
        return null;
    }

    private static RecordDecl findEnclosingRecord(List<Node> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof RecordDecl rec)
                return rec;
        }
        return null;
    }

    /** Resolve a type name (e.g. "Dog", "Animal") to its top-level declaration. */
    public static Node resolveTypeName(Program program, String typeName) {
        return resolveTypeName(program, typeName, null);
    }

    /** Resolve a type name, also searching imported modules via {@code modules}. */
    public static Node resolveTypeName(Program program, String typeName, ModuleResolver modules) {
        if (program != null && program.statements != null) {
            for (Statement stmt : program.statements) {
                switch (stmt) {
                    case ClassDecl cd when typeName.equals(cd.name) -> {
                        return cd;
                    }
                    case RecordDecl rd when typeName.equals(rd.name) -> {
                        return rd;
                    }
                    case InterfaceDecl id when typeName.equals(id.name) -> {
                        return id;
                    }
                    case EnumDecl ed when typeName.equals(ed.name) -> {
                        return ed;
                    }
                    default -> {}
                }
            }
        }
        // Not found locally — try imported modules
        if (modules != null && program != null) {
            Declaration decl = modules.resolveFromImports(program, typeName);
            if (decl == null) decl = modules.resolveImportedModule(program, typeName);
            return decl;
        }
        return null;
    }
}