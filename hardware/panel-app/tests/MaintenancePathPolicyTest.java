package net.quber.afactorymode;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class MaintenancePathPolicyTest {
    private static final String ROOT = "/data/local/tmp/home-panel-maintenance/";
    private static final Set<String> ACCESSORS = new HashSet<String>(Arrays.asList(
            "rootFile", "readRoot", "writeRoot", "existsRoot", "removeRootIfPresent"));
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (String value : new String[]{"a", "job.json", "install-session.json", "home-state.json", "home-operation.json", "file_name-1.json.next"}) {
            check(value.equals(MaintenancePathPolicy.basename(value)));
            check((value + ".next").equals(MaintenancePathPolicy.temporary(value)));
        }
        for (String value : new String[]{null, "", ".", "..", "../job.json", "/job.json", "a/b", "a\\b", "a..b", ".hidden", "a.",
                "-flag", "A.json", "a b", "a\n", "a\r", "a\t", "a\0b", "a%2fb", "a;id", "a$(id)", "a`id`", "a:b", "한글.json"}) {
            reject(value, false); reject(value, true);
        }
        String fiftyNine = repeat('a', 59), sixtyFour = repeat('a', 64);
        check(sixtyFour.equals(MaintenancePathPolicy.basename(sixtyFour)));
        check(MaintenancePathPolicy.temporary(fiftyNine).length() == 64);
        reject(repeat('a', 65), false); reject(repeat('a', 60), true);
        Path sources = args.length == 1 ? Paths.get(args[0]) : Paths.get("hardware/panel-app/src/net/quber/afactorymode");
        Set<String> names = contract(sources);
        for (String required : new String[]{"install-session.json", "home-state.json", "home-operation.json",
                "helper.apk", "candidate.apk", "release.json", "job.json", "result.json", "update.lock", "maintenance.lock"}) {
            check(names.contains(required));
        }
        for (String name : names) {
            check(name.equals(MaintenancePathPolicy.basename(name)));
            check((name + ".next").equals(MaintenancePathPolicy.temporary(name)));
            File file = new File(ROOT, name);
            check(ROOT.substring(0, ROOT.length() - 1).equals(file.getParentFile().getCanonicalPath()));
        }
        System.out.println("MaintenancePathPolicyTest passed: " + checks + " checks; " + names.size() + " source-defined root basenames and temporary names verified");
    }

    private static Set<String> contract(Path sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("JDK compiler required");
        List<File> files = new ArrayList<File>();
        for (String filename : new String[]{"MaintenanceRoot.java", "PanelInstallWorker.java", "MaintenanceHome.java", "MaintenanceInstallProtocol.java"}) {
            Path path = sources.resolve(filename);
            check(Files.isRegularFile(path)); files.add(path.toFile());
        }
        final Set<String> names = new LinkedHashSet<String>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, Arrays.asList("-proc:none"), null, manager.getJavaFileObjectsFromFiles(files));
            for (CompilationUnitTree unit : task.parse()) {
                final boolean facade = unit.getSourceFile().getName().endsWith("MaintenanceRoot.java");
                final Map<String, List<String>> bindings = new HashMap<String, List<String>>();
                bindings.put("MaintenanceRoot.DIRECTORY", Collections.singletonList(ROOT.substring(0, ROOT.length() - 1)));
                new TreeScanner<Void, Void>() {
                    @Override public Void visitVariable(VariableTree node, Void ignored) {
                        if (node.getInitializer() instanceof LiteralTree && ((LiteralTree) node.getInitializer()).getValue() instanceof String) {
                            bindings.put(node.getName().toString(), Collections.singletonList((String) ((LiteralTree) node.getInitializer()).getValue()));
                        }
                        return super.visitVariable(node, ignored);
                    }
                }.scan(unit, null);
                new TreeScanner<Void, Void>() {
                    private String method = "";
                    @Override public Void visitMethod(MethodTree node, Void ignored) {
                        String old = method; method = node.getName().toString();
                        super.visitMethod(node, ignored); method = old; return null;
                    }
                    @Override public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void ignored) {
                        List<String> values = resolve(node.getExpression(), bindings);
                        String key = node.getVariable().getName().toString();
                        List<String> old = bindings.put(key, values);
                        scan(node.getStatement(), ignored);
                        if (old == null) bindings.remove(key); else bindings.put(key, old);
                        return null;
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void ignored) {
                        String target = node.getMethodSelect().toString();
                        String name = target.startsWith("MaintenanceRoot.") ? target.substring("MaintenanceRoot.".length()) : target;
                        if (ACCESSORS.contains(name) && !(facade && ACCESSORS.contains(method))) {
                            if (node.getArguments().isEmpty()) throw new AssertionError("missing root filename");
                            List<String> found = resolve(node.getArguments().get(0), bindings);
                            if (found.isEmpty()) throw new AssertionError("unresolved root filename: " + node);
                            for (String value : found) { MaintenancePathPolicy.basename(value); names.add(value); }
                        }
                        return super.visitMethodInvocation(node, ignored);
                    }
                    @Override public Void visitLiteral(LiteralTree node, Void ignored) {
                        if (node.getValue() instanceof String) addRootPath((String) node.getValue());
                        return super.visitLiteral(node, ignored);
                    }
                    @Override public Void visitBinary(BinaryTree node, Void ignored) {
                        for (String value : resolve(node, bindings)) addRootPath(value);
                        return super.visitBinary(node, ignored);
                    }
                    private void addRootPath(String value) {
                        if (value.startsWith(ROOT) && value.length() > ROOT.length()) {
                            String name = value.substring(ROOT.length()); MaintenancePathPolicy.basename(name); names.add(name);
                        }
                    }
                }.scan(unit, null);
            }
        }
        String root = new String(Files.readAllBytes(sources.resolve("MaintenanceRoot.java")), StandardCharsets.UTF_8);
        check(root.contains("MaintenancePathPolicy.basename(name)"));
        check(root.contains("MaintenancePathPolicy.temporary(name)"));
        return names;
    }

    private static List<String> resolve(ExpressionTree tree, Map<String, List<String>> bindings) {
        if (tree instanceof LiteralTree && ((LiteralTree) tree).getValue() instanceof String) {
            return Collections.singletonList((String) ((LiteralTree) tree).getValue());
        }
        if (tree instanceof IdentifierTree || tree.getKind() == Tree.Kind.MEMBER_SELECT) {
            List<String> found = bindings.get(tree.toString()); return found == null ? Collections.<String>emptyList() : found;
        }
        List<String> result = new ArrayList<String>();
        if (tree instanceof NewArrayTree && ((NewArrayTree) tree).getInitializers() != null) {
            for (ExpressionTree item : ((NewArrayTree) tree).getInitializers()) result.addAll(resolve(item, bindings));
        } else if (tree instanceof BinaryTree && tree.getKind() == Tree.Kind.PLUS) {
            for (String left : resolve(((BinaryTree) tree).getLeftOperand(), bindings)) {
                for (String right : resolve(((BinaryTree) tree).getRightOperand(), bindings)) result.add(left + right);
            }
        }
        return result;
    }

    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private static void reject(String value, boolean temporary) {
        try {
            if (temporary) MaintenancePathPolicy.temporary(value); else MaintenancePathPolicy.basename(value);
            throw new AssertionError("invalid name accepted");
        } catch (IllegalArgumentException expected) { check("root_filename".equals(expected.getMessage())); }
    }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
}
