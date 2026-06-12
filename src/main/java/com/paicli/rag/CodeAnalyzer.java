package com.paicli.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 代码分析器：基于 JavaParser AST 构建代码关系图谱
 * <p>
 * 提取的关系类型：
 * - extends：类继承
 * - implements：接口实现
 * - imports：导入依赖
 * - calls：方法调用（简化版，只记录同项目内的调用）
 * - contains：类包含方法
 */
public class CodeAnalyzer {
    private final JavaParser parser;
    private final ClasspathEpoch classpathEpoch;

    public CodeAnalyzer() {
        this(null);
    }

    public CodeAnalyzer(Path projectRoot) {
        this.classpathEpoch = ClasspathEpoch.detect(projectRoot);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            typeSolver.add(new JavaParserTypeSolver(projectRoot));
        }
        configuration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        this.parser = new JavaParser(configuration);
    }

    /**
     * 分析单个 Java 文件，提取所有代码关系
     */
    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = filePath.toString();
        List<CodeRelation> relations = new ArrayList<>();

        ParseResult<CompilationUnit> result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return relations;
        }

        CompilationUnit cu = result.getResult().get();

        // 提取导入关系
        extractImports(relativePath, cu, relations);

        // 提取类级别关系（extends, implements, contains）
        extractClassRelations(relativePath, cu, relations);

        return relations;
    }

    private void extractImports(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        for (ImportDeclaration imp : cu.getImports()) {
            String importName = imp.getNameAsString();
            String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
            // 只记录非 JDK 导入（作为项目内依赖的近似判断）
            if (!importName.startsWith("java.") && !importName.startsWith("javax.")) {
                relations.add(new CodeRelation(
                        filePath, "file", null, simpleName, "imports",
                        CodeRelation.AST_INFERRED, 0.50, classpathEpoch.value()));
            }
        }
    }

    private void extractClassRelations(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        // Bug #18 修复：只处理顶级类，避免内部类/匿名类的方法调用被归属到外部类
        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(clazz -> !clazz.isNestedType()) // 过滤掉内部类
                .forEach(clazz -> {
            String className = clazz.getNameAsString();

            // extends 关系
            clazz.getExtendedTypes().forEach(ext -> {
                relations.add(new CodeRelation(
                        filePath, className, null, ext.getNameAsString(), "extends",
                        CodeRelation.AST_INFERRED, 0.65, classpathEpoch.value()));
            });

            // implements 关系
            clazz.getImplementedTypes().forEach(impl -> {
                relations.add(new CodeRelation(
                        filePath, className, null, impl.getNameAsString(), "implements",
                        CodeRelation.AST_INFERRED, 0.65, classpathEpoch.value()));
            });

            // contains 关系：类包含方法
            clazz.getMethods().forEach(method -> {
                String methodName = method.getNameAsString();
                relations.add(new CodeRelation(
                        filePath, className, filePath, className + "." + methodName, "contains",
                        CodeRelation.SOURCE_RESOLVED, 0.90, classpathEpoch.value()));
                clazz.getImplementedTypes().forEach(impl -> relations.add(new CodeRelation(
                        filePath, className + "." + methodName, null,
                        impl.getNameAsString() + "." + methodName, "implements",
                        CodeRelation.AST_INFERRED, 0.65, classpathEpoch.value())));
                clazz.getExtendedTypes().forEach(ext -> relations.add(new CodeRelation(
                        filePath, className + "." + methodName, null,
                        ext.getNameAsString() + "." + methodName, "extends",
                        CodeRelation.AST_INFERRED, 0.65, classpathEpoch.value())));
            });

            Map<String, String> receiverTypes = collectReceiverTypes(clazz);

            // calls 关系：优先把 userService.login() 解析为 UserService.login，失败再退回方法名。
            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                ResolvedCallee callee = resolveCallee(call, receiverTypes);
                // 尝试获取调用者方法
                Optional<MethodDeclaration> parentMethod = findParentMethod(call);
                if (parentMethod.isPresent()) {
                    String caller = className + "." + parentMethod.get().getNameAsString();
                    relations.add(new CodeRelation(
                            filePath, caller, null, callee.name(), "calls",
                            callee.source(), callee.confidence(), classpathEpoch.value()));
                }
            });
        });
    }

    private Map<String, String> collectReceiverTypes(ClassOrInterfaceDeclaration clazz) {
        Map<String, String> receiverTypes = new HashMap<>();

        // 收集字段（实例变量）
        clazz.findAll(FieldDeclaration.class).forEach(field -> {
            String type = field.getElementType().asString();
            for (VariableDeclarator variable : field.getVariables()) {
                receiverTypes.put(variable.getNameAsString(), type);
            }
        });

        // 收集局部变量
        clazz.findAll(VariableDeclarator.class).forEach(variable ->
                receiverTypes.putIfAbsent(variable.getNameAsString(), variable.getType().asString()));

        // 收集方法参数（Bug #2 修复：方法参数在 AST 中是独立的 Parameter 节点）
        clazz.findAll(MethodDeclaration.class).forEach(method -> {
            for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
                receiverTypes.putIfAbsent(param.getNameAsString(), param.getType().asString());
            }
        });

        return receiverTypes;
    }

    private ResolvedCallee resolveCallee(MethodCallExpr call, Map<String, String> receiverTypes) {
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            return new ResolvedCallee(resolved.declaringType().getClassName() + "." + resolved.getName(),
                    CodeRelation.SYMBOL_SOLVER, 0.92);
        } catch (RuntimeException ignored) {
            // Fall back to source-local receiver inference.
        }
        String methodName = call.getNameAsString();
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return new ResolvedCallee(methodName, CodeRelation.AST_INFERRED, 0.35);
        }
        String receiver = scope.get().toString();
        String type = receiverTypes.get(receiver);
        if (type == null || type.isBlank()) {
            return new ResolvedCallee(methodName, CodeRelation.AST_INFERRED, 0.35);
        }
        return new ResolvedCallee(type + "." + methodName, CodeRelation.SOURCE_RESOLVED, 0.75);
    }

    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return Optional.of(method);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    private record ResolvedCallee(String name, String source, double confidence) {
    }
}
